package ru.trkpo.goals.identity.service;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.identity.config.IdentityProperties;
import ru.trkpo.goals.identity.domain.PasswordRecoveryEventEntity;
import ru.trkpo.goals.identity.repository.PasswordRecoveryEventRepository;

/**
 * Лимиты F_Auth_13 и F_Auth_14. События хранятся в базе, а транзакции сериализуются advisory-lock
 * по e-mail, поэтому лимиты соблюдаются и при нескольких репликах сервиса.
 */
@Service
public class RateLimitService {

  private static final String REQUEST_ACTION = "request";
  private static final String VERIFY_ACTION = "verify";

  private final EntityManager entityManager;
  private final PasswordRecoveryEventRepository eventRepository;
  private final IdentityProperties properties;

  public RateLimitService(
      EntityManager entityManager,
      PasswordRecoveryEventRepository eventRepository,
      IdentityProperties properties) {
    this.entityManager = entityManager;
    this.eventRepository = eventRepository;
    this.properties = properties;
  }

  /**
   * @return {@code null}, если запрос разрешён и событие записано; иначе сколько ждать до
   *     следующего запроса
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Nullable
  public Duration recordRequestAttempt(String email, Instant now) {
    advisoryLock("identity:request:" + email);
    Instant latest = eventRepository.findLatest(email, REQUEST_ACTION);
    if (latest != null) {
      Instant nextAllowed = latest.plus(properties.getRequestCooldown());
      if (nextAllowed.isAfter(now)) {
        return Duration.between(now, nextAllowed);
      }
    }
    eventRepository.save(new PasswordRecoveryEventEntity(email, REQUEST_ACTION, now));
    return null;
  }

  /**
   * @return {@code null}, если попытка разрешена и событие записано; иначе сколько ждать до снятия
   *     лимита
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Nullable
  public Duration recordVerifyAttempt(String email, Instant now) {
    advisoryLock("identity:verify:" + email);
    Instant since = now.minus(properties.getVerifyWindow());
    long attempts = eventRepository.countSince(email, VERIFY_ACTION, since);
    if (attempts >= properties.getVerifyLimit()) {
      Instant oldest = eventRepository.findOldestSince(email, VERIFY_ACTION, since);
      Instant retryAt = (oldest != null ? oldest : now).plus(properties.getVerifyWindow());
      return Duration.between(now, retryAt);
    }
    eventRepository.save(new PasswordRecoveryEventEntity(email, VERIFY_ACTION, now));
    return null;
  }

  private void advisoryLock(String key) {
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(hashtext(:key))")
        .setParameter("key", key)
        .getResultList();
  }
}
