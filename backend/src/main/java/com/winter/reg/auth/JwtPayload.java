package com.winter.reg.auth;

/** 校验通过的 token 内容。 */
public record JwtPayload(Long userId, String username, long iat, long exp) {}
