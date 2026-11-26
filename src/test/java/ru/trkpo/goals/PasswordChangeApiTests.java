package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "identity.bcrypt-strength=4",
      "AUTH_JWT_SECRET=dGVzdC1vbmx5IHNpZ25pbmcga2V5IGZvciBKV1QgaW50ZWdyYXRpb24="
    })
@AutoConfigureMockMvc
class PasswordChangeApiTests {

  private static final String PASSWORD_ENDPOINT = "/api/v1/profiles/me/password";
  private static final String CURRENT_PASSWORD = "OldSecret123";
  private static final String NEW_PASSWORD = "NewSecret456";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient db;
  @Autowired private PasswordEncoder passwordEncoder;

  private UUID userId;
  private String email;

  @BeforeEach
  void createUser() {
    userId = UUID.randomUUID();
    email = userId + "@example.com";
    db.sql(
            """
            insert into identity.users (id, email, password_hash, email_confirmed_at)
            values (:id, :email, :hash, now())
            """)
        .param("id", userId)
        .param("email", email)
        .param("hash", passwordEncoder.encode(CURRENT_PASSWORD))
        .update();
  }

  @Test
  void changesPasswordAndRevokesOnlyOtherSessions() throws Exception {
    Login current = login(CURRENT_PASSWORD);
    Login other = login(CURRENT_PASSWORD);

    mvc.perform(change(current.accessToken(), CURRENT_PASSWORD, NEW_PASSWORD))
        .andExpect(status().isNoContent());

    assertThat(passwordMatches(NEW_PASSWORD)).isTrue();
    assertThat(passwordMatches(CURRENT_PASSWORD)).isFalse();
    assertThat(version()).isEqualTo(1L);

    // Текущий сеанс продолжает работать.
    mvc.perform(
            get("/api/v1/identity/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken())))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/identity/auth/refresh").cookie(current.refresh()))
        .andExpect(status().isOk());

    // Прочие сеансы отозваны: их access- и refresh-токены больше не действуют.
    mvc.perform(
            get("/api/v1/identity/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(post("/api/v1/identity/auth/refresh").cookie(other.refresh()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));

    loginExpectingInvalidPassword(CURRENT_PASSWORD);
    login(NEW_PASSWORD);
  }

  @Test
  void invalidCurrentPasswordReturnsUnauthorizedAndKeepsSessions() throws Exception {
    Login login = login(CURRENT_PASSWORD);

    mvc.perform(change(login.accessToken(), "WrongSecret1", NEW_PASSWORD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"))
        .andExpect(jsonPath("$.field").value("current_password"));

    assertThat(passwordMatches(CURRENT_PASSWORD)).isTrue();
    assertThat(version()).isZero();
    mvc.perform(post("/api/v1/identity/auth/refresh").cookie(login.refresh()))
        .andExpect(status().isOk());
  }

  @Test
  void weakNewPasswordIsRejectedWithoutChangingPassword() throws Exception {
    Login login = login(CURRENT_PASSWORD);

    for (String weak : new String[] {"Short1", "alllowercase1", "NoDigitsHere", "Пароль123"}) {
      mvc.perform(change(login.accessToken(), CURRENT_PASSWORD, weak))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
          .andExpect(jsonPath("$.field").value("new_password"));
    }

    assertThat(passwordMatches(CURRENT_PASSWORD)).isTrue();
    assertThat(version()).isZero();
    mvc.perform(post("/api/v1/identity/auth/refresh").cookie(login.refresh()))
        .andExpect(status().isOk());
  }

  @Test
  void acceptsPasswordPolicyBoundaries() throws Exception {
    Login login = login(CURRENT_PASSWORD);

    mvc.perform(change(login.accessToken(), CURRENT_PASSWORD, "Abcdefg1"))
        .andExpect(status().isNoContent());
    assertThat(passwordMatches("Abcdefg1")).isTrue();
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(
            put(PASSWORD_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  private Login login(String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/identity/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"%s","password":"%s"}
                        """
                            .formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn();
    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
    Cookie refresh = result.getResponse().getCookie("refresh_token");
    assertThat(refresh).isNotNull();
    return new Login(accessToken, refresh);
  }

  private void loginExpectingInvalidPassword(String password) throws Exception {
    mvc.perform(
            post("/api/v1/identity/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"%s"}
                    """
                        .formatted(email, password)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
  }

  private MockHttpServletRequestBuilder change(
      String accessToken, String currentPassword, String newPassword) {
    return put(PASSWORD_ENDPOINT)
        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(currentPassword, newPassword));
  }

  private static String body(String currentPassword, String newPassword) {
    return """
        {"current_password":"%s","new_password":"%s"}
        """
        .formatted(currentPassword, newPassword);
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private boolean passwordMatches(String password) {
    String hash =
        db.sql("select password_hash from identity.users where id = :id")
            .param("id", userId)
            .query(String.class)
            .single();
    return passwordEncoder.matches(password, hash);
  }

  private long version() {
    return db.sql("select version from identity.users where id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  private record Login(String accessToken, Cookie refresh) {}
}
