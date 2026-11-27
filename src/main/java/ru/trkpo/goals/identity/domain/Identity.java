package ru.trkpo.goals.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record Identity(UUID id, String email, boolean emailConfirmed, Instant createdAt) {}
