package com.winter.reg.web;

/** 业务码。HTTP 状态恒为 200，成败只看 body 的 {@code code}（压测前提 ③ 的直接结果）。 */
public enum ResultCode {
  SUCCESS(0, "ok"),
  FULL(1001, "名额已满"),
  ALREADY(1002, "已报名过该活动"),
  NOT_STARTED(1003, "未到开抢时间"),
  CLOSED(1004, "活动已关闭"),
  NOT_FOUND(1005, "不存在"),
  RATE_LIMITED(1006, "请求过于频繁"),
  DEGRADED(1007, "服务降级中，请稍后重试"),
  UNAUTHORIZED(1400, "未登录或登录已过期"),
  PARAM_ERROR(1500, "参数错误"),
  /** 服务端自己出了问题，与调用方无关。HTTP 500，堆栈只进日志。 */
  INTERNAL_ERROR(1600, "internal error");

  private final int code;
  private final String msg;

  ResultCode(int code, String msg) {
    this.code = code;
    this.msg = msg;
  }

  public int code() {
    return code;
  }

  public String msg() {
    return msg;
  }
}
