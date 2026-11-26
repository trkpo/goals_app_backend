package ru.trkpo.goals.profiles.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.identity.domain.UserEntity;
import ru.trkpo.goals.identity.repository.UserRepository;
import ru.trkpo.goals.identity.security.PasswordPolicy;
import ru.trkpo.goals.identity.service.Sessions;

/** F_Prof_5: смена пароля с проверкой текущего и отзывом прочих сеансов. */
@Service
public class PasswordChangeService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final Sessions sessions;
  private final Clock clock;

  public PasswordChangeService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      Sessions sessions,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.sessions = sessions;
    this.clock = clock;
  }

  @Transactional
  public void changePassword(
      UUID userId,
      @Nullable UUID currentSessionId,
      @Nullable String currentPassword,
      @Nullable String newPassword) {
    UserEntity user =
        userRepository
            .findActiveByIdForUpdate(userId)
            .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

    // BCrypt сравнивает не больше 72 байт; длинный ввод отклоняем, а не обрезаем.
    if (currentPassword == null
        || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw new ApiException(ErrorCode.INVALID_PASSWORD, "current_password");
    }

    if (!PasswordPolicy.isValid(newPassword)) {
      throw new ApiException(ErrorCode.WEAK_PASSWORD, "new_password", PasswordPolicy.MESSAGE);
    }

    Instant now = clock.instant();
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setVersion(user.getVersion() + 1);
    user.setUpdatedAt(now);

    // NF_Sec_4: текущий сеанс продолжает работать, все остальные отзываются.
    sessions.revokeOtherSessions(userId, currentSessionId);
  }
}
