package com.winter.reg.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常映射。
 *
 * <p>{@link IllegalArgumentException} 是调用方参数错误 → HTTP 200 + {@code code=1500}，与业务契约一致。
 * {@link MethodArgumentNotValidException} 是 {@code @Valid} 拦下的字段校验失败，同样归为 {@code 1500}。
 * {@link HttpMessageNotReadableException} 是请求体根本解析不了（JSON 语法错、字段类型错），
 * 也是调用方的问题，一样归 {@code 1500}——否则客户端把 {@code startTime} 写成空格分隔的
 * {@code 2026-11-23 17:34:57} 而不是 {@code T}，会拿到 HTTP 500 和「internal error」，
 * 压测时看起来像服务端挂了，实际只是自己参数写错。
 * 其余异常是服务端故障 → HTTP 500 + {@code code=1600}，消息只给通用文案，
 * 真实堆栈只进日志，不泄漏给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ApiResponse<Void> handleBadParam(IllegalArgumentException e) {
    return ApiResponse.fail(ResultCode.PARAM_ERROR, e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
    String msg = e.getBindingResult().getFieldErrors().stream()
        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
        .findFirst()
        .orElse("参数错误");
    return ApiResponse.fail(ResultCode.PARAM_ERROR, msg);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
    return ApiResponse.fail(ResultCode.PARAM_ERROR, "请求体格式错误：" + e.getMostSpecificCause().getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("unhandled exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiResponse<>(ResultCode.INTERNAL_ERROR.code(), ResultCode.INTERNAL_ERROR.msg(), null));
  }
}
