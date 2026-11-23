CREATE TABLE identity.auth_rate_attempts (
    bucket text NOT NULL,
    subject text NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX auth_rate_attempts_bucket_idx
    ON identity.auth_rate_attempts (bucket, subject, expires_at);
CREATE INDEX auth_rate_attempts_expiry_idx
    ON identity.auth_rate_attempts (expires_at);
