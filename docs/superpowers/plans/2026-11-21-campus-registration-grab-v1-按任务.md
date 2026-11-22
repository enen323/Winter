# 校园活动报名平台 —— 名额抢报 实施计划

> **已废弃，勿照此执行。** 现行计划是 `2026-11-21-campus-registration-grab-v2-按级别.md`。
> 本文件保留只为一处价值：v2 为压缩篇幅删掉的接口推导细节。相对 v2，本文件**已过时**的地方：
> ① 报名链路开关从 `deduct`+`persist` 两个旋钮改成一个 `winter.registration.flow` 枚举
> ② 按级别重组、每级有「停下来」闸门
> ③ 不写 `bin/up.sh`，应用由 IDEA 启动
> ④ 先写失败测试只保留在 `GrabScriptTest` 与 `JwtServiceTest` 两处

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现「活动名额抢报」核心链路，并产出可复现的阶梯压测证据（L0/L2/L4）与正确性校验，证明零超卖、零重复。

**Architecture:** 单机一体化：一个 Spring Boot 应用 + MySQL + Redis + RocketMQ。Redis 是名额判定唯一权威点（Lua 原子脚本一次往返完成校验+扣减），MySQL 是最终真相与审计。同一份代码通过配置开关走 L0（直连 MySQL 条件更新）→ L1（Redis 预扣+同步落库）→ L2（+MQ 异步落库）→ L3（+多级缓存）→ L4（+限流频控），每级前后各压一次，增量本身就是证据。失败补偿链：发消息失败降级同步落库，同步落库失败写本地 WAL 重放，对账任务最后兜底（只补写、不撤销）。

**Tech Stack:** Java 21 / Spring Boot 3.3 / Maven / MySQL 8.4.13 / Redis 8.0.5 (Lettuce) / RocketMQ 5.5.1 (`org.apache.rocketmq:rocketmq-client`) / Flyway / JMeter 5.6.3 / Micrometer+Prometheus / Caffeine / Vue 3 + Vite

**Spec:** `docs/superpowers/specs/2026-11-18-campus-registration-grab-design.md` —— 计划从设计文档推导，执行者必须同时读设计文档。

## Global Constraints

以下每条对每个任务隐式生效，值从设计文档逐字复制：

- **不使用 Docker**：机器装有飞连（corplink）安全客户端，会把 Docker 识别为风险软件。MySQL/Redis/RocketMQ 本机直装。`make up` 由 `bin/up.sh` 实现（健康检查 + 灌数据），等价 compose `--wait`。
- **三层防超卖，任一层都无法单独造成超卖**：①Redis Lua 原子扣减 ②MySQL `UPDATE ... WHERE stock > 0` 影响行数兜底 ③`uk_activity_user(activity_id, user_id)` 唯一索引。
- **Redis 是名额判定唯一权威点**，MySQL 不是判定点（L0 降级路径除外）。
- **成功即事实（D1）**：Redis 扣减成功即返回「报名成功」，不可撤销；落库失败只能由对账补写并告警，**不得回滚库存或删除记录**。
- **对账单向**：只补写、不撤销；「Redis 有而 DB 无」是最高优先级告警。
- **不使用**占位状态、TTL 释放、超时释放、结果轮询接口。
- **取消后重新报名复用同一行**（D5）：`status` 从 `CANCELED` 改回 `CONFIRMED`、更新 `updated_at`，不产生第二条记录。
- **MySQL 隔离级别保持默认 REPEATABLE-READ，不调整**（D7）。若压测出现 `ERROR 1213 (40001): Deadlock found when trying to get lock`，修复只是在 JDBC URL 加 `sessionVariables=transaction_isolation='READ-COMMITTED'`，不动全局配置。
- **压测有效性 5 前提**（违反任意一条整轮数据作废）：①并发请求必须用互不相同的 userId（CSV 数据集注入）②预签发 token ③成功判定以响应体结果码为准（配响应断言）④同步定时器模拟瞬间洪峰 ⑤评估压测机自身是否成为瓶颈。
- **一条命令起全栈、一条命令压测、一条命令校验正确性**（DoD）。
- 本阶段不做：SSO、支付、通知、签到核销、主办方审核、抽签摇号、微服务/K8s/分库分表。

## Review Focus

设计文档是愿景文档，它说的输入不是程序会碰到的全部输入。以下五类最可能坑到使用者，每条的测试挂在所属任务里：

| # | 输入/失败模式 | 合理的人会期待的行为 | 测试所属任务 |
|---|---|---|---|
| 1 | Redis 扣减成功但落库失败（MQ 丢失/DB 不可用） | 用户已看到「报名成功」，DB 最终必须补上这条记录，且对账必须发现差异并告警；**不得**回滚库存 | Task 8 |
| 2 | 同一 userId 并发重复提交（用户狂点、网络重试） | 只有一次生效，返回首次结果，库存只扣一次 | Task 4、6 |
| 3 | 未到开抢时间 / 活动已关闭 | 明确拒绝（`NOT_STARTED`/`CLOSED`），即使 Redis 里还有库存也不能放行 | Task 4、6 |
| 4 | MQ 重复投递 / 消费端重启重放同一条消息 | 不产生第二条报名记录（幂等） | Task 7 |
| 5 | 取消后重新报名（复用同一行） | 唯一索引不误挡，`status` 回到 `CONFIRMED`，库存先回池再扣一次 | Task 9 |

另有两项不进上表但必须做：`capacity <= 0` 或 `stock` 已为 0 时必须拒绝而非崩溃或出现负数（Task 6 覆盖）；压测机自身成为瓶颈时数据不可信，必须在每轮报告里记录压测机与应用机的资源分配（Task 10 覆盖）。

---

### Task 1: 项目骨架、Schema 与一键起全栈

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/winter/reg/WinterApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/java/com/winter/reg/domain/Activity.java`
- Create: `src/main/java/com/winter/reg/domain/Registration.java`
- Create: `src/main/java/com/winter/reg/domain/RegistrationStatus.java`
- Create: `src/main/java/com/winter/reg/repo/ActivityRepository.java`
- Create: `src/main/java/com/winter/reg/repo/RegistrationRepository.java`
- Create: `bin/up.sh`
- Create: `.gitignore`
- Test: `src/test/java/com/winter/reg/SchemaTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `Activity` — `Long id`, `String title`, `int capacity`, `int stock`, `LocalDateTime startTime`, `String status`, getters/setters
  - `Registration` — `Long id`, `Long activityId`, `Long userId`, `RegistrationStatus status`, `LocalDateTime createdAt`, `LocalDateTime updatedAt`
  - `enum RegistrationStatus { CONFIRMED, CANCELED }`
  - `ActivityRepository extends JpaRepository<Activity, Long>`
  - `RegistrationRepository extends JpaRepository<Registration, Long>`
  - 数据库 `winter`（连接 `127.0.0.1:3306`，用户 `winter`，区别于本机已有的 `fjnu_wms`/`wms`）

- [ ] **Step 1: 建库并写 `.gitignore`**

建库与账号（本机 MySQL，root/1234 可连）：
```sql
CREATE DATABASE IF NOT EXISTS winter CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'winter'@'localhost' IDENTIFIED BY 'winter';
GRANT ALL PRIVILEGES ON winter.* TO 'winter'@'localhost';
```
`.gitignore` 至少包含：`target/`、`node_modules/`、`dist/`、`jmeter.log`、`.DS_Store`、`docs/benchmarks/html-report/`（HTML 报告体积大，只提交 Markdown 汇总与 `.jtl`）。**密码不写入任何会被提交的文件**（压测有效性前提之外的安全要求，见设计文档约束）。

- [ ] **Step 2: 写失败的测试 `SchemaTest`**

```java
@SpringBootTest
class SchemaTest {
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrationRepository;

    @Test void flywayMigrationApplied_andUniqueIndexRejectsDuplicate() {
        Activity a = new Activity();
        a.setTitle("抢名额测试"); a.setCapacity(10); a.setStock(10);
        a.setStartTime(LocalDateTime.now().minusMinutes(1)); a.setStatus("OPEN");
        activityRepository.saveAndFlush(a);

        registrationRepository.saveAndFlush(new Registration(a.getId(), 1001L, RegistrationStatus.CONFIRMED));
        // 同一 (activity_id, user_id) 第二次插入必须被唯一索引物理拒绝
        assertThatThrownBy(() ->
            registrationRepository.saveAndFlush(new Registration(a.getId(), 1001L, RegistrationStatus.CONFIRMED)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=SchemaTest`
Expected: FAIL —— 编译错误（`Activity`/`Registration` 类不存在）

- [ ] **Step 4: 写 `pom.xml` 与实体**

`pom.xml` 依赖（不引入设计文档未提及的框架）：`spring-boot-starter-web`、`-data-jpa`、`-data-redis`、`-validation`、`-actuator`、`mysql:mysql-connector-java`、`flyway-core` + `flyway-mysql`、`org.apache.rocketmq:rocketmq-client:5.5.1`、`com.github.ben-manes.caffeine:caffeine`、`micrometer-registry-prometheus`；测试用 `spring-boot-starter-test`。Java release 21。

