package ru.trkpo.goals.profiles.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.identity.web.AuthenticationWebConfiguration;
import ru.trkpo.goals.profiles.service.PasswordChangeService;

@RestController
@RequestMapping("/api/v1/profiles/me")
class ProfilePasswordController {

  private final PasswordChangeService passwordChangeService;

  ProfilePasswordController(PasswordChangeService passwordChangeService) {
    this.passwordChangeService = passwordChangeService;
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void changePassword(
      @RequestAttribute(AuthenticationWebConfiguration.AUTHENTICATED_USER) UUID userId,
      @RequestAttribute(
              name = AuthenticationWebConfiguration.AUTHENTICATED_SESSION,
              required = false)
          UUID sessionId,
      @Valid @RequestBody PasswordChangeRequest request) {
    passwordChangeService.changePassword(
        userId, sessionId, request.currentPassword(), request.newPassword());
  }

  record PasswordChangeRequest(
      @JsonProperty("current_password") @NotBlank String currentPassword,
      @JsonProperty("new_password") @NotBlank String newPassword) {}
}
