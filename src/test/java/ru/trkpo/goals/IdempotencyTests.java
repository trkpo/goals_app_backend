package ru.trkpo.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.common.idempotency.Idempotency;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyTests {

  @Autowired private Idempotency idempotency;
  @Autowired private JdbcClient db;

  @Test
  void concurrentCreatesReturnOneResultAndDifferentBodyConflicts() throws Exception {
    String actor = UUID.randomUUID().toString();
    String email = actor + "@example.com";
    String path = "/api/v1/test/users";
    byte[] body = email.getBytes(StandardCharsets.UTF_8);
    var start = new CountDownLatch(1);

    Supplier<ResponseEntity<byte[]>> create =
        () -> {
          UUID userId = createUser(email);
          return ResponseEntity.created(URI.create(path + "/" + userId))
              .eTag("\"1\"")
              .body(userId.toString().getBytes(StandardCharsets.UTF_8));
        };

    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                start.await();
                return idempotency.execute(actor, "POST", path, "create", body, create);
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return idempotency.execute(actor, "POST", path, "create", body, create);
              });
      start.countDown();
      var response = first.get(10, TimeUnit.SECONDS);
      var replay = second.get(10, TimeUnit.SECONDS);
      assertThat(replay.getStatusCode()).isEqualTo(response.getStatusCode());
      assertThat(replay.getHeaders()).isEqualTo(response.getHeaders());
      assertThat(replay.getBody()).containsExactly(response.getBody());
    }

    assertThat(
            db.sql("select count(*) from identity.users where email = :email")
                .param("email", email)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThatThrownBy(
            () -> idempotency.execute(actor, "POST", path, "create", new byte[0], create))
        .isInstanceOfSatisfying(
            ApiException.class,
            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void deleteReplaysNoContentAndAnExpiredKeyRunsTheOperationAgain() {
    String actor = UUID.randomUUID().toString();
    UUID userId = createUser(actor + "@example.com");
    String path = "/api/v1/test/users/" + userId;
    Supplier<ResponseEntity<byte[]>> delete =
        () -> {
          int affected =
              db.sql("delete from identity.users where id = :id").param("id", userId).update();
          if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND);
          }
          return ResponseEntity.noContent().build();
        };

    for (int attempt = 0; attempt < 2; attempt++) {
      var response = idempotency.execute(actor, "DELETE", path, "delete", new byte[0], delete);
      assertThat(response.getStatusCode().value()).isEqualTo(204);
      assertThat(response.getBody()).isNull();
    }

    db.sql("update public.idempotency_results set expires_at = now() where actor = :actor")
        .param("actor", actor)
        .update();
    assertThatThrownBy(
            () -> idempotency.execute(actor, "DELETE", path, "delete", new byte[0], delete))
        .isInstanceOfSatisfying(
            ApiException.class,
            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void failedMutationRollsBackAndDoesNotConsumeTheKey() {
    String actor = UUID.randomUUID().toString();
    String email = actor + "@example.com";
    String path = "/api/v1/test/users";
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    actor,
                    "POST",
                    path,
                    "retry",
                    new byte[0],
                    () -> {
                      createUser(email);
                      throw new ApiException(ErrorCode.INVALID_REQUEST);
                    }))
        .isInstanceOf(ApiException.class);

    var retry =
        idempotency.execute(
            actor,
            "POST",
            path,
            "retry",
            new byte[0],
            () -> ResponseEntity.ok(createUser(email).toString().getBytes(StandardCharsets.UTF_8)));
    assertThat(retry.getBody()).isNotEmpty();
  }

  private UUID createUser(String email) {
    return db.sql(
            "insert into identity.users (email, password_hash) values (:email, 'hash') returning id")
        .param("email", email)
        .query(UUID.class)
        .single();
  }
}
