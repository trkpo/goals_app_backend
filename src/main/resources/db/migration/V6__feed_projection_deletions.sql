CREATE TYPE feed.deletion_reason AS ENUM ('publication_deleted', 'goal_deleted', 'account_deleted');

CREATE TABLE feed.projection_deletions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id uuid NOT NULL,
    reason feed.deletion_reason NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    CONSTRAINT projection_deletions_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX projection_deletions_pending_idx ON feed.projection_deletions (occurred_at, id) WHERE processed_at IS NULL;
CREATE INDEX projection_deletions_processed_idx ON feed.projection_deletions (processed_at) WHERE processed_at IS NOT NULL;
CREATE INDEX projection_deletions_publication_idx ON feed.projection_deletions (publication_id);
