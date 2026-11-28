package ru.trkpo.goals.goals.domain;

import java.time.Instant;
import java.util.UUID;

public record GoalCursor(Instant createdAt, UUID id) {}
