package com.winter.reg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.winter.reg.domain.Activity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 活动表。建活动与展示走 MyBatis-Plus 的 {@code BaseMapper}，列表用一条手写 SQL。 */
public interface ActivityMapper extends BaseMapper<Activity> {

  /** 开抢中的活动，供活动列表页使用。 */
  @Select("SELECT id, title, capacity, stock, start_time, status FROM activity WHERE status = 'OPEN' ORDER BY id DESC")
  List<Activity> selectOpen();

  /**
   * 报名写路径第一步：对活动行加排他锁。
   *
   * <p>不是可选的优化，是死锁的关键。**S 锁不是 UPDATE 加的，是 INSERT 的外键检查加的**——
   * {@code registration} 上有外键 {@code fk_reg_activity} 指向 {@code activity}，
   * 往 registration 插一行时，InnoDB 必须给 activity 那一行加 S 锁来校验外键。
   * {@code performance_schema.data_locks} 实测（UPDATE 还没执行时就已经挂着）：
   * <pre>
   * activity     PRIMARY   RECORD   S,REC_NOT_GAP   GRANTED
   * registration null      TABLE    IX              GRANTED
   * </pre>
   * 同事务接下来的 {@code UPDATE activity} 要把这行从 S 升级成 X，
   * 两个事务同时持 S 等 X 就是死锁。
   *
   * <p>所以这跟 {@code decrementStockIfPositive} 的谓词有没有非索引列**无关**：
   * 把 {@code AND stock > 0} 整个去掉，死锁从 129 涨到 137（/200）。决定性的一组对照：
   * <pre>
   * 只有 UPDATE，没有 INSERT      死锁   0/200   ← 没有外键检查就没有 S 锁
   * INSERT + UPDATE              死锁  92/200
   * </pre>
   *
   * <p>{@code FOR UPDATE} 放在**事务第一条**直接拿 X，之后外键检查发现本事务已持 X（自己兼容自己），
   * 全程没有升级，形不成环。放晚一步反而更差：
   * <pre>
   * FOR UPDATE → INSERT → UPDATE  死锁   0/200
   * INSERT → FOR UPDATE → UPDATE  死锁 146/200   ← 等 X 时手里还捏着外键检查给的 S
   * </pre>
   * 最后一行在默认 REPEATABLE-READ 下同样 0 死锁，所以不必改隔离级别（D7 的决定得以保留）。
   *
   * @return 库存；行不存在时返回 {@code null}（调用方已确认活动存在，这个分支只为让返回类型可空）
   */
  @Select("SELECT stock FROM activity WHERE id = #{id} FOR UPDATE")
  Integer lockActivityRow(@Param("id") Long id);

  /**
   * 报名写路径第三步：条件扣库存。
   *
   * @return 1 扣成功；0 该活动不存在或库存已空（{@code stock > 0} 没命中）
   */
  @Update("UPDATE activity SET stock = stock - 1 WHERE id = #{id} AND stock > 0")
  int decrementStockIfPositive(@Param("id") Long id);
}
