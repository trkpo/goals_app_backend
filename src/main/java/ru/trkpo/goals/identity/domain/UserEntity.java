package ru.trkpo.goals.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Учётная запись из {@code identity.users}. */
@Entity
@Table(schema = "identity", name = "users")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "email", nullable = false, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "email_confirmed_at")
  @Nullable
  private Instant emailConfirmedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "deleted_at")
  @Nullable
  private Instant deletedAt;

  public UserEntity() {}

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  @Nullable
  public Instant getEmailConfirmedAt() {
    return emailConfirmedAt;
  }

  public void setEmailConfirmedAt(@Nullable Instant emailConfirmedAt) {
    this.emailConfirmedAt = emailConfirmedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  @Nullable
  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(@Nullable Instant deletedAt) {
    this.deletedAt = deletedAt;
  }
}
