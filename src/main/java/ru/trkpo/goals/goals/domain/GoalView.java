package ru.trkpo.goals.goals.domain;

import java.time.Instant;
import java.util.UUID;

public record GoalView(
    UUID id,
    String title,
    String goalText,
    String pathText,
    String expectedResult,
    String status,
    Instant createdAt,
    Instant plannedEndAt,
    long version,
    String reflectionResult,
    String reflectionFactText,
    Short reflectionSelfScore,
    Instant reflectedAt,
    UUID publicationId) {}
