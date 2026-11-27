package ru.trkpo.goals.identity.api;

import jakarta.validation.constraints.NotBlank;

public record EmailRequest(@NotBlank String email) {}
