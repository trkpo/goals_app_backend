package ru.trkpo.goals.goals.domain;

import java.time.Instant;
import java.util.UUID;

public record GoalSummary(
    UUID id,
    String title,
    String status,
    Instant createdAt,
    Instant plannedEndAt,
    double averageSelfScore) {}
