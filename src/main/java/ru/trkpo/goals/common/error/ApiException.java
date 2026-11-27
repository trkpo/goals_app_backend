package ru.trkpo.goals.common.error;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Ожидаемая прикладная ошибка: сервисы бросают её, обработчик превращает в единый конверт. */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  @Nullable private final String field;
  @Nullable private final Duration retryAfter;

  public ApiException(ErrorCode code) {
    this(code, null, null);
  }

  public ApiException(ErrorCode code, @Nullable String field) {
    this(code, field, null);
  }

  public ApiException(ErrorCode code, @Nullable String field, @Nullable String message) {
    this(code, field, message, null);
  }

  /**
   * @param field JSON-путь поля запроса или {@code null}, если ошибка не привязана к полю
   * @param message текст для пользователя; если {@code null}, берётся текст кода по умолчанию
   * @param retryAfter через сколько можно повторить запрос; попадает в заголовок {@code
   *     Retry-After} для {@code 429}
   */
  public ApiException(
      ErrorCode code,
      @Nullable String field,
      @Nullable String message,
      @Nullable Duration retryAfter) {
    super(message != null ? message : code.defaultMessage());
    this.code = code;
    this.field = field;
    this.retryAfter = retryAfter;
  }

  public ErrorCode code() {
    return code;
  }

  @Nullable
  public String field() {
    return field;
  }

  @Nullable
  public Duration retryAfter() {
    return retryAfter;
  }
}
