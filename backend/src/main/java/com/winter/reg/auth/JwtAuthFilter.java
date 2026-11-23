package com.winter.reg.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.reg.web.ApiResponse;
import com.winter.reg.web.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer token 鉴权。
 *
 * <p>{@code /api/auth/**} 放行（注册登录本来就没 token），其余 {@code /api/**} 必须带有效 token。
 * 非 {@code /api/} 前缀的路径（actuator 等）不拦。校验通过后把 userId 挂到 request attribute 上，
 * 控制器用 {@code @RequestAttribute("userId")} 取，避免每个控制器再解析一遍 token。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  /** request attribute 名，控制器用 {@code @RequestAttribute(JwtAuthFilter.ATTR_USER_ID)} 取。 */
  public static final String ATTR_USER_ID = "userId";

  private final JwtService jwtService;
  private final ObjectMapper json = new ObjectMapper();

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/") || path.startsWith("/api/auth/")) {
      chain.doFilter(request, response);
      return;
    }

    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      reject(response);
      return;
    }
    try {
      Long userId = jwtService.verify(header.substring("Bearer ".length())).userId();
      request.setAttribute(ATTR_USER_ID, userId);
      chain.doFilter(request, response);
    } catch (JwtException e) {
      reject(response);
    }
  }

  private void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getWriter(), ApiResponse.fail(ResultCode.UNAUTHORIZED));
  }
}
