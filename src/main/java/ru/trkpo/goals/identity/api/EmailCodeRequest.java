package ru.trkpo.goals.identity.api;

import jakarta.validation.constraints.NotBlank;

public record EmailCodeRequest(@NotBlank String email, @NotBlank String code) {}
