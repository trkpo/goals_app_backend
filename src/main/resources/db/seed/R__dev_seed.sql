INSERT INTO profiles.avatars (avatar_id, url, content_type, image_data, version)
VALUES
    ('avatar_01', '/api/v1/profiles/avatars/avatar_01', 'image/png', decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64'), 1),
    ('avatar_02', '/api/v1/profiles/avatars/avatar_02', 'image/png', decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64'), 1),
    ('avatar_03', '/api/v1/profiles/avatars/avatar_03', 'image/png', decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64'), 1)
ON CONFLICT DO NOTHING;

INSERT INTO identity.users (id, email, password_hash, email_confirmed_at, created_at, updated_at)
VALUES
    ('a0000000-0000-4000-8000-000000000001', 'alice@example.com', '$2b$10$lGG9I02IMoWj8uWzgjKI4O7LWwvYFxNRwGzrxzuGopZUKBexizoFW', now() - interval '30 days', now() - interval '30 days', now() - interval '30 days'),
    ('a0000000-0000-4000-8000-000000000002', 'bob@example.com', '$2b$10$lGG9I02IMoWj8uWzgjKI4O7LWwvYFxNRwGzrxzuGopZUKBexizoFW', now() - interval '25 days', now() - interval '25 days', now() - interval '25 days'),
    ('a0000000-0000-4000-8000-000000000003', 'carol@example.com', '$2b$10$lGG9I02IMoWj8uWzgjKI4O7LWwvYFxNRwGzrxzuGopZUKBexizoFW', now() - interval '20 days', now() - interval '20 days', now() - interval '20 days')
ON CONFLICT DO NOTHING;

INSERT INTO profiles.profiles (user_id, nickname, avatar_id, goals_created, results_published, completed_goals, self_score_total, created_at, updated_at)
VALUES
    ('a0000000-0000-4000-8000-000000000001', 'alice', 'avatar_01', 2, 1, 1, 8, now() - interval '30 days', now() - interval '9 days'),
    ('a0000000-0000-4000-8000-000000000002', 'bob', 'avatar_02', 1, 1, 1, 9, now() - interval '25 days', now() - interval '5 days'),
    ('a0000000-0000-4000-8000-000000000003', 'carol', 'avatar_03', 1, 0, 0, 0, now() - interval '20 days', now() - interval '2 days')
ON CONFLICT DO NOTHING;

INSERT INTO goals.goals (id, user_id, title, goal_text, path_text, expected_result, status, planned_end_at, completed_at, created_at, updated_at, version)
VALUES
    ('b0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001',
     'Run a half marathon', 'Complete a half marathon without walking.', '## Plan' || chr(10) || '1. Three runs per week' || chr(10) || '2. Long run on Sunday',
     'Finish 21.1 km under two hours', 'completed', now() - interval '20 days', now() - interval '10 days',
     now() - interval '45 days', now() - interval '10 days', 2),
    ('b0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001',
     'Read twelve books', 'Read one book every month.', 'Read thirty pages every evening.', 'Twelve books by the end of the year',
     'active', now() + interval '30 days', NULL, now() - interval '15 days', now() - interval '15 days', 1),
    ('b0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000002',
     'Launch a side project', 'Ship a small product to real users.', 'Build an MVP and collect first feedback.', 'Ten active users',
     'completed', now() - interval '15 days', now() - interval '5 days', now() - interval '60 days', now() - interval '5 days', 4),
    ('b0000000-0000-4000-8000-000000000004', 'a0000000-0000-4000-8000-000000000003',
     'Learn to cook five dishes', 'Master five recipes.', 'Cook one new recipe every week.', 'Five repeatable recipes',
     'draft', now() + interval '60 days', NULL, now() - interval '2 days', now() - interval '2 days', 0)
ON CONFLICT DO NOTHING;

INSERT INTO goals.reflections (id, goal_id, result, fact_text, self_score, reflected_at, created_at)
VALUES
    ('c0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', 'partially',
     'Finished 18 km; the last 3 km were too much.', 8, now() - interval '10 days', now() - interval '10 days'),
    ('c0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000003', 'came_true',
     NULL, 9, now() - interval '5 days', now() - interval '5 days')
ON CONFLICT DO NOTHING;

INSERT INTO feed.publications (id, goal_id, author_id, visibility, published_at, created_at, updated_at, version)
VALUES
    ('d0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001',
     'visible', now() - interval '9 days', now() - interval '9 days', now() - interval '9 days', 3),
    ('d0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000002',
     'visible', now() - interval '5 days', now() - interval '5 days', now() - interval '5 days', 4)
ON CONFLICT DO NOTHING;

INSERT INTO feed.reactions (publication_id, user_id, reaction)
VALUES
    ('d0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000002', 'support'),
    ('d0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000003', 'energy'),
    ('d0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001', 'support')
ON CONFLICT DO NOTHING;

INSERT INTO notifications.settings (user_id, email_duplicate_enabled, default_remind_after_days)
VALUES
    ('a0000000-0000-4000-8000-000000000001', true, 3),
    ('a0000000-0000-4000-8000-000000000002', false, 1),
    ('a0000000-0000-4000-8000-000000000003', false, 7)
ON CONFLICT DO NOTHING;

INSERT INTO notifications.reminders (id, goal_id, user_id, enabled, remind_after_days, scheduled_at, version, created_at, updated_at)
VALUES
    ('e0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001',
     true, 2, now() - interval '1 day', 1, now() - interval '15 days', now() - interval '1 day')
ON CONFLICT DO NOTHING;

INSERT INTO notifications.notifications (id, goal_id, user_id, scheduled_at, delivered_at, email_status, version, created_at, updated_at)
VALUES
    ('f0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001',
     now() - interval '1 day', now() - interval '1 day', 'sent', 1, now() - interval '1 day', now() - interval '1 day')
ON CONFLICT DO NOTHING;
