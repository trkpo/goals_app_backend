package ru.trkpo.goals.identity.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import ru.trkpo.goals.identity.domain.PasswordResetRequestEntity;

public interface PasswordResetRequestRepository
    extends JpaRepository<PasswordResetRequestEntity, UUID> {

  List<PasswordResetRequestEntity> findByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PasswordResetRequestEntity> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(
      UUID userId);

  Optional<PasswordResetRequestEntity> findFirstByResetTokenHashOrderByCreatedAtDesc(
      String resetTokenHash);
}
