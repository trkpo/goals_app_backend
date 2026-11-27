package ru.trkpo.goals.identity.security;

import java.util.regex.Pattern;

/** F_Auth_7: 8–32 печатных ASCII, хотя бы одна цифра и одна заглавная буква. */
public final class PasswordPolicy {

  public static final String MESSAGE =
      "Пароль должен быть от 8 до 32 символов, содержать хотя бы одну заглавную букву и одну цифру";

  private static final Pattern PATTERN =
      Pattern.compile("^(?=.*[0-9])(?=.*[A-Z])[\\x21-\\x7E]{8,32}$");

  private PasswordPolicy() {}

  public static boolean isValid(String password) {
    return password != null && PATTERN.matcher(password).matches();
  }
}
