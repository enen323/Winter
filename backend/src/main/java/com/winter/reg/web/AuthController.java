package com.winter.reg.web;

import com.winter.reg.service.AuthService;
import com.winter.reg.web.dto.AuthRequest;
import com.winter.reg.web.dto.AuthResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 注册 / 登录。这两个路径被 {@code JwtAuthFilter} 放行 —— 本来就没有 token。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  public ApiResponse<AuthResponse> register(@Valid @RequestBody AuthRequest request) {
    return ApiResponse.ok(authService.register(request));
  }

  @PostMapping("/login")
  public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
    return ApiResponse.ok(authService.login(request));
  }
}
