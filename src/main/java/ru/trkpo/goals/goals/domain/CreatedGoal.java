package ru.trkpo.goals.goals.domain;

import java.time.Instant;
import java.util.UUID;

public record CreatedGoal(
    UUID id,
    String status,
    Instant createdAt,
    Instant plannedEndAt,
    String expectedResult,
    long version) {}
