package ru.trkpo.goals.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.identity.config.IdentityProperties;
import ru.trkpo.goals.identity.domain.PasswordResetRequestEntity;
import ru.trkpo.goals.identity.domain.UserEntity;
import ru.trkpo.goals.identity.email.PasswordResetEmail;
import ru.trkpo.goals.identity.email.PasswordResetEmailSender;
import ru.trkpo.goals.identity.repository.PasswordResetRequestRepository;
import ru.trkpo.goals.identity.repository.SessionRepository;
import ru.trkpo.goals.identity.repository.UserRepository;
import ru.trkpo.goals.identity.security.EmailAddress;
import ru.trkpo.goals.identity.security.PasswordPolicy;
import ru.trkpo.goals.identity.security.RecoverySecrets;

/** Восстановление пароля: запрос кода, проверка кода и установка нового пароля. */
@Service
public class PasswordRecoveryService {

  public static final String INVALID_CODE_MESSAGE = "Код подтверждения некорректный";
  public static final String REQUEST_LIMIT_MESSAGE =
      "Новый код можно запросить не чаще одного раза в 5 минут";
  public static final String VERIFY_LIMIT_MESSAGE =
      "Слишком много попыток проверки кода. Попробуйте позже";

  private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
  private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9]{6}$");

  private final UserRepository userRepository;
  private final PasswordResetRequestRepository resetRepository;
  private final SessionRepository sessionRepository;
  private final RateLimitService rateLimitService;
  private final RecoverySecrets secrets;
  private final PasswordEncoder passwordEncoder;
  private final PasswordResetEmailSender emailSender;
  private final IdentityProperties properties;
  private final Clock clock;

  public PasswordRecoveryService(
      UserRepository userRepository,
      PasswordResetRequestRepository resetRepository,
      SessionRepository sessionRepository,
      RateLimitService rateLimitService,
      RecoverySecrets secrets,
      PasswordEncoder passwordEncoder,
      PasswordResetEmailSender emailSender,
      IdentityProperties properties,
      Clock clock) {
    this.userRepository = userRepository;
    this.resetRepository = resetRepository;
    this.sessionRepository = sessionRepository;
    this.rateLimitService = rateLimitService;
    this.secrets = secrets;
    this.passwordEncoder = passwordEncoder;
    this.emailSender = emailSender;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Шаг 1. Ответ одинаков для зарегистрированного и неизвестного адреса (F_Auth_16): код
   * отправляется только владельцу, но тело ответа не раскрывает существование адреса.
   */
  @Transactional
  public CodeSentResult requestCode(String rawEmail) {
    String email = normalizeEmail(rawEmail);
    Instant now = clock.instant();
    UserEntity user = userRepository.findActiveByEmail(email).orElse(null);
    Optional<PasswordResetRequestEntity> active = findActive(user);
    if (active.isPresent() && isDuringCooldown(active.get(), now)) {
      throw requestTooOften(active.get(), now);
    }

    Duration wait = rateLimitService.recordRequestAttempt(email, now);
    if (wait != null) {
      throw tooManyRequests(REQUEST_LIMIT_MESSAGE, wait);
    }

    String code = secrets.newCode();
    String codeHash = secrets.hashCode(code);
    if (user != null) {
      PasswordResetRequestEntity previous =
          resetRepository
              .findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId())
              .orElse(null);
      if (previous != null) {
        // F_Auth_15: старый код перестаёт действовать сразу после запроса нового.
        if (isDuringCooldown(previous, now)) {
          throw requestTooOften(previous, now);
        }
        previous.setConsumedAt(now);
        resetRepository.flush();
      }

      PasswordResetRequestEntity reset = new PasswordResetRequestEntity();
      reset.setUserId(user.getId());
      reset.setCodeHash(codeHash);
      reset.setCreatedAt(now);
      reset.setExpiresAt(now.plus(properties.getCodeTtl()));
      resetRepository.save(reset);
      sendAfterCommit(
          new PasswordResetEmail(
              user.getEmail(), code, Math.max(1, properties.getCodeTtl().toMinutes())));
    }
    return new CodeSentResult(properties.getCodeTtl().toSeconds());
  }

  /** Шаг 2. Проверяет код и выдаёт одноразовый reset-токен (F_Auth_15, F_Auth_17). */
  @Transactional
  public ResetTokenResult verifyCode(String rawEmail, String code) {
    String email = normalizeEmail(rawEmail);
    Instant now = clock.instant();
    Duration wait = rateLimitService.recordVerifyAttempt(email, now);
    if (wait != null) {
      throw tooManyRequests(VERIFY_LIMIT_MESSAGE, wait);
    }

    if (code == null || !CODE_PATTERN.matcher(code).matches()) {
      throw invalidCode();
    }
    UserEntity user = userRepository.findActiveByEmail(email).orElseThrow(this::invalidCode);
    PasswordResetRequestEntity reset =
        resetRepository
            .findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId())
            .orElseThrow(this::invalidCode);

    if (reset.getResetTokenHash() != null
        || reset.getExpiresAt() == null
        || !reset.getExpiresAt().isAfter(now)
        || !secrets.matchesCode(code, reset.getCodeHash())) {
      throw invalidCode();
    }

    String token = secrets.newResetToken();
    reset.setResetTokenHash(secrets.hashResetToken(token));
    reset.setResetTokenExpiresAt(now.plus(properties.getResetTokenTtl()));
    return new ResetTokenResult(token, properties.getResetTokenTtl().toSeconds());
  }

  /** Шаг 3. Устанавливает новый пароль по F_Auth_7 и отзывает все активные сеансы. */
  @Transactional
  public void resetPassword(String resetToken, String newPassword) {
    if (!PasswordPolicy.isValid(newPassword)) {
      throw new ApiException(ErrorCode.WEAK_PASSWORD, "new_password", PasswordPolicy.MESSAGE, null);
    }
    String token = resetToken != null ? resetToken.trim() : "";
    Instant now = clock.instant();
    PasswordResetRequestEntity reset =
        resetRepository
            .findFirstByResetTokenHashOrderByCreatedAtDesc(secrets.hashResetToken(token))
            .orElseThrow(this::invalidResetToken);

    if (reset.getConsumedAt() != null || reset.getResetTokenHash() == null) {
      throw invalidResetToken();
    }
    if (reset.getResetTokenExpiresAt() == null || !reset.getResetTokenExpiresAt().isAfter(now)) {
      throw new ApiException(
          ErrorCode.RESET_TOKEN_EXPIRED,
          "reset_token",
          "Срок действия токена восстановления истёк",
          null);
    }

    UserEntity user =
        userRepository
            .findActiveByIdForUpdate(reset.getUserId())
            .orElseThrow(this::invalidResetToken);
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setVersion(user.getVersion() + 1);
    user.setUpdatedAt(now);
    reset.setConsumedAt(now);
    sessionRepository.revokeAllActive(user.getId(), now);
  }

  private Optional<PasswordResetRequestEntity> findActive(@Nullable UserEntity user) {
    if (user == null) {
      return Optional.empty();
    }
    return resetRepository
        .findByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId())
        .stream()
        .findFirst();
  }

  private boolean isDuringCooldown(PasswordResetRequestEntity reset, Instant now) {
    return reset.getCreatedAt().plus(properties.getRequestCooldown()).isAfter(now);
  }

  private ApiException requestTooOften(PasswordResetRequestEntity reset, Instant now) {
    Instant nextAllowed = reset.getCreatedAt().plus(properties.getRequestCooldown());
    return tooManyRequests(REQUEST_LIMIT_MESSAGE, Duration.between(now, nextAllowed));
  }

  private ApiException tooManyRequests(String message, Duration retryAfter) {
    return new ApiException(ErrorCode.TOO_MANY_REQUESTS, null, message, retryAfter);
  }

  private ApiException invalidCode() {
    return new ApiException(ErrorCode.INVALID_CODE, "code", INVALID_CODE_MESSAGE, null);
  }

  private ApiException invalidResetToken() {
    return new ApiException(
        ErrorCode.RESET_TOKEN_INVALID,
        "reset_token",
        "Токен восстановления пароля недействителен",
        null);
  }

  private String normalizeEmail(String rawEmail) {
    try {
      return EmailAddress.normalize(rawEmail);
    } catch (IllegalArgumentException ex) {
      throw new ApiException(ErrorCode.INVALID_EMAIL, "email", "Некорректный e-mail", null);
    }
  }

  private void sendAfterCommit(PasswordResetEmail message) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              sendSafely(message);
            }
          });
      return;
    }
    sendSafely(message);
  }

  private void sendSafely(PasswordResetEmail message) {
    try {
      emailSender.send(message);
    } catch (RuntimeException ex) {
      log.error("Не удалось отправить код на {}", message.email(), ex);
    }
  }

  public record CodeSentResult(long expiresInSeconds) {}

  public record ResetTokenResult(String resetToken, long expiresInSeconds) {}
}
