CREATE SCHEMA IF NOT EXISTS notifications;
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TYPE notifications.email_delivery_status AS ENUM ('not_requested', 'sent', 'failed');
CREATE TYPE audit.entity_type AS ENUM ('goal', 'post', 'profile', 'session');
CREATE TYPE audit.operation_type AS ENUM ('create', 'update', 'delete');

CREATE TABLE notifications.settings (
    user_id uuid PRIMARY KEY REFERENCES identity.users (id) ON DELETE CASCADE,
    email_duplicate_enabled boolean NOT NULL DEFAULT false,
    default_remind_after_days smallint NOT NULL DEFAULT 1,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT settings_remind_after_days_range CHECK (default_remind_after_days BETWEEN 1 AND 365)
);

CREATE TABLE notifications.reminders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id uuid NOT NULL REFERENCES goals.goals (id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    remind_after_days smallint,
    scheduled_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reminders_goal_unique UNIQUE (goal_id),
    CONSTRAINT reminders_remind_after_days_range CHECK (remind_after_days IS NULL OR remind_after_days BETWEEN 1 AND 365)
);

CREATE INDEX reminders_user_idx ON notifications.reminders (user_id, goal_id);
CREATE INDEX reminders_due_idx ON notifications.reminders (scheduled_at) WHERE enabled AND scheduled_at IS NOT NULL;

CREATE TABLE notifications.notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id uuid NOT NULL REFERENCES goals.goals (id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    scheduled_at timestamptz NOT NULL,
    delivered_at timestamptz,
    email_status notifications.email_delivery_status NOT NULL DEFAULT 'not_requested',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_goal_unique UNIQUE (goal_id)
);

CREATE INDEX notifications_user_scheduled_idx ON notifications.notifications (user_id, scheduled_at DESC, id DESC);
CREATE INDEX notifications_user_delivered_idx ON notifications.notifications (user_id, delivered_at DESC, id DESC);

CREATE TABLE audit.events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type audit.entity_type NOT NULL,
    entity_id uuid NOT NULL,
    operation audit.operation_type NOT NULL,
    user_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_events_occurred_idx ON audit.events (occurred_at DESC, id DESC);
CREATE INDEX audit_events_user_occurred_idx ON audit.events (user_id, occurred_at DESC, id DESC);
CREATE INDEX audit_events_entity_occurred_idx ON audit.events (entity_type, entity_id, occurred_at DESC, id DESC);

CREATE FUNCTION audit.reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit.events is append-only';
END;
$$;

CREATE TRIGGER audit_events_append_only
BEFORE UPDATE OR DELETE ON audit.events
FOR EACH ROW
EXECUTE FUNCTION audit.reject_mutation();
