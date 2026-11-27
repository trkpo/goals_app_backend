package ru.trkpo.goals.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.identity.domain.Identity;
import ru.trkpo.goals.identity.persistence.IdentityStore;

@Service
public class Sessions {

  public static final Duration REFRESH_TTL = Duration.ofDays(30);
  private static final Pattern REFRESH_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final IdentityStore store;
  private final PasswordEncoder passwords;
  private final AccessTokens accessTokens;
  private final SecureRandom random = new SecureRandom();

  public Sessions(IdentityStore store, PasswordEncoder passwords, AccessTokens accessTokens) {
    this.store = store;
    this.passwords = passwords;
    this.accessTokens = accessTokens;
  }

  public record Grant(AccessTokens.Token access, String refresh) {}

  public record Login(Identity identity, Grant grant) {}

  @Transactional
  public Login login(String email, String password) {
    var credentials =
        store
            .findCredentials(email)
            .filter(found -> found.identity().emailConfirmed())
            .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "email"));

    // BCrypt uses at most 72 UTF-8 bytes. Avoid truncation and reject oversized input.
    if (password.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(password, credentials.passwordHash())) {
      throw new ApiException(ErrorCode.INVALID_PASSWORD, "password");
    }

    UUID userId = credentials.identity().id();
    String refresh = newRefreshToken();
    store.createSession(userId, hash(refresh), REFRESH_TTL);
    return new Login(credentials.identity(), new Grant(accessTokens.issue(userId), refresh));
  }

  @Transactional
  public Grant refresh(String refresh) {
    if (refresh == null || !REFRESH_FORMAT.matcher(refresh).matches()) {
      throw new ApiException(ErrorCode.REFRESH_INVALID);
    }

    // The row lock and both writes share one transaction. A second request sees the revocation.
    var previous =
        store
            .lockActiveSession(hash(refresh))
            .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID));
    String replacement = newRefreshToken();
    UUID replacementId = store.createSession(previous.userId(), hash(replacement), REFRESH_TTL);
    store.markReplaced(previous.id(), replacementId);
    return new Grant(accessTokens.issue(previous.userId()), replacement);
  }

  @Transactional(readOnly = true)
  public Identity currentIdentity(UUID id) {
    return store
        .findConfirmedIdentity(id)
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
  }

  private String newRefreshToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hash(String refresh) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(refresh.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
