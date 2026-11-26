package ru.trkpo.goals.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResetTokenResponse(
    @JsonProperty("reset_token") String resetToken, @JsonProperty("expires_in") long expiresIn) {}
