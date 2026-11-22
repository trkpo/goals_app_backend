package ru.trkpo.goals.common.error;

import org.springframework.http.HttpStatus;

/**
 * Машинные коды ошибок из {@code ErrorCode} в OpenAPI. Один код всегда отдаётся с одним статусом
 * (ADR 0001, п. 1), поэтому статус хранится здесь, а не в месте броска исключения.
 */
public enum ErrorCode {
  // общие
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Некорректный запрос"),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Требуется вход в систему"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Действие запрещено"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "Объект не найден"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Метод не поддерживается"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый формат запроса"),
  REQUIRED_FIELD(HttpStatus.UNPROCESSABLE_CONTENT, "Поле обязательно для заполнения"),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Слишком много запросов, попробуйте позже"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка, попробуйте позже"),
  CONFIRM_REQUIRED(HttpStatus.BAD_REQUEST, "Требуется подтверждение действия"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "Данные уже изменены, обновите страницу"),
  IDEMPOTENCY_CONFLICT(
      HttpStatus.CONFLICT, "Ключ идемпотентности уже использован с другим запросом"),

  // identity
  INVALID_EMAIL(HttpStatus.BAD_REQUEST, "Некорректный адрес"),
  WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "Пароль не соответствует требованиям"),
  EMAIL_TAKEN(HttpStatus.CONFLICT, "Этот e-mail уже зарегистрирован"),
  INVALID_CODE(HttpStatus.UNPROCESSABLE_CONTENT, "Код подтверждения некорректный"),
  USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "Пользователь с таким e-mail не найден"),
  INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "Неверный пароль"),
  REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "Сеанс истёк, войдите снова"),
  RESET_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Ссылка для сброса пароля недействительна"),
  RESET_TOKEN_EXPIRED(HttpStatus.GONE, "Срок действия ссылки для сброса пароля истёк"),

  // profiles
  NICKNAME_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Никнейм должен быть от 2 до 30 символов"),
  AVATAR_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Выбран недопустимый аватар"),
  AVATARS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Набор аватаров временно недоступен"),
  PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Профиль не найден"),

  // goals
  TITLE_LENGTH(HttpStatus.UNPROCESSABLE_CONTENT, "Заголовок должен быть от 3 до 100 символов"),
  TEXT_TOO_LONG(HttpStatus.UNPROCESSABLE_CONTENT, "Текст должен быть от 1 до 5000 символов"),
  GOAL_LOCKED(HttpStatus.CONFLICT, "Текст цели нельзя менять у активной цели"),
  INVALID_STATUS(HttpStatus.BAD_REQUEST, "Некорректный статус"),
  INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "Такой переход статуса недопустим"),
  REFLECTION_LOCKED(HttpStatus.CONFLICT, "Итог уже сохранён и не редактируется"),
  FACT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Опишите, что получилось на деле"),
  INVALID_SCORE(HttpStatus.UNPROCESSABLE_CONTENT, "Некорректная оценка"),

  // feed
  INVALID_SORT(HttpStatus.BAD_REQUEST, "Неизвестный вариант сортировки"),
  INVALID_RESULT(HttpStatus.BAD_REQUEST, "Неизвестный тип итога"),
  INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Некорректный курсор"),
  REFLECTION_REQUIRED(HttpStatus.CONFLICT, "Сначала сохраните итог цели"),
  DRAFT_NOT_PUBLISHABLE(HttpStatus.CONFLICT, "Черновик и активную цель опубликовать нельзя"),
  ALREADY_PUBLISHED(HttpStatus.CONFLICT, "Цель уже опубликована"),
  INVALID_REACTION(HttpStatus.BAD_REQUEST, "Неизвестный тип реакции"),
  CANNOT_REACT_TO_OWN(HttpStatus.FORBIDDEN, "Нельзя реагировать на свою публикацию"),
  STREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Обновления в реальном времени недоступны"),

  // notifications
  INVALID_REMIND_AFTER_DAYS(HttpStatus.UNPROCESSABLE_CONTENT, "Напоминание: от 1 до 365 дней"),
  GOAL_ID_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Не указана цель");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  /** Сообщение для пользователя, если место броска не задало более точное. */
  public String defaultMessage() {
    return defaultMessage;
  }

  /** Код по умолчанию для ошибки, которую породил сам Spring (маршрут, метод, формат тела). */
  public static ErrorCode forStatus(int status) {
    return switch (status) {
      case 401 -> UNAUTHORIZED;
      case 403 -> FORBIDDEN;
      case 404 -> NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 429 -> TOO_MANY_REQUESTS;
      default -> status >= 500 ? INTERNAL_ERROR : INVALID_REQUEST;
    };
  }
}
