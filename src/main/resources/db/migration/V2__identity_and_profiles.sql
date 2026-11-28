CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS profiles;

CREATE TABLE identity.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    email_confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    CONSTRAINT users_email_not_blank CHECK (btrim(email) <> ''),
    CONSTRAINT users_password_hash_not_blank CHECK (btrim(password_hash) <> '')
);

CREATE UNIQUE INDEX users_email_active_ux ON identity.users (lower(email)) WHERE deleted_at IS NULL;
CREATE INDEX users_deleted_at_idx ON identity.users (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE identity.email_confirmation_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    code_hash varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT email_confirmation_codes_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX email_confirmation_codes_user_idx ON identity.email_confirmation_codes (user_id, created_at DESC);
CREATE UNIQUE INDEX email_confirmation_codes_active_ux ON identity.email_confirmation_codes (user_id) WHERE consumed_at IS NULL;

CREATE TABLE identity.password_reset_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    code_hash varchar(255) NOT NULL,
    reset_token_hash varchar(255),
    expires_at timestamptz NOT NULL,
    reset_token_expires_at timestamptz,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT password_reset_requests_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT password_reset_requests_token_pair CHECK ((reset_token_hash IS NULL) = (reset_token_expires_at IS NULL))
);

CREATE INDEX password_reset_requests_user_idx ON identity.password_reset_requests (user_id, created_at DESC);
CREATE UNIQUE INDEX password_reset_requests_active_ux ON identity.password_reset_requests (user_id) WHERE consumed_at IS NULL;

CREATE TABLE identity.sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    refresh_token_hash varchar(255) NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by_id uuid REFERENCES identity.sessions (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sessions_expiry_after_issue CHECK (expires_at > issued_at)
);

CREATE UNIQUE INDEX sessions_refresh_token_ux ON identity.sessions (refresh_token_hash);
CREATE INDEX sessions_user_expires_idx ON identity.sessions (user_id, expires_at DESC);
CREATE INDEX sessions_revoked_idx ON identity.sessions (revoked_at) WHERE revoked_at IS NOT NULL;

CREATE TABLE profiles.avatars (
    avatar_id varchar(64) PRIMARY KEY,
    url text NOT NULL,
    content_type varchar(128) NOT NULL,
    image_data bytea NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT avatars_id_not_blank CHECK (btrim(avatar_id) <> ''),
    CONSTRAINT avatars_url_not_blank CHECK (btrim(url) <> '')
);

CREATE TABLE profiles.profiles (
    user_id uuid PRIMARY KEY REFERENCES identity.users (id) ON DELETE CASCADE,
    nickname varchar(30) NOT NULL,
    avatar_id varchar(64) REFERENCES profiles.avatars (avatar_id) ON DELETE SET NULL,
    goals_created integer NOT NULL DEFAULT 0,
    results_published integer NOT NULL DEFAULT 0,
    completed_goals integer NOT NULL DEFAULT 0,
    self_score_total integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT profiles_nickname_length CHECK (char_length(nickname) BETWEEN 2 AND 30),
    CONSTRAINT profiles_counters_non_negative CHECK (goals_created >= 0 AND results_published >= 0 AND completed_goals >= 0 AND self_score_total >= 0),
    CONSTRAINT profiles_self_score_total_range CHECK (self_score_total BETWEEN 0 AND completed_goals * 10)
);

CREATE INDEX profiles_nickname_idx ON profiles.profiles (lower(nickname));
CREATE INDEX profiles_avatar_idx ON profiles.profiles (avatar_id);
