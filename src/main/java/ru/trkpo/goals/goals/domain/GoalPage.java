package ru.trkpo.goals.goals.domain;

import java.util.List;

public record GoalPage(List<GoalSummary> items, String nextCursor, boolean empty) {}
