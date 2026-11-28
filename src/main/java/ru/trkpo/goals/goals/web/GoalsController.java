package ru.trkpo.goals.goals.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.common.error.ErrorResponse;
import ru.trkpo.goals.common.idempotency.Idempotency;
import ru.trkpo.goals.common.web.RequestId;
import ru.trkpo.goals.goals.domain.GoalCreate;
import ru.trkpo.goals.goals.domain.GoalSummary;
import ru.trkpo.goals.goals.domain.GoalUpdate;
import ru.trkpo.goals.goals.domain.GoalView;
import ru.trkpo.goals.goals.service.GoalsService;
import ru.trkpo.goals.identity.web.AuthenticationWebConfiguration;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/goals")
class GoalsController {

  private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "completed");
  private static final List<String> UPDATE_FIELDS =
      List.of("title", "goal_text", "path_text", "planned_end_at", "expected_result");

  private final GoalsService goals;
  private final Idempotency idempotency;
  private final JsonMapper json;

  GoalsController(GoalsService goals, Idempotency idempotency, JsonMapper json) {
    this.goals = goals;
    this.idempotency = idempotency;
    this.json = json;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> create(
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    return idempotency.executeHttp(
        userId,
        request,
        "/api/v1/goals",
        body,
        () -> {
          var input = parseCreate(body);
          validateCreate(input);
          var created =
              goals.create(
                  userId,
                  new GoalCreate(
                      input.title(),
                      input.goalText(),
                      input.pathText(),
                      input.expectedResult(),
                      input.plannedEndAt(),
                      Boolean.TRUE.equals(input.publish())));
          return ResponseEntity.created(URI.create("/api/v1/goals/" + created.id()))
              .eTag(etag(created.version()))
              .body(
                  new CreateGoalResponse(
                      created.id(),
                      created.status(),
                      created.createdAt(),
                      created.plannedEndAt(),
                      created.expectedResult()));
        });
  }

  @GetMapping
  GoalListResponse list(
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId,
      @RequestParam(required = false) List<String> status,
      @RequestParam(defaultValue = "created_desc") String sort,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    var statuses = status == null ? List.<String>of() : status;
    if (statuses.stream().anyMatch(value -> !VALID_STATUSES.contains(value))) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "status");
    }
    if (!"created_desc".equals(sort) && !"created_asc".equals(sort)) {
      throw new ApiException(ErrorCode.INVALID_SORT, "sort");
    }
    if (limit < 1 || limit > 100) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "limit");
    }
    var page = goals.list(userId, statuses, sort, limit, cursor);
    return new GoalListResponse(
        page.items().stream().map(GoalItem::from).toList(), page.nextCursor(), page.empty());
  }

  @GetMapping("/{goalId}")
  ResponseEntity<GoalResponse> get(
      @PathVariable UUID goalId,
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId) {
    var goal = goals.get(goalId, userId);
    return ResponseEntity.ok().eTag(etag(goal.version())).body(GoalResponse.from(goal));
  }

  @PatchMapping(path = "/{goalId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> update(
      @PathVariable UUID goalId,
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    return idempotency.executeHttp(
        userId,
        request,
        "/api/v1/goals/" + goalId,
        body,
        () -> {
          var input = readUpdate(body);
          validateUpdate(input);
          var outcome =
              goals.update(
                  goalId,
                  userId,
                  parseIfMatch(ifMatch),
                  new GoalUpdate(
                      input.title(),
                      input.goalText(),
                      input.pathText(),
                      input.expectedResult(),
                      input.plannedEndAt()));
          if (outcome.conflictVersion() != null) {
            return versionConflict(outcome.conflictVersion(), request);
          }
          var goal = outcome.goal();
          return ResponseEntity.ok().eTag(etag(goal.version())).body(GoalResponse.from(goal));
        });
  }

  private UpdateGoalRequest readUpdate(byte[] body) {
    try {
      var tree = json.readTree(body == null ? new byte[0] : body);
      if (!tree.isObject()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST);
      }
      boolean hasField = false;
      for (String field : UPDATE_FIELDS) {
        if (tree.has(field)) {
          hasField = true;
          if (tree.get(field).isNull()) {
            throw new ApiException(ErrorCode.REQUIRED_FIELD, field);
          }
        }
      }
      if (!hasField) {
        throw new ApiException(ErrorCode.REQUIRED_FIELD);
      }
      return json.readValue(body, UpdateGoalRequest.class);
    } catch (ApiException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApiException(ErrorCode.INVALID_REQUEST);
    }
  }

  private static void validateUpdate(UpdateGoalRequest input) {
    if (input.title() != null && (input.title().length() < 3 || input.title().length() > 100)) {
      throw new ApiException(ErrorCode.TITLE_LENGTH, "title");
    }
    if (input.goalText() != null
        && (input.goalText().isEmpty() || input.goalText().length() > 5000)) {
      throw new ApiException(ErrorCode.TEXT_TOO_LONG, "goal_text");
    }
    if (input.pathText() != null
        && (input.pathText().isEmpty() || input.pathText().length() > 5000)) {
      throw new ApiException(ErrorCode.TEXT_TOO_LONG, "path_text");
    }
    if (input.expectedResult() != null
        && (input.expectedResult().isEmpty() || input.expectedResult().length() > 500)) {
      throw new ApiException(ErrorCode.TEXT_TOO_LONG, "expected_result");
    }
  }

  private CreateGoalRequest parseCreate(byte[] body) {
    try {
      var tree = json.readTree(body == null ? new byte[0] : body);
      if (!tree.isObject()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST);
      }
      return json.readValue(body, CreateGoalRequest.class);
    } catch (ApiException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApiException(ErrorCode.INVALID_REQUEST);
    }
  }

  private static void validateCreate(CreateGoalRequest input) {
    require(input.title(), "title");
    if (input.title().length() < 3 || input.title().length() > 100) {
      throw new ApiException(ErrorCode.TITLE_LENGTH, "title");
    }
    require(input.goalText(), "goal_text");
    if (input.goalText().length() > 5000) {
      throw new ApiException(ErrorCode.TEXT_TOO_LONG, "goal_text");
    }
    require(input.pathText(), "path_text");
    if (input.pathText().length() > 5000) {
      throw new ApiException(ErrorCode.TEXT_TOO_LONG, "path_text");
    }
    require(input.expectedResult(), "expected_result");
    if (input.expectedResult().length() > 500) {
      throw new ApiException(ErrorCode.TEXT_TOO_LONG, "expected_result");
    }
    if (input.plannedEndAt() == null) {
      throw new ApiException(ErrorCode.REQUIRED_FIELD, "planned_end_at");
    }
    if (!input.plannedEndAt().isAfter(Instant.now())) {
      throw new ApiException(ErrorCode.INVALID_PLANNED_END_AT, "planned_end_at");
    }
  }

  private static void require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ApiException(ErrorCode.REQUIRED_FIELD, field);
    }
  }

  private static ResponseEntity<ErrorResponse> versionConflict(
      long version, HttpServletRequest request) {
    var code = ErrorCode.VERSION_CONFLICT;
    return ResponseEntity.status(code.status())
        .eTag(etag(version))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ErrorResponse(code.name(), code.defaultMessage(), null, RequestId.of(request)));
  }

  private static Long parseIfMatch(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
      return null;
    }
    var value = ifMatch.trim();
    if (value.startsWith("W/")) {
      value = value.substring(2);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException exception) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT);
    }
  }

  private static String etag(long version) {
    return "\"" + version + "\"";
  }

  @JsonNaming(SnakeCaseStrategy.class)
  record CreateGoalRequest(
      String title,
      String goalText,
      String pathText,
      String expectedResult,
      Instant plannedEndAt,
      Boolean publish) {}

  @JsonNaming(SnakeCaseStrategy.class)
  record CreateGoalResponse(
      UUID goalId, String status, Instant createdAt, Instant plannedEndAt, String expectedResult) {}

  @JsonNaming(SnakeCaseStrategy.class)
  record GoalListResponse(List<GoalItem> items, String nextCursor, boolean empty) {}

  @JsonNaming(SnakeCaseStrategy.class)
  record GoalItem(
      UUID goalId,
      String title,
      String status,
      Instant createdAt,
      Instant plannedEndAt,
      Double averageSelfScore) {

    static GoalItem from(GoalSummary goal) {
      return new GoalItem(
          goal.id(),
          goal.title(),
          goal.status(),
          goal.createdAt(),
          goal.plannedEndAt(),
          goal.averageSelfScore());
    }
  }

  @JsonNaming(SnakeCaseStrategy.class)
  record UpdateGoalRequest(
      String title,
      String goalText,
      String pathText,
      String expectedResult,
      Instant plannedEndAt) {}

  @JsonNaming(SnakeCaseStrategy.class)
  record GoalResponse(
      UUID goalId,
      String title,
      String goalText,
      String pathText,
      String status,
      Instant createdAt,
      Instant plannedEndAt,
      String expectedResult,
      ReflectionResponse reflection) {

    static GoalResponse from(GoalView goal) {
      ReflectionResponse reflection = null;
      if ("completed".equals(goal.status()) && goal.reflectionResult() != null) {
        reflection =
            new ReflectionResponse(
                goal.reflectionResult(),
                goal.reflectionFactText(),
                goal.reflectionSelfScore(),
                goal.reflectedAt(),
                goal.publicationId());
      }
      return new GoalResponse(
          goal.id(),
          goal.title(),
          goal.goalText(),
          goal.pathText(),
          goal.status(),
          goal.createdAt(),
          goal.plannedEndAt(),
          goal.expectedResult(),
          reflection);
    }
  }

  @JsonNaming(SnakeCaseStrategy.class)
  record ReflectionResponse(
      String result, String factText, Short selfScore, Instant reflectedAt, UUID publicationId) {}
}
