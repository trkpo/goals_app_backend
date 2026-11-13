package ru.trkpo.goals.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/** Доступ к идентификатору текущего запроса. */
public final class RequestId {

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "request_id";
  static final String ATTRIBUTE = RequestId.class.getName() + ".value";

  private RequestId() {}

  @Nullable
  public static String of(HttpServletRequest request) {
    return (String) request.getAttribute(ATTRIBUTE);
  }
}
