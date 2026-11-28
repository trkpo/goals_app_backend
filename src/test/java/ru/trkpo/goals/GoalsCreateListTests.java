package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.trkpo.goals.identity.service.AccessTokens;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GoalsCreateListTests {

  private static final String GOALS = "/api/v1/goals";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient db;
  @Autowired private AccessTokens tokens;
  @Autowired private JsonMapper json;

  private UUID userId;
  private UUID draftGoalId;
  private UUID activeGoalId;
  private UUID completedGoalId;
  private String token;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    draftGoalId = UUID.randomUUID();
    activeGoalId = UUID.randomUUID();
    completedGoalId = UUID.randomUUID();
    insertUser(userId);
    insertGoal(draftGoalId, "Draft goal", "draft", 3, null, 1);
    insertGoal(activeGoalId, "Active goal", "active", 2, null, 2);
    insertGoal(completedGoalId, "Completed goal", "completed", 1, 8, 3);
    token = tokens.issue(userId).value();
  }

  @Test
  void createIsIdempotentAndReturnsLocation() throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "title",
                "New goal",
                "goal_text",
                "Goal text",
                "path_text",
                "Path text",
                "expected_result",
                "Expected result",
                "planned_end_at",
                Instant.now().plus(Duration.ofDays(30)).toString(),
                "publish",
                false));

    MvcResult created =
        mvc.perform(
                post(GOALS)
                    .header(HttpHeaders.AUTHORIZATION, auth())
                    .header("Idempotency-Key", "create-goal-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.title").doesNotExist())
            .andReturn();

    String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
    mvc.perform(
            post(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header("Idempotency-Key", "create-goal-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, location))
        .andExpect(content().bytes(created.getResponse().getContentAsByteArray()));

    assertThat(
            db.sql(
                    "select count(*) from goals.goals where user_id = :userId and title = 'New goal'")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(1);

    mvc.perform(
            post(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header("Idempotency-Key", "create-goal-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("New goal", "Other goal")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void createValidatesTitleAndPublishesActiveGoal() throws Exception {
    mvc.perform(
            post(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"ab","goal_text":"Text","path_text":"Path",
                     "expected_result":"Expected","planned_end_at":"2030-01-01T00:00:00Z"}
                    """))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("TITLE_LENGTH"))
        .andExpect(jsonPath("$.field").value("title"));

    mvc.perform(
            post(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"Active goal","goal_text":"Text","path_text":"Path",
                     "expected_result":"Expected","planned_end_at":"2030-01-01T00:00:00Z",
                     "publish":true}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("active"));
  }

  @Test
  void listPagesWithCursorAndFiltersByStatus() throws Exception {
    MvcResult firstPage =
        mvc.perform(get(GOALS).header(HttpHeaders.AUTHORIZATION, auth()).param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].title").value("Completed goal"))
            .andExpect(jsonPath("$.items[0].average_self_score").value(8.0))
            .andExpect(jsonPath("$.empty").value(false))
            .andReturn();

    String cursor =
        json.readTree(firstPage.getResponse().getContentAsString()).get("next_cursor").asString();

    mvc.perform(
            get(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .param("limit", "2")
                .param("cursor", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Draft goal"))
        .andExpect(jsonPath("$.items[0].average_self_score").value(nullValue()))
        .andExpect(jsonPath("$.next_cursor").value(nullValue()));

    mvc.perform(get(GOALS).header(HttpHeaders.AUTHORIZATION, auth()).param("status", "active"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("active"));
  }

  @Test
  void listRejectsInvalidSortAndCursor() throws Exception {
    mvc.perform(get(GOALS).header(HttpHeaders.AUTHORIZATION, auth()).param("sort", "unknown"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SORT"));

    mvc.perform(get(GOALS).header(HttpHeaders.AUTHORIZATION, auth()).param("cursor", "broken"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
  }

  @Test
  void createRejectsNullJsonBody() throws Exception {
    mvc.perform(
            post(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void createRejectsPastPlannedEndAt() throws Exception {
    mvc.perform(
            post(GOALS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"New goal","goal_text":"Goal text","path_text":"Path",
                     "expected_result":"Expected","planned_end_at":"2020-01-01T00:00:00Z"}
                    """))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVALID_PLANNED_END_AT"))
        .andExpect(jsonPath("$.field").value("planned_end_at"));
  }

  @Test
  void createRequiresAuthentication() throws Exception {
    mvc.perform(post(GOALS).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  private String auth() {
    return "Bearer " + token;
  }

  private void insertUser(UUID id) {
    db.sql(
            """
            insert into identity.users (id, email, password_hash, email_confirmed_at)
            values (:id, :email, 'hash', now())
            """)
        .param("id", id)
        .param("email", id + "@example.com")
        .update();
  }

  private void insertGoal(
      UUID id, String title, String status, int daysAgo, Integer selfScore, long version) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysAgo);
    db.sql(
            """
            insert into goals.goals
                (id, user_id, title, goal_text, path_text, expected_result, status,
                 planned_end_at, completed_at, created_at, updated_at, version)
            values
                (:id, :userId, :title, 'Goal text', 'Path text', 'Expected result',
                 cast(:status as goals.goal_status), now() + interval '30 days',
                 cast(:completedAt as timestamptz), cast(:createdAt as timestamptz),
                 cast(:createdAt as timestamptz), :version)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("title", title)
        .param("status", status)
        .param("completedAt", "completed".equals(status) ? createdAt : null)
        .param("createdAt", createdAt)
        .param("version", version)
        .update();

    if (selfScore != null) {
      db.sql(
              """
              insert into goals.reflections (goal_id, result, fact_text, self_score, reflected_at)
              values (:goalId, cast('partially' as goals.reflection_result), 'Almost',
                      cast(:score as smallint), now())
              """)
          .param("goalId", id)
          .param("score", selfScore)
          .update();
    }
  }
}
