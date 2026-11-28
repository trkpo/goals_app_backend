package ru.trkpo.goals.goals.domain;

import java.time.Instant;

public record GoalUpdate(
    String title, String goalText, String pathText, String expectedResult, Instant plannedEndAt) {

  public boolean empty() {
    return title == null
        && goalText == null
        && pathText == null
        && expectedResult == null
        && plannedEndAt == null;
  }
}
