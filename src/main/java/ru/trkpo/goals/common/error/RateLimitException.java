package ru.trkpo.goals.common.error;

public final class RateLimitException extends ApiException {

  private static final long serialVersionUID = 1L;
  private final long retryAfterSeconds;

  public RateLimitException(long retryAfterSeconds) {
    super(ErrorCode.TOO_MANY_REQUESTS);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
