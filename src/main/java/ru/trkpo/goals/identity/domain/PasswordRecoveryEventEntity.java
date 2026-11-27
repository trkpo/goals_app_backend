package ru.trkpo.goals.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Событие запроса или проверки кода для лимитов F_Auth_13 и F_Auth_14. */
@Entity
@Table(schema = "identity", name = "password_recovery_events")
public class PasswordRecoveryEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "email", nullable = false, length = 254)
  private String email;

  @Column(name = "action", nullable = false, length = 16)
  private String action;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected PasswordRecoveryEventEntity() {}

  public PasswordRecoveryEventEntity(String email, String action, Instant createdAt) {
    this.email = email;
    this.action = action;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getAction() {
    return action;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
