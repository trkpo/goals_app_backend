package ru.trkpo.goals.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.common.error.ApiErrorController;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.common.error.GlobalExceptionHandler;
import ru.trkpo.goals.common.web.RequestIdFilter;

@WebMvcTest(ErrorHandlingTests.ProbeController.class)
@Import({
  GlobalExceptionHandler.class,
  ApiErrorController.class,
  RequestIdFilter.class,
  ErrorHandlingTests.ProbeController.class
})
class ErrorHandlingTests {

  @Autowired private MockMvc mvc;

  @Test
  void apiExceptionBecomesEnvelopeWithStatusOfCode() throws Exception {
    mvc.perform(get("/probe/conflict").header("X-Request-Id", "req-123"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Request-Id", "req-123"))
        .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
        .andExpect(jsonPath("$.message").value("Этот e-mail уже зарегистрирован"))
        .andExpect(jsonPath("$.field").value("email"))
        .andExpect(jsonPath("$.request_id").value("req-123"));
  }

  @Test
  void errorWithoutFieldHasNullField() throws Exception {
    mvc.perform(get("/probe/forbidden"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.field").hasJsonPath())
        .andExpect(jsonPath("$.field").value(nullValue()));
  }

  @Test
  void unexpectedErrorDoesNotLeakDetails() throws Exception {
    mvc.perform(get("/probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value(not(containsString("jdbc"))))
        .andExpect(jsonPath("$.message").value(not(containsString("secret"))))
        .andExpect(jsonPath("$.request_id").exists());
  }

  @Test
  void generatesRequestIdWhenMissing() throws Exception {
    mvc.perform(get("/probe/forbidden"))
        .andExpect(header().string("X-Request-Id", hasLength(36)))
        .andExpect(jsonPath("$.request_id").exists());
  }

  @Test
  void replacesUnsafeRequestId() throws Exception {
    mvc.perform(get("/probe/forbidden").header("X-Request-Id", "bad id\twith spaces"))
        .andExpect(header().string("X-Request-Id", hasLength(36)));
  }

  @Test
  void validationErrorIs422WithJsonPathField() throws Exception {
    mvc.perform(
            post("/probe/goals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"ab\",\"goalText\":\"x\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("TITLE_LENGTH"))
        .andExpect(jsonPath("$.field").value("title"));
  }

  @Test
  void missingRequiredFieldIsRequiredField() throws Exception {
    mvc.perform(
            post("/probe/goals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Нормальный заголовок\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("REQUIRED_FIELD"))
        .andExpect(jsonPath("$.field").value("goal_text"));
  }

  @Test
  void malformedBodyIs400() throws Exception {
    mvc.perform(post("/probe/goals").contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value(not(containsString("oops"))));
  }

  @Test
  void unknownRouteIs404Envelope() throws Exception {
    mvc.perform(get("/no/such/route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void wrongMethodIs405Envelope() throws Exception {
    mvc.perform(post("/probe/forbidden"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }

  @RestController
  static class ProbeController {

    record GoalRequest(
        @Size(min = 3, max = 100, message = "TITLE_LENGTH") String title,
        @NotBlank String goalText) {}

    @GetMapping("/probe/conflict")
    void conflict() {
      throw new ApiException(ErrorCode.EMAIL_TAKEN, "email");
    }

    @GetMapping("/probe/forbidden")
    void forbidden() {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }

    @GetMapping("/probe/boom")
    void boom() {
      throw new IllegalStateException("jdbc:postgresql://db/goals password=secret");
    }

    @PostMapping("/probe/goals")
    void create(@Valid @RequestBody GoalRequest request) {}
  }
}
