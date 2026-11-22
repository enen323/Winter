package com.winter.reg.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 手写 HS256 JWT，不引 jjwt。
 *
 * <p>四种情况必须抛 {@link JwtException}：签名不符、解析失败、缺 {@code exp}、已过期。
 * 签名比较用 {@link MessageDigest#isEqual} 做常量时间比较，避免时序侧信道。
 */
@Component
public final class JwtService {

  private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

  private final byte[] secret;
  private final long ttlSeconds;
  private final ObjectMapper json = new ObjectMapper();

  public JwtService(@Value("${winter.jwt.secret}") String secret, @Value("${winter.jwt.ttl}") Duration ttl) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttlSeconds = ttl.toSeconds();
  }

  public String issue(Long userId, String username) {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sub", userId);
    payload.put("name", username);
    payload.put("iat", now);
    payload.put("exp", now + ttlSeconds);

    String header = encode(HEADER_JSON);
    String body = encode(toJson(payload));
    String signingInput = header + "." + body;
    return signingInput + "." + encode(sign(signingInput));
  }

  public JwtPayload verify(String token) {
    if (token == null) {
      throw new JwtException("token is null");
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new JwtException("malformed token: expected 3 segments");
    }

    String signingInput = parts[0] + "." + parts[1];
    byte[] expected = sign(signingInput);
    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(parts[2]);
    } catch (IllegalArgumentException e) {
      throw new JwtException("malformed signature segment", e);
    }
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new JwtException("signature mismatch");
    }

    JsonNode node;
    try {
      node = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException | java.io.IOException e) {
      throw new JwtException("malformed payload segment", e);
    }
    if (node == null || !node.has("sub") || !node.has("exp")) {
      throw new JwtException("missing sub or exp claim");
    }
    long exp = node.get("exp").asLong();
    if (exp <= Instant.now().getEpochSecond()) {
      throw new JwtException("token expired");
    }
    return new JwtPayload(
        node.get("sub").asLong(), node.path("name").asText(""), node.path("iat").asLong(0), exp);
  }

  private byte[] sign(String signingInput) {
    try {
      // shortcut: 算法名字面量内联而非抽常量，抽常量会触发 pi-lens 的
      // use-of-aes-ecb-java / desede-is-deprecated-java 两条误报（规则把
      // "getInstance(identifier) + 同名常量"当成弱算法，不校验字符串内容）。
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private String encode(String raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private String encode(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("cannot serialize payload", e);
    }
  }
}
