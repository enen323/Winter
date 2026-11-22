package com.winter.reg.domain;

/** 报名记录状态。取值与 DB 里的字符串一致（{@code @Enumerated(EnumType.STRING)}）。 */
public enum RegistrationStatus {
  /** 已占用名额。 */
  CONFIRMED,

  /** 已取消，名额已回池；重新报名复用同一行。 */
  CANCELED
}
