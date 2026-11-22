package com.winter.reg.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.winter.reg.auth.JwtService;
import com.winter.reg.domain.User;
import com.winter.reg.mapper.UserMapper;
import com.winter.reg.web.dto.AuthRequest;
import com.winter.reg.web.dto.AuthResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 注册 / 登录。密码只以 BCrypt 哈希落库。 */
@Service
public class AuthService {

  private final UserMapper userMapper;
  private final JwtService jwtService;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public AuthService(UserMapper userMapper, JwtService jwtService) {
    this.userMapper = userMapper;
    this.jwtService = jwtService;
  }

  public AuthResponse register(AuthRequest request) {
    if (userMapper.selectCount(
            new LambdaQueryWrapper<User>().eq(User::getUsername, request.username()))
        > 0) {
      throw new IllegalArgumentException("用户名已被占用");
    }
    User user = new User();
    user.setUsername(request.username());
    user.setPassword(passwordEncoder.encode(request.password()));
    userMapper.insert(user);
    return token(user);
  }

  public AuthResponse login(AuthRequest request) {
    User user = userMapper.selectByUsername(request.username());
    if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
      // 用户名不存在与密码错误给同一条，防止靠返回值枚举出哪些用户名已注册。
      throw new IllegalArgumentException("用户名或密码错误");
    }
    return token(user);
  }

  private AuthResponse token(User user) {
    return new AuthResponse(jwtService.issue(user.getId(), user.getUsername()), user.getId(), user.getUsername());
  }
}
