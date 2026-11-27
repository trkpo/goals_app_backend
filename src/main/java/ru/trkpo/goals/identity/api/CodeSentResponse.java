package ru.trkpo.goals.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CodeSentResponse(
    @JsonProperty("code_sent") boolean codeSent, @JsonProperty("expires_in") long expiresIn) {}
