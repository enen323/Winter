package com.winter.reg.service;

/** 报名链路。L0/L1/L2 各一个实现，由 {@code winter.registration.flow} 选择其一（见 {@code RegistrationConfig}）。 */
public interface RegistrationService {

  /**
   * @param activityId 活动 id
   * @param userId 取自 token，不信任客户端传来的
   * @return 永不抛业务异常；{@code SUCCESS} 表示名额已到手且不可撤销（D1：同步终态）
   */
  RegResult register(Long activityId, Long userId);
}
