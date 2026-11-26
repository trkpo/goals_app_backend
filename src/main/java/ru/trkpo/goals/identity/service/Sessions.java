package ru.trkpo.goals.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.identity.domain.Identity;
import ru.trkpo.goals.identity.domain.SessionEntity;
import ru.trkpo.goals.identity.domain.UserEntity;
import ru.trkpo.goals.identity.repository.SessionRepository;
import ru.trkpo.goals.identity.repository.UserRepository;

/** Вход, ротация refresh-токена и проверка активного сеанса. */
@Service
public class Sessions {

  public static final Duration REFRESH_TTL = Duration.ofDays(30);
  private static final Pattern REFRESH_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final UserRepository userRepository;
  private final SessionRepository sessionRepository;
  private final PasswordEncoder passwordEncoder;
  private final AccessTokens accessTokens;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public Sessions(
      UserRepository userRepository,
      SessionRepository sessionRepository,
      PasswordEncoder passwordEncoder,
      AccessTokens accessTokens,
      Clock clock) {
    this.userRepository = userRepository;
    this.sessionRepository = sessionRepository;
    this.passwordEncoder = passwordEncoder;
    this.accessTokens = accessTokens;
    this.clock = clock;
  }

  public record Grant(AccessTokens.Token access, String refresh) {}

  public record Login(Identity identity, Grant grant) {}

  @Transactional
  public Login login(@Nullable String email, @Nullable String password) {
    UserEntity user =
        userRepository
            .findActiveByEmail(email)
            .filter(found -> found.getEmailConfirmedAt() != null)
            .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "email"));

    // BCrypt использует не больше 72 байт; длинный ввод отклоняем, а не обрезаем.
    if (password == null
        || password.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new ApiException(ErrorCode.INVALID_PASSWORD, "password");
    }

    Instant now = clock.instant();
    IssuedSession session = createSession(user.getId(), now);
    return new Login(
        identityOf(user), grant(user.getId(), session.session().getId(), session.refresh()));
  }

  @Transactional
  public Grant refresh(@Nullable String refresh) {
    if (refresh == null || !REFRESH_FORMAT.matcher(refresh).matches()) {
      throw new ApiException(ErrorCode.REFRESH_INVALID);
    }

    Instant now = clock.instant();
    SessionEntity previous =
        sessionRepository
            .findByRefreshTokenHash(hash(refresh))
            .filter(session -> session.getRevokedAt() == null)
            .filter(session -> session.getExpiresAt().isAfter(now))
            .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID));

    UserEntity user =
        userRepository
            .findActiveById(previous.getUserId())
            .filter(found -> found.getEmailConfirmedAt() != null)
            .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID));

    IssuedSession replacement = createSession(user.getId(), now);
    previous.setRevokedAt(now);
    previous.setReplacedById(replacement.session().getId());
    previous.setUpdatedAt(now);
    return grant(user.getId(), replacement.session().getId(), replacement.refresh());
  }

  @Transactional(readOnly = true)
  public Identity currentIdentity(UUID userId, @Nullable UUID sessionId) {
    UserEntity user = requireActiveUser(userId);
    requireActiveSession(userId, sessionId);
    return identityOf(user);
  }

  /**
   * Проверяет, что пользователь существует и, если токен привязан к сеансу, что сеанс не отозван.
   * Токены без {@code sid} поддерживаются для совместимости: они действуют, пока жива учётная
   * запись.
   */
  @Transactional(readOnly = true)
  public void requireActive(UUID userId, @Nullable UUID sessionId) {
    requireActiveUser(userId);
    requireActiveSession(userId, sessionId);
  }

  /**
   * Отзывает все сеансы пользователя, кроме текущего. Токены без привязки к сеансу не позволяют
   * выделить текущий, поэтому для них отзываются все сеансы.
   *
   * @return количество отозванных сеансов
   */
  @Transactional
  public int revokeOtherSessions(UUID userId, @Nullable UUID sessionId) {
    Instant now = clock.instant();
    if (sessionId == null) {
      return sessionRepository.revokeAllActive(userId, now);
    }
    return sessionRepository.revokeAllActiveExcept(userId, sessionId, now);
  }

  private UserEntity requireActiveUser(UUID userId) {
    return userRepository
        .findActiveById(userId)
        .filter(user -> user.getEmailConfirmedAt() != null)
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
  }

  private void requireActiveSession(UUID userId, @Nullable UUID sessionId) {
    if (sessionId == null) {
      return;
    }
    sessionRepository
        .findByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(sessionId, userId, clock.instant())
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
  }

  private IssuedSession createSession(UUID userId, Instant now) {
    String refresh = newRefreshToken();
    SessionEntity session = new SessionEntity();
    session.setUserId(userId);
    session.setRefreshTokenHash(hash(refresh));
    session.setIssuedAt(now);
    session.setExpiresAt(now.plus(REFRESH_TTL));
    session.setCreatedAt(now);
    session.setUpdatedAt(now);
    sessionRepository.saveAndFlush(session);
    return new IssuedSession(session, refresh);
  }

  private Grant grant(UUID userId, UUID sessionId, String refresh) {
    return new Grant(accessTokens.issue(userId, sessionId), refresh);
  }

  private record IssuedSession(SessionEntity session, String refresh) {}

  private String newRefreshToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static Identity identityOf(UserEntity user) {
    return new Identity(
        user.getId(), user.getEmail(), user.getEmailConfirmedAt() != null, user.getCreatedAt());
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
