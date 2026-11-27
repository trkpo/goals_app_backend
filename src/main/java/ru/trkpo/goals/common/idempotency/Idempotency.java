package ru.trkpo.goals.common.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class Idempotency {

  private static final TypeReference<Map<String, List<String>>> STORED_HEADERS =
      new TypeReference<>() {};

  private final JdbcClient db;
  private final JsonMapper json;

  public Idempotency(JdbcClient db, JsonMapper json) {
    this.db = db;
    this.json = json;
  }

  /**
   * Execute a JSON API mutation for an authenticated actor. Use a canonical concrete API path and
   * pass the same body bytes to the operation for deserialization and validation.
   */
  @Transactional
  public ResponseEntity<byte[]> executeHttp(
      UUID actor,
      HttpServletRequest request,
      String path,
      byte[] requestBody,
      Supplier<ResponseEntity<?>> operation) {
    String key = request.getHeader("Idempotency-Key");
    byte[] fingerprint =
        key == null
            ? requestBody
            : json.writeValueAsBytes(
                new HttpInput(request.getHeader(HttpHeaders.IF_MATCH), requestBody));
    return execute(
        actor.toString(),
        request.getMethod(),
        path,
        key,
        fingerprint,
        () -> serialize(operation.get()));
  }

  private ResponseEntity<byte[]> serialize(ResponseEntity<?> response) {
    var builder = ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders());
    if (response.getBody() == null) {
      return builder.build();
    }
    if (response.getHeaders().getContentType() == null) {
      builder.contentType(MediaType.APPLICATION_JSON);
    }
    return builder.body(json.writeValueAsBytes(response.getBody()));
  }

  /**
   * The controller supplies a trusted actor, the concrete resource path and original request bytes.
   * Authorization precedes lookup. The mutation and result must use the same database transaction.
   * Error responses should be raised as exceptions to roll back partial writes.
   */
  @Transactional
  public ResponseEntity<byte[]> execute(
      String actor,
      String method,
      String path,
      @Nullable String key,
      byte[] requestBody,
      Supplier<ResponseEntity<byte[]>> operation) {
    if (key == null) {
      return operation.get();
    }
    if (key.isBlank() || key.length() > 255) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Idempotency-Key");
    }

    var scope = new Scope(actor, method, path, key);
    byte[] hash = sha256(requestBody);
    db.sql("select pg_advisory_xact_lock(hashtextextended(:scope, 148560))")
        .param("scope", json.writeValueAsString(scope))
        .query((rs, row) -> Boolean.TRUE)
        .single();

    var cached =
        bind(
                """
                select request_hash, response_status, response_headers, response_body
                from public.idempotency_results
                where actor = :actor and method = :method and path = :path
                  and idempotency_key = :key and expires_at > clock_timestamp()
                """,
                scope)
            .query(
                (rs, row) ->
                    new SavedResult(
                        rs.getBytes("request_hash"),
                        rs.getInt("response_status"),
                        rs.getString("response_headers"),
                        rs.getBytes("response_body")))
            .optional();
    if (cached.isPresent()) {
      return replay(cached.get(), hash);
    }

    var response = operation.get();
    if (!response.getStatusCode().is2xxSuccessful()) {
      return response;
    }

    bind(
            """
            insert into public.idempotency_results
                (actor, method, path, idempotency_key, request_hash, response_status,
                 response_headers, response_body, expires_at)
            values (:actor, :method, :path, :key, :hash, :status,
                    :headers, :body, clock_timestamp() + interval '24 hours')
            on conflict (actor, method, path, idempotency_key) do update set
                request_hash = excluded.request_hash, response_status = excluded.response_status,
                response_headers = excluded.response_headers, response_body = excluded.response_body,
                expires_at = excluded.expires_at
            """,
            scope)
        .param("hash", hash)
        .param("status", response.getStatusCode().value())
        .param("headers", json.writeValueAsString(replayableHeaders(response.getHeaders())))
        .param("body", response.getBody(), java.sql.Types.BINARY)
        .update();
    // Remove expired rows in bounded batches without blocking operations in other scopes.
    db.sql(
            """
            delete from public.idempotency_results
            where ctid in (
                select ctid from public.idempotency_results
                where expires_at <= clock_timestamp()
                order by expires_at limit 100 for update skip locked
            )
            """)
        .update();
    return response;
  }

  private ResponseEntity<byte[]> replay(SavedResult result, byte[] hash) {
    if (!MessageDigest.isEqual(result.hash(), hash)) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
    }
    var builder = ResponseEntity.status(result.status());
    json.readValue(result.headers(), STORED_HEADERS)
        .forEach((name, values) -> builder.header(name, values.toArray(String[]::new)));
    return builder.body(result.body());
  }

  private JdbcClient.StatementSpec bind(String query, Scope scope) {
    return db.sql(query)
        .param("actor", scope.actor())
        .param("method", scope.method())
        .param("path", scope.path())
        .param("key", scope.key());
  }

  private static Map<String, List<String>> replayableHeaders(HttpHeaders headers) {
    var result = new java.util.LinkedHashMap<String, List<String>>();
    for (String name :
        List.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.LOCATION,
            HttpHeaders.ETAG,
            HttpHeaders.CACHE_CONTROL,
            HttpHeaders.VARY)) {
      List<String> values = headers.get(name);
      if (values != null) {
        result.put(name, values);
      }
    }
    return result;
  }

  private static byte[] sha256(byte[] body) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(body);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record HttpInput(@Nullable String ifMatch, byte[] body) {}

  private record Scope(String actor, String method, String path, String key) {}

  private record SavedResult(byte[] hash, int status, String headers, byte @Nullable [] body) {}
}
