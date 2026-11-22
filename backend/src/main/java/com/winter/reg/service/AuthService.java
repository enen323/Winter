package com.winter.reg.service;

import com.winter.reg.auth.JwtService;
import com.winter.reg.domain.User;
import com.winter.reg.repo.UserRepository;
import com.winter.reg.web.dto.AuthRequest;
import com.winter.reg.web.dto.AuthResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册 / 登录。密码只以 BCrypt 哈希落库。 */
@Service
public class AuthService {

  private final UserRepository userRepository;
  private final JwtService jwtService;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public AuthService(UserRepository userRepository, JwtService jwtService) {
    this.userRepository = userRepository;
    this.jwtService = jwtService;
  }

  @Transactional
  public AuthResponse register(AuthRequest request) {
    if (userRepository.existsByUsername(request.username())) {
      throw new IllegalArgumentException("用户名已被占用");
    }
    User user = new User();
    user.setUsername(request.username());
    user.setPassword(passwordEncoder.encode(request.password()));
    User saved = userRepository.save(user);
    return token(saved);
  }

  @Transactional(readOnly = true)
  public AuthResponse login(AuthRequest request) {
    User user = userRepository
        .findByUsername(request.username())
        .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
      throw new IllegalArgumentException("用户名或密码错误");
    }
    return token(user);
  }

  private AuthResponse token(User user) {
    return new AuthResponse(jwtService.issue(user.getId(), user.getUsername()), user.getId(), user.getUsername());
  }
}
