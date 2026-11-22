package com.winter.reg.web.dto;

/** 注册 / 登录成功后返回的 token。前端存它，之后每个请求带 {@code Authorization: Bearer <token>}。 */
public record AuthResponse(String token, Long userId, String username) {}
