package ru.trkpo.goals.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Принимает {@code X-Request-Id} или генерирует UUID, кладёт его в MDC и всегда возвращает в
 * ответе. Чужое значение принимается только если оно короткое и безопасное для логов: иначе через
 * заголовок можно подделать строки журнала.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = resolve(request.getHeader(RequestId.HEADER));
    request.setAttribute(RequestId.ATTRIBUTE, requestId);
    response.setHeader(RequestId.HEADER, requestId);
    MDC.put(RequestId.MDC_KEY, requestId);
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      long ms = (System.nanoTime() - start) / 1_000_000;
      // проверки живости идут каждые несколько секунд, в INFO их не пишем
      boolean probe = request.getRequestURI().startsWith("/actuator/");
      if (probe ? log.isDebugEnabled() : log.isInfoEnabled()) {
        String line = "{} {} -> {} ({} ms)";
        Object[] args = {request.getMethod(), request.getRequestURI(), response.getStatus(), ms};
        if (probe) {
          log.debug(line, args);
        } else {
          log.info(line, args);
        }
      }
      MDC.remove(RequestId.MDC_KEY);
    }
  }

  private static String resolve(String incoming) {
    if (incoming != null && VALID.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString();
  }
}
