package ru.trkpo.goals.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.trkpo.goals.identity.domain.SessionEntity;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

  List<SessionEntity> findByUserId(UUID userId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update SessionEntity s
         set s.revokedAt = :now, s.updatedAt = :now
       where s.userId = :userId
         and s.revokedAt is null
      """)
  int revokeAllActive(@Param("userId") UUID userId, @Param("now") Instant now);
}
