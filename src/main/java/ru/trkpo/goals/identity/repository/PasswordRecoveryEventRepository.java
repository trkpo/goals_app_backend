package ru.trkpo.goals.identity.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.trkpo.goals.identity.domain.PasswordRecoveryEventEntity;

public interface PasswordRecoveryEventRepository
    extends JpaRepository<PasswordRecoveryEventEntity, UUID> {

  @Query(
      """
      select max(e.createdAt) from PasswordRecoveryEventEntity e
       where e.email = :email and e.action = :action
      """)
  Instant findLatest(@Param("email") String email, @Param("action") String action);

  @Query(
      """
      select count(e) from PasswordRecoveryEventEntity e
       where e.email = :email and e.action = :action and e.createdAt > :since
      """)
  long countSince(
      @Param("email") String email, @Param("action") String action, @Param("since") Instant since);

  @Query(
      """
      select min(e.createdAt) from PasswordRecoveryEventEntity e
       where e.email = :email and e.action = :action and e.createdAt > :since
      """)
  Instant findOldestSince(
      @Param("email") String email, @Param("action") String action, @Param("since") Instant since);
}
