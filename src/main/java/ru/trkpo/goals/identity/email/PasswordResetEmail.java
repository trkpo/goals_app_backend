package ru.trkpo.goals.identity.email;

/** Письмо с кодом восстановления пароля. */
public record PasswordResetEmail(String email, String code, long expiresMinutes) {}
