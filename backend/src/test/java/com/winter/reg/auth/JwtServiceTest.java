package com.winter.reg.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtService 的四种拒绝情况必须有测试钉住：签名不符、解析失败、缺 exp、已过期。
 * 这是安全边界——登录接口是整套认证的唯一入口，它错了后面全错。
 */
class JwtServiceTest {

  private static final String SECRET = "test-secret-32-bytes-minimum-length!";

  private final JwtService svc = new JwtService(SECRET, Duration.ofMinutes(30));

  @Test
  void issueThenVerifyRoundTrips() {
    String token = svc.issue(1001L, "alice");

    JwtPayload payload = svc.verify(token);

    assertThat(payload.userId()).isEqualTo(1001L);
    assertThat(payload.username()).isEqualTo("alice");
    assertThat(payload.iat()).isGreaterThan(0L);
    assertThat(payload.exp()).isGreaterThan(payload.iat());
  }

  @Test
  void tamperedPayloadRejected() {
    String token = svc.issue(1001L, "alice");
    String[] parts = token.split("\\.");
    // 换掉 payload 但保留原签名 —— 典型的「改 sub 提权」攻击
    String evilPayload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"9999\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
    String forged = parts[0] + "." + evilPayload + "." + parts[2];

    assertThatThrownBy(() -> svc.verify(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void expiredTokenRejected() {
    JwtService shortLived = new JwtService(SECRET, Duration.ofSeconds(-1));
    String token = shortLived.issue(1001L, "alice");

    assertThatThrownBy(() -> svc.verify(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void wrongSecretRejected() {
    String token = svc.issue(1001L, "alice");
    JwtService attacker = new JwtService("different-secret-also-32-bytes-xx!", Duration.ofMinutes(30));

    assertThatThrownBy(() -> attacker.verify(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void malformedTokenRejected() {
    assertThatThrownBy(() -> svc.verify("not-a-token")).isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> svc.verify("a.b")).isInstanceOf(JwtException.class);
  }
}
