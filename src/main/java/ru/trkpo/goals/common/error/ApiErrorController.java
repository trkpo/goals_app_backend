package ru.trkpo.goals.common.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.common.web.RequestId;

/**
 * Ошибки, которые случились вне контроллеров (фильтры, контейнер), попадают на {@code /error}.
 * Отдаём тот же конверт, что и обработчик исключений, а не страницу контейнера.
 */
@RestController
public class ApiErrorController implements ErrorController {

  @RequestMapping("/error")
  ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
    Object attr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = attr instanceof Integer i ? i : 500;
    ErrorCode code = ErrorCode.forStatus(status);
    return ResponseEntity.status(status >= 400 && status < 600 ? status : code.status().value())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ErrorResponse(code.name(), code.defaultMessage(), null, RequestId.of(request)));
  }
}
