package ru.trkpo.goals.identity.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.trkpo.goals.identity.domain.SessionEntity;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<SessionEntity> findByRefreshTokenHash(String refreshTokenHash);

  Optional<SessionEntity> findByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(
      UUID id, UUID userId, Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update SessionEntity s
         set s.revokedAt = :now, s.updatedAt = :now
       where s.userId = :userId
         and s.revokedAt is null
      """)
  int revokeAllActive(@Param("userId") UUID userId, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update SessionEntity s
         set s.revokedAt = :now, s.updatedAt = :now
       where s.userId = :userId
         and s.id <> :keepSessionId
         and s.revokedAt is null
      """)
  int revokeAllActiveExcept(
      @Param("userId") UUID userId,
      @Param("keepSessionId") UUID keepSessionId,
      @Param("now") Instant now);
}
