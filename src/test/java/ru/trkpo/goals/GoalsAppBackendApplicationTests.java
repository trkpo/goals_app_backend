package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GoalsAppBackendApplicationTests {

  @Autowired private Flyway flyway;

  @Test
  void contextLoads() {}

  @Test
  void migrationsAreApplied() {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied()).isNotEmpty();
  }
}
