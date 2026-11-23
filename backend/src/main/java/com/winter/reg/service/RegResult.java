package com.winter.reg.service;

/** 报名链路的返回。控制器按它映射 {@code ResultCode}。 */
public enum RegResult {
  SUCCESS,
  FULL,
  ALREADY,
  NOT_STARTED,
  CLOSED,
  NOT_FOUND,
  RATE_LIMITED,
  DEGRADED
}
