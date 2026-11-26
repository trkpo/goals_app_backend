package ru.trkpo.goals.identity.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.common.error.ErrorResponse;
import ru.trkpo.goals.common.web.RequestId;
import ru.trkpo.goals.identity.service.Sessions;

@RestController
@RequestMapping("/api/v1/identity")
class AuthController {

  private static final String REFRESH_COOKIE = "refresh_token";
  private static final String AUTH_PATH = "/api/v1/identity/auth";

  private final Sessions sessions;

  AuthController(Sessions sessions) {
    this.sessions = sessions;
  }

  @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest input, BindingResult binding, HttpServletRequest request) {
    if (input.email() == null || input.email().isBlank()) {
      return missingField("email", request);
    }
    if (binding.hasFieldErrors("email")) {
      throw new ApiException(ErrorCode.INVALID_EMAIL, "email");
    }
    if (input.password() == null || input.password().isBlank()) {
      return missingField("password", request);
    }

    var login = sessions.login(input.email(), input.password());
    var identity = login.identity();
    var grant = login.grant();
    return tokenResponse(grant.refresh())
        .body(
            new LoginResponse(
                identity.id(),
                identity.email(),
                identity.emailConfirmed(),
                grant.access().value(),
                "Bearer",
                grant.access().expiresIn()));
  }

  @PostMapping("/auth/refresh")
  ResponseEntity<AccessResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refresh) {
    var grant = sessions.refresh(refresh);
    return tokenResponse(grant.refresh())
        .body(new AccessResponse(grant.access().value(), "Bearer", grant.access().expiresIn()));
  }

  @GetMapping("/users/me")
  CurrentIdentity me(
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId,
      @RequestAttribute(
              name = AuthenticationWebConfiguration.AUTHENTICATED_SESSION,
              required = false)
          UUID sessionId) {
    var identity = sessions.currentIdentity(userId, sessionId);
    return new CurrentIdentity(
        identity.id(), identity.email(), identity.emailConfirmed(), identity.createdAt());
  }

  private static ResponseEntity.BodyBuilder tokenResponse(String refresh) {
    var cookie =
        ResponseCookie.from(REFRESH_COOKIE, refresh)
            .path(AUTH_PATH)
            .maxAge(Sessions.REFRESH_TTL)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private static ResponseEntity<ErrorResponse> missingField(
      String field, HttpServletRequest request) {
    // Контракт входа требует 400 для отсутствующего поля; остальная валидация отдаёт 422.
    var code = ErrorCode.REQUIRED_FIELD;
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(code.name(), code.defaultMessage(), field, RequestId.of(request)));
  }

  record LoginRequest(@Email @Size(max = 254) String email, String password) {}

  record LoginResponse(
      @JsonProperty("user_id") UUID userId,
      String email,
      @JsonProperty("email_confirmed") boolean emailConfirmed,
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") long expiresIn) {}

  record AccessResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") long expiresIn) {}

  record CurrentIdentity(
      @JsonProperty("user_id") UUID userId,
      String email,
      @JsonProperty("email_confirmed") boolean emailConfirmed,
      @JsonProperty("created_at") Instant createdAt) {}
}
