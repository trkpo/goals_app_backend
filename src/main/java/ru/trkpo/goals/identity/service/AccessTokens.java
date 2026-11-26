package ru.trkpo.goals.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Выпуск и проверка access-токенов (JWT, HS256). */
@Component
public class AccessTokens {

  private static final String ISSUER = "goals-app-backend";
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Duration TTL = Duration.ofHours(1);
  private static final String HEADER =
      base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII));
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final byte[] signingKey;

  public AccessTokens(@Value("${AUTH_JWT_SECRET:}") String configuredSecret) {
    this.signingKey = signingKey(configuredSecret);
  }

  public record Token(String value, long expiresIn) {}

  public record Claims(UUID userId, @Nullable UUID sessionId) {}

  public Token issue(UUID userId, UUID sessionId) {
    Instant now = Instant.now();
    long expiresAt = now.plus(TTL).getEpochSecond();
    String payload =
        """
        {"iss":"%s","sub":"%s","iat":%d,"exp":%d,"jti":"%s","sid":"%s"}
        """
            .formatted(
                ISSUER, userId, now.getEpochSecond(), expiresAt, UUID.randomUUID(), sessionId)
            .trim();
    String unsigned = HEADER + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
    String signature = base64Url(sign(unsigned));
    return new Token(unsigned + "." + signature, TTL.toSeconds());
  }

  public Claims authenticate(String token) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
        throw unauthorized();
      }

      byte[] expected = sign(parts[0] + "." + parts[1]);
      byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
      if (!MessageDigest.isEqual(expected, actual)) {
        throw unauthorized();
      }

      JsonNode header = JSON.readTree(decode(parts[0]));
      if (!"HS256".equals(header.path("alg").asString())) {
        throw unauthorized();
      }

      JsonNode payload = JSON.readTree(decode(parts[1]));
      if (!ISSUER.equals(payload.path("iss").asString())) {
        throw unauthorized();
      }
      if (payload.path("exp").asLong(0) <= Instant.now().getEpochSecond()) {
        throw unauthorized();
      }
      String subject = payload.path("sub").asString();
      if (subject == null) {
        throw unauthorized();
      }
      String sid = payload.path("sid").asString();
      return new Claims(UUID.fromString(subject), sid == null ? null : UUID.fromString(sid));
    } catch (ApiException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unauthorized();
    }
  }

  private byte[] sign(String value) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
      return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
      throw new IllegalStateException("HS256 is unavailable", exception);
    }
  }

  private static ApiException unauthorized() {
    return new ApiException(ErrorCode.UNAUTHORIZED);
  }

  private static String decode(String part) {
    return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] signingKey(String configuredSecret) {
    if (configuredSecret.isEmpty()) {
      // Локальный запуск: ключ живёт вместе с процессом, refresh-cookie переживает перезапуск.
      byte[] generated = new byte[32];
      new SecureRandom().nextBytes(generated);
      return generated;
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(configuredSecret);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("AUTH_JWT_SECRET must be Base64-encoded", exception);
    }
    if (decoded.length < 32) {
      throw new IllegalStateException("AUTH_JWT_SECRET must decode to at least 32 bytes");
    }
    return decoded;
  }
}
