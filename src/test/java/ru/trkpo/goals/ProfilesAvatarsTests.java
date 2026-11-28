package ru.trkpo.goals;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import ru.trkpo.goals.identity.service.AccessTokens;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfilesAvatarsTests {

  private static final String AVATARS = "/api/v1/profiles/avatars";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient db;
  @Autowired private AccessTokens tokens;

  private String token;

  @BeforeEach
  void setUp() {
    db.sql("delete from profiles.avatars").update();
    insertAvatar("avatar_01", "/api/v1/profiles/avatars/avatar_01", 3);
    insertAvatar("avatar_02", "/api/v1/profiles/avatars/avatar_02", 1);
    token = tokens.issue(UUID.randomUUID()).value();
  }

  @Test
  void listReturnsAvatarsWithCacheHeaders() throws Exception {
    var result =
        mvc.perform(get(AVATARS).header(HttpHeaders.AUTHORIZATION, auth()))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].avatar_id").value("avatar_01"))
            .andExpect(jsonPath("$.items[0].url").value("/api/v1/profiles/avatars/avatar_01"))
            .andExpect(jsonPath("$.items[0].version").value(3))
            .andExpect(jsonPath("$.items[1].avatar_id").value("avatar_02"))
            .andReturn();

    String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
    mvc.perform(
            get(AVATARS)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header(HttpHeaders.IF_NONE_MATCH, etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.ETAG, etag));
  }

  @Test
  void listRequiresAuthentication() throws Exception {
    mvc.perform(get(AVATARS))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  private String auth() {
    return "Bearer " + token;
  }

  private void insertAvatar(String id, String url, long version) {
    db.sql(
            """
            insert into profiles.avatars (avatar_id, url, content_type, image_data, version)
            values (:id, :url, 'image/png', '\\x'::bytea, :version)
            """)
        .param("id", id)
        .param("url", url)
        .param("version", version)
        .update();
  }
}
