CREATE TABLE public.idempotency_results (
    actor text NOT NULL,
    method text NOT NULL,
    path text NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    request_hash bytea NOT NULL,
    response_status integer NOT NULL,
    response_headers text NOT NULL,
    response_body bytea,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (actor, method, path, idempotency_key)
);

CREATE INDEX idempotency_results_expiry_idx ON public.idempotency_results (expires_at);
