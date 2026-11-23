#!/usr/bin/env bash
# 压测正确性校验。
#
#   bin/verify.sh reset                      清空报名与活动，准备下一轮
#   bin/verify.sh check <activityId> <capacity> [redis]
#
# reset 用 TRUNCATE 不用 DELETE：DELETE 不重置 AUTO_INCREMENT，第二轮活动会变成 id=2，
# 就得每轮改一次 jmx 里的 activityId。TRUNCATE 之后 id 恒为 1。
#
# check 的断言（设计文档第 8 节）：
#   ① DB CONFIRMED 行数 == capacity         超卖或名额被吞都会让这个数不对
#   ② 无重复 (activity_id, user_id)          一个人占多个名额
#   ③ DB stock == 0                          与 ① 互相印证，另抓 stock 变负
#   ④ deadlock 增量                           见下面 InnoDB 计数器的说明
#   ⑤ [redis] Redis stock == 0 且 enrolled 集合大小 == capacity   L1 起才传 redis
#
# 为什么必须有 check：L1/L2 是「Redis 扣成功就返回、DB 后写」，跑完立刻查 DB，
# 成功数必然小于名额——那不是超卖，是消费者没写完。跳过屏障直接查 DB 会每次都
# 以为写出了超卖 bug。（L2 起 check 会先发屏障消息等消费者追平，见阶段 L2。）
#
# ④ 的说明：MySQL 8.4 已经把 Innodb_deadlocks 从 SHOW GLOBAL STATUS 里删了
# （SHOW GLOBAL STATUS LIKE '%deadlock%' 查不到任何东西），现在在
# information_schema.INNODB_METRICS 里，叫 lock_deadlocks。
# 而且它是「自 MySQL 启动以来」的累计值，没有按轮次归零的办法，所以 reset 时把
# 当前值存下来，check 时报增量。
#
# 顺带记 Innodb_row_lock_waits（行锁等待次数）——这个比死锁次数更能说明 L0 的
# 瓶颈在哪：它证明 3000 个请求真的在同一条行锁上排过队。
set -uo pipefail

DB_HOST="${WINTER_DB_HOST:-127.0.0.1}"
DB_PORT="${WINTER_DB_PORT:-3306}"
DB_NAME="${WINTER_DB_NAME:-winter}"
DB_USER="${WINTER_DB_USER:-root}"
DB_PASS="${WINTER_DB_PASSWORD:-1234}"
BASELINE_FILE="${WINTER_DEADLOCK_BASELINE:-/tmp/winter-deadlock-baseline}"

# 不把密码写进命令行（ps 能看见），用 MYSQL_PWD。
mysql_q() {
  MYSQL_PWD="$DB_PASS" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -B "$DB_NAME" -e "$1" 2>&1
}

# MySQL 8.4 起死锁计数搬到了这里，SHOW GLOBAL STATUS 里没有了。
innodb_metric() {
  mysql_q "SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = '$1'"
}

die() { echo "VERIFY FAILED: $*" >&2; exit 1; }

cmd_reset() {
  # 先子表后父表，否则外键挡着 TRUNCATE 不了 activity。
  mysql_q "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE registration; TRUNCATE TABLE activity;" \
    || die "清表失败"

  local deadlocks lock_waits
  deadlocks="$(innodb_metric lock_deadlocks)"
  lock_waits="$(mysql_q "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'" | awk '{print $2}')"
  printf '%s %s\n' "$deadlocks" "$lock_waits" > "$BASELINE_FILE"
  echo "reset 完成：registration 与 activity 已清空，id 从 1 重新开始"
  echo "          死锁基线 = $deadlocks，行锁等待基线 = $lock_waits（均自 MySQL 启动累计）"
}

cmd_check() {
  local activity_id="$1" capacity="$2" use_redis="${3:-}"

  # --- ① DB CONFIRMED 行数 == capacity -------------------------------------
  local confirmed
  confirmed="$(mysql_q "SELECT COUNT(*) FROM registration WHERE activity_id=$activity_id AND status='CONFIRMED'")"
  [[ "$confirmed" == "$capacity" ]] \
    || die "DB 报名成功数 = $confirmed，期望 $capacity（名额被吞或超卖）"
  echo "PASS ① DB CONFIRMED = $capacity"

  # --- ② 无重复 (activity_id, user_id) -------------------------------------
  # 唯一索引本身会挡重复插入，所以这条永远为真；留着是因为它是「唯一索引真的建了」
  # 的活证明——索引被谁删掉的话这里立刻炸。
  local dupes
  dupes="$(mysql_q "SELECT COUNT(*) FROM (SELECT 1 FROM registration WHERE activity_id=$activity_id GROUP BY user_id HAVING COUNT(*) > 1) t")"
  [[ "$dupes" == "0" ]] \
    || die "有 $dupes 个用户占了多个名额"
  echo "PASS ② 无重复 (activity_id, user_id)"

  # --- ③ DB stock == 0 ------------------------------------------------------
  local stock
  stock="$(mysql_q "SELECT stock FROM activity WHERE id=$activity_id")"
  [[ "$stock" == "0" ]] \
    || die "DB stock = $stock，期望 0"
  echo "PASS ③ DB stock = 0"

  # --- ④ deadlock 增量 ------------------------------------------------------
  local now deadlocks_base lock_waits_base deadlocks_delta lock_waits_delta
  now="$(innodb_metric lock_deadlocks)"
  read -r deadlocks_base lock_waits_base < "$BASELINE_FILE" 2>/dev/null || {
    deadlocks_base=0; lock_waits_base=0; }
  deadlocks_delta=$(( now - deadlocks_base ))
  lock_waits_delta=$(( $(mysql_q "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'" | awk '{print $2}') - lock_waits_base ))
  if [[ "$deadlocks_delta" -gt 0 ]]; then
    echo "FAIL ④ 本轮死锁 $deadlocks_delta 次（lock_deadlocks $deadlocks_base → $now）"
    echo "       看应用日志里的 ERROR 1213 (40001): Deadlock found when trying to get lock"
    die "出现死锁"
  fi
  echo "PASS ④ 死锁增量 = 0"
  echo "     行锁等待增量 = $lock_waits_delta（这些请求真的在同一条行锁上排过队）"

  # --- ⑤ Redis（L1 起）------------------------------------------------------
  if [[ "$use_redis" == "redis" ]]; then
    local r_stock r_enrolled
    r_stock="$(redis-cli --raw GET "stock:$activity_id" 2>/dev/null)"
    [[ "$r_stock" == "0" ]] \
      || die "Redis stock:$activity_id = '$r_stock'，期望 0"
    echo "PASS ⑤ Redis stock = 0"
    r_enrolled="$(redis-cli --raw SCARD "enrolled:$activity_id" 2>/dev/null)"
    [[ "$r_enrolled" == "$capacity" ]] \
      || die "Redis enrolled:$activity_id = '$r_enrolled'，期望 $capacity"
    echo "PASS ⑤ Redis enrolled 集合大小 = $capacity"
  fi

  echo ""
  echo "VERIFY OK —— 五条断言全过，这批数字可以用来写进 benchmarks/ 了"
}

case "${1:-}" in
  reset) cmd_reset ;;
  check)
    [[ -n "${2:-}" && -n "${3:-}" ]] || die "用法：bin/verify.sh check <activityId> <capacity> [redis]"
    cmd_check "$2" "$3" "${4:-}"
    ;;
  *) echo "用法：bin/verify.sh reset | check <activityId> <capacity> [redis]" >&2; exit 2 ;;
esac
