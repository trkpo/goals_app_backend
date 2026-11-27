package ru.trkpo.goals.goals.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.trkpo.goals.goals.domain.CreatedGoal;
import ru.trkpo.goals.goals.domain.GoalCreate;
import ru.trkpo.goals.goals.domain.GoalCursor;
import ru.trkpo.goals.goals.domain.GoalSummary;
import ru.trkpo.goals.goals.domain.GoalUpdate;
import ru.trkpo.goals.goals.domain.GoalView;

@Repository
public class GoalStore {

  private static final String VIEW_SELECT =
      """
      select g.id, g.title, g.goal_text, g.path_text, g.expected_result, g.status,
             g.created_at, g.planned_end_at, g.version,
             r.result as reflection_result, r.fact_text as reflection_fact_text,
             r.self_score as reflection_self_score, r.reflected_at,
             p.id as publication_id
      from goals.goals g
      left join goals.reflections r on r.goal_id = g.id
      left join feed.publications p on p.goal_id = g.id
      """;

  private final JdbcClient db;

  public GoalStore(JdbcClient db) {
    this.db = db;
  }

  public Optional<GoalView> findView(UUID goalId, UUID userId) {
    return db.sql(VIEW_SELECT + " where g.id = :goalId and g.user_id = :userId")
        .param("goalId", goalId)
        .param("userId", userId)
        .query((rs, row) -> view(rs))
        .optional();
  }

  public Optional<LockedGoal> lock(UUID goalId, UUID userId) {
    return db.sql(
            """
            select id, status, version
            from goals.goals
            where id = :goalId and user_id = :userId
            for update
            """)
        .param("goalId", goalId)
        .param("userId", userId)
        .query(
            (rs, row) ->
                new LockedGoal(
                    rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version")))
        .optional();
  }

  public void update(UUID goalId, GoalUpdate update) {
    var sql = new StringBuilder("update goals.goals set version = version + 1, updated_at = now()");
    var params = new LinkedHashMap<String, Object>();
    if (update.title() != null) {
      sql.append(", title = :title");
      params.put("title", update.title());
    }
    if (update.goalText() != null) {
      sql.append(", goal_text = :goalText");
      params.put("goalText", update.goalText());
    }
    if (update.pathText() != null) {
      sql.append(", path_text = :pathText");
      params.put("pathText", update.pathText());
    }
    if (update.expectedResult() != null) {
      sql.append(", expected_result = :expectedResult");
      params.put("expectedResult", update.expectedResult());
    }
    if (update.plannedEndAt() != null) {
      sql.append(", planned_end_at = :plannedEndAt");
      params.put("plannedEndAt", OffsetDateTime.ofInstant(update.plannedEndAt(), ZoneOffset.UTC));
    }
    sql.append(" where id = :goalId");

    var spec = db.sql(sql.toString()).param("goalId", goalId);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    spec.update();
  }

  public CreatedGoal insert(UUID userId, GoalCreate goal) {
    return db.sql(
            """
            insert into goals.goals
                (user_id, title, goal_text, path_text, expected_result, status, planned_end_at)
            values
                (:userId, :title, :goalText, :pathText, :expectedResult,
                 cast(:status as goals.goal_status), cast(:plannedEndAt as timestamptz))
            returning id, status, created_at, planned_end_at, expected_result, version
            """)
        .param("userId", userId)
        .param("title", goal.title())
        .param("goalText", goal.goalText())
        .param("pathText", goal.pathText())
        .param("expectedResult", goal.expectedResult())
        .param("status", goal.publish() ? "active" : "draft")
        .param("plannedEndAt", OffsetDateTime.ofInstant(goal.plannedEndAt(), ZoneOffset.UTC))
        .query(
            (rs, row) ->
                new CreatedGoal(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                    toInstant(rs.getObject("planned_end_at", OffsetDateTime.class)),
                    rs.getString("expected_result"),
                    rs.getLong("version")))
        .single();
  }

  public List<GoalSummary> findPage(
      UUID userId, List<String> statuses, boolean ascending, GoalCursor cursor, int limit) {
    var sql =
        new StringBuilder(
            """
            select g.id, g.title, g.status, g.created_at, g.planned_end_at,
                   coalesce(r.self_score, 0) as self_score
            from goals.goals g
            left join goals.reflections r on r.goal_id = g.id
            where g.user_id = :userId
            """);
    var params = new LinkedHashMap<String, Object>();
    params.put("userId", userId);

    if (!statuses.isEmpty()) {
      sql.append(" and g.status in (");
      for (int index = 0; index < statuses.size(); index++) {
        if (index > 0) {
          sql.append(", ");
        }
        sql.append("cast(:status").append(index).append(" as goals.goal_status)");
        params.put("status" + index, statuses.get(index));
      }
      sql.append(")");
    }

    if (cursor != null) {
      sql.append(
          ascending
              ? " and (g.created_at, g.id) > (:cursorCreatedAt, :cursorId)"
              : " and (g.created_at, g.id) < (:cursorCreatedAt, :cursorId)");
      params.put("cursorCreatedAt", OffsetDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC));
      params.put("cursorId", cursor.id());
    }

    sql.append(
        ascending
            ? " order by g.created_at asc, g.id asc"
            : " order by g.created_at desc, g.id desc");
    sql.append(" limit :limit");
    params.put("limit", limit + 1);

    var spec = db.sql(sql.toString());
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(
            (rs, row) ->
                new GoalSummary(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("status"),
                    toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                    toInstant(rs.getObject("planned_end_at", OffsetDateTime.class)),
                    rs.getDouble("self_score")))
        .list();
  }

  private static GoalView view(ResultSet rs) throws SQLException {
    Number score = (Number) rs.getObject("reflection_self_score");
    return new GoalView(
        rs.getObject("id", UUID.class),
        rs.getString("title"),
        rs.getString("goal_text"),
        rs.getString("path_text"),
        rs.getString("expected_result"),
        rs.getString("status"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("planned_end_at", OffsetDateTime.class)),
        rs.getLong("version"),
        rs.getString("reflection_result"),
        rs.getString("reflection_fact_text"),
        score == null ? null : score.shortValue(),
        toInstant(rs.getObject("reflected_at", OffsetDateTime.class)),
        rs.getObject("publication_id", UUID.class));
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  public record LockedGoal(UUID id, String status, long version) {}
}
