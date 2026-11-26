package ru.trkpo.goals.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import ru.trkpo.goals.identity.config.IdentityProperties;

/** Генерация и проверка секретов восстановления; в базе хранятся только HMAC-хеши. */
@Component
public class RecoverySecrets {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final IdentityProperties properties;

  public RecoverySecrets(IdentityProperties properties) {
    this.properties = properties;
  }

  public String newCode() {
    return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
  }

  public String hashCode(String code) {
    return hmac(code, properties.getCodePepper());
  }

  public boolean matchesCode(String code, String expectedHash) {
    return constantTimeEquals(hashCode(code), expectedHash);
  }

  public String newResetToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "rst_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String hashResetToken(String token) {
    return hmac(token, properties.getResetTokenPepper());
  }

  private static boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String hmac(String value, String pepper) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new IllegalStateException("Не удалось вычислить HMAC", ex);
    }
  }
}
