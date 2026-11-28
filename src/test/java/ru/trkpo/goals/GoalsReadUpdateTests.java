package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import ru.trkpo.goals.identity.service.AccessTokens;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GoalsReadUpdateTests {

  private static final String GOALS = "/api/v1/goals";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient db;
  @Autowired private AccessTokens tokens;

  private UUID userId;
  private UUID otherUserId;
  private UUID draftGoalId;
  private UUID activeGoalId;
  private UUID completedGoalId;
  private UUID otherGoalId;
  private UUID publicationId;
  private String token;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    otherUserId = UUID.randomUUID();
    draftGoalId = UUID.randomUUID();
    activeGoalId = UUID.randomUUID();
    completedGoalId = UUID.randomUUID();
    otherGoalId = UUID.randomUUID();

    insertUser(userId);
    insertUser(otherUserId);
    insertGoal(draftGoalId, userId, "Draft goal", "draft", null, 1);
    insertGoal(activeGoalId, userId, "Active goal", "active", null, 2);
    insertGoal(
        completedGoalId,
        userId,
        "Completed goal",
        "completed",
        OffsetDateTime.now(ZoneOffset.UTC),
        3);
    insertGoal(otherGoalId, otherUserId, "Other goal", "draft", null, 4);
    insertReflection(completedGoalId);
    publicationId = insertPublication(completedGoalId, userId);
    token = tokens.issue(userId).value();
  }

  @Test
  void getReturnsGoalAndEtag() throws Exception {
    mvc.perform(get(GOALS + "/{id}", draftGoalId).header(HttpHeaders.AUTHORIZATION, auth()))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
        .andExpect(jsonPath("$.goal_id").value(draftGoalId.toString()))
        .andExpect(jsonPath("$.title").value("Draft goal"))
        .andExpect(jsonPath("$.status").value("draft"))
        .andExpect(jsonPath("$.reflection").value(nullValue()));
  }

  @Test
  void getCompletedGoalReturnsReflectionAndPublication() throws Exception {
    mvc.perform(get(GOALS + "/{id}", completedGoalId).header(HttpHeaders.AUTHORIZATION, auth()))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.reflection.result").value("partially"))
        .andExpect(jsonPath("$.reflection.fact_text").value("Almost"))
        .andExpect(jsonPath("$.reflection.self_score").value(7))
        .andExpect(jsonPath("$.reflection.reflected_at").exists())
        .andExpect(jsonPath("$.reflection.publication_id").value(publicationId.toString()));
  }

  @Test
  void getDoesNotExposeAnotherUsersGoal() throws Exception {
    mvc.perform(get(GOALS + "/{id}", otherGoalId).header(HttpHeaders.AUTHORIZATION, auth()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void patchDraftUpdatesFieldsAndBumpsVersion() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"Updated title","goal_text":"Updated text","expected_result":"Updated result"}
                    """))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
        .andExpect(jsonPath("$.title").value("Updated title"))
        .andExpect(jsonPath("$.goal_text").value("Updated text"))
        .andExpect(jsonPath("$.expected_result").value("Updated result"));
  }

  @Test
  void patchWithoutIfMatchWorksForDraftAutosave() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Autosaved title\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
        .andExpect(jsonPath("$.title").value("Autosaved title"));
  }

  @Test
  void patchActiveGoalWithoutVersionReturnsConflict() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", activeGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Changed title\"}"))
        .andExpect(status().isConflict())
        .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void patchActiveGoalRejectsGoalText() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", activeGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header(HttpHeaders.IF_MATCH, "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal_text\":\"Forbidden change\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("GOAL_LOCKED"))
        .andExpect(jsonPath("$.field").value("goal_text"));
  }

  @Test
  void patchWithStaleEtagReturnsConflictAndCurrentEtag() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Stale update\"}"))
        .andExpect(status().isConflict())
        .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void patchRejectsEmptyBody() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("REQUIRED_FIELD"));
  }

  @Test
  void patchRejectsExplicitNullField() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":null}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("REQUIRED_FIELD"))
        .andExpect(jsonPath("$.field").value("title"));
  }

  @Test
  void patchIsIdempotentWithKey() throws Exception {
    String body = "{\"title\":\"Idempotent title\"}";
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(
              patch(GOALS + "/{id}", draftGoalId)
                  .header(HttpHeaders.AUTHORIZATION, auth())
                  .header(HttpHeaders.IF_MATCH, "\"1\"")
                  .header("Idempotency-Key", "patch-draft-1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
          .andExpect(jsonPath("$.title").value("Idempotent title"));
    }

    assertThat(
            db.sql("select version from goals.goals where id = :id")
                .param("id", draftGoalId)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void patchValidatesTitle() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"ab\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("TITLE_LENGTH"))
        .andExpect(jsonPath("$.field").value("title"));
  }

  @Test
  void patchRejectsPastPlannedEndAt() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", draftGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planned_end_at\":\"2020-01-01T00:00:00Z\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVALID_PLANNED_END_AT"))
        .andExpect(jsonPath("$.field").value("planned_end_at"));
  }

  @Test
  void patchDoesNotUpdateAnotherUsersGoal() throws Exception {
    mvc.perform(
            patch(GOALS + "/{id}", otherGoalId)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Hijacked title\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void getWithoutTokenIsUnauthorized() throws Exception {
    mvc.perform(get(GOALS + "/{id}", draftGoalId))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
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
      UUID id,
      UUID ownerId,
      String title,
      String status,
      OffsetDateTime completedAt,
      long version) {
    db.sql(
            """
            insert into goals.goals
                (id, user_id, title, goal_text, path_text, expected_result, status,
                 planned_end_at, completed_at, version)
            values
                (:id, :userId, :title, 'Goal text', 'Path text', 'Expected result',
                 cast(:status as goals.goal_status), now() + interval '30 days',
                 cast(:completedAt as timestamptz), :version)
            """)
        .param("id", id)
        .param("userId", ownerId)
        .param("title", title)
        .param("status", status)
        .param("completedAt", completedAt)
        .param("version", version)
        .update();
  }

  private void insertReflection(UUID goalId) {
    db.sql(
            """
            insert into goals.reflections (goal_id, result, fact_text, self_score, reflected_at)
            values (:goalId, cast('partially' as goals.reflection_result), 'Almost',
                    cast(7 as smallint), now())
            """)
        .param("goalId", goalId)
        .update();
  }

  private UUID insertPublication(UUID goalId, UUID authorId) {
    return db.sql(
            """
            insert into feed.publications (goal_id, author_id, visibility)
            values (:goalId, :authorId, 'visible')
            returning id
            """)
        .param("goalId", goalId)
        .param("authorId", authorId)
        .query(UUID.class)
        .single();
  }
}
