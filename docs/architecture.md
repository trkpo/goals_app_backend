# Архитектура backend

Spring Boot 4, Java 21, PostgreSQL + Flyway. Один деплоимый модуль, внутри — модульный монолит.

## Слои и пакеты

Корень — `ru.trkpo.goals`.

| Пакет | Назначение |
|-------|------------|
| `common.error` | `ErrorCode`, `ApiException`, `ErrorResponse`, единый обработчик исключений |
| `common.web` | сквозные веб-компоненты: фильтр `X-Request-Id` |
| `<модуль>` | `identity`, `profiles`, `goals`, `feed`, `notifications` — по модулю на раздел API |

Внутри модуля четыре слоя; зависимость идёт только сверху вниз:

```
web          контроллеры, DTO запросов/ответов, валидация формы
service      сценарии, транзакции, правила предметной области
persistence  JPA-сущности и репозитории
domain       типы предметной области без зависимости от Spring
```

Контроллер не обращается к репозиторию напрямую, сущности не выходят за `persistence`.

## Конфигурация

`src/main/resources/application.yml`, значения окружения читаются из переменных: `DB_URL`,
`DB_USER`, `DB_PASSWORD`, `PORT`. Профили `dev` и `stage` включаются через `SPRING_PROFILES_ACTIVE`
(см. [deploy.md](deploy.md)). Секретов в репозитории нет.

## Единая обработка ошибок (NF_Err_1)

Любой ответ с ошибкой — JSON-конверт из ADR 0001:

```json
{ "code": "TITLE_LENGTH", "message": "…", "field": "title", "request_id": "7f3c…" }
```

- Сервисы бросают `ApiException(ErrorCode, field)`. Статус HTTP определяется кодом, а не местом броска:
  один код — один статус.
- `@Valid`-ошибки превращаются в `422`. Код ошибки задаётся в `message` ограничения:
  `@Size(min = 3, max = 100, message = "TITLE_LENGTH")`; если имени из `ErrorCode` нет, это
  `REQUIRED_FIELD`. `field` — JSON-путь в snake_case.
- Ошибки самого Spring (неизвестный маршрут, метод, формат, битый JSON) и ошибки вне контроллеров
  (`/error`) тоже отдаются в конверте.
- Всё непредвиденное — `500 INTERNAL_ERROR` с общим текстом. Исключение, SQL и трассировка пишутся
  только в журнал, клиент их не видит.

Коды `INVALID_REQUEST`, `METHOD_NOT_ALLOWED` и `UNSUPPORTED_MEDIA_TYPE` добавлены сверх перечня
OpenAPI для ошибок протокола, которые не относятся к предметной области. Их нужно добавить в
`ErrorCode` в `api/openapi.yaml`.

## X-Request-Id

`RequestIdFilter` выполняется первым: принимает входящий `X-Request-Id` (до 64 символов
`A-Za-z0-9._-`, иначе заменяет на новый UUID), кладёт в MDC под ключом `request_id`, возвращает в
заголовке ответа и в поле `request_id` ошибки. Идентификатор есть в каждой строке журнала
(`logging.pattern.level`), а на каждый запрос пишется строка `метод путь -> статус (мс)`.

## Health-check

Spring Boot Actuator на том же порту, открыты только `health` и `info`:

- `GET /actuator/health` — общий статус;
- `GET /actuator/health/liveness` — процесс жив;
- `GET /actuator/health/readiness` — приложение готово, включает проверку базы.

Docker и Caddy используют эти же пути (см. [deploy.md](deploy.md)).

## Тесты

Интеграционные тесты поднимают PostgreSQL в Testcontainers. Если Docker стоит через Colima или
OrbStack и Testcontainers его не находит, задайте `DOCKER_HOST`, например
`export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.
