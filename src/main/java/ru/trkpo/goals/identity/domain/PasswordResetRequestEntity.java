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

/** Запрос восстановления пароля: код, а после проверки — одноразовый reset-токен. */
@Entity
@Table(schema = "identity", name = "password_reset_requests")
public class PasswordResetRequestEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "code_hash", nullable = false, length = 255)
  private String codeHash;

  @Column(name = "reset_token_hash", length = 255)
  @Nullable
  private String resetTokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "reset_token_expires_at")
  @Nullable
  private Instant resetTokenExpiresAt;

  @Column(name = "consumed_at")
  @Nullable
  private Instant consumedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public PasswordResetRequestEntity() {}

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public void setCodeHash(String codeHash) {
    this.codeHash = codeHash;
  }

  @Nullable
  public String getResetTokenHash() {
    return resetTokenHash;
  }

  public void setResetTokenHash(@Nullable String resetTokenHash) {
    this.resetTokenHash = resetTokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  @Nullable
  public Instant getResetTokenExpiresAt() {
    return resetTokenExpiresAt;
  }

  public void setResetTokenExpiresAt(@Nullable Instant resetTokenExpiresAt) {
    this.resetTokenExpiresAt = resetTokenExpiresAt;
  }

  @Nullable
  public Instant getConsumedAt() {
    return consumedAt;
  }

  public void setConsumedAt(@Nullable Instant consumedAt) {
    this.consumedAt = consumedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
