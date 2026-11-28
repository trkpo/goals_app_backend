package ru.trkpo.goals.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.trkpo.goals.common.web.RequestId;

/**
 * Единственное место, где исключения превращаются в ответ. Клиент получает только {@link
 * ErrorResponse}: сообщение исключения, трассировка и детали БД в ответ не попадают, они остаются в
 * журнале рядом с {@code request_id}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
    ErrorCode code = ex.code();
    if (code.status().is5xxServerError()) {
      log.error("Ошибка {} при обработке запроса", code, ex);
    } else {
      log.debug("Ошибка {}: {}", code, ex.getMessage());
    }
    return body(code, ex.getMessage(), ex.field(), request);
  }

  /** Всё непредвиденное: клиенту общий текст, причина только в журнале. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Необработанная ошибка", ex);
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return body(code, code.defaultMessage(), null, request);
  }

  /** Ошибки проверки {@code @Valid}: 422, поле — первое с ошибкой. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    FieldError error = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    if (error == null) {
      return body(ErrorCode.REQUIRED_FIELD, null, null, request);
    }
    return body(codeOf(error), null, toJsonPath(error.getField()), request);
  }

  /**
   * Стандартные ошибки Spring MVC (неизвестный маршрут, метод, формат, битое тело) тоже идут в
   * единый конверт; сообщение берём из кода, а не из исключения.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ErrorCode code = ErrorCode.forStatus(statusCode.value());
    if (statusCode.is5xxServerError()) {
      log.error("Ошибка {} при обработке запроса", code, ex);
    }
    HttpStatus status = HttpStatus.resolve(statusCode.value());
    ResponseEntity<Object> response = body(code, null, null, request);
    return ResponseEntity.status(status != null ? status : code.status())
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(response.getBody());
  }

  /**
   * Ограничение валидации называет код ошибки в {@code message}: {@code @Size(message =
   * "TITLE_LENGTH")}. Если имя не из перечня, это «обязательное поле».
   */
  private static ErrorCode codeOf(FieldError error) {
    String message = error.getDefaultMessage();
    return Arrays.stream(ErrorCode.values())
        .filter(c -> c.name().equals(message))
        .findFirst()
        .orElse(ErrorCode.REQUIRED_FIELD);
  }

  /** {@code reflection.factText} → {@code reflection.fact_text}, как в JSON. */
  private static String toJsonPath(String property) {
    return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
  }

  private static ResponseEntity<Object> body(
      ErrorCode code, @Nullable String message, @Nullable String field, Object request) {
    String requestId = requestIdOf(request);
    ErrorResponse response =
        new ErrorResponse(
            code.name(), message != null ? message : code.defaultMessage(), field, requestId);
    return ResponseEntity.status(code.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(response);
  }

  @Nullable
  private static String requestIdOf(Object request) {
    if (request instanceof HttpServletRequest http) {
      return RequestId.of(http);
    }
    if (request instanceof ServletWebRequest web) {
      return RequestId.of(web.getRequest());
    }
    return null;
  }
}
