package ru.trkpo.goals.goals.domain;

import java.time.Instant;

public record GoalCreate(
    String title,
    String goalText,
    String pathText,
    String expectedResult,
    Instant plannedEndAt,
    boolean publish) {}
