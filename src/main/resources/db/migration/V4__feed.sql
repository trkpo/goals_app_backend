CREATE SCHEMA IF NOT EXISTS feed;

CREATE TYPE feed.publication_visibility AS ENUM ('visible', 'hidden');
CREATE TYPE feed.reaction_type AS ENUM ('support', 'energy');

CREATE TABLE feed.publications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id uuid NOT NULL REFERENCES goals.goals (id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    visibility feed.publication_visibility NOT NULL DEFAULT 'visible',
    published_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT publications_goal_unique UNIQUE (goal_id)
);

CREATE INDEX publications_visibility_published_idx ON feed.publications (visibility, published_at DESC, id DESC);
CREATE INDEX publications_author_idx ON feed.publications (author_id, created_at DESC, id DESC);

CREATE TABLE feed.reactions (
    publication_id uuid NOT NULL REFERENCES feed.publications (id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    reaction feed.reaction_type NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (publication_id, user_id)
);

CREATE INDEX reactions_publication_type_idx ON feed.reactions (publication_id, reaction);
CREATE INDEX reactions_user_idx ON feed.reactions (user_id);
