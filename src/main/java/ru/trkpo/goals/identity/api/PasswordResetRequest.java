package ru.trkpo.goals.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
    @JsonProperty("reset_token") @NotBlank String resetToken,
    @JsonProperty("new_password") @NotBlank String newPassword) {}
