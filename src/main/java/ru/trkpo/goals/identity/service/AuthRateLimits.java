package ru.trkpo.goals.identity.service;

import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.RateLimitException;

@Service
public class AuthRateLimits {

  private final JdbcClient db;

  public AuthRateLimits(JdbcClient db) {
    this.db = db;
  }

  public enum CodePurpose {
    EMAIL_CONFIRMATION,
    PASSWORD_RECOVERY
  }

  // Verification counts even if the caller subsequently rejects the supplied code.
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordCodeVerification(CodePurpose purpose, String email) {
    reserve("verify:" + purpose.name(), subject(email), 3, 60);
  }

  // Call before checking whether the address exists, to keep the cooldown response uniform.
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordCodeRequest(CodePurpose purpose, String email) {
    reserve("send:" + purpose.name(), subject(email), 1, 300);
  }

  void checkLogin(String email) {
    check("login", subject(email), 5);
  }

  void recordFailedLogin(String email) {
    record("login", subject(email), 60);
  }

  private void reserve(String bucket, String subject, int limit, int seconds) {
    check(bucket, subject, limit);
    record(bucket, subject, seconds);
  }

  private void check(String bucket, String subject, int limit) {
    // Serialize each bucket across application instances until the surrounding transaction ends.
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key, 937461))")
        .param("key", bucket + ":" + subject)
        .query((rs, row) -> Boolean.TRUE)
        .single();

    OffsetDateTime now = db.sql("select clock_timestamp()").query(OffsetDateTime.class).single();
    var expirations =
        db.sql(
                """
                select expires_at from identity.auth_rate_attempts
                where bucket = :bucket and subject = :subject and expires_at > :now
                order by expires_at desc
                offset :offset limit 1
                """)
            .param("bucket", bucket)
            .param("subject", subject)
            .param("now", now)
            .param("offset", limit - 1)
            .query(OffsetDateTime.class)
            .optional();
    expirations.ifPresent(
        expires -> {
          var remaining = java.time.Duration.between(now, expires);
          long seconds = remaining.toSeconds() + (remaining.getNano() == 0 ? 0 : 1);
          throw new RateLimitException(seconds);
        });
  }

  private void record(String bucket, String subject, int seconds) {
    db.sql(
            """
            delete from identity.auth_rate_attempts
            where ctid in (
                select ctid from identity.auth_rate_attempts
                where expires_at <= clock_timestamp()
                order by expires_at limit 100 for update skip locked
            )
            """)
        .update();
    db.sql(
            """
            insert into identity.auth_rate_attempts (bucket, subject, expires_at)
            values (:bucket, :subject, clock_timestamp() + make_interval(secs => :seconds))
            """)
        .param("bucket", bucket)
        .param("subject", subject)
        .param("seconds", seconds)
        .update();
  }

  private static String subject(String email) {
    return email.toLowerCase(Locale.ROOT);
  }
}
