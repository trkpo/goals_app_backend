package ru.trkpo.goals.common.error;

import org.jspecify.annotations.Nullable;

/** Ожидаемая прикладная ошибка: сервисы бросают её, обработчик превращает в единый конверт. */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  @Nullable private final String field;

  public ApiException(ErrorCode code) {
    this(code, null, null);
  }

  public ApiException(ErrorCode code, @Nullable String field) {
    this(code, field, null);
  }

  /**
   * @param field JSON-путь поля запроса или {@code null}, если ошибка не привязана к полю
   * @param message текст для пользователя; если {@code null}, берётся текст кода по умолчанию
   */
  public ApiException(ErrorCode code, @Nullable String field, @Nullable String message) {
    super(message != null ? message : code.defaultMessage());
    this.code = code;
    this.field = field;
  }

  public ErrorCode code() {
    return code;
  }

  @Nullable
  public String field() {
    return field;
  }
}
