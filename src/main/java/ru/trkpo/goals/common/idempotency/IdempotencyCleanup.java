package ru.trkpo.goals.common.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
class IdempotencyCleanup {

  private final JdbcClient db;

  IdempotencyCleanup(JdbcClient db) {
    this.db = db;
  }

  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
  void deleteExpired() {
    db.sql(
            """
            delete from public.idempotency_results
            where ctid in (
                select ctid from public.idempotency_results
                where expires_at <= clock_timestamp()
                order by expires_at limit 1000 for update skip locked
            )
            """)
        .update();
  }
}