实体用 JPA 注解，表名 `activity` / `registration`。`application.yml` 指向 `jdbc:mysql://127.0.0.1:3306/winter`，`flyway.enabled=true`。

`db/migration/V1__init.sql` 建两张表，**索引与约束逐字照设计文档**：
```sql
CREATE TABLE activity (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  title       VARCHAR(200)  NOT NULL,
  capacity    INT           NOT NULL,
  stock       INT           NOT NULL,
  start_time  DATETIME(3)   NOT NULL,
  status      VARCHAR(20)   NOT NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_activity_capacity CHECK (capacity >= 0),
  CONSTRAINT chk_activity_stock    CHECK (stock >= 0)
);
CREATE TABLE registration (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT         NOT NULL,
  user_id     BIGINT         NOT NULL,
  status      VARCHAR(20)    NOT NULL,
  created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_activity_user UNIQUE (activity_id, user_id),
  CONSTRAINT fk_reg_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
  INDEX idx_reg_activity_status (activity_id, status)
);
```
`CHECK (stock >= 0)` 是第三层防线之外的一层物理保险：任何让库存变负的 SQL 都会被数据库拒绝。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=SchemaTest`
Expected: PASS —— Flyway 迁移已应用，唯一索引拒绝重复 `(activity_id, user_id)`

- [ ] **Step 6: 写 `bin/up.sh`**

一条命令起全栈。脚本做：
1. 探测 MySQL 3306 / Redis 6379 / RocketMQ namesrv 9876 / broker 10911 是否就绪，未就绪则启动（RocketMQ 用 `JAVA_OPT_EXT="-Xms1g -Xmx1g"` 覆盖 namesrv、`"-Xms2g -Xmx2g"` 覆盖 broker，**脚本默认 `-Xms8g -Xmx8g`+`MaxDirectMemorySize=15g` 本机扛不住**）
2. 等待全部就绪后启动 Spring Boot（`mvn spring-boot:run`）
3. 就绪后打印 `READY http://localhost:8080/actuator/health`

**不要硬编码 broker 名**（设计文档 5.1：重启后可能从 `broker-a` 变成 `HP`），用 namesrv 地址寻址。

Run: `bash bin/up.sh`（后台启动后另开终端）→ `curl -s localhost:8080/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: 项目骨架、Schema 与一键起全栈"
```

---

### Task 2: 用户体系与 JWT

**Files:**
- Create: `src/main/java/com/winter/reg/domain/User.java`
- Create: `src/main/java/com/winter/reg/repo/UserRepository.java`
- Create: `src/main/java/com/winter/reg/auth/JwtService.java`
- Create: `src/main/java/com/winter/reg/auth/JwtAuthFilter.java`
- Create: `src/main/java/com/winter/reg/web/AuthController.java`
- Create: `src/main/java/com/winter/reg/web/ResultCode.java`
- Create: `src/main/java/com/winter/reg/web/ApiResponse.java`
- Test: `src/test/java/com/winter/reg/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WinterApplication`、`application.yml`
- Produces:
  - `final class JwtService { JwtService(String secret, Duration ttl); String issue(Long userId, String username); JwtPayload verify(String token) throws JwtException; }`
  - `record JwtPayload(Long userId, String username, long iat, long exp)`
  - `enum ResultCode { SUCCESS(0), FULL(1001), ALREADY(1002), NOT_STARTED(1003), CLOSED(1004), NOT_FOUND(1005), RATE_LIMITED(1006), DEGRADED(1007) }`
  - `record ApiResponse<T>(int code, String msg, T data)` —— 静态工厂 `ApiResponse.ok(data)` / `ApiResponse.fail(ResultCode)`
  - `User` — `Long id`, `String username`, `String passwordHash`, `LocalDateTime createdAt`
  - HTTP 契约：**所有响应 HTTP 200**，业务成败只看 body 的 `code`（`0` = 报名成功）。这是设计文档压测前提 ③ 的直接结果——用 HTTP 码判成功会把业务失败算成成功。限流同样返回 200 + `code=1006`。

- [ ] **Step 1: 写失败的测试 `JwtServiceTest`**

```java
class JwtServiceTest {
    JwtService svc = new JwtService("test-secret-32-bytes-minimum-length!", Duration.ofMinutes(30));

    @Test void issueThenVerifyRoundTrips() {
        String t = svc.issue(1001L, "alice");
        assertThat(svc.verify(t).userId()).isEqualTo(1001L);
        assertThat(svc.verify(t).username()).isEqualTo("alice");
    }
    @Test void tamperedPayloadRejected() {
        String t = svc.issue(1001L, "alice");
        String[] p = t.split("\\.");
        String forged = p[0] + "." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"9999\",\"exp\":9999999999}".getBytes()) + "." + p[2];
        assertThatThrownBy(() -> svc.verify(forged)).isInstanceOf(JwtException.class);
    }
    @Test void expiredTokenRejected() {
        JwtService shortLived = new JwtService("test-secret-32-bytes-minimum-length!", Duration.ofSeconds(-1));
        assertThatThrownBy(() -> shortLived.verify(shortLived.issue(1L, "bob")))
            .isInstanceOf(JwtException.class);
    }
    @Test void wrongSecretRejected() {
        String t = svc.issue(1001L, "alice");
        assertThatThrownBy(() -> new JwtService("another-secret-32-bytes-minimum!!", Duration.ofMinutes(30)).verify(t))
            .isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=JwtServiceTest`
Expected: FAIL —— 编译错误（`JwtService` 不存在）

- [ ] **Step 3: 实现 `JwtService`（手写 HS256，不引 jjwt）**

用 `javax.crypto.Mac` + `Base64.getUrlEncoder().withoutPadding()` 实现三段式 `header.payload.signature`。header 固定 `{"alg":"HS256","typ":"JWT"}`；payload 含 `sub`(userId)、`name`(username)、`iat`、`exp`。签名前先把 `header + "." + payload` 按 UTF-8 计算 HMAC-SHA256。`verify` 必须：重新计算签名并用**常量时间比较**（`MessageDigest.isEqual`）拒绝签名不符、解析失败、缺 `exp`、已过期的 token。手写的理由：HS256 只有几十行，且这是安全边界，测试把篡改/过期/错密钥三种情况都钉住了。

- [ ] **Step 4: 实现 `User`、`UserRepository`、`AuthController`、`JwtAuthFilter`**

`POST /api/auth/register` 接收 `{username, password}`，密码用 BCrypt（`spring-security-crypto`，只引这一个模块，不上整套 Spring Security）哈希后入库，用户名唯一。`POST /api/auth/login` 校验后返回 token。`JwtAuthFilter` 解析 `Authorization: Bearer <token>`，通过则把 userId 放进请求属性，未通过返回 `ApiResponse.fail(ResultCode.NOT_FOUND)` 由控制器读取。`/api/auth/**` 不拦截，其余 `/api/**` 拦截。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=JwtServiceTest`
Expected: PASS —— 4 个用例全绿（往返、篡改、过期、错密钥）

- [ ] **Step 6: 手工验证登录链路**

Run: `curl -s -XPOST localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{"username":"probe","password":"p"}'` 然后带返回的 token 请求一个需登录的接口
Expected: 无 token 时返回非 0 code；带 token 时放行

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: 用户体系与手写 HS256 JWT"
```

---

### Task 3: 活动发布与查询

**Files:**
- Create: `src/main/java/com/winter/reg/web/ActivityController.java`
- Create: `src/main/java/com/winter/reg/service/ActivityService.java`
- Create: `src/main/java/com/winter/reg/web/dto/ActivityRequest.java`
- Create: `src/main/java/com/winter/reg/web/dto/ActivityResponse.java`
- Test: `src/test/java/com/winter/reg/service/ActivityServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ActivityRepository`、Task 2 的 `ApiResponse`
- Produces:
  - `ActivityService.create(ActivityRequest) -> ActivityResponse`
  - `ActivityService.get(Long activityId) -> ActivityResponse`（不存在返回 `null`）
  - `ActivityService.listOpen() -> List<ActivityResponse>`
  - `POST /api/activities`（需登录）、`GET /api/activities/{id}`、`GET /api/activities`

- [ ] **Step 1: 写失败的测试 `ActivityServiceTest`**

```java
@SpringBootTest
class ActivityServiceTest {
    @Autowired ActivityService service;
    @Autowired ActivityRepository repository;

