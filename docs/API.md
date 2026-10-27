# API Documentation

Аутентификация: `Authorization: Bearer <access_token>`. Refresh-токен передаётся в `HttpOnly; Secure; SameSite=Strict` cookie.
Пагинация — cursor-based: `?limit=<1..100>&cursor=<opaque>`, ответ содержит `next_cursor`.
Версия API: только `v1`, дальнейшее версионирование — в OpenAPI ([`api/openapi.yaml`](../api/openapi.yaml)).

Состав доменов: `identity`, `profiles`, `goals`, `feed`, `notifications`, `audit` — каждому домену соответствует отдельный сервис со своим выпуском.

## Сквозные правила

1. **Аутентификация.** Все защищённые эндпоинты требуют `Authorization: Bearer <access_token>`. При истечении access-токена клиент вызывает `identity.auth.refresh`; при неудаче — открывается экран входа с сообщением о завершённом сеансе (NF_Sec_4).
2. **Валидация.** Клиентская валидация (NF_Common_1) дополняется серверной. Сообщения об ошибках привязываются к полю (F_Common_4); при отправке обязательного поля с пустым значением форма блокируется (F_Common_3).
3. **Кодировка.** Все текстовые поля — UTF-8, кроме пароля (F_Common_5).
4. **Безопасность.** HTTPS-only (NF_Sec_3); пароли хранятся только в виде хеша (NF_Sec_1); личные цели и черновики доступны только автору (NF_Sec_2).
5. **Подтверждение адреса.** После регистрации учётная запись создаётся в статусе «Ожидает подтверждения e-mail»; вход невозможен до подтверждения адреса (F_Auth_9), а зарегистрированный, но неподтверждённый адрес при входе считается незарегистрированным (F_Auth_4). После успешного подтверждения адреса учётная запись становится доступной для входа (F_Auth_18). Подтверждение — шестизначным кодом из письма (F_Auth_12), восстановление пароля — тоже кодом (F_Auth_13–F_Auth_15).
6. **Согласованность.** База данных общая для всех сервисов. Операция, затрагивающая несколько областей (итог, публикация, реакции), выполняется одной транзакцией в общей базе; междоменных событий и очереди сообщений нет.
   - `identity`, `profiles`, `goals`, `notifications`, `feed` — запись строгая: изменение и увеличение признака версии записи выполняются вместе.
   - Лента — производное представление: страницы обновляет задание проекции по признаку версии, допускается отложенная согласованность.
   - `audit` — только добавление, записи неизменяемы.
7. **Идемпотентность.** Изменяющие операции принимают заголовок `Idempotency-Key`; повтор с тем же ключом возвращает результат первого запроса и не создаёт второй записи. Для операций без ключа повтор безопасен: повторное создание даёт 409, повторное чтение — 200.
8. **Ограничение частоты.** Входы, отправка писем с кодами и проверка кодов ограничены по частоте (F_Auth_13, F_Auth_14): не более 3 проверок кода в минуту (F_Auth_13), новый код — не чаще одного раза в 5 минут (F_Auth_14), не более 5 неудачных попыток входа в минуту. Ответ — `429 TOO_MANY_REQUESTS` с `Retry-After`.
9. **Канал подписки (необязательный).** Клиентское приложение может открыть поток событий `GET /api/v1/feed/stream` и обновлять страницу, когда данные изменились. Поток присылает только признак изменения, содержимое клиент получает обычным запросом; если канал недоступен, клиент переходит на периодическое обновление (раз в 30 секунд) или перечитывает данные по действию пользователя.
10. **Журнал операций.** Каждая операция создания, изменения и удаления фиксируется в `audit` (пользователь, объект, операция, метка времени — NF_Obs_1). Изменение профиля, цели или публикации увеличивает признак версии: по нему задание проекции обновляет отображаемые в ленте ник и аватар.

## Домен identity

### identity.auth.register

**Метод и путь:** `POST /api/v1/identity/auth/register`
**Назначение:** регистрация учётной записи по e-mail и паролю.
**Требования:** F_Auth_3, F_Auth_7, F_Auth_8, F_Auth_9, F_Auth_12, F_Auth_14, NF_Sec_1, NF_Sec_3, NF_Err_1
**Авторизация:** не требуется.

Body:

```json
{ "email": "user@example.com", "password": "Secret123" }
```

Валидация:
- `email` — RFC 5322, иначе `400 INVALID_EMAIL` / «Некорректный адрес».
- `password` — 8–32 ASCII, ≥1 цифра, ≥1 заглавная буква; иначе `400 WEAK_PASSWORD`.
- E-mail уже используется → `409 EMAIL_TAKEN` / «Этот e-mail уже зарегистрирован».

