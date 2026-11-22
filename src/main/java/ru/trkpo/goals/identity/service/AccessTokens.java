package ru.trkpo.goals.identity.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;

@Component
public class AccessTokens {

  private static final String ISSUER = "goals-app-backend";
  private static final Duration TTL = Duration.ofHours(1);
  private static final JwsHeader HEADER = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

  private final JwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  public AccessTokens(@Value("${AUTH_JWT_SECRET:}") String configuredSecret) {
    var secret = new SecretKeySpec(signingKey(configuredSecret), "HmacSHA256");
    encoder = NimbusJwtEncoder.withSecretKey(secret).algorithm(MacAlgorithm.HS256).build();
    decoder = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtIssuerValidator(ISSUER), new JwtTimestampValidator(Duration.ZERO)));
  }

  public record Token(String value, long expiresIn) {}

  public Token issue(UUID userId) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(TTL))
            .id(UUID.randomUUID().toString())
            .build();
    String value = encoder.encode(JwtEncoderParameters.from(HEADER, claims)).getTokenValue();
    return new Token(value, TTL.toSeconds());
  }

  public UUID authenticate(String token) {
    try {
      var jwt = decoder.decode(token);
      if (jwt.getExpiresAt() == null || jwt.getSubject() == null) {
        throw new ApiException(ErrorCode.UNAUTHORIZED);
      }
      return UUID.fromString(jwt.getSubject());
    } catch (JwtException | IllegalArgumentException exception) {
      throw new ApiException(ErrorCode.UNAUTHORIZED);
    }
  }

  private static byte[] signingKey(String configuredSecret) {
    if (configuredSecret.isEmpty()) {
      // Local bootstrapping and single-instance deployments can use a process-scoped key.
      byte[] generated = new byte[32];
      new SecureRandom().nextBytes(generated);
      return generated;
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(configuredSecret);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("AUTH_JWT_SECRET must be Base64-encoded");
    }
    if (decoded.length < 32) {
      throw new IllegalStateException("AUTH_JWT_SECRET must decode to at least 32 bytes");
    }
    return decoded;
  }
}
