package ru.trkpo.goals.goals.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.common.error.ErrorResponse;
import ru.trkpo.goals.common.web.RequestId;
import ru.trkpo.goals.goals.domain.GoalUpdate;
import ru.trkpo.goals.goals.domain.GoalView;
import ru.trkpo.goals.goals.service.GoalsService;
import ru.trkpo.goals.identity.web.AuthenticationWebConfiguration;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

@RestController
@RequestMapping("/api/v1/goals")
class GoalsController {

  private final GoalsService goals;

  GoalsController(GoalsService goals) {
    this.goals = goals;
  }

  @GetMapping("/{goalId}")
  ResponseEntity<GoalResponse> get(
      @PathVariable UUID goalId,
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId) {
    var goal = goals.get(goalId, userId);
    return ResponseEntity.ok().eTag(etag(goal.version())).body(GoalResponse.from(goal));
  }

  @PatchMapping(path = "/{goalId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @PathVariable UUID goalId,
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody UpdateGoalRequest input,
      HttpServletRequest request) {
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
  record UpdateGoalRequest(
      @Size(min = 3, max = 100, message = "TITLE_LENGTH") String title,
      @Size(min = 1, max = 5000, message = "TEXT_TOO_LONG") String goalText,
      @Size(min = 1, max = 5000, message = "TEXT_TOO_LONG") String pathText,
      @Size(min = 1, max = 500, message = "TEXT_TOO_LONG") String expectedResult,
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
