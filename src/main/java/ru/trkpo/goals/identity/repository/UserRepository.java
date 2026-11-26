package ru.trkpo.goals.identity.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.trkpo.goals.identity.domain.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

  @Query("select u from UserEntity u where lower(u.email) = lower(:email) and u.deletedAt is null")
  Optional<UserEntity> findActiveByEmail(@Param("email") String email);

  @Query("select u from UserEntity u where u.id = :id and u.deletedAt is null")
  Optional<UserEntity> findActiveById(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserEntity u where u.id = :id and u.deletedAt is null")
  Optional<UserEntity> findActiveByIdForUpdate(@Param("id") UUID id);
}
