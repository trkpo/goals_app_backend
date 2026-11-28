package ru.trkpo.goals.identity.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.trkpo.goals.identity.domain.Identity;

@Repository
public class IdentityStore {

  private final JdbcClient db;

  public IdentityStore(JdbcClient db) {
    this.db = db;
  }

  public record Credentials(Identity identity, String passwordHash) {}

  public record RefreshSession(UUID id, UUID userId) {}

  public Optional<Credentials> findCredentials(String email) {
    return db.sql(
            """
            select id, email, email_confirmed_at, created_at, password_hash
            from identity.users
            where lower(email) = lower(:email) and deleted_at is null
            """)
        .param("email", email)
        .query((rs, row) -> new Credentials(identity(rs), rs.getString("password_hash")))
        .optional();
  }

  public Optional<Identity> findConfirmedIdentity(UUID userId) {
    return db.sql(
            """
            select id, email, email_confirmed_at, created_at
            from identity.users
            where id = :id and deleted_at is null and email_confirmed_at is not null
            """)
        .param("id", userId)
        .query((rs, row) -> identity(rs))
        .optional();
  }

  public Optional<RefreshSession> lockActiveSession(String hash) {
    return db.sql(
            """
            select s.id, s.user_id
            from identity.sessions s
            join identity.users u on u.id = s.user_id
            where s.refresh_token_hash = :hash and s.revoked_at is null
              and s.expires_at > clock_timestamp()
              and u.deleted_at is null and u.email_confirmed_at is not null
            for update of s
            """)
        .param("hash", hash)
        .query(
            (rs, row) ->
                new RefreshSession(
                    rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)))
        .optional();
  }

  public UUID createSession(UUID userId, String hash, Duration ttl) {
    return db.sql(
            """
            insert into identity.sessions (user_id, refresh_token_hash, expires_at)
            values (:userId, :hash, now() + make_interval(secs => :seconds))
            returning id
            """)
        .param("userId", userId)
        .param("hash", hash)
        .param("seconds", ttl.toSeconds())
        .query(UUID.class)
        .single();
  }

  public void markReplaced(UUID previousId, UUID replacementId) {
    db.sql(
            """
            update identity.sessions
            set revoked_at = now(), replaced_by_id = :replacement, updated_at = now()
            where id = :previous
            """)
        .param("replacement", replacementId)
        .param("previous", previousId)
        .update();
  }

  private static Identity identity(ResultSet rs) throws SQLException {
    return new Identity(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getObject("email_confirmed_at") != null,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
