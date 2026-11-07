package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeedDataTests {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void seedCreatesAccountsProfilesAndGoals() {
    assertThat(count("identity.users")).isEqualTo(3);
    assertThat(count("profiles.profiles")).isEqualTo(3);
    assertThat(count("goals.goals")).isEqualTo(4);
  }

  @Test
  void seedCreatesReflectionsAndPublications() {
    assertThat(count("goals.reflections")).isEqualTo(2);
    assertThat(count("feed.publications")).isEqualTo(2);
    assertThat(count("feed.reactions")).isEqualTo(3);
  }

  @Test
  void seedCreatesNotificationSettingsAndReminders() {
    assertThat(count("notifications.settings")).isEqualTo(3);
    assertThat(count("notifications.reminders")).isEqualTo(1);
    assertThat(count("notifications.notifications")).isEqualTo(1);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }
}
