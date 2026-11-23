package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.trkpo.goals.common.error.RateLimitException;
import ru.trkpo.goals.identity.service.AuthRateLimits;
import ru.trkpo.goals.identity.service.AuthRateLimits.CodePurpose;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "AUTH_JWT_SECRET=" + AuthenticationTests.TEST_SIGNING_SECRET)
@AutoConfigureMockMvc
class AuthenticationTests {

  static final String TEST_SIGNING_SECRET =
      "dGVzdC1vbmx5IHNpZ25pbmcga2V5IGZvciBKV1QgaW50ZWdyYXRpb24=";

  private static final String AUTH = "/api/v1/identity/auth";
  private static final String ME = "/api/v1/identity/users/me";
  private static final String PASSWORD = "Secret123";
  private static final String PASSWORD_HASH =
      "$2b$10$5HLiXh17mUeQIOJOpqppvu3MLOIBZa6DdWGvZ3WXULgWPHn7kNOf.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient db;
  @Autowired private JsonMapper json;
  @Autowired private AuthRateLimits rateLimits;

  private UUID userId;
  private String email;

  @BeforeEach
  void createConfirmedUser() {
    userId = UUID.randomUUID();
    email = userId + "@example.com";
    db.sql(
            """
            insert into identity.users (id, email, password_hash, email_confirmed_at)
            values (:id, :email, :hash, now())
            """)
        .param("id", userId)
        .param("email", email)
        .param("hash", PASSWORD_HASH)
        .update();
  }

  @Test
  void confirmedUserCanSignInAndAccessOwnAccount() throws Exception {
    MvcResult login =
        mvc.perform(login(PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value(userId.toString()))
            .andExpect(jsonPath("$.email_confirmed").value(true))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.expires_in").value(3600))
            .andExpect(
                header()
                    .string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                            containsString("HttpOnly"),
                            containsString("Secure"),
                            containsString("SameSite=Strict"),
                            containsString("Path=" + AUTH),
                            containsString("Max-Age=2592000"))))
            .andReturn();

    String accessToken =
        json.readTree(login.getResponse().getContentAsString()).get("access_token").asString();
    mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(userId.toString()))
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.created_at").isString());
  }

  @Test
  void incorrectPasswordCannotCreateSession() throws Exception {
    mvc.perform(login("Wrong123"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"))
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  @Test
  void loginFailuresArePersistedAndExpire() throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      mvc.perform(login("Wrong123")).andExpect(status().isUnauthorized());
    }
    mvc.perform(login(PASSWORD))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    db.sql("update identity.auth_rate_attempts set expires_at = now() where subject = :email")
        .param("email", email)
        .update();
    mvc.perform(login(PASSWORD)).andExpect(status().isOk());
  }

  @Test
  void codeLimitsUseSeparateBucketsAndNormalizeEmail() {
    for (var purpose : CodePurpose.values()) {
      for (int attempt = 0; attempt < 3; attempt++) {
        rateLimits.recordCodeVerification(purpose, email);
      }
      assertThatThrownBy(
              () ->
                  rateLimits.recordCodeVerification(
                      purpose, email.toUpperCase(java.util.Locale.ROOT)))
          .isInstanceOfSatisfying(
              RateLimitException.class,
              error -> assertThat(error.retryAfterSeconds()).isBetween(1L, 60L));

      rateLimits.recordCodeRequest(purpose, email);
      assertThatThrownBy(() -> rateLimits.recordCodeRequest(purpose, email))
          .isInstanceOfSatisfying(
              RateLimitException.class,
              error -> assertThat(error.retryAfterSeconds()).isBetween(1L, 300L));
    }
  }

  @Test
  void unconfirmedUserCannotSignIn() throws Exception {
    db.sql("update identity.users set email_confirmed_at = null where id = :id")
        .param("id", userId)
        .update();
    mvc.perform(login(PASSWORD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void protectedEndpointRejectsMissingAndInvalidCredentials() throws Exception {
    mvc.perform(get(ME).header("X-Request-Id", "auth-test"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.request_id").value("auth-test"));
    mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void loginReportsMissingPasswordUsingTheContractStatus() throws Exception {
    mvc.perform(
            post(AUTH + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REQUIRED_FIELD"))
        .andExpect(jsonPath("$.field").value("password"));
  }

  @Test
  void correctlySignedAccessTokenIsRejectedAfterExpiry() throws Exception {
    Instant now = Instant.now();
    var key = new SecretKeySpec(Base64.getDecoder().decode(TEST_SIGNING_SECRET), "HmacSHA256");
    var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    var claims =
        JwtClaimsSet.builder()
            .issuer("goals-app-backend")
            .subject(userId.toString())
            .issuedAt(now.minusSeconds(3601))
            .expiresAt(now.minusSeconds(1))
            .build();
    String expired = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

    mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void missingAndExpiredRefreshCookiesAreRejected() throws Exception {
    mvc.perform(post(AUTH + "/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));

    Cookie refresh = signIn();
    db.sql(
            """
            update identity.sessions
            set issued_at = now() - interval '31 days', expires_at = now() - interval '1 day'
            where user_id = :id
            """)
        .param("id", userId)
        .update();
    mvc.perform(post(AUTH + "/refresh").cookie(refresh))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
  }

  @Test
  void refreshRotatesCookieAndRejectsThePreviousToken() throws Exception {
    Cookie previous = signIn();
    MvcResult response =
        mvc.perform(post(AUTH + "/refresh").cookie(previous))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isString())
            .andReturn();
    Cookie rotated = response.getResponse().getCookie("refresh_token");

    assertThat(rotated.getValue()).isNotEqualTo(previous.getValue());
    assertThat(
            db.sql("select refresh_token_hash from identity.sessions where user_id = :id")
                .param("id", userId)
                .query(String.class)
                .list())
        .hasSize(2)
        .doesNotContain(previous.getValue(), rotated.getValue());
    mvc.perform(post(AUTH + "/refresh").cookie(previous))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    mvc.perform(post(AUTH + "/refresh").cookie(rotated)).andExpect(status().isOk());
  }

  @Test
  void concurrentRefreshCanSucceedOnlyOnce() throws Exception {
    Cookie refresh = signIn();
    CountDownLatch start = new CountDownLatch(1);
    Callable<Integer> rotate =
        () -> {
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent refresh did not start");
          }
          return mvc.perform(post(AUTH + "/refresh").cookie(refresh))
              .andReturn()
              .getResponse()
              .getStatus();
        };

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(rotate);
      var second = executor.submit(rotate);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 401);
    }
  }

  private Cookie signIn() throws Exception {
    return mvc.perform(login(PASSWORD))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getCookie("refresh_token");
  }

  private MockHttpServletRequestBuilder login(String password) {
    return post(AUTH + "/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("email", email, "password", password)));
  }
}
