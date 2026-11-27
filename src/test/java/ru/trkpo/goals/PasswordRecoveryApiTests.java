package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.trkpo.goals.identity.domain.SessionEntity;
import ru.trkpo.goals.identity.domain.UserEntity;
import ru.trkpo.goals.identity.email.PasswordResetEmail;
import ru.trkpo.goals.identity.email.PasswordResetEmailSender;
import ru.trkpo.goals.identity.repository.SessionRepository;
import ru.trkpo.goals.identity.repository.UserRepository;

@SpringBootTest(properties = "identity.bcrypt-strength=4")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PasswordRecoveryApiTests.TestBeans.class})
class PasswordRecoveryApiTests {

  private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RecordingEmailSender emailSender;
  @Autowired private MutableClock clock;

  @BeforeEach
  void reset() {
    clock.set(START);
    emailSender.clear();
  }

  @Test
  void requestCodeDoesNotRevealWhetherEmailExists() throws Exception {
    String known = email("known");
    createUser(known, "OldSecret1");
    String unknown = email("unknown");

    MvcResult knownResult =
        mockMvc
            .perform(
                post("/api/v1/identity/password/request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\"}".formatted(known)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code_sent").value(true))
            .andExpect(jsonPath("$.expires_in").value(600))
            .andReturn();
    MvcResult unknownResult =
        mockMvc
            .perform(
                post("/api/v1/identity/password/request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\"}".formatted(unknown)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code_sent").value(true))
            .andExpect(jsonPath("$.expires_in").value(600))
            .andReturn();

    assertThat(unknownResult.getResponse().getContentAsString())
        .isEqualTo(knownResult.getResponse().getContentAsString());
    assertThat(emailSender.messages()).hasSize(1);
    assertThat(emailSender.messages().getFirst().email()).isEqualTo(known);
    assertThat(emailSender.messages().getFirst().code()).matches("[0-9]{6}");
  }

  @Test
  void fullFlowChangesPasswordAndRevokesAllSessions() throws Exception {
    String email = email("full");
    UserEntity user = createUser(email, "OldSecret1");
    createSession(user.getId());
    createSession(user.getId());

    String code = requestCode(email);
    String token = verifyCode(email, code);
    mockMvc
        .perform(
            post("/api/v1/identity/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reset_token\":\"%s\",\"new_password\":\"NewSecret456\"}".formatted(token)))
        .andExpect(status().isNoContent());

    UserEntity updated = userRepository.findById(user.getId()).orElseThrow();
    assertThat(passwordEncoder.matches("NewSecret456", updated.getPasswordHash())).isTrue();
    assertThat(passwordEncoder.matches("OldSecret1", updated.getPasswordHash())).isFalse();
    assertThat(updated.getVersion()).isEqualTo(user.getVersion() + 1);

    List<SessionEntity> sessions = sessionRepository.findByUserId(user.getId());
    assertThat(sessions).hasSize(2);
    assertThat(sessions).allMatch(s -> s.getRevokedAt() != null);

    mockMvc
        .perform(
            post("/api/v1/identity/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reset_token\":\"%s\",\"new_password\":\"AnotherPass789\"}"
                        .formatted(token)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
  }

  @Test
  void requestCooldownIsFiveMinutesAndNewCodeInvalidatesOld() throws Exception {
    String email = email("cooldown");
    createUser(email, "OldSecret1");
    String oldCode = requestCode(email);

    mockMvc
        .perform(
            post("/api/v1/identity/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
        .andExpect(header().exists("Retry-After"));

    clock.advance(Duration.ofSeconds(299));
    mockMvc
        .perform(
            post("/api/v1/identity/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
        .andExpect(status().isTooManyRequests());

    clock.advance(Duration.ofSeconds(1));
    String newCode = requestCode(email);
    verifyExpectingInvalidCode(email, oldCode);
    assertThat(verify(email, newCode).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void verifyIsLimitedToThreeAttemptsPerMinute() throws Exception {
    String email = email("verify-limit");
    createUser(email, "OldSecret1");
    String code = requestCode(email);

    for (int i = 0; i < 3; i++) {
      verifyExpectingInvalidCode(email, "000000");
    }

    mockMvc
        .perform(
            post("/api/v1/identity/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"000000\"}".formatted(email)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
        .andExpect(header().exists("Retry-After"));

    clock.advance(Duration.ofSeconds(61));
    mockMvc
        .perform(
            post("/api/v1/identity/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reset_token").isNotEmpty());
  }

  @Test
  void invalidAndExpiredCodeReturnSameMessage() throws Exception {
    String email = email("expired-code");
    createUser(email, "OldSecret1");
    String code = requestCode(email);

    mockMvc
        .perform(
            post("/api/v1/identity/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"000000\"}".formatted(email)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVALID_CODE"))
        .andExpect(jsonPath("$.message").value("Код подтверждения некорректный"));

    clock.advance(Duration.ofSeconds(601));
    mockMvc
        .perform(
            post("/api/v1/identity/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVALID_CODE"))
        .andExpect(jsonPath("$.message").value("Код подтверждения некорректный"));
  }

  @Test
  void weakPasswordIsRejectedAndDoesNotConsumeResetToken() throws Exception {
    String email = email("weak");
    createUser(email, "OldSecret1");
    String token = issueResetToken(email);

    mockMvc
        .perform(
            post("/api/v1/identity/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reset_token\":\"%s\",\"new_password\":\"Short1A\"}".formatted(token)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
        .andExpect(jsonPath("$.field").value("new_password"));

    mockMvc
        .perform(
            post("/api/v1/identity/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reset_token\":\"%s\",\"new_password\":\"ValidPass123\"}".formatted(token)))
        .andExpect(status().isNoContent());
  }

  @Test
  void expiredResetTokenReturnsGone() throws Exception {
    String email = email("expired-token");
    createUser(email, "OldSecret1");
    String token = issueResetToken(email);
    clock.advance(Duration.ofSeconds(601));

    mockMvc
        .perform(
            post("/api/v1/identity/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reset_token\":\"%s\",\"new_password\":\"NewSecret456\"}".formatted(token)))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("RESET_TOKEN_EXPIRED"));
  }

  @Test
  void unknownResetTokenReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reset_token\":\"rst_unknown\",\"new_password\":\"NewSecret456\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
  }

  @Test
  void invalidEmailAndMissingFieldUseContractErrors() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_EMAIL"))
        .andExpect(jsonPath("$.field").value("email"));

    mockMvc
        .perform(
            post("/api/v1/identity/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("REQUIRED_FIELD"))
        .andExpect(jsonPath("$.field").value("email"));
  }

  @Test
  void requestIdIsEchoedInErrorsAndHeaders() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity/password/request")
                .header("X-Request-Id", "request-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Request-Id", "request-123"))
        .andExpect(jsonPath("$.request_id").value("request-123"));
  }

  private String requestCode(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/identity/password/request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\"}".formatted(email)))
            .andExpect(status().isAccepted())
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).contains("\"expires_in\":600");
    return emailSender.lastCode(email);
  }

  private MvcResult verify(String email, String code) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/identity/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
        .andReturn();
  }

  private void verifyExpectingInvalidCode(String email, String code) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVALID_CODE"))
        .andExpect(jsonPath("$.message").value("Код подтверждения некорректный"));
  }

  private String verifyCode(String email, String code) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/identity/password/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expires_in").value(600))
            .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.reset_token");
  }

  private String issueResetToken(String email) throws Exception {
    return verifyCode(email, requestCode(email));
  }

  private UserEntity createUser(String email, String password) {
    Instant now = clock.instant();
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setEmailConfirmedAt(now);
    user.setCreatedAt(now);
    user.setUpdatedAt(now);
    user.setVersion(0);
    return userRepository.saveAndFlush(user);
  }

  private void createSession(UUID userId) {
    Instant now = clock.instant();
    SessionEntity session = new SessionEntity();
    session.setUserId(userId);
    session.setRefreshTokenHash("refresh-" + UUID.randomUUID());
    session.setIssuedAt(now);
    session.setExpiresAt(now.plus(Duration.ofDays(30)));
    session.setCreatedAt(now);
    session.setUpdatedAt(now);
    sessionRepository.saveAndFlush(session);
  }

  private static String email(String name) {
    return name + "-" + UUID.randomUUID() + "@example.com";
  }

  static final class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  static final class RecordingEmailSender implements PasswordResetEmailSender {

    private final List<PasswordResetEmail> messages = new CopyOnWriteArrayList<>();

    @Override
    public void send(PasswordResetEmail message) {
      messages.add(message);
    }

    List<PasswordResetEmail> messages() {
      return messages;
    }

    String lastCode(String email) {
      return messages.stream()
          .filter(m -> m.email().equals(email))
          .reduce((first, second) -> second)
          .map(PasswordResetEmail::code)
          .orElseThrow();
    }

    void clear() {
      messages.clear();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(START);
    }

    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
      return new RecordingEmailSender();
    }
  }
}
