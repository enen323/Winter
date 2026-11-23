package com.winter.reg.service;

import com.winter.reg.mapper.ActivityMapper;
import com.winter.reg.metrics.RegMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 报名链路的级别选择。
 *
 * <p>用 {@code @ConditionalOnProperty} 选 bean，不用 if-else 工厂。**刻意不加
 * {@code matchIfMissing=true}**：{@code winter.registration.flow} 写错（比如小写成 {@code l0}）
 * 时三个条件全不命中，启动报 {@code No qualifying bean of type 'RegistrationService'}——
 * 这是想要的失败方式。给了默认值会把打错的值静默变成 L0，让人在错误的级别上跑完整轮压测。
 */
@Configuration
public class RegistrationConfig {

  @Bean
  @ConditionalOnProperty(name = "winter.registration.flow", havingValue = "L0")
  public RegistrationService dbRegistrationService(
      ActivityMapper activityMapper, SyncWriter syncWriter, RegMetrics metrics) {
    return new DbRegistrationService(activityMapper, syncWriter, metrics);
  }
}
