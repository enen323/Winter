package com.winter.reg.web;

/** 统一响应体。HTTP 状态恒为 200，成败只看 {@link #code()}。 */
public record ApiResponse<T>(int code, String msg, T data) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.msg(), data);
  }

  public static ApiResponse<Void> ok() {
    return ok(null);
  }

  public static <T> ApiResponse<T> fail(ResultCode resultCode) {
    return new ApiResponse<>(resultCode.code(), resultCode.msg(), null);
  }

  public static <T> ApiResponse<T> fail(ResultCode resultCode, String msg) {
    return new ApiResponse<>(resultCode.code(), msg, null);
  }
}
