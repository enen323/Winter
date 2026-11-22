package com.winter.reg.auth;

/** token 解析或校验失败。这是安全边界，调用方必须显式处理，不能当成普通 RuntimeException 吞掉。 */
public class JwtException extends RuntimeException {
  public JwtException(String message) {
    super(message);
  }

  public JwtException(String message, Throwable cause) {
    super(message, cause);
  }
}
