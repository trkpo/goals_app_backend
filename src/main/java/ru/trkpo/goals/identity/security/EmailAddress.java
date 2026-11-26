package ru.trkpo.goals.identity.security;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;

/** Нормализация e-mail: адрес сравнивается без учёта регистра. */
public final class EmailAddress {

  private EmailAddress() {}

  public static String normalize(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("email is required");
    }
    String value = raw.trim();
    int at = value.indexOf('@');
    if (value.isEmpty()
        || value.length() > 254
        || at <= 0
        || at != value.lastIndexOf('@')
        || at == value.length() - 1
        || value.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("invalid email");
    }
    try {
      InternetAddress address = new InternetAddress(value, true);
      address.validate();
      return address.getAddress().toLowerCase(Locale.ROOT);
    } catch (AddressException ex) {
      throw new IllegalArgumentException("invalid email", ex);
    }
  }
}
