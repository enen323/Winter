package com.winter.reg.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winter.reg.domain.Activity;
import com.winter.reg.domain.Registration;
import com.winter.reg.mapper.ActivityMapper;
import com.winter.reg.mapper.RegistrationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * L0：报名走 MySQL 条件更新，不碰 Redis。
 *
 * <p>本类是 L0 的验收标准。并发用例断言「恰好 {@code == capacity}」而不是「{@code <= capacity}」——
 * 写成 {@code <=} 会把「名额被吞」（只成交 80 个）也算成通过，那是本项目最阴的一类 bug。
 */
@SpringBootTest
@ActiveProfiles("l0")
class DbRegistrationServiceTest {

  @Autowired RegistrationService service;
  @Autowired ActivityMapper activityMapper;
  @Autowired RegistrationMapper registrationMapper;

  Long aid;

  @BeforeEach
  void seed() {
    Activity a = new Activity();
    a.setTitle("L0测试");
    a.setCapacity(100);
    a.setStock(100);
    a.setStartTime(LocalDateTime.now().minusMinutes(1));
    a.setStatus("OPEN");
    activityMapper.insert(a);
    aid = a.getId();
  }

  @AfterEach
  void clean() {
    // 先删子表再删父表，否则 fk_reg_activity 拦着删不掉
    registrationMapper.delete(
        new LambdaQueryWrapper<Registration>().eq(Registration::getActivityId, aid));
    activityMapper.deleteById(aid);
  }

  @Test
  void singleRegisterSucceeds_thenSameUserAlready() {
    assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.SUCCESS);
    assertThat(service.register(aid, 1001L)).isEqualTo(RegResult.ALREADY);
    assertThat(activityMapper.selectById(aid).getStock()).isEqualTo(99);
  }

  @Test
  void notStartedAndClosedRejected() {
    Activity future = new Activity();
    future.setTitle("未开始");
    future.setCapacity(10);
    future.setStock(10);
    future.setStartTime(LocalDateTime.now().plusHours(1));
    future.setStatus("OPEN");
    Long futureId = insertActivity(future);
    assertThat(service.register(futureId, 2001L)).isEqualTo(RegResult.NOT_STARTED);

    Activity closed = new Activity();
    closed.setTitle("已关闭");
    closed.setCapacity(10);
    closed.setStock(10);
    closed.setStartTime(LocalDateTime.now().minusHours(1));
    closed.setStatus("CLOSED");
    Long closedId = insertActivity(closed);
    assertThat(service.register(closedId, 2002L)).isEqualTo(RegResult.CLOSED);

    // CLOSED 与未开始同时成立时返回 CLOSED：与 L1 的 grab.lua 顺序一致，
    // 否则同一请求在 L0/L1 两个级别会得到不同答案（D2 要求降级路径行为一致）
    Activity both = new Activity();
    both.setTitle("既未开始又关闭");
    both.setCapacity(10);
    both.setStock(10);
    both.setStartTime(LocalDateTime.now().plusHours(1));
    both.setStatus("CLOSED");
    Long bothId = insertActivity(both);
    assertThat(service.register(bothId, 2003L)).isEqualTo(RegResult.CLOSED);

    registrationMapper.delete(
        new LambdaQueryWrapper<Registration>().eq(Registration::getActivityId, futureId));
    registrationMapper.delete(
        new LambdaQueryWrapper<Registration>().eq(Registration::getActivityId, closedId));
    registrationMapper.delete(
        new LambdaQueryWrapper<Registration>().eq(Registration::getActivityId, bothId));
    activityMapper.deleteBatchIds(List.of(futureId, closedId, bothId));
  }

  @Test
  void unknownActivityReturnsNotFound() {
    assertThat(service.register(999999L, 8001L)).isEqualTo(RegResult.NOT_FOUND);
  }

  @Test
  void concurrentRegister_noOversell_noDuplicate() throws Exception {
    int threads = 400; // 远超 capacity=100
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<RegResult>> fs = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      final long uid = 20_000L + i; // 互不相同的 userId
      fs.add(pool.submit(() -> {
        start.await();
        return service.register(aid, uid);
      }));
    }
    start.countDown(); // 同刻释放
    long success = fs.stream()
        .map(f -> {
          try {
            return f.get();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        })
        .filter(r -> r == RegResult.SUCCESS)
        .count();
    pool.shutdown();

    assertThat(success).isEqualTo(100); // 恰好 100，不是 <= 100
    assertThat(activityMapper.selectById(aid).getStock()).isEqualTo(0);
    // 计数必须限定在本活动上，否则其他测试 seed 出来的活动会把计数喂饱
    assertThat(registrationCount(aid)).isEqualTo(100);
  }

  private Long insertActivity(Activity a) {
    activityMapper.insert(a);
    return a.getId();
  }

  private long registrationCount(Long activityId) {
    return registrationMapper.selectCount(
        new LambdaQueryWrapper<Registration>().eq(Registration::getActivityId, activityId));
  }
}
