package ru.trkpo.goals.goals.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.goals.domain.CreatedGoal;
import ru.trkpo.goals.goals.domain.GoalCreate;
import ru.trkpo.goals.goals.domain.GoalPage;
import ru.trkpo.goals.goals.domain.GoalUpdate;
import ru.trkpo.goals.goals.domain.GoalView;
import ru.trkpo.goals.goals.persistence.GoalStore;

@Service
public class GoalsService {

  private final GoalStore store;

  public GoalsService(GoalStore store) {
    this.store = store;
  }

  @Transactional
  public CreatedGoal create(UUID userId, GoalCreate goal) {
    return store.insert(userId, goal);
  }

  @Transactional(readOnly = true)
  public GoalPage list(UUID userId, List<String> statuses, String sort, int limit, String cursor) {
    var decoded = CursorCodec.decode(cursor);
    var rows = store.findPage(userId, statuses, "created_asc".equals(sort), decoded, limit);
    boolean hasMore = rows.size() > limit;
    var items = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor = null;
    if (hasMore && !items.isEmpty()) {
      var last = items.get(items.size() - 1);
      nextCursor = CursorCodec.encode(last.createdAt(), last.id());
    }
    return new GoalPage(items, nextCursor, items.isEmpty());
  }

  @Transactional(readOnly = true)
  public GoalView get(UUID goalId, UUID userId) {
    return store.findView(goalId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  @Transactional
  public UpdateOutcome update(UUID goalId, UUID userId, Long expectedVersion, GoalUpdate update) {
    var locked =
        store.lock(goalId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    boolean versionMissingForNonDraft = expectedVersion == null && !"draft".equals(locked.status());
    boolean versionMismatch =
        expectedVersion != null && expectedVersion.longValue() != locked.version();
    if (versionMissingForNonDraft || versionMismatch) {
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
