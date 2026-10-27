# goals_app

Backend приложения целей: Java 21, Spring Boot 4, PostgreSQL, Flyway.

## Запуск

Нужны JDK 21 и Docker.

```bash
docker compose up -d db   # только база
./gradlew bootRun         # приложение на http://localhost:8080
```

Всё в контейнерах: `docker compose up --build`. Проверка: `curl localhost:8080/actuator/health`.

## Проверки

```bash
./gradlew spotlessApply   # отформатировать код
./gradlew build           # сборка, линтер и тесты (тестам нужен Docker)
```

## Структура

```
src/main/java/ru/trkpo/goals   код приложения
src/main/resources/db/migration миграции Flyway (V<n>__<описание>.sql)
deploy/                         compose и Caddy для серверов dev/stage
docs/                           документация: деплой, API
.github/workflows/              CI и деплой
```

## Как работаем

- В `main` напрямую не пушим. Ветка под задачу создаётся из карточки (Development → Create a branch) или командой `gh issue develop <номер>` и называется `feature/<номер>-<кратко>`.
- Изменения попадают в `main` через pull request с зелёным CI. В описании PR пишем `Closes #<номер>`.
- Коммиты — в формате `тип: что сделано`: `feat`, `fix`, `docs`, `chore`, `build`, `ci`, `test`, `refactor`. Например: `docs: add openapi spec`.

CI/CD, окружения и секреты описаны в [docs/deploy.md](docs/deploy.md).
