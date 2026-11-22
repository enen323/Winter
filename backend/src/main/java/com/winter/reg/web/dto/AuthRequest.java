package com.winter.reg.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 注册 / 登录请求体。 */
public record AuthRequest(
    @NotBlank(message = "用户名不能为空") @Size(min = 3, max = 64, message = "用户名长度 3-64") String username,
    @NotBlank(message = "密码不能为空") @Size(min = 6, max = 100, message = "密码长度至少 6 位") String password) {}