Успех: `201 Created`

```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "created_at": "2026-10-07T12:00:00Z",
  "email_confirmation_sent": true,
  "email_confirmation_expires_in": 600
}
```

Учётная запись создаётся в статусе «Ожидает подтверждения e-mail»; до подтверждения адреса вход невозможен (F_Auth_9). Код подтверждения — 6 цифр, срок действия 10 минут (F_Auth_12).

Ошибки: `400 INVALID_EMAIL`, `400 WEAK_PASSWORD`, `409 EMAIL_TAKEN`, `429 TOO_MANY_REQUESTS`, `500 INTERNAL_ERROR`.
Идемпотентность: по `Idempotency-Key`; повтор без ключа с тем же e-mail → 409.

### identity.email.confirm

**Метод и путь:** `POST /api/v1/identity/email/confirm`
**Назначение:** подтверждение адреса шестизначным кодом из письма.
**Требования:** F_Auth_12, F_Auth_13, F_Auth_15, F_Auth_18
**Авторизация:** не требуется (достаточно e-mail и кода).

Body:

```json
{ "email": "user@example.com", "code": "123456" }
```

Правила:
- Код — 6 арабских цифр; срок действия 10 минут; одноразовый.
- Неверный или истёкший код → `422 INVALID_CODE` / «Код подтверждения некорректный»; можно продолжать вводить, но не более 3 проверок в минуту (F_Auth_13, F_Auth_15).

Успех: `200 OK`

```json
{ "email_confirmed": true }
```

Ошибки: `400 INVALID_EMAIL`, `422 INVALID_CODE`, `429 TOO_MANY_REQUESTS`.

### identity.email.confirm.resend

**Метод и путь:** `POST /api/v1/identity/email/confirm/resend`
**Назначение:** повторная отправка кода подтверждения.
**Требования:** F_Auth_12, F_Auth_14, F_Auth_15
**Авторизация:** не требуется.

Body: `{ "email": "user@example.com" }`

Правила: новый код можно запросить не чаще одного раза в 5 минут (F_Auth_14); предыдущий код становится недействительным (F_Auth_15).

Успех: `202 Accepted` → `{ "email_confirmation_sent": true, "email_confirmation_expires_in": 600 }`

Ошибки: `400 INVALID_EMAIL`, `429 TOO_MANY_REQUESTS` / «Новый код можно запросить не чаще одного раза в 5 минут».

### identity.auth.login

**Метод и путь:** `POST /api/v1/identity/auth/login`
**Требования:** F_Auth_3, F_Auth_4, F_Auth_5, F_Auth_6, F_Auth_9, F_Auth_18, NF_Sec_1, NF_Sec_3, NF_Sec_4, NF_Err_1
**Авторизация:** не требуется.

Body: `{ "email": "...", "password": "..." }`

Правила: вход возможен только после подтверждения адреса (F_Auth_9, F_Auth_18). Зарегистрированный, но неподтверждённый адрес при входе считается незарегистрированным → `401 USER_NOT_FOUND` (F_Auth_4). При истёкшем сеансе клиент показывает отдельное окно с повторным входом (NF_Sec_4).

Успех: `200 OK`

