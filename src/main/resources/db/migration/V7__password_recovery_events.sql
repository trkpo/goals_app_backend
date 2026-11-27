-- Служебная таблица для лимитов F_Auth_13/F_Auth_14.
-- Хранится в базе, а не в памяти, чтобы лимиты соблюдались всеми репликами сервиса.
CREATE TABLE identity.password_recovery_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(254) NOT NULL,
    action varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT password_recovery_events_action_check CHECK (action IN ('request', 'verify'))
);

CREATE INDEX password_recovery_events_email_action_idx
    ON identity.password_recovery_events (email, action, created_at);
