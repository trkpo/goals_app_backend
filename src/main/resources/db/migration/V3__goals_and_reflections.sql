CREATE SCHEMA IF NOT EXISTS goals;

CREATE TYPE goals.goal_status AS ENUM ('draft', 'active', 'completed');
CREATE TYPE goals.reflection_result AS ENUM ('came_true', 'partially', 'failed');

CREATE TABLE goals.goals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    title varchar(100) NOT NULL,
    goal_text text NOT NULL,
    path_text text NOT NULL,
    expected_result varchar(500) NOT NULL,
    status goals.goal_status NOT NULL DEFAULT 'draft',
    planned_end_at timestamptz NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT goals_title_length CHECK (char_length(title) BETWEEN 3 AND 100),
    CONSTRAINT goals_goal_text_length CHECK (char_length(goal_text) BETWEEN 1 AND 5000),
    CONSTRAINT goals_path_text_length CHECK (char_length(path_text) BETWEEN 1 AND 5000),
    CONSTRAINT goals_expected_result_length CHECK (char_length(expected_result) BETWEEN 1 AND 500),
    CONSTRAINT goals_completed_at_matches_status CHECK ((status = 'completed') = (completed_at IS NOT NULL))
);

CREATE INDEX goals_user_created_idx ON goals.goals (user_id, created_at DESC, id DESC);
CREATE INDEX goals_user_status_created_idx ON goals.goals (user_id, status, created_at DESC, id DESC);
CREATE INDEX goals_status_planned_end_idx ON goals.goals (status, planned_end_at);

CREATE TABLE goals.reflections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id uuid NOT NULL REFERENCES goals.goals (id) ON DELETE CASCADE,
    result goals.reflection_result NOT NULL,
    fact_text varchar(3000),
    self_score smallint NOT NULL,
    reflected_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reflections_goal_unique UNIQUE (goal_id),
    CONSTRAINT reflections_self_score_range CHECK (self_score BETWEEN 1 AND 10),
    CONSTRAINT reflections_fact_text_required CHECK (result = 'came_true' OR fact_text IS NOT NULL),
    CONSTRAINT reflections_fact_text_length CHECK (fact_text IS NULL OR char_length(fact_text) BETWEEN 1 AND 3000)
);

CREATE INDEX reflections_result_reflected_idx ON goals.reflections (result, reflected_at DESC, id DESC);
