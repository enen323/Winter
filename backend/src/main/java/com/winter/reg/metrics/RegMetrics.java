package com.winter.reg.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 报名链路的计数器。
 *
 * <p>字段直接暴露、不包 {@code incrementSuccess()} 之类的方法——调用处写
 * {@code metrics.success.increment()} 一样短，还少 9 个方法。
 *
 * <p>L0 只建它用得到的 5 个；{@code registration_rate_limited_total} 等 L4、
 * {@code reconcile_missing_in_db_total} / {@code wal_replayed_total} / {@code mq_dead_lettered_total}
 * 等 L2.5 再加，现在建了也没有人自增。
 */
@Component
public class RegMetrics {

  public final Counter success;
  public final Counter full;
  public final Counter already;
  public final Counter notStarted;
  public final Counter closed;

  public RegMetrics(MeterRegistry registry) {
    success = registry.counter("registration_success_total");
    full = registry.counter("registration_full_total");
    already = registry.counter("registration_already_total");
    notStarted = registry.counter("registration_not_started_total");
    closed = registry.counter("registration_closed_total");
  }
}
