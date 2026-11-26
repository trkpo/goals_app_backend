package ru.trkpo.goals.identity.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.identity.service.PasswordRecoveryService;

/** Три шага восстановления пароля: запрос кода, проверка кода, новый пароль. */
@RestController
@RequestMapping("/api/v1/identity/password")
public class PasswordRecoveryController {

  private final PasswordRecoveryService service;

  public PasswordRecoveryController(PasswordRecoveryService service) {
    this.service = service;
  }

  @PostMapping("/request")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public CodeSentResponse request(@Valid @RequestBody EmailRequest request) {
    PasswordRecoveryService.CodeSentResult result = service.requestCode(request.email());
    return new CodeSentResponse(true, result.expiresInSeconds());
  }

  @PostMapping("/verify")
  public ResetTokenResponse verify(@Valid @RequestBody EmailCodeRequest request) {
    PasswordRecoveryService.ResetTokenResult result =
        service.verifyCode(request.email(), request.code());
    return new ResetTokenResponse(result.resetToken(), result.expiresInSeconds());
  }

  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@Valid @RequestBody PasswordResetRequest request) {
    service.resetPassword(request.resetToken(), request.newPassword());
  }
}
