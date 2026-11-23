package com.winter.reg.service;

import com.winter.reg.domain.Activity;
import com.winter.reg.mapper.ActivityMapper;
import com.winter.reg.metrics.RegMetrics;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * L0：报名全程走 MySQL，不碰 Redis。
 *
 * <p>同时是 D2 的长期降级链路——Redis 挂了就回落到这里，所以不会因为上了 L1/L2 而删除。
 * 也正因如此，本类的时间/状态判断顺序必须与 L1 的 {@code grab.lua} 一致
 * （NOT_FOUND → CLOSED → NOT_STARTED），否则同一请求在两个级别会得到不同答案。
 */
@Service
public class DbRegistrationService implements RegistrationService {

  private final ActivityMapper activityMapper;
  private final SyncWriter syncWriter;
  private final RegMetrics metrics;

  public DbRegistrationService(ActivityMapper activityMapper, SyncWriter syncWriter, RegMetrics metrics) {
    this.activityMapper = activityMapper;
    this.syncWriter = syncWriter;
    this.metrics = metrics;
  }

  @Override
  public RegResult register(Long activityId, Long userId) {
    Activity activity = activityMapper.selectById(activityId);
    if (activity == null) {
      return RegResult.NOT_FOUND;
    }
    if (!ActivityService.STATUS_OPEN.equals(activity.getStatus())) {
      metrics.closed.increment();
      return RegResult.CLOSED;
    }
    if (activity.getStartTime().isAfter(LocalDateTime.now())) {
      metrics.notStarted.increment();
      return RegResult.NOT_STARTED;
    }
    try {
      RegResult written = syncWriter.write(activityId, userId);
      if (written == RegResult.SUCCESS) {
        metrics.success.increment();
      } else {
        metrics.already.increment();
      }
      return written;
    } catch (StockExhaustedException e) {
      // SyncWriter 里抛异常不是为了报错，是为了让 @Transactional 回滚，
      // 把刚插入的那行撤掉。异常在这里终结，不往控制器外传。
      metrics.full.increment();
      return RegResult.FULL;
    }
  }
}
