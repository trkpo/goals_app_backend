package ru.trkpo.goals.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Enumeration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.MappedInterceptor;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.identity.service.AccessTokens;
import ru.trkpo.goals.identity.service.Sessions;

@Configuration(proxyBeanMethods = false)
public class AuthenticationWebConfiguration {

  public static final String AUTHENTICATED_USER = "ru.trkpo.goals.auth.userId";
  public static final String AUTHENTICATED_SESSION = "ru.trkpo.goals.auth.sessionId";

  @Bean
  MappedInterceptor bearerAuthentication(AccessTokens tokens, Sessions sessions) {
    return new MappedInterceptor(
        new String[] {"/api/v1/**"}, new BearerAuthentication(tokens, sessions));
  }

  private record BearerAuthentication(AccessTokens tokens, Sessions sessions)
      implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      if (!(handler instanceof HandlerMethod)) {
        return true;
      }

      String route =
          String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
      String method = request.getMethod();
      if (isPublic(method, route)) {
        return true;
      }

      Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
      String authorization = headers.hasMoreElements() ? headers.nextElement() : null;
      if (authorization == null
          && isRead(method)
          && (route.equals("/api/v1/feed") || route.equals("/api/v1/feed/stream"))) {
        return true;
      }

      try {
        if (authorization == null
            || headers.hasMoreElements()
            || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
          throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        AccessTokens.Claims claims = tokens.authenticate(authorization.substring(BEARER.length()));
        sessions.requireActive(claims.userId(), claims.sessionId());
        request.setAttribute(AUTHENTICATED_USER, claims.userId());
        request.setAttribute(AUTHENTICATED_SESSION, claims.sessionId());
        return true;
      } catch (ApiException exception) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        throw exception;
      }
    }

    private static boolean isPublic(String method, String route) {
      if (method.equals("OPTIONS")) {
        return true;
      }
      if (isRead(method)) {
        return route.equals("/api/v1/feed")
            || route.equals("/api/v1/feed/stream")
            || route.equals("/api/v1/profiles/avatars/{avatar_id}");
      }
      if (!method.equals("POST")) {
        return false;
      }

      return switch (route) {
        case "/api/v1/identity/auth/register",
                "/api/v1/identity/auth/login",
                "/api/v1/identity/auth/refresh",
                "/api/v1/identity/auth/logout",
                "/api/v1/identity/email/confirm",
                "/api/v1/identity/email/confirm/resend",
                "/api/v1/identity/password/request",
                "/api/v1/identity/password/verify",
                "/api/v1/identity/password/reset" ->
            true;
        default -> false;
      };
    }

    private static boolean isRead(String method) {
      return method.equals("GET") || method.equals("HEAD");
    }
  }
}
