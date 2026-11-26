package ru.trkpo.goals.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** Данные учётной записи, безопасные для передачи в слой API. */
public record Identity(UUID id, String email, boolean emailConfirmed, Instant createdAt) {}
