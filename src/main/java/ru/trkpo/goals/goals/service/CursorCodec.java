package ru.trkpo.goals.goals.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.goals.domain.GoalCursor;

final class CursorCodec {

  private CursorCodec() {}

  static String encode(Instant createdAt, UUID id) {
    String value = createdAt + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static GoalCursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int separator = value.lastIndexOf('|');
      if (separator < 0) {
        throw new IllegalArgumentException("Missing cursor separator");
      }
      return new GoalCursor(
          Instant.parse(value.substring(0, separator)),
          UUID.fromString(value.substring(separator + 1)));
    } catch (RuntimeException exception) {
      throw new ApiException(ErrorCode.INVALID_CURSOR);
    }
  }
}
