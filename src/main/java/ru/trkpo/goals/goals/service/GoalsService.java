package ru.trkpo.goals.goals.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.goals.domain.GoalUpdate;
import ru.trkpo.goals.goals.domain.GoalView;
import ru.trkpo.goals.goals.persistence.GoalStore;

@Service
public class GoalsService {

  private final GoalStore store;

  public GoalsService(GoalStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public GoalView get(UUID goalId, UUID userId) {
    return store.findView(goalId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  @Transactional
  public UpdateOutcome update(UUID goalId, UUID userId, Long expectedVersion, GoalUpdate update) {
    var locked =
        store.lock(goalId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    if (expectedVersion != null && expectedVersion.longValue() != locked.version()) {
      return new UpdateOutcome(null, locked.version());
    }
    if ("active".equals(locked.status()) && update.goalText() != null) {
      throw new ApiException(ErrorCode.GOAL_LOCKED, "goal_text");
    }
    if (update.plannedEndAt() != null && !update.plannedEndAt().isAfter(Instant.now())) {
      throw new ApiException(ErrorCode.INVALID_PLANNED_END_AT, "planned_end_at");
    }
    if (!update.empty()) {
      store.update(goalId, update);
    }
    var view =
        store.findView(goalId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return new UpdateOutcome(view, null);
  }

  public record UpdateOutcome(GoalView goal, Long conflictVersion) {}
}