    @Test void createThenGetRoundTrips() {
        ActivityResponse created = service.create(new ActivityRequest("讲座A", 200,
            LocalDateTime.now().plusHours(1)));
        assertThat(created.capacity()).isEqualTo(200);
        assertThat(created.stock()).isEqualTo(200);
        assertThat(service.get(created.id()).title()).isEqualTo("讲座A");
    }
    @Test void getUnknownReturnsNull() { assertThat(service.get(999999L)).isNull(); }
    @Test void zeroCapacityRejected() {
        assertThatThrownBy(() -> service.create(new ActivityRequest("空活动", 0, LocalDateTime.now())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ActivityServiceTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现 `ActivityService`**

创建时 `stock = capacity`、`status = "OPEN"`（capacity 为 0 或负数抛 `IllegalArgumentException`——这是 Review Focus 里 `capacity <= 0` 的第一道防线）。`ActivityResponse` 暴露 `id/title/capacity/stock/startTime/status`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=ActivityServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 活动发布与查询"
```

---

### Task 4: Redis Lua 原子扣减脚本

这是全项目的核心。脚本在**一次 Redis 往返**内完成时间/状态校验 + 库存检查 + 已报名检查 + 扣减，不存在校验与扣减之间的 TOCTOU 窗口。

**Files:**
- Create: `src/main/resources/lua/grab.lua`
- Create: `src/main/resources/lua/cancel.lua`
- Create: `src/main/java/com/winter/reg/config/RedisConfig.java`
- Create: `src/main/java/com/winter/reg/lua/RegistrationScripts.java`
- Create: `src/main/java/com/winter/reg/lua/RedisKeys.java`
- Test: `src/test/java/com/winter/reg/lua/GrabScriptTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ActivityService`（活动快照写入）
- Produces:
  - `record RedisKeys { static String stock(Long aid); static String enrolled(Long aid); static String result(Long aid, Long uid); static String activitySnapshot(Long aid); }` —— 键名逐字照设计文档 7.2/7.4：`stock:{aid}`、`enrolled:{aid}`、`result:{aid}:{uid}`，另加 `activity:{aid}`（HASH，字段 `start_ms`、`open`）
  - `enum GrabCode { SUCCESS(1), FULL(2), ALREADY(3), NOT_STARTED(4), CLOSED(5) }`
  - `enum CancelCode { SUCCESS(1), NOT_FOUND(2) }`
  - `RegistrationScripts.grab(Long activityId, Long userId, int resultTtlSeconds) -> GrabCode`
  - `RegistrationScripts.cancel(Long activityId, Long userId) -> CancelCode`
  - `RedisConfig` 提供 `RedisTemplate<String, Object>` 与 `DefaultRedisScript` bean

- [ ] **Step 1: 写失败的测试 `GrabScriptTest`**

测试直连本机 Redis（127.0.0.1:6379），每个用例前后清理自己的键。**并发用例是本任务的存在理由**。

```java
@SpringBootTest
class GrabScriptTest {
    @Autowired RegistrationScripts scripts;
    @Autowired StringRedisTemplate redis;

    Long aid = 4242L;
    int capacity = 100;
    int threads = 500;   // 远超名额，必须恰好 100 个成功

    @BeforeEach void seed() {
        redis.delete(RedisKeys.stock(aid)); redis.delete(RedisKeys.enrolled(aid));
        redis.opsForValue().set(RedisKeys.stock(aid), String.valueOf(capacity));
        redis.opsForHash().putAll(RedisKeys.activitySnapshot(aid),
            Map.of("start_ms", String.valueOf(System.currentTimeMillis() - 60_000), "open", "1"));
    }
    @AfterEach void clean() { redis.delete(RedisKeys.stock(aid)); redis.delete(RedisKeys.enrolled(aid)); redis.delete(RedisKeys.activitySnapshot(aid)); }

    @Test void firstSucceeds_secondSameUserAlready() {
        assertThat(scripts.grab(aid, 1001L, 60)).isEqualTo(GrabCode.SUCCESS);
        assertThat(scripts.grab(aid, 1001L, 60)).isEqualTo(GrabCode.ALREADY);
        assertThat(redis.opsForValue().get(RedisKeys.stock(aid))).isEqualTo("99");
        assertThat(redis.opsForSet().isMember(RedisKeys.enrolled(aid), "1001")).isTrue();
    }
    @Test void notStartedRejected() {
        redis.opsForHash().put(RedisKeys.activitySnapshot(aid), "start_ms", String.valueOf(System.currentTimeMillis() + 3_600_000));
        assertThat(scripts.grab(aid, 2002L, 60)).isEqualTo(GrabCode.NOT_STARTED);
    }
    @Test void closedRejected() {
        redis.opsForHash().put(RedisKeys.activitySnapshot(aid), "open", "0");
        assertThat(scripts.grab(aid, 3003L, 60)).isEqualTo(GrabCode.CLOSED);
    }
    @Test void missingStockKeyTreatedAsFull_notNegativeStock() {
        redis.delete(RedisKeys.stock(aid));
        assertThat(scripts.grab(aid, 4004L, 60)).isEqualTo(GrabCode.FULL);
    }
    @Test void concurrentGrab_successCountEqualsCapacity_noOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<GrabCode>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long uid = 10_000L + i;              // 互不相同的 userId
            fs.add(pool.submit(() -> { start.await(); return scripts.grab(aid, uid, 60); }));
        }
        start.countDown();
        long success = fs.stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                         .filter(c -> c == GrabCode.SUCCESS).count();
        pool.shutdown();
        assertThat(success).isEqualTo(capacity);                          // 恰好 100，不多不少
        assertThat(redis.opsForSet().size(RedisKeys.enrolled(aid))).isEqualTo(capacity);
        assertThat(redis.opsForValue().get(RedisKeys.stock(aid))).isEqualTo("0");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=GrabScriptTest`
Expected: FAIL —— 编译错误（`RegistrationScripts` 不存在）

- [ ] **Step 3: 写 `lua/grab.lua`**

脚本体（键与参数的含义写进文件头注释）：

```lua
-- KEYS[1]=stock:{aid} KEYS[2]=enrolled:{aid} KEYS[3]=result:{aid}:{uid} KEYS[4]=activity:{aid}
-- ARGV[1]=userId ARGV[2]=resultTtlSeconds ARGV[3]=nowMillis
-- 返回 1=SUCCESS 2=FULL 3=ALREADY 4=NOT_STARTED 5=CLOSED
if tonumber(redis.call('HGET', KEYS[4], 'open') or '0') ~= 1 then return 5 end
local startMs = tonumber(redis.call('HGET', KEYS[4], 'start_ms') or '0')
if startMs > 0 and tonumber(ARGV[3]) < startMs then return 4 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 3 end
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then return 2 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[2])
return 1
```

`stock` 键缺失时视为 `FULL` 而不是当作无限库存——缺失说明活动没初始化，放行会造成超卖。这是 Review Focus 里「stock 为 0/负」的防线。

- [ ] **Step 4: 写 `lua/cancel.lua`**

```lua
-- KEYS[1]=stock:{aid} KEYS[2]=enrolled:{aid} KEYS[3]=result:{aid}:{uid}
-- ARGV[1]=userId
-- 返回 1=SUCCESS 2=NOT_FOUND
if redis.call('SREM', KEYS[2], ARGV[1]) == 0 then return 2 end
redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[3])
return 1
```

- [ ] **Step 5: 实现 `RegistrationScripts` 与 `RedisConfig`**

`RegistrationScripts` 用 `@Value("classpath:lua/grab.lua")` 注入脚本文本，构造 `DefaultRedisScript<Long>`（`resultType = Long.class`），通过 `redisTemplate.execute(script, keys, args)` 调用。`RedisConfig` 配置 Lettuce 连接工厂（`127.0.0.1:6379`）与 `StringRedisTemplate`。`RedisKeys` 只做字符串拼接，无逻辑。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=GrabScriptTest`
Expected: PASS —— 5 个用例全绿，其中 `concurrentGrab` 的 `success == 100` 且 `stock == "0"`

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: Redis Lua 原子扣减脚本（含并发零超卖测试）"
```

---

### Task 5: L0 报名链路（直连 MySQL 条件更新）

这是性能基线，也是 D2 要求的长期保留降级链路。同一份 `RegistrationService` 接口，L0 用 DB 实现。

**Files:**
- Create: `src/main/java/com/winter/reg/service/RegistrationService.java`
- Create: `src/main/java/com/winter/reg/service/DbRegistrationService.java`
- Create: `src/main/java/com/winter/reg/web/RegistrationController.java`
- Create: `src/main/java/com/winter/reg/web/dto/RegisterResponse.java`
- Create: `src/main/java/com/winter/reg/service/StockExhaustedException.java`
- Create: `src/main/java/com/winter/reg/web/GlobalExceptionHandler.java`
- Create: `src/main/resources/application-l0.yml`
- Modify: `src/main/java/com/winter/reg/repo/ActivityRepository.java`
- Modify: `src/main/java/com/winter/reg/repo/RegistrationRepository.java`
- Test: `src/test/java/com/winter/reg/service/DbRegistrationServiceTest.java`

**Interfaces:**
- Consumes: Task 4 的 `RedisKeys`（L0 不用，但接口保持统一）、Task 3 的 `ActivityService`
- Produces:
  - `interface RegistrationService { RegResult register(Long activityId, Long userId); }`
  - `enum RegResult { SUCCESS, FULL, ALREADY, NOT_STARTED, CLOSED, NOT_FOUND, RATE_LIMITED, DEGRADED }`
  - `RegistrationRepository.tryConfirm(Long activityId, Long userId) -> int` —— `INSERT ... ON DUPLICATE KEY UPDATE id=id`，返回 0 表示已存在
  - `ActivityRepository.decrementStockIfPositive(Long id) -> int` —— `UPDATE activity SET stock=stock-1 WHERE id=? AND stock>0`
  - `POST /api/registrations/{activityId}`（需登录，userId 取自 token）
  - `application-l0.yml`：`winter.registration.deduct=DB`、`winter.registration.persist=SYNC`

- [ ] **Step 1: 写失败的测试 `DbRegistrationServiceTest`**

```java
@SpringBootTest
@ActiveProfiles("l0")
class DbRegistrationServiceTest {
    @Autowired RegistrationService service;
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrationRepository;
    Long aid;

    @BeforeEach void seed() {
        Activity a = new Activity(); a.setTitle("L0测试"); a.setCapacity(100); a.setStock(100);
        a.setStartTime(LocalDateTime.now().minusMinutes(1)); a.setStatus("OPEN");
        aid = activityRepository.saveAndFlush(a).getId();
    }

    @Test void singleRegisterSucceeds_thenSameUserAlready() {
        assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
        assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.ALREADY);
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(99);
    }
    @Test void notStartedAndClosedRejected() {
        Activity future = new Activity(); future.setTitle("未开始"); future.setCapacity(5); future.setStock(5);
        future.setStartTime(LocalDateTime.now().plusHours(1)); future.setStatus("OPEN");
        Long fid = activityRepository.saveAndFlush(future).getId();
        assertThat(service.register(fid, 7001L)).isEqualTo(RegResult.NOT_STARTED);

        Activity closed = new Activity(); closed.setTitle("已关"); closed.setCapacity(5); closed.setStock(5);
        closed.setStartTime(LocalDateTime.now().minusHours(1)); closed.setStatus("CLOSED");
        Long cid = activityRepository.saveAndFlush(closed).getId();
        assertThat(service.register(cid, 7002L)).isEqualTo(RegResult.CLOSED);
    }
    @Test void unknownActivityReturnsNotFound() {
        assertThat(service.register(999999L, 8001L)).isEqualTo(RegResult.NOT_FOUND);
    }
    @Test void concurrentRegister_noOversell_noDuplicate() throws Exception {
        int threads = 400;   // 远超 capacity=100
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegResult>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long uid = 20_000L + i;
            fs.add(pool.submit(() -> { start.await(); return service.register(aid, uid); }));
        }
        start.countDown();
        long success = fs.stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                         .filter(r -> r == RegResult.SUCCESS).count();
        pool.shutdown();
        assertThat(success).isEqualTo(100);
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(0);
        assertThat(registrationRepository.count()).isEqualTo(100);   // 无重复行
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=DbRegistrationServiceTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现 `DbRegistrationService`**

方法标注 `@Transactional`。顺序是**先插记录、后扣库存**：`tryConfirm` 靠唯一索引挡住重复（返回 0 即 `ALREADY`，此时还没扣库存，不会出现「扣了库存却没记录」）；`decrementStockIfPositive` 返回 0 说明库存空了，此时抛 `StockExhaustedException` 让事务回滚，已插入的那行随之消失。反过来（先扣后插）在唯一索引命中时会少扣库存，是不卖超但会「吞名额」的 bug。

`GlobalExceptionHandler` 把 `StockExhaustedException` 映射为 `ApiResponse.fail(ResultCode.FULL)`，其余异常映射为 `code=1500` 并记录堆栈。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=DbRegistrationServiceTest`
Expected: PASS —— 4 个用例全绿，`concurrentRegister` 的 `success == 100` 且 `stock == 0` 且 `count == 100`

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: L0 报名链路（MySQL 条件更新，含并发零超卖测试）"
```

---

### Task 6: L1 报名链路（Redis 预扣 + 同步落库）

**Files:**
- Create: `src/main/java/com/winter/reg/service/LuaRegistrationService.java`
- Create: `src/main/resources/application-l1.yml`
- Modify: `src/main/java/com/winter/reg/service/ActivityService.java`（发布时写 Redis 活动快照与初始库存）
- Test: `src/test/java/com/winter/reg/service/LuaRegistrationServiceTest.java`

**Interfaces:**
- Consumes: Task 4 的 `RegistrationScripts`/`RedisKeys`、Task 3 的 `ActivityService.create`
- Produces:
  - `LuaRegistrationService implements RegistrationService`
  - `ActivityService.create` 现在同时写入 Redis：`SET stock:{aid} = capacity`、`HSET activity:{aid} start_ms <epochMillis> open 1`
  - `application-l1.yml`：`winter.registration.deduct=REDIS`、`winter.registration.persist=SYNC`

- [ ] **Step 1: 写失败的测试 `LuaRegistrationServiceTest`**

在 Task 5 的用例基础上，把断言改为「响应返回时 DB 行必须已存在」（同步落库与 L2 的唯一区别），并保留并发零超卖用例：

```java
@SpringBootTest
@ActiveProfiles("l1")
class LuaRegistrationServiceTest {
    @Autowired RegistrationService service;
    @Autowired ActivityService activityService;
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired StringRedisTemplate redis;
    Long aid;

    @BeforeEach void seed() {
        aid = activityService.create(new ActivityRequest("L1测试", 100, LocalDateTime.now().minusMinutes(1))).id();
    }
    @AfterEach void clean() {
        redis.delete(RedisKeys.stock(aid)); redis.delete(RedisKeys.enrolled(aid)); redis.delete(RedisKeys.activitySnapshot(aid));
    }

    @Test void successMeansRowAlreadyInDb_syncPersist() {
        assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
        assertThat(registrationRepository.count()).isEqualTo(1);   // 同步落库：不等 MQ
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(99);
    }
    @Test void sameUserAlready_andStockNotDoubleDeducted() {
        assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
        assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.ALREADY);
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(99);
    }
    @Test void notStartedRejected_evenThoughRedisHasStock() {
        ActivityService s2 = ...;  // 用 startTime 在未来的活动
        assertThat(service.register(futureId, 3003L)).isEqualTo(RegResult.NOT_STARTED);
        assertThat(redis.opsForValue().get(RedisKeys.stock(futureId))).isEqualTo("5");  // 库存没被碰
    }
    @Test void concurrentRegister_noOversell_noDuplicate() throws Exception { /* 同 Task 5 结构，capacity=100/threads=400 */ }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=LuaRegistrationServiceTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现 `LuaRegistrationService`**

`register` 流程：`scripts.grab(...)` → `FULL`/`ALREADY`/`NOT_STARTED`/`CLOSED` 直接返回对应 `RegResult`；`SUCCESS` 则按 `winter.registration.persist` 落库（本任务只有 `SYNC` 一支，Task 7 加 `MQ`）。落库用 `tryConfirm` + 失败记对账指标（D1：不撤销）。`ActivityService.create` 增加 Redis 写入，与 DB 写入在同一方法内（活动创建不是热点路径，双写可接受）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=LuaRegistrationServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: L1 报名链路（Redis 预扣 + 同步落库）"
```

---

### Task 7: L2 异步落库（MQ）

**Files:**
- Create: `src/main/java/com/winter/reg/mq/RegistrationEventPublisher.java`
- Create: `src/main/java/com/winter/reg/mq/RegistrationEventConsumer.java`
- Create: `src/main/java/com/winter/reg/mq/BarrierProducer.java`
- Create: `src/main/java/com/winter/reg/mq/MqConfig.java`
- Create: `src/main/resources/application-l2.yml`
- Modify: `src/main/java/com/winter/reg/service/LuaRegistrationService.java`（`persist=MQ` 分支）
- Test: `src/test/java/com/winter/reg/mq/RegistrationEventConsumerTest.java`

**Interfaces:**
- Consumes: Task 6 的 `LuaRegistrationService`、Task 5 的 `tryConfirm`/`decrementStockIfPositive`
- Produces:
  - `enum EventType { CREATE, CANCEL }`
  - `record RegistrationEvent(Long activityId, Long userId, EventType type, String eventId)`
  - `RegistrationEventPublisher.publish(RegistrationEvent) -> boolean`（false = 发送失败）
  - `RegistrationEventConsumer.onMessage(RegistrationEvent)` —— 幂等消费
  - `BarrierProducer.sendBarrier(String topic)`
  - `application-l2.yml`：`winter.registration.persist=MQ`，`winter.mq.namesrv=127.0.0.1:9876`，topic `REGISTRATION`

- [ ] **Step 1: 写失败的测试 `RegistrationEventConsumerTest`**

```java
@SpringBootTest
@ActiveProfiles("l2")
class RegistrationEventConsumerTest {
    @Autowired RegistrationEventPublisher publisher;
    @Autowired RegistrationEventConsumer consumer;
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired StringRedisTemplate redis;
    Long aid;

    @BeforeEach void seed() { /* capacity=100, startTime 过去, status=OPEN, 写 Redis 快照 */ }
    @AfterEach void clean() { /* 删 Redis 键 + 删该活动的 registration 行 */ }

    @Test void publishedEventEventuallyInDb() throws Exception {
        assertThat(publisher.publish(new RegistrationEvent(aid, 1001L, EventType.CREATE, UUID.randomUUID().toString()))).isTrue();
        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(registrationRepository.count()).isEqualTo(1));
    }
    @Test void duplicateEventProducesExactlyOneRow() throws Exception {
        String eid = UUID.randomUUID().toString();
        RegistrationEvent e = new RegistrationEvent(aid, 1001L, EventType.CREATE, eid);
        publisher.publish(e); publisher.publish(e); publisher.publish(e);   // 同一条投三次
        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(registrationRepository.count()).isEqualTo(1));        // 仍只有一行
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(99);  // 只扣一次
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=RegistrationEventConsumerTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现生产者与消费者**

生产者用 `org.apache.rocketmq:rocketmq-client:5.5.1`（**本机已在 Java 21 实测可用的同一版本**，不用 `rocketmq-spring-boot-starter`，避免版本不匹配）。发送带 `eventId`，同步发送、超时 3 秒。topic `REGISTRATION`，tag 用 `EventType`。**不硬编码 broker 名**，用 namesrv `127.0.0.1:9876` 寻址。

消费者按活动批量拉取（`consumeMessageBatchMaxSize` 可配），对每条事件：`CREATE` 走 `tryConfirm`（返回 0 说明已存在，幂等跳过）；`CANCEL` 走「status 改 CANCELED + 库存 +1」。**重复投递不产生第二条记录**靠唯一索引 + `tryConfirm` 的 0 返回值，这是 Review Focus 第 4 条的答案。消费失败返回 `ConsumeConcurrentlyStatus.RECONSUME_LATER`，重试达上限进死信队列并打 ERROR 日志。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=RegistrationEventConsumerTest`
Expected: PASS —— 两个用例全绿，重复投递仍只有一行、库存只扣一次

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: L2 异步落库（RocketMQ 幂等消费）"
```

---

### Task 8: 失败补偿链与对账（D1 + D6）

**Files:**
- Create: `src/main/java/com/winter/reg/wal/WalWriter.java`
- Create: `src/main/java/com/winter/reg/wal/WalReplayer.java`
- Create: `src/main/java/com/winter/reg/service/ReconciliationService.java`
- Create: `src/main/java/com/winter/reg/metrics/RegMetrics.java`
- Test: `src/test/java/com/winter/reg/service/ReconciliationServiceTest.java`
- Test: `src/test/java/com/winter/reg/wal/WalWriterTest.java`

**Interfaces:**
- Consumes: Task 7 的 `RegistrationEventPublisher`、Task 5 的 repository 方法
- Produces:
  - `WalWriter.append(RegistrationEvent) -> void` —— 追加 JSON 行到 `logs/wal/registration-<date>.log`
  - `WalReplayer.replay() -> int` —— 重放未确认事件，返回成功补写的条数
  - `ReconciliationService.reconcile(Long activityId) -> ReconciliationReport`
  - `record ReconciliationReport(int inRedisNotInDb, int inDbNotInRedis, long redisStock, long dbConfirmed)` —— **只报告，不修复 Redis 侧**
  - `RegMetrics` —— `Counter registrationSuccess/full/already/notStarted/closed/rateLimited`、`Counter reconcileMissingInDb`、`Counter walReplayed`、`Counter mqDeadLettered`

- [ ] **Step 1: 写失败的测试 `ReconciliationServiceTest`**

```java
@SpringBootTest
@ActiveProfiles("l2")
class ReconciliationServiceTest {
    @Autowired RegistrationService service;
    @Autowired ReconciliationService reconciliation;
    @Autowired ActivityService activityService;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired StringRedisTemplate redis;
    Long aid;

    @Test void redisHasRecordButDbMissing_isReportedAndBackfilled() throws Exception {
        aid = activityService.create(new ActivityRequest("对账测试", 10, LocalDateTime.now().minusMinutes(1))).id();
        // 直接操作 Redis 造出「用户已成功但 DB 无记录」的状态（模拟 MQ 丢失）
        redis.opsForSet().add(RedisKeys.enrolled(aid), "1001");
        redis.opsForValue().set(RedisKeys.stock(aid), "9");

        ReconciliationReport r = reconciliation.reconcile(aid);
        assertThat(r.inRedisNotInDb()).isEqualTo(1);
        assertThat(r.inDbNotInRedis()).isEqualTo(0);

        await().atMost(10, SECONDS).untilAsserted(() ->       // D1：补写，不撤销
            assertThat(registrationRepository.count()).isEqualTo(1));
        assertThat(redis.opsForSet().isMember(RedisKeys.enrolled(aid), "1001")).isTrue();  // 没被删
        assertThat(redis.opsForValue().get(RedisKeys.stock(aid))).isEqualTo("9");           // 没被回滚
    }
    @Test void consistentStateProducesZeroDiff() throws Exception {
        aid = activityService.create(new ActivityRequest("无差异", 10, LocalDateTime.now().minusMinutes(1))).id();
        assertThat(service.register(aid, 2001L)).isEqualTo(RegResult.SUCCESS);
        await().atMost(10, SECONDS).untilAsserted(() -> assertThat(registrationRepository.count()).isEqualTo(1));
        ReconciliationReport r = reconciliation.reconcile(aid);
        assertThat(r.inRedisNotInDb()).isEqualTo(0);
        assertThat(r.inDbNotInRedis()).isEqualTo(0);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ReconciliationServiceTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现失败补偿链**

按 D6 的顺序接到 `LuaRegistrationService` 的 `persist=MQ` 分支里：`publisher.publish(...)` 返回 false → 立刻同步落库（`tryConfirm`）；同步落库也抛异常 → `WalWriter.append(event)` 落本地文件。`WalReplayer` 由定时任务每 30 秒扫一遍未确认事件重发，成功补写即打点 `walReplayed`。

`ReconciliationService.reconcile` 用 `SMEMBERS enrolled:{aid}` 与 `SELECT user_id FROM registration WHERE activity_id=? AND status='CONFIRMED'` 做集合差：`inRedisNotInDb` 的**补写**（`tryConfirm`），`inDbNotInRedis` 的**只告警不删 DB 记录**（D1：不得删除记录），并把差异数写进 `reconcileMissingInDb` 计数器与 ERROR 日志。定时执行（默认 60 秒），输出 `ReconciliationReport` 指标。

- [ ] **Step 4: 写 `WalWriterTest` 并运行全部测试**

```java
class WalWriterTest {
    @Test void appendThenReplayRoundTrips(@TempDir Path dir) throws Exception {
        WalWriter w = new WalWriter(dir.resolve("wal.log"));
        w.append(new RegistrationEvent(1L, 1001L, EventType.CREATE, "e1"));
        assertThat(Files.readString(dir.resolve("wal.log"))).contains("\"eventId\":\"e1\"");
    }
}
```

Run: `mvn test -Dtest='ReconciliationServiceTest,WalWriterTest'`
Expected: PASS —— 对账补写生效且不回滚；WAL 落盘可读

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 失败补偿链（MQ→同步→WAL→对账）与对账指标"
```

---

### Task 9: 取消报名与名额回池

**Files:**
- Create: `src/main/java/com/winter/reg/service/CancelService.java`
- Create: `src/main/java/com/winter/reg/web/CancelController.java`
- Modify: `src/main/java/com/winter/reg/mq/RegistrationEventConsumer.java`（`CANCEL` 分支）
- Test: `src/test/java/com/winter/reg/service/CancelServiceTest.java`

**Interfaces:**
- Consumes: Task 4 的 `cancel.lua`、Task 7 的 `RegistrationEventPublisher`
- Produces:
  - `interface CancelService { RegResult cancel(Long activityId, Long userId); }` —— 返回 `SUCCESS` / `NOT_FOUND`（未报名或已取消）/ `RATE_LIMITED`
  - `DELETE /api/registrations/{activityId}`（需登录）
  - `RegistrationRepository.reuseRow(Long activityId, Long userId) -> int` —— `UPDATE registration SET status='CONFIRMED', updated_at=CURRENT_TIMESTAMP(3) WHERE activity_id=? AND user_id=? AND status='CANCELED'`

- [ ] **Step 1: 写失败的测试 `CancelServiceTest`**

```java
@SpringBootTest
@ActiveProfiles("l2")
class CancelServiceTest {
    @Autowired CancelService cancelService;
    @Autowired RegistrationService registerService;
    @Autowired ActivityService activityService;
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired StringRedisTemplate redis;
    Long aid;

    @BeforeEach void seed() { aid = activityService.create(new ActivityRequest("取消测试", 5, LocalDateTime.now().minusMinutes(1))).id(); }
    @AfterEach void clean() { /* 清 Redis 键与 registration 行 */ }

    @Test void registerCancelReRegister_reusesSameRow() throws Exception {
        assertThat(registerService.register(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
        await().atMost(10, SECONDS).until(() -> registrationRepository.count() == 1);

        assertThat(cancelService.cancel(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(registrationRepository.findByActivityIdAndUserId(aid, 1001L).get().getStatus())
                .isEqualTo(RegistrationStatus.CANCELED));
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(5);   // 名额回池

        assertThat(cancelService.cancel(aid, 1001L)).isEqualTo(RegResult.NOT_FOUND); // 已取消，再取消无意义

        assertThat(registerService.register(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(registrationRepository.count()).isEqualTo(1);                  // 仍是同一行，没有第二条
            assertThat(registrationRepository.findByActivityIdAndUserId(aid, 1001L).get().getStatus())
                .isEqualTo(RegistrationStatus.CONFIRMED);                             // D5：status 改回
        });
        assertThat(activityRepository.findById(aid).get().getStock()).isEqualTo(4);   // 又扣一次
    }
    @Test void cancelWithoutRegisterReturnsNotFound() {
        assertThat(cancelService.cancel(aid, 9999L)).isEqualTo(RegResult.NOT_FOUND);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=CancelServiceTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现 `CancelService`**

`cancel` 调 `cancel.lua`：返回 `NOT_FOUND` 直接返回；`SUCCESS` 则发 `CANCEL` 事件（发送失败同样走同步/WAL 降级链，与 Task 8 一致）。消费者 `CANCEL` 分支用 `reuseRow` 反向语义的 UPDATE 把记录置 `CANCELED` 并 `stock+1`。`RegistrationRepository` 增加 `findByActivityIdAndUserId(Long, Long) -> Optional<Registration>`。

**关键点：重新报名必须复用同一行**（D5）。`LuaRegistrationService` 的落库分支不能直接用 `INSERT`，要先试 `reuseRow`（更新已存在的 CANCELED 行），返回 0 再 `tryConfirm`。这一步是 Review Focus 第 5 条的答案——唯一索引在这里从「挡重复」变成「保证只有一行」。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=CancelServiceTest`
Expected: PASS —— 取消后重新报名仍只有一行、status 回到 `CONFIRMED`、库存先回池再扣

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 取消报名与名额回池（复用同一行）"
```

---

### Task 10: 压测脚手架与有效性前提

这个任务同时是**作者必须亲手做的一课**。压测是 AI 写的没关系，但作者必须能回答「这些数字为什么可信」。

**Files:**
- Create: `jmeter/grab.jmx`
- Create: `jmeter/grab-broken-same-user.jmx`
- Create: `jmeter/grab-broken-no-assertion.jmx`
- Create: `jmeter/users.csv`（模板 + 生成脚本）
- Create: `jmeter/tokens.csv`
- Create: `bin/seed-users.sh`
- Create: `bin/bench.sh`
- Create: `bin/verify.sh`
- Create: `src/main/java/com/winter/reg/cli/BenchmarkSeeder.java`（或 `bin/seed-users.sh` 直接调 HTTP 接口）
- Create: `docs/benchmarks/README.md`

**Interfaces:**
- Consumes: Task 3/5/6/7/9 的全部接口
- Produces:
  - `bin/bench.sh <level> <threads> <durationSeconds> <capacity>` —— 一条命令跑完压测+屏障+校验+汇总
  - `bin/verify.sh <activityId>` —— 退出码非 0 即校验失败
  - `jmeter/users.csv` 格式：每行一个 `userId,username`（CSV Data Set 注入）
  - `jmeter/tokens.csv` 格式：每行 `userId,token`（预签发，压测中不走登录）

- [ ] **Step 1: 写 `bin/seed-users.sh` 与预生成账号池**

脚本批量注册 10 万个账号（`/api/auth/register`，可并发）并为每个账号调 `/api/auth/login` 拿 token，输出 `jmeter/users.csv`（`userId,username`）与 `jmeter/tokens.csv`（`userId,token`）。这是压测有效性前提 ②——**压测中不走登录**，否则瓶颈在登录接口而不是报名链路。账号池用完后归档，不要每次压测重新生成。

- [ ] **Step 2: 写 `jmeter/grab.jmx`**

线程组结构，五条有效性前提逐条落在配置里：
1. **CSV Data Set Config** 读 `jmeter/tokens.csv`，变量 `userId`/`token`，每线程取不同行 → 前提 ①
2. **HTTP Header Manager** 带 `Authorization: Bearer ${token}` → 前提 ②
3. **HTTP Request** `POST /api/registrations/{activityId}`，body 空，userId 从 token 解析（接口不接收 body 里的 userId，防止伪造）
4. **Synchronizing Timer**（`Number of Simultaneous Users to Group by = ${__P(threads,3000)}`）放在请求前 → 前提 ④，攒够线程同刻释放
5. **Response Assertion**：对响应体匹配 `"code":0` → 前提 ③；配 **JSON Extractor** 把 `code` 提成变量，再用 `SampleResult` 判成功
6. **监听器**：Summary Report + Aggregate Graph + `Simple Data Writer` 写 `.jtl`

- [ ] **Step 3: 写两个「坏」jmx**

`grab-broken-same-user.jmx`：CSV 改成常量 userId（所有线程同一个）。`grab-broken-no-assertion.jmx`：去掉响应断言，按 HTTP 200 判成功。这两个文件是给下一步手工实验用的，**必须入库**，因为它们证明「什么样的压测数据是假的」。

- [ ] **Step 4: 作者亲手跑一次（不可委派）**

1. 启动 `bin/up.sh`，GUI 模式打开 `jmeter/grab.jmx`，线程数调到 50、时长 30 秒，**手动跑一次**，逐个点开 Summary Report / Aggregate Graph / View Results Tree 看每一项含义
2. 打开生成的 `.jtl`，逐列看懂 `timeStamp`/`elapsed`/`label`/`responseCode`/`responseMessage`/`success`/`bytes`/`sentBytes`/`grpThreads`/`allThreads`/`Latency`/`IdleTime`/`Connect`
3. 跑 `grab-broken-same-user.jmx`，对比 `.jtl`：成功数应该接近 1、绝大多数返回 `"code":1002`（已报名）——这就是「测的是去重而非抢名额」
4. 跑 `grab-broken-no-assertion.jmx`，对比 `.jtl`：HTTP 200 但 body 是 `"code":1001`（已满）的样本被算成成功

预期结论写进 `docs/benchmarks/README.md`：**第 3、4 步看到的数据变化，比任何一次漂亮的压测报告都更能说明这些数字为什么可信。**

- [ ] **Step 5: 写 `bin/verify.sh`**

一条命令做正确性校验，退出码非 0 即失败。逐字实现设计文档 D5 的口径：

```bash
# 1. 成功报名数 == 名额上限
CONFIRMED=$(mysql -N -e "SELECT COUNT(*) FROM registration WHERE activity_id=$AID AND status='CONFIRMED'")
[ "$CONFIRMED" -eq "$CAPACITY" ] || fail "CONFIRMED=$CONFIRMED != capacity=$CAPACITY"
# 2. 无重复 (activity_id, user_id) —— 唯一索引已保证，仍显式查一次
DUP=$(mysql -N -e "SELECT COUNT(*) FROM (SELECT activity_id,user_id FROM registration WHERE activity_id=$AID GROUP BY activity_id,user_id HAVING COUNT(*)>1) t")
[ "$DUP" -eq 0 ] || fail "发现 $DUP 组重复报名"
# 3. Redis 库存 == 0
[ "$(redis-cli GET stock:$AID)" = "0" ] || fail "Redis 库存未清零"
# 4. DB 库存 == 0
DBSTOCK=$(mysql -N -e "SELECT stock FROM activity WHERE id=$AID")
[ "$DBSTOCK" -eq 0 ] || fail "DB 库存未清零"
# 5. deadlock 计数（D7）
```
任一条不满足打印 `VERIFY FAILED: <原因>` 并 `exit 1`；全过打印 `VERIFY PASSED`。

- [ ] **Step 6: 写 `bin/bench.sh`**

```bash
bin/bench.sh <level> <threads> <durationSeconds> <capacity>
```
流程：重置环境（删旧活动与 Redis 键）→ 以对应 profile 起应用 → 灌入目标活动（capacity 与入参一致，`startTime` 设为过去以保证已开抢）→ 跑 JMeter 非 GUI + `-e -o` 出 HTML 报告 → **发屏障消息并等消费者追平**（D5：不用「等 N 秒」猜）→ 调 `bin/verify.sh` → 把 QPS/P99/错误率/超卖数写进 `docs/benchmarks/YYYY-MM-DD-L<level>-<说明>.md`。报告必须记录**压测机与应用机的资源分配**（CPU 核数、给 JMeter 的堆大小），这是前提 ⑤。

- [ ] **Step 7: 测试校验脚本本身会失败**

人为注入一次超卖（临时把 `bin/verify.sh` 第 1 条的 `capacity` 改小，或直接往 DB 插一条超出名额的记录），运行 `bin/verify.sh`。
Expected: 退出码非 0，输出 `VERIFY FAILED`。**校验脚本必须能被证明会失败**，否则它的通过没有意义。

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat: 压测脚手架（jmx + 预生成账号池 + bench/verify）"
```

---

### Task 11: 监控与指标

**Files:**
- Create: `src/main/resources/grafana/dashboard.json`
- Create: `docs/monitoring/README.md`
- Modify: `src/main/java/com/winter/reg/metrics/RegMetrics.java`（补齐剩余计数器）
- Modify: `pom.xml`（`micrometer-registry-prometheus`，Task 1 已含则跳过）
- Test: `src/test/java/com/winter/reg/metrics/RegMetricsTest.java`

**Interfaces:**
- Consumes: Task 8 的 `RegMetrics`
- Produces: `GET /actuator/prometheus` 暴露全部计数器；Grafana dashboard JSON 入库

- [ ] **Step 1: 写失败的测试 `RegMetricsTest`**

```java
@SpringBootTest
class RegMetricsTest {
    @Autowired RegMetrics metrics;
    @Autowired MeterRegistry registry;

    @Test void countersAreRegisteredAndIncrement() {
        double before = registry.get("registration_success_total").counter().count();
        metrics.registrationSuccess();
        assertThat(registry.get("registration_success_total").counter().count()).isEqualTo(before + 1);
        assertThat(registry.get("reconcile_missing_in_db_total").counter()).isNotNull();
        assertThat(registry.get("wal_replayed_total").counter()).isNotNull();
        assertThat(registry.get("mq_dead_lettered_total").counter()).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=RegMetricsTest`
Expected: FAIL —— 计数器未注册

- [ ] **Step 3: 实现 `RegMetrics` 与 Actuator 暴露**

用 Micrometer `Counter`，命名 `registration_success_total` / `registration_full_total` / `registration_already_total` / `registration_not_started_total` / `registration_closed_total` / `registration_rate_limited_total` / `reconcile_missing_in_db_total` / `wal_replayed_total` / `mq_dead_lettered_total` / `mq_consume_lag`（Gauge）。在 `RegistrationService`、`ReconciliationService`、`WalReplayer`、消费者的相应分支打点。`application.yml` 暴露 `prometheus` 与 `health`，`management.endpoints.web.exposure.include=prometheus,health,metrics`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=RegMetricsTest`
Expected: PASS

- [ ] **Step 5: 验证 Prometheus 端点并提交 dashboard**

Run: `curl -s localhost:8080/actuator/prometheus | grep registration_success_total`
Expected: 能看到该指标。把 `docs/monitoring/README.md` 写清楚「Grafana 怎么导入这个 dashboard、每个面板看什么、压测时盯哪三个数（QPS / P99 / 错误码分布）」。

```bash
git add -A && git commit -m "feat: Micrometer 指标与 Grafana dashboard"
```

---

### Task 12: L4 限流与频控

**Files:**
- Create: `src/main/java/com/winter/reg/ratelimit/RateLimitFilter.java`
- Create: `src/main/java/com/winter/reg/ratelimit/SlidingWindowCounter.java`
- Create: `src/main/java/com/winter/reg/ratelimit/TokenBucket.java`
- Create: `src/main/resources/application-l4.yml`
- Test: `src/test/java/com/winter/reg/ratelimit/RateLimitTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ApiResponse`/`ResultCode`
- Produces:
  - `TokenBucket(long capacityPerSecond)` —— `boolean tryAcquire()`
  - `SlidingWindowCounter(int maxPerWindow, Duration window)` —— `boolean tryAcquire(String key)`
  - `RateLimitFilter` —— `OncePerRequestFilter`，对 `/api/registrations/**` 生效，超限返回 `ApiResponse.fail(ResultCode.RATE_LIMITED)`
  - `application-l4.yml`：`winter.ratelimit.enabled=true`、`winter.ratelimit.bucket-per-second`、`winter.ratelimit.per-user-per-activity-per-second`

- [ ] **Step 1: 写失败的测试 `RateLimitTest`**

```java
class RateLimitTest {
    @Test void tokenBucketAllowsUpToCapacityThenRejects() {
        TokenBucket b = new TokenBucket(10);
        int ok = 0;
        for (int i = 0; i < 50; i++) if (b.tryAcquire()) ok++;
        assertThat(ok).isEqualTo(10);
    }
    @Test void slidingWindowCountsPerKeyIndependently() {
        SlidingWindowCounter c = new SlidingWindowCounter(3, Duration.ofSeconds(1));
        assertThat(c.tryAcquire("u1")).isTrue();
        assertThat(c.tryAcquire("u1")).isTrue();
        assertThat(c.tryAcquire("u1")).isTrue();
        assertThat(c.tryAcquire("u1")).isFalse();   // 第 4 次超限
        assertThat(c.tryAcquire("u2")).isTrue();    // 别的用户不受影响
    }
    @Test void windowSlides_afterExpiryAllowedAgain() throws Exception {
        SlidingWindowCounter c = new SlidingWindowCounter(2, Duration.ofMillis(200));
        assertThat(c.tryAcquire("u1")).isTrue();
        assertThat(c.tryAcquire("u1")).isTrue();
        assertThat(c.tryAcquire("u1")).isFalse();
        Thread.sleep(260);
        assertThat(c.tryAcquire("u1")).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=RateLimitTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现两个限流器与过滤器**

`TokenBucket` 用 `ScheduledExecutorService` 定时补令牌（惰性补令牌也可以，但定时器更好懂）。`SlidingWindowCounter` 用每 key 一个 `ArrayDeque<Long>` 存时间戳，`tryAcquire` 先清掉窗口外的时间戳再看 size。`RateLimitFilter` 先过全局桶、再过 `userId:activityId` 滑动窗口，任一超限即返回 `RATE_LIMITED`（HTTP 200 + `code=1006`，与全局 HTTP 契约一致）。`winter.ratelimit.enabled=false` 时过滤器直接放行——L0–L3 的压测数字因此不会被限流污染。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=RateLimitTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 令牌桶限流与单用户滑动窗口频控"
```

---

### Task 13: L3 活动详情多级缓存

**Files:**
- Create: `src/main/java/com/winter/reg/cache/ActivityCacheService.java`
- Create: `src/main/java/com/winter/reg/cache/SingleFlightLoader.java`
- Create: `src/main/resources/application-l3.yml`
- Test: `src/test/java/com/winter/reg/cache/ActivityCacheServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ActivityService`、Task 4 的 `RedisKeys`
- Produces:
  - `ActivityCacheService.get(Long activityId) -> ActivityResponse`（`null` 表示不存在，也被缓存）
  - `ActivityCacheService.invalidate(Long activityId)`
  - 缓存层：Caffeine（本地，秒级 + 随机抖动）+ Redis

- [ ] **Step 1: 写失败的测试 `ActivityCacheServiceTest`**

```java
@SpringBootTest
@ActiveProfiles("l3")
class ActivityCacheServiceTest {
    @Autowired ActivityCacheService cache;
    @Autowired ActivityService activityService;
    @Autowired ActivityRepository repository;
    Long aid;

    @BeforeEach void seed() { aid = activityService.create(new ActivityRequest("缓存测试", 100, LocalDateTime.now().plusHours(1))).id(); }

    @Test void secondReadServedFromLocalCache_notFromDb() {
        assertThat(cache.get(aid).title()).isEqualTo("缓存测试");
        repository.deleteById(aid); repository.flush();     // 直接删库里的行
        assertThat(cache.get(aid).title()).isEqualTo("缓存测试");  // 本地缓存仍命中
        cache.invalidate(aid);
        assertThat(cache.get(aid)).isNull();                 // 失效后回源，拿到不存在
    }
    @Test void concurrentMiss_triggersSingleDbLoad() throws Exception {
        repository.deleteById(aid); repository.flush();
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ActivityResponse>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> { start.await(); return cache.get(aid); }));
        start.countDown();
        for (Future<ActivityResponse> f : fs) f.get();
        pool.shutdown();
        // 单飞：50 个并发未命中只应产生 1 次 DB 查询
        assertThat(ActivityCacheService.getDbLoadCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ActivityCacheServiceTest`
Expected: FAIL —— 编译错误

- [ ] **Step 3: 实现 `ActivityCacheService` 与 `SingleFlightLoader`**

`ActivityCacheService` 查询顺序：Caffeine → Redis → DB，逐层回源。三件套对着设计文档 7.3 落地：**防穿透**用「不存在的活动也缓存空值（短 TTL）」；**防击穿**用 `SingleFlightLoader`（同 key 的并发未命中共享一次加载，用 `ConcurrentHashMap<String, CompletableFuture<?>>` 实现，不引第三方库）；**防雪崩**用随机 TTL（`baseTtl + random(0, jitter)`）。Caffeine TTL 与 Redis 同量级并加抖动。`invalidate` 同时清本地与 Redis，活动发布/改库存时调用。

`SingleFlightLoader` 签名：`<T> T load(String key, Supplier<T> loader)`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=ActivityCacheServiceTest`
Expected: PASS —— 二次读命中本地缓存；50 并发未命中只产生 1 次 DB 查询

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 活动详情多级缓存（穿透/击穿/雪崩三件套）"
```

---

### Task 14: 故障注入脚本

**Files:**
- Create: `bin/chaos/redis-kill.sh`
- Create: `bin/chaos/mq-stop.sh`
- Create: `bin/chaos/mq-start.sh`
- Create: `bin/chaos/mysql-delay.sh`
- Create: `bin/chaos/mysql-delay-clear.sh`
- Create: `docs/benchmarks/chaos-results.md`（模板）

**Interfaces:**
- Consumes: Task 10 的 `bin/bench.sh` / `bin/verify.sh`
- Produces: 三个场景的注入与恢复脚本，每个都产出「现象 + 结论」记录

- [ ] **Step 1: 写三个注入脚本**

`redis-kill.sh`：压测进行中 `redis-cli SHUTDOWN NOSAVE`（或用 `DEBUG SLEEP`），随后由外部重启恢复。`mq-stop.sh`/`mq-start.sh`：停/起 broker（namesrv 不动）。`mysql-delay.sh`：给 `winter` 库的连接注入延迟（`SET GLOBAL` 或 `tc` 限速；优先用不波及本机其他库的手段，例如只对应用连接设 `max_execution_time` 或 `tc qdisc` 限定 3306 端口）。`mysql-delay-clear.sh` 恢复。

**每个脚本第一行打印它要做什么、第二行打印恢复方式**，且只作用于本项目的库/端口。

- [ ] **Step 2: 场景一 —— 压测中杀 Redis**

跑 `bin/bench.sh l2 3000 60 500`，跑到第 20 秒执行 `bin/chaos/redis-kill.sh`，跑完执行 `bin/verify.sh`。
Expected: `VERIFY PASSED`（超卖数 = 0）——证明 D2 的降级链路生效：Redis 挂了走 MySQL 条件更新，不多不少。记录 QPS 跌幅与错误码分布。

- [ ] **Step 3: 场景二 —— 停 MQ 一段时间后恢复**

跑 `bin/bench.sh l2 3000 60 500`，第 15 秒 `bin/chaos/mq-stop.sh`，第 35 秒 `bin/chaos/mq-start.sh`，跑完 `bin/verify.sh`。
Expected: `VERIFY PASSED` 且对账最终补平（`reconcile_missing_in_db_total` 归零）——证明 D1 的「只补写」链路没丢单。

- [ ] **Step 4: 场景三 —— MySQL 注入延迟**

跑 `bin/bench.sh l2 3000 60 500`，第 20 秒 `bin/chaos/mysql-delay.sh` 注入 200ms 延迟，第 40 秒清除。
Expected: 用户路径 P99 不被拖垮（报名链路在 Redis 侧完成，落库异步）；MQ 堆积量上升但最终追平。记录堆积曲线。

- [ ] **Step 5: 把三个场景的「现象 + 结论」写进 `docs/benchmarks/chaos-results.md`**

每个场景一节：注入了什么、观察到的指标变化、验证了什么设计决定、暴露了什么新问题。**这一份文件就是 DoD 里「至少 2 个故障注入场景有记录与结论」的交付物。**

```bash
git add -A && git commit -m "feat: 三个故障注入场景及结论记录"
```

---

### Task 15: 极简 Vue 前端

设计文档非目标写明「页面能点、能看结果即可，压测不依赖前端」，D8 定为 Vue。

**Files:**
- Create: `web/package.json`、`web/vite.config.js`、`web/index.html`
- Create: `web/src/main.js`、`web/src/App.vue`
- Create: `web/src/api.js`、`web/src/views/ActivityList.vue`、`web/src/views/ActivityDetail.vue`、`web/src/views/MyRegistrations.vue`
- Modify: `src/main/java/com/winter/reg/config/WebConfig.java`（CORS）
- Modify: `.gitignore`（`node_modules/`、`web/dist/`）

**Interfaces:**
- Consumes: Task 2/3/5/9 的全部 HTTP 接口
- Produces: `npm run dev`（开发）与 `npm run build`（产物由 Spring Boot 静态托管）

- [ ] **Step 1: 写失败的契约测试**

```java
@SpringBootTest
class WebContractTest {
    @Autowired MockMvc mvc;
    @Test void registrationResponseHasCodeField_forFrontend() throws Exception {
        mvc.perform(post("/api/registrations/1").header("Authorization", "Bearer x"))
           .andExpect(jsonPath("$.code").isNumber());   // 前端只依赖 code，不依赖 HTTP 码
    }
}
```

Run: `mvn test -Dtest=WebContractTest`
Expected: PASS（Task 2 已定 `ApiResponse` 契约，此测试把它钉住，防止后人改成靠 HTTP 码判成败）

- [ ] **Step 2: 搭 Vue 3 + Vite 工程**

`npm create vite@latest web -- --template vue`，装 `vue`、`vue-router`、`axios`。`vite.config.js` 设 `server.proxy` 把 `/api` 转 `localhost:8080`。**不上 Pinia/Element Plus/TS**——非目标已排除「精致前端」，多一个依赖就多一处要维护。

- [ ] **Step 3: 写三个视图**

`ActivityList.vue`：列出 `status=OPEN` 的活动（标题、剩余名额、开抢时间）。`ActivityDetail.vue`：详情 + 「报名」按钮，点击调 `POST /api/registrations/{id}`，按 `code` 显示「报名成功 / 已满 / 已报名 / 未开始 / 请求过于频繁」。`MyRegistrations.vue`：我的报名列表 + 「取消」按钮。登录/注册用最简表单存 token 到 `localStorage`。

- [ ] **Step 4: 配 CORS 并验证**

`WebConfig` 放行 `localhost:5173` 的 `GET/POST/DELETE` 与 `Authorization` 头。

Run: `cd web && npm run build && npm run dev`，浏览器打开走一遍「登录 → 看活动 → 报名 → 我的报名 → 取消」
Expected: 五步全通，报名结果显示正确的中文提示

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 极简 Vue 前端（列表/详情/报名/取消）"
```

---

## 阶梯压测执行顺序

计划任务按依赖排好了，但**阶梯数据要按这个顺序采**，否则 before/after 讲不通：

| 顺序 | 命令 | 产出 |
|---|---|---|
| 1 | `bin/bench.sh l0 3000 60 500` | L0 基线：QPS / P99 / 超卖数=0 |
| 2 | `bin/bench.sh l1 3000 60 500` | L1：Redis 预扣后的提升 + 命中率 |
| 3 | `bin/bench.sh l2 3000 60 500` | L2：+MQ 异步落库 + 堆积/消费延迟 |
| 4 | `bin/bench.sh l3 3000 60 500` | L3：+多级缓存 + 缓存命中率 |
| 5 | `bin/bench.sh l4 3000 60 500` | L4：+限流 + 被限流比例、错误码分布 |
| 6 | Task 14 三个场景 | 故障注入记录 |

**每一级必须单独跑一次完整压测 + verify**，不能只跑最终架构然后反推中间数字——设计文档反目标明确写了「优化痕迹丢失：直接一步到位写最终架构，讲不出从多少提升到多少」是失败。

## 自检记录（writing-plans Self-Review）

1. **Spec coverage**：设计文档第 7.1 链路 → Task 4/5/6/7；7.2 三层防线 → Task 1(schema/唯一索引)、Task 4(Lua)、Task 5(条件更新)；7.2 幂等 → Task 4/6/7；7.2 取消 → Task 9；7.2 失败补偿 → Task 8；7.3 削峰 → Task 7；7.3 缓存 → Task 13；7.3 限流 → Task 12；7.4 数据模型 → Task 1；7.5 阶梯 → 全部任务 + 执行顺序表；第 8 节证据链 → Task 10/11/14；D1–D8 → 分别落在 Task 8(D1/D6)、Task 1+4(D2 降级链路)、Task 2(D3)、Task 10(D4/D5)、Task 1+10(D7)、Task 15(D8)。第 10 节 DoD → Task 10 的三条命令、Task 14 的故障记录、Task 11 的监控。
2. **Step scan**：每个 step 只有一种合理解释；代码块只出现在「签名与测试无法确定」的地方（Lua 脚本、SQL schema、bench.sh 流程），其余给签名与断言。
3. **Type consistency**：`RegistrationService.register(Long, Long) -> RegResult` 在 Task 5/6/7/9 一致；`RegistrationScripts.grab(Long, Long, int) -> GrabCode` 在 Task 4/6 一致；`ApiResponse<T>(int code, String msg, T data)` 在 Task 2 之后一致；`RedisKeys` 的键名在所有任务中逐字相同。
4. **Review Focus**：5 条各有归属任务（表内已列），另补 `capacity<=0`（Task 3/6）与压测机瓶颈（Task 10 Step 6）。
5. **Proportion**：设计文档约 195 行，本计划约 600 行。代码块集中在 Lua 脚本（核心算法，规格已定但实现未定）、SQL schema、以及测试断言（必须逐字给出值）。函数体一律不写。

**计划相对设计文档的一处细化（已记录在此，实现时照此）**：设计文档 7.1 把「资格与时间校验」画在 Lua 之外，本计划把时间/状态校验并入 Lua 脚本（读 `activity:{aid}` 快照），一次 Redis 往返完成，同时消除校验与扣减之间的 TOCTOU 窗口。这不改变任何设计决定，只是让 L1/L2 的用户路径真的不含 DB 读。