```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "email_confirmed": true,
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Headers: `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/identity/auth; Max-Age=2592000`

Клиент после успеха открывает вкладку «Мои цели» (F_Auth_6).

Ошибки: `400 INVALID_EMAIL`, `400 REQUIRED_FIELD`, `401 USER_NOT_FOUND` / «Пользователь с таким e-mail не найден», `401 INVALID_PASSWORD` / «Неверный пароль», `429 TOO_MANY_REQUESTS`, `500 INTERNAL_ERROR`.
Идемпотентность: нет.

### identity.auth.refresh

**Метод и путь:** `POST /api/v1/identity/auth/refresh`
**Назначение:** обновление access-токена по refresh-токену из cookie.
**Требования:** NF_Sec_3, NF_Sec_4, NF_Err_1
**Авторизация:** по refresh-cookie.

Body: пусто.

Успех: `200 OK`

```json
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 3600 }
```

Refresh-токен ротируется: выдаётся новый `Set-Cookie`; предыдущий становится недействительным.

Ошибки: `401 REFRESH_INVALID` (токен отсутствует, истёк или отозван).
Идемпотентность: нет (ротация токена).

### identity.password.request

**Метод и путь:** `POST /api/v1/identity/password/request`
**Назначение:** первый шаг восстановления пароля — отправка кода на e-mail.
**Требования:** F_Auth_16, F_Auth_12, F_Auth_14, F_Auth_15, NF_Sec_3
**Авторизация:** не требуется.

Body: `{ "email": "user@example.com" }`

Правила: код — 6 цифр, срок действия 10 минут (F_Auth_12); новый код можно запросить не чаще одного раза в 5 минут (F_Auth_14), предыдущий становится недействительным (F_Auth_15). Ответ не зависит от того, существует ли адрес (защита от перебора адресов).

Успех: `202 Accepted` → `{ "code_sent": true, "expires_in": 600 }`

Ошибки: `400 INVALID_EMAIL`, `429 TOO_MANY_REQUESTS` / «Новый код можно запросить не чаще одного раза в 5 минут».

### identity.password.verify

**Метод и путь:** `POST /api/v1/identity/password/verify`
**Назначение:** второй шаг — проверка кода; при успехе выдаётся одноразовый токен смены пароля.
**Требования:** F_Auth_13, F_Auth_15
**Авторизация:** не требуется.

Body: `{ "email": "user@example.com", "code": "123456" }`

Правила: не более 3 проверок в минуту (F_Auth_13); неверный или истёкший код → сообщение «Код восстановления некорректный», ввод можно продолжать (F_Auth_15).

Успех: `200 OK` → `{ "reset_token": "…", "expires_in": 600 }`

Ошибки: `422 INVALID_CODE`, `429 TOO_MANY_REQUESTS`.

### identity.password.reset

**Метод и путь:** `POST /api/v1/identity/password/reset`
**Назначение:** третий шаг — установка нового пароля.
**Требования:** F_Auth_17, F_Auth_7, NF_Sec_1, NF_Sec_4
**Авторизация:** по `reset_token`.

Body: `{ "reset_token": "…", "new_password": "NewSecret456" }`

Валидация: `new_password` удовлетворяет F_Auth_7 → иначе `400 WEAK_PASSWORD`. Токен одноразовый, срок 10 минут.

Успех: `204 No Content`; все активные сеансы отзываются (NF_Sec_4).

Ошибки: `400 WEAK_PASSWORD`, `401 RESET_TOKEN_INVALID`, `410 RESET_TOKEN_EXPIRED`.

### identity.users.me

**Метод и путь:** `GET /api/v1/identity/users/me`
**Назначение:** идентификатор, e-mail и состояние подтверждения адреса текущего пользователя.
**Требования:** NF_Sec_4
**Авторизация:** Bearer.

Успех: `200 OK`

```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "email_confirmed": true,
  "created_at": "2026-10-07T12:00:00Z"
}
```

Ошибки: `401 UNAUTHORIZED`.

## Домен profiles

### profiles.me.get

**Метод и путь:** `GET /api/v1/profiles/me`
**Требования:** F_Prof_7, F_Prof_8, NF_Sec_4
**Авторизация:** Bearer.

Успех: `200 OK`

```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "nickname": "nick",
  "email": "user@example.com",
  "email_visible_to_others": false,
  "avatar_id": "avatar_03",
  "registered_at": "2026-10-07T12:00:00Z",
  "stats": {
    "goals_created": 12,
    "results_published": 5,
    "average_self_score": 7.4
  }
}
```

`goals_created` — количество созданных целей, `results_published` — количество опубликованных постов (F_Prof_8). `average_self_score` — средняя самооценка по всем завершённым целям, округление до одного знака; если завершённых целей нет, отдаётся 0.0 (F_Prof_8). Счётчики обновляются при подведении итога, в той же транзакции общей базы.

Ошибки: `401 UNAUTHORIZED`, `500 INTERNAL_ERROR`.

### profiles.me.update

**Метод и путь:** `PATCH /api/v1/profiles/me`
**Требования:** F_Prof_1, F_Prof_2, F_Prof_4, F_Auth_3, F_Auth_8, F_Auth_14
**Авторизация:** Bearer.

Body (частично):

```json
{ "nickname": "new_nick", "avatar_id": "avatar_07", "email": "new@example.com" }
```

Валидация:
- `nickname`: 2–30 символов (UTF-8) → иначе `422 NICKNAME_INVALID`.
- `avatar_id`: из списка `GET /profiles/avatars` → иначе `422 AVATAR_INVALID`.
- `email`: RFC 5322 + уникальность среди неудалённых учётных записей (F_Auth_3, F_Auth_8) → `400 INVALID_EMAIL` / `409 EMAIL_TAKEN`. Новый адрес требует подтверждения кодом (F_Prof_4): до подтверждения сохраняется прежний адрес.

Согласованность: изменение профиля и признака версии — в одной транзакции; задание проекции обновляет отображаемые в ленте ник и аватар.

Успех: `200 OK` — обновлённый профиль.

Ошибки: `400 INVALID_EMAIL`, `409 EMAIL_TAKEN`, `422 NICKNAME_INVALID`, `422 AVATAR_INVALID`, `401 UNAUTHORIZED`, `429 TOO_MANY_REQUESTS`.
Идемпотентность: PATCH идемпотентен по значению полей.

### profiles.me.password.change

**Метод и путь:** `PUT /api/v1/profiles/me/password`
**Требования:** F_Prof_5, F_Auth_7, NF_Sec_1, NF_Sec_3
**Авторизация:** Bearer.

Body:

```json
{ "current_password": "Old12345", "new_password": "NewSecret456" }
```

Валидация:
- `current_password` должен соответствовать текущему хешу → иначе `401 INVALID_PASSWORD`.
- `new_password` удовлетворяет F_Auth_7 → иначе `400 WEAK_PASSWORD`.

Успех: `204 No Content`. Все прочие сеансы отзываются (NF_Sec_4).

Ошибки: `400 WEAK_PASSWORD`, `401 INVALID_PASSWORD`, `401 UNAUTHORIZED`.

### profiles.avatars.list

**Метод и путь:** `GET /api/v1/profiles/avatars`
**Требования:** F_Prof_2, F_Prof_3
**Авторизация:** Bearer.

Успех: `200 OK`

```json
{
  "items": [
    { "avatar_id": "avatar_01", "url": "/api/v1/profiles/avatars/avatar_01", "version": 3 },
    { "avatar_id": "avatar_02", "url": "/api/v1/profiles/avatars/avatar_02", "version": 3 }
  ]
}
```

Набор аватаров хранится в общей базе данных; отдельного файлового хранилища нет (F_Prof_2). Отдача — с кэшированием по `version`.

Ошибки: `503 AVATARS_UNAVAILABLE` / «Набор аватаров временно недоступен». Приложение продолжает работать, остальные операции не блокируются (F_Prof_3).

### profiles.me.delete

**Метод и путь:** `DELETE /api/v1/profiles/me`
**Требования:** F_Prof_6, NF_Data_1
**Авторизация:** Bearer.

Body: `{ "confirm": true }`

Правила: профиль, цели, итоги, публикации, реакции и напоминания удаляются одной транзакцией общей базы с каскадом по связям; строки производной ленты убирает задание очистки — его можно запускать повторно (NF_Data_1, не более 24 часов).

Успех: `202 Accepted`

```json
{ "deleted_at": "2026-10-07T12:05:00Z", "feed_cleanup_deadline": "2026-10-08T12:00:00Z" }
```

Ошибки: `400 CONFIRM_REQUIRED`, `401 UNAUTHORIZED`.

### profiles.get

**Метод и путь:** `GET /api/v1/profiles/{user_id}`
**Требования:** F_Prof_7, NF_Sec_2
**Авторизация:** Bearer.

Успех: `200 OK`

```json
{
  "user_id": "550e8400-...",
  "nickname": "nick",
  "avatar_id": "avatar_03",
  "registered_at": "2026-10-07T12:00:00Z",
  "stats": { "goals_created": 12, "results_published": 5 }
}
```

E-mail в ответе отсутствует: он виден только владельцу (F_Prof_7).

Ошибки: `404 PROFILE_NOT_FOUND`.

## Домен goals

### goals.create

**Метод и путь:** `POST /api/v1/goals`
**Требования:** F_Goal_1, F_Goal_2, F_Goal_3, F_Goal_4, F_Goal_14, F_Goal_15, F_Common_3, F_Common_4, F_Common_5, NF_Common_1
**Авторизация:** Bearer.

Body:

```json
{
  "title": "Научиться играть на гитаре",
  "goal_text": "Описание цели…",
  "path_text": "## План\n1. …",
  "publish": false,
  "planned_end_at": "2027-01-01T00:00:00Z",
  "expected_result": "первая песня целиком к 1 марта"
}
```

Валидация:
- `title`: 3–100 символов UTF-8 → иначе `422 TITLE_LENGTH`.
- `goal_text` («цель/план») и `path_text` («путь достижения»): 1–5000 UTF-8, `path_text` — Markdown → иначе `422 TEXT_TOO_LONG`.
- `planned_end_at`: обязательное, ISO 8601 в будущем (F_Goal_14).
- `expected_result`: обязательное, 1–500 UTF-8 — что пользователь рассчитывает получить (ожидаемый результат цели; отдельной сущности нет) (F_Goal_15).
- Обязательные поля (`title`, `goal_text`, `path_text`, `planned_end_at`, `expected_result`) → иначе `422 REQUIRED_FIELD`; форма блокируется, сообщение показывается рядом с полем (F_Common_3, F_Common_4).

Успех: `201 Created`

```json
{
  "goal_id": "…",
  "status": "draft",
  "created_at": "…",
  "planned_end_at": "…",
  "expected_result": "первая песня целиком к 1 марта"
}
```

`status = draft` при `publish: false`, иначе `active` (F_Goal_4).

Ошибки: `422 REQUIRED_FIELD`, `422 TITLE_LENGTH`, `422 TEXT_TOO_LONG`, `401 UNAUTHORIZED`.
Идемпотентность: по `Idempotency-Key`.

### goals.list («Мои цели»)

**Метод и путь:** `GET /api/v1/goals`
**Требования:** F_Goal_8, F_Goal_9, F_Goal_10, F_Common_2, NF_Perf_1

Query:

| Параметр | Тип | Описание |
|----------|-----|----------|
| `status` | enum | `draft`, `active`, `completed` (можно повторять) |
| `sort` | enum | `created_desc` (default), `created_asc` |
| `limit` | int | 1–100, default 20 |
| `cursor` | string | cursor пагинации |

Успех: `200 OK`

```json
{
  "items": [
    {
      "goal_id": "…",
      "title": "…",
      "status": "active",
      "created_at": "…",
      "planned_end_at": "…",
      "average_self_score": 7.4
    }
  ],
  "next_cursor": "opaque",
  "empty": false
}
```

При отсутствии целей отдаётся пустой список (`empty: true`) — клиент показывает заглушку «нет целей» (F_Common_2).

NFR: p95 времени отклика списка не более 500 мс при 100 записях (NF_Perf_1).

### goals.get

**Метод и путь:** `GET /api/v1/goals/{goal_id}`
**Требования:** F_Goal_3, F_Ref_4, F_Ref_5, NF_Sec_2

Успех: `200 OK`

```json
{
  "goal_id": "…",
  "title": "…",
  "goal_text": "…",
  "path_text": "…",
  "status": "completed",
  "created_at": "…",
  "planned_end_at": "…",
  "expected_result": "12 км без остановки к 20 октября",
  "reflection": {
    "result": "partially",
    "fact_text": "Что фактически произошло…",
    "self_score": 7,
    "reflected_at": "2026-11-01T10:00:00Z",
    "publication_id": "…"
  }
}
```

`reflection` присутствует только при `status: "completed"` (одна цель — один итог, F_Ref_5). Ожидаемый результат — обычное поле цели: пользователь заполняет его при создании и правит в черновике.

### goals.update

**Метод и путь:** `PATCH /api/v1/goals/{goal_id}`
**Требования:** F_Goal_6, F_Goal_13, NF_Sec_2

Body (частично):

```json
{ "title": "…", "goal_text": "…", "path_text": "…", "planned_end_at": "…" }
```

Правила:
- `status=draft` — менять можно все поля тела; автосохранение черновика выполняется этим же эндпоинтом (F_Goal_13).
- `status=active` — менять `goal_text` («цель/план») запрещено (F_Goal_6) → `409 GOAL_LOCKED`; остальные поля (`title`, `path_text`, `planned_end_at`, `expected_result`) менять можно.
- Конфликт одновременных правок решается признаком версии: устаревшая правка → `409 VERSION_CONFLICT`.
- Статус не меняется через этот эндпоинт — для этого `goals.status.update`.

### goals.status.update

**Метод и путь:** `PATCH /api/v1/goals/{goal_id}/status`
**Назначение:** единственная точка смены статуса цели. Запуск (`draft → active`) и завершение (`active → completed`) выполняются одним запросом; итог по цели передаётся здесь же.
**Требования:** F_Goal_4, F_Goal_5, F_Goal_6, F_Goal_11, F_Goal_12, F_Ref_1–F_Ref_5, F_Feed_4, NF_Data_1
**Авторизация:** Bearer (автор).

Body:

```json
{
  "status": "completed",
  "reflection": {
    "result": "came_true | partially | failed",
    "fact_text": "Что фактически произошло…",
    "self_score": 8,
    "publish_to_feed": true
  }
}
```

Поля:

| Поле | Обязательность | Правила |
|------|----------------|---------|
| `status` | да | `active` или `completed` |
| `reflection` | только для `status=completed` | обязателен при завершении (F_Goal_12); один итог на цель; повторно задать нельзя |
| `reflection.result` | да (при completed) | enum (F_Ref_1) |
| `reflection.fact_text` | да при `result ∈ {partially, failed}` (F_Ref_2) | до 3000 UTF-8 |
| `reflection.self_score` | да | целое 1..10 (F_Ref_3) |
| `reflection.publish_to_feed` | нет, default `false` | при `true` создаётся публикация в feed (F_Feed_4) |

Правила переходов:
- `draft → active` — допустимо в любой момент (F_Goal_4).
- `active → completed` — допустимо досрочно и после `planned_end_at` (F_Goal_5).
- Любые другие переходы → `409 INVALID_STATUS_TRANSITION`.
- Отредактировать/перезаписать `reflection` после завершения нельзя (`409 REFLECTION_LOCKED`).

Согласованность: смена статуса, сохранение итога, публикация и увеличение признака версии выполняются одной транзакцией общей базы — состояние «итог сохранён, а публикация не создана» невозможно (NF_Data_1). Перенос в представления ленты делает задание проекции; пользователь его не ждёт.

Успех: `200 OK`

```json
{
  "goal_id": "…",
  "status": "completed",
  "updated_at": "…",
  "reflection": {
    "result": "partially",
    "fact_text": "…",
    "self_score": 7,
    "reflected_at": "…",
    "publication_id": "…"
  }
}
```

`publication_id` присутствует, если `publish_to_feed: true`.

Ошибки: `400 INVALID_STATUS`, `403 FORBIDDEN`, `404 NOT_FOUND`, `409 INVALID_STATUS_TRANSITION`, `409 REFLECTION_LOCKED`, `422 FACT_REQUIRED`, `422 INVALID_SCORE`, `422 TEXT_TOO_LONG`.
Идемпотентность: по `Idempotency-Key`; повтор с тем же ключом не создаёт вторую публикацию.

### goals.delete

**Метод и путь:** `DELETE /api/v1/goals/{goal_id}`
**Требования:** F_Goal_7, F_Feed_10, NF_Data_1
**Авторизация:** Bearer (автор).

Body: `{ "confirm": true }`

Правила: цель, её итог, публикация и связанные реакции удаляются одной транзакцией общей базы (каскад, F_Feed_10); строки производной ленты снимает задание проекции.

Успех: `204 No Content`

Ошибки: `400 CONFIRM_REQUIRED`, `403 FORBIDDEN`, `404 NOT_FOUND`.
Идемпотентность: по `Idempotency-Key`; повторное удаление → 404.

## Домен feed

### feed.list

**Метод и путь:** `GET /api/v1/feed`
**Требования:** F_Feed_1, F_Feed_2, F_Feed_3, F_Feed_4, F_Feed_5, F_Feed_9, NF_Perf_2
**Авторизация:** не требуется (лента доступна анонимно, F_Feed_1).

Query:

| Параметр | Тип | Описание |
|----------|-----|----------|
| `sort` | enum | `popular` (default, F_Feed_2), `newest`, `oldest` |
| `result` | enum | `came_true`, `partially`, `failed` (F_Feed_9), опционально |
| `limit` | int | 1–100, default 20 |
| `cursor` | string | пагинация |

Успех: `200 OK`

```json
{
  "items": [
    {
      "publication_id": "…",
      "author": { "user_id": "…", "nickname": "…", "avatar_id": "…" },
      "published_at": "…",
      "title": "Пробежать полумарафон за 3 месяца",
      "text": "…",
      "expected_result": "12 км без остановки к 20 октября",
      "result": "partially",
      "self_score": 7,
      "reactions": { "support": 12, "energy": 4 },
      "reactions_total": 16,
      "my_reaction": "support"
    }
  ],
  "next_cursor": "opaque"
}
```

Правила:
- Популярность — суммарное число реакций (F_Feed_2).
- Сортировки «новые» и «старые» читаются из одной таблицы порядка в двух направлениях; «популярные» — из заранее посчитанного окна.
- Фильтр по типу итога («Сбылось» / «Частично сбылось» / «Не сбылось») применяется к уже полученной странице (F_Feed_9).
- `expected_result` — ожидаемый результат цели, показанный в публикации (F_Feed_4).
- В карточке отдаются счётчики реакций по типам и отметка реакции читателя (`my_reaction`); реакции и отметка хранятся в общей базе, а не в производном представлении.

Ошибки: `400 INVALID_SORT`, `400 INVALID_RESULT`.

Согласованность: лента — производное представление; строки обновляет задание проекции по признаку версии записи, допускается отложенная согласованность; о появлении строк клиент узнаёт по каналу подписки или при следующем запросе. Истина — в общей базе данных (`goals`, `feed.publications`).

NFR: p95 не более 800 мс при 500 публикациях (NF_Perf_2); цель архитектуры — не более 150 мс.

### feed.publications.create

**Метод и путь:** `POST /api/v1/feed/publications`
**Требования:** F_Feed_4, F_Feed_10, NF_Data_1
**Авторизация:** Bearer.

Body: `{ "goal_id": "…" }`

Правила:
- Публикация создаётся только для цели в статусе `completed` с сохранённым итогом → иначе `409 REFLECTION_REQUIRED`.
- Для `draft`/`active` — `409 DRAFT_NOT_PUBLISHABLE` (F_Feed_10).
- Повторная публикация той же цели — `409 ALREADY_PUBLISHED` (для повторного показа — `feed.publications.visibility.update`).
- Обычно публикация создаётся тем же запросом, что и завершение цели (`goals.status.update` с `publish_to_feed: true`), одной транзакцией общей базы; отдельный вызов нужен, если публикация не была запрошена при завершении.

Успех: `201 Created`

```json
{ "publication_id": "…", "goal_id": "…", "status": "visible", "expected_result": "12 км без остановки к 20 октября" }
```

Ошибки: `403 FORBIDDEN`, `404 NOT_FOUND`, `409 REFLECTION_REQUIRED`, `409 DRAFT_NOT_PUBLISHABLE`, `409 ALREADY_PUBLISHED`.

### feed.publications.visibility.update

**Метод и путь:** `PATCH /api/v1/feed/publications/{publication_id}`
**Требования:** F_Feed_8
**Авторизация:** Bearer (автор).

Body:

```json
{ "visibility": "hidden" }
```

или `{ "visibility": "visible" }` для повторной публикации.

Правила:
- Скрытие цели не удаляет связанные реакции (F_Feed_8).
- Скрытая публикация не отображается в ленте; повторная публикация возвращает её (F_Feed_8).
- Изменение видимости и признака версии — в одной транзакции; строки ленты обновляет задание проекции.

Успех: `200 OK`.

Ошибки: `403 FORBIDDEN`, `404 NOT_FOUND`.

### feed.publications.delete

**Метод и путь:** `DELETE /api/v1/feed/publications/{publication_id}`
**Требования:** F_Feed_10
**Авторизация:** Bearer (автор).

Успех: `204 No Content`. Связанные реакции удаляются каскадно, одной транзакцией.

Ошибки: `403 FORBIDDEN`, `404 NOT_FOUND`.

### feed.reactions.set

**Метод и путь:** `PUT /api/v1/feed/publications/{publication_id}/reactions`
**Требования:** F_Feed_6, F_Feed_7, NF_Sec_2, NF_Sec_4
**Авторизация:** Bearer.

Body: `{ "reaction": "support | energy" }`

Правила:
- Один пользователь — не более одной реакции на публикацию (F_Feed_6). Повторный вызов заменяет реакцию: выбор другого типа меняет её, вызов с текущим типом — снимает (F_Feed_7).
- Реакцию на свою публикацию ставить нельзя → `403 CANNOT_REACT_TO_OWN`.
- Реакция и счётчики по типам меняются в одной транзакции общей базы; задание проекции обновляет карточку и пересчитывает окно популярности по сумме реакций.

Успех: `200 OK`

```json
{ "publication_id": "…", "my_reaction": "support", "reactions": { "support": 13, "energy": 4 }, "reactions_total": 17 }
```

Ошибки: `400 INVALID_REACTION`, `401 UNAUTHORIZED`, `403 CANNOT_REACT_TO_OWN`, `404 NOT_FOUND`.

### feed.reactions.delete

**Метод и путь:** `DELETE /api/v1/feed/publications/{publication_id}/reactions`
**Требования:** F_Feed_7
**Авторизация:** Bearer.

Успех: `204 No Content`.

Ошибки: `401 UNAUTHORIZED`, `404 NOT_FOUND`.

### feed.stream

**Метод и путь:** `GET /api/v1/feed/stream`
**Назначение:** необязательный канал подписки на изменения ленты (Server-Sent Events).
**Авторизация:** не требуется.

Ответ: `200 OK`, `Content-Type: text/event-stream`; сообщения вида `{"event": "changed", "scope": "feed"}` — публикация появилась в ленте, изменилась видимость публикации, завершился сеанс.

Правила: поток не является источником истины: он сообщает только признак изменения, содержимое клиент запрашивает обычным запросом (`feed.list`). При разрыве соединения клиент переходит на периодическое обновление раз в 30 секунд. Соединение живёт не дольше сеанса; при истечении access-токена клиент переподключается после `identity.auth.refresh`.

Ошибки: `401 UNAUTHORIZED` (при закрытом доступе), `503 STREAM_UNAVAILABLE` — клиент работает по периодическому обновлению.

## Домен notifications

### notifications.settings.get

**Метод и путь:** `GET /api/v1/notifications/settings`
**Требования:** F_Rem_1, F_Rem_4
**Авторизация:** Bearer.

Успех: `200 OK`

```json
{
  "email_duplicate_enabled": false,
  "default_remind_after_days": 1
}
```

`email_duplicate_enabled` — глобальное дублирование напоминаний письмом, по умолчанию выключено (F_Rem_4). `default_remind_after_days` — через сколько дней после создания цели отправляется напоминание по умолчанию; положительное целое 1..365 (F_Rem_1). По умолчанию напоминания включены (F_Rem_2).

### notifications.settings.update

**Метод и путь:** `PATCH /api/v1/notifications/settings`
**Требования:** F_Rem_1, F_Rem_4
**Авторизация:** Bearer.

Body:

```json
{ "email_duplicate_enabled": true, "default_remind_after_days": 3 }
```

Валидация: `default_remind_after_days` — целое 1..365 → иначе `422 INVALID_REMIND_AFTER_DAYS`.

Успех: `200 OK` — обновлённые настройки.

### notifications.activity.set

**Метод и путь:** `PUT /api/v1/notifications/activity`
**Назначение:** включение/выключение напоминаний по конкретной цели и настройка срока напоминания.
**Требования:** F_Rem_1, F_Rem_2
**Авторизация:** Bearer.

Body:

```json
{ "goal_id": "…", "enabled": false, "remind_after_days": 3 }
```

Поля:

| Поле | Обязательность | Правила |
|------|----------------|---------|
| `goal_id` | да | UUID; автор — текущий пользователь |
| `enabled` | да | `true` — включить, `false` — отключить; по умолчанию напоминания включены (F_Rem_2) |
| `remind_after_days` | нет | переопределение `default_remind_after_days` для конкретной цели — через сколько дней после создания цели напоминать (F_Rem_1); положительное целое 1..365 |

Правила:
- Отключение по конкретной цели (`enabled=false`) действует и на уведомление в веб-приложении, и на e-mail; остальные цели продолжают работать (F_Rem_2).
- `remind_after_days` — положительное целое (≥1).

Успех: `200 OK`

```json
{ "goal_id": "…", "enabled": false, "remind_after_days": 3 }
```

Ошибки: `403 FORBIDDEN` (цель принадлежит другому пользователю), `404 NOT_FOUND`, `422 INVALID_REMIND_AFTER_DAYS`, `422 GOAL_ID_REQUIRED`.

### notifications.list

**Метод и путь:** `GET /api/v1/notifications`
**Требования:** F_Rem_3, F_Rem_5, F_Rem_6, F_Rem_7, F_Rem_8, F_Rem_9, NF_Rel_1
**Авторизация:** Bearer.

Query: `?limit=…&cursor=…`

Успех: `200 OK` — список внутренних напоминаний с `notification_id`, `goal_id`, `scheduled_at`, `delivered_at`, `email_status` (`sent` | `failed` | `not_requested`).

Правила:
- Напоминание срабатывает только для целей в статусе «Активен»; для «Черновик» и «Завершён» — не срабатывает (F_Rem_3).
- Если к моменту наступления установленного дня цель ещё не переведена в «Активен», напоминание срабатывает в момент перевода в «Активен» (F_Rem_7).
- Если цель завершена раньше наступления установленного дня, напоминание по ней не срабатывает (F_Rem_8).
- Напоминание срабатывает однократно — и как уведомление в веб-приложении, и как письмо (F_Rem_9).
- При включённом глобальном дублировании (F_Rem_4) и не отключённых напоминаниях по цели на e-mail отправляется письмо с заголовком цели и ссылкой на цель (F_Rem_5).
- Если отправка письма завершилась ошибкой, в интерфейсе отображается уведомление «Не удалось отправить письмо»; уведомление в веб-приложении сохраняется, повторная отправка письма не выполняется (F_Rem_6).
- При недоступности сервиса отправки писем внутренние напоминания продолжают работать (NF_Rel_1).

## Домен audit

### audit.events.list

**Метод и путь:** `GET /api/v1/audit/events`
**Требования:** NF_Obs_1
**Авторизация:** внутренний сервисный токен (`X-Service-Token`); снаружи недоступен.

Query: `?user_id=…&entity_type=goal|post|profile|session&from=…&to=…&limit=…&cursor=…`

Успех: `200 OK`

```json
{
  "items": [
    {
      "event_id": "…",
      "entity_type": "goal",
      "entity_id": "…",
      "operation": "create | update | delete",
      "user_id": "…",
      "occurred_at": "2026-10-07T12:00:00Z",
      "payload": { }
    }
  ],
  "next_cursor": "opaque"
}
```

Согласованность: только добавление; записи неизменяемы. Идемпотентность: не применимо (read-only).
