-- Базовая миграция: проверяет, что Flyway подключён к общей базе.
-- Таблицы доменов появятся в задачах #3 и #4.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
