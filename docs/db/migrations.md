# Миграции и тестовые данные

- **Статус:** принято
- **Задача:** [#4 «Настроить миграции и тестовые данные»](https://github.com/trkpo/goals_app_backend/issues/4)
- **Связанные документы:** [`docs/db/schema.md`](schema.md), HLD (раздел 9.4), `compose.yaml`

## 1. Состав

| Каталог | Назначение |
|---------|------------|
| `src/main/resources/db/migration` | версионные миграции Flyway `V1…V5` |
| `src/main/resources/db/seed` | repeatable-миграция `R__dev_seed.sql` с данными для разработки |
| `db/rollback` | down-скрипты для отката каждой версии |
| `src/dbTools/java` | утилита отката, запускаемая Gradle-задачей `dbRollback` |

Seed подключается только в профиле `dev`:

```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/seed
```

В профилях `stage` и по умолчанию используются только `db/migration`, поэтому тестовые данные не попадают на стенды.

## 2. Локальная база одной командой

```sh
docker compose up -d db
```

Контейнер `postgres:17-alpine` публикует порт `5432` и хранит данные в томе `db-data`.

Запуск приложения с миграциями и seed:

```sh
docker compose up --build
```

или из IDE / терминала:

```sh
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Эквивалентные Gradle-задачи:

```sh
./gradlew dbUp
./gradlew dbDown
```

`dbDown` останавливает контейнеры и **не удаляет** том с данными.

## 3. Seed-данные

`R__dev_seed.sql` идемпотентен (`ON CONFLICT DO NOTHING`) и создаёт:

- 3 подтверждённые учётные записи и профиля;
- 4 цели в статусах `draft`, `active` и `completed`;
- 2 итога и 2 публикации;
- 3 реакции;
- настройки напоминаний для каждого пользователя, одно расписание и одно доставленное уведомление.

Учётные записи:

| E-mail | Пароль | Никнейм |
|--------|--------|---------|
| `alice@example.com` | `Password1` | `alice` |
| `bob@example.com` | `Password1` | `bob` |
| `carol@example.com` | `Password1` | `carol` |

Пароли хранятся в виде bcrypt-хеша, как требует `NF_Sec_1`.

## 4. Применение миграций к пустой базе

Flyway запускается при старте приложения и применяет `V1…V5`, затем repeatable-seed в профиле `dev`. Проверка выполняется тестами `GoalsAppBackendApplicationTests` и `SeedDataTests` на Testcontainers.

Ручной прогон на пустой базе:

```sh
docker compose down -v
docker compose up -d db
./gradlew bootRun --args='--spring.profiles.active=dev'
```

## 5. Откат миграций

```sh
./gradlew dbRollback
```

Задача выполняет down-скрипты из `db/rollback` в обратном порядке (`V5 → V1`), удаляет соответствующие строки из `flyway_schema_history` и очищает записи repeatable-миграций, чтобы seed применился повторно.

Параметры подключения совпадают с приложением и задаются переменными окружения:

| Переменная | Значение по умолчанию |
|------------|-----------------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/goals` |
| `DB_USER` | `goals` |
| `DB_PASSWORD` | `goals` |

Пример для отдельной базы:

```sh
DB_URL=jdbc:postgresql://localhost:5432/goals_test ./gradlew dbRollback
```

После отката повторный запуск приложения снова применит миграции и seed.

## 6. Полный сброс

```sh
docker compose down -v
docker compose up -d db
./gradlew bootRun --args='--spring.profiles.active=dev'
```

`down -v` удаляет том `db-data`; обычный `dbDown` данные сохраняет.

## 7. Сохранность данных

Данные лежат в томе `db-data`, а не в контейнере приложения. Перезапуск приложения или контейнера не изменяет содержимое базы (`NF_Rel_2`):

```sh
docker compose restart app
docker compose exec db psql -U goals -d goals -c 'SELECT count(*) FROM goals.goals'
```

Ожидаемый результат для seed-набора — `4`.

## 8. Проверки

| Проверка | Где |
|----------|-----|
| миграции применяются на пустой базе | `GoalsAppBackendApplicationTests`, CI (`./gradlew build`) |
| seed создаёт пользователей, цели, публикации, реакции и настройки | `SeedDataTests`, CI |
| откат и повторное применение | `./gradlew dbRollback`, ручная проверка |
| данные переживают перезапуск | том `db-data`, `docker compose restart app` |
