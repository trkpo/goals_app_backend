# API-контракт

- `openapi.yaml` — спецификация OpenAPI 3.1 всех эндпоинтов `v1` из [`docs/API.md`](../docs/API.md).
- `client/` — типизированный клиент для frontend на [openapi-fetch](https://openapi-ts.dev/openapi-fetch/). Файл `client/schema.d.ts` генерируется из спецификации.

## Команды

Нужен Node.js 22.18 или новее.

```bash
cd api
npm ci
npm run lint       # валидация спецификации (Redocly)
npm run generate   # пересобрать client/schema.d.ts
npm test           # тесты клиента
npm run check      # всё сразу: lint, generate, typecheck, test
npm run mock       # mock-сервер на http://localhost:4010
```

После правки `openapi.yaml` запустите `npm run generate` и закоммитьте `schema.d.ts`: CI проверяет, что он не устарел.

## Моки

Mock-сервер [Prism](https://docs.stoplight.io/docs/prism) отвечает по спецификации: данные берутся из примеров, запросы проверяются по схемам. Им можно пользоваться для экранов, которые ещё не подключены к backend.

- Запрос без `Authorization` к защищённому эндпоинту получает `401`.
- Невалидное тело запроса получает `422`.
- Нужный ответ можно запросить заголовком `Prefer: code=409`. Если у ошибки несколько примеров, выбрать конкретный можно так: `Prefer: code=409, example=VersionConflict`. Имена примеров лежат в `components.examples`; без `example` отдаётся первый из перечисленных у операции.
- Случайные данные вместо примеров — `Prefer: dynamic=true`.

Без Node.js сервер запускается так:

```bash
docker run --rm -p 4010:4010 -v "$PWD/api:/api" stoplight/prism:5 mock -h 0.0.0.0 /api/openapi.yaml
```

## Подключение во frontend

```ts
import { createApiClient, fieldError } from "@trkpo/goals-api";

const api = createApiClient({
  baseUrl: import.meta.env.VITE_API_URL, // http://localhost:4010 для моков
  getAccessToken: () => auth.accessToken,
  onUnauthorized: () => auth.refresh(), // POST /identity/auth/refresh, true при успехе
});

const { data, error } = await api.POST("/api/v1/goals", { body: form });
const titleError = fieldError(error, "title");
```

Пока frontend-проект пустой, пакет можно подключить копией каталога `api/` или зависимостью из git после того, как контракт уйдёт в отдельный пакет.

## Решения, которые ждут задачу #1

В `docs/API.md` их нет, в спецификации приняты временно:

- Для `text` в публикации ленты разрешён `null`: у итога «Сбылось» текст необязателен (F_Ref_2), как и `Reflection.fact_text`.
- Ошибки некорректных `limit` и `cursor` в `API.md` не описаны, поэтому у `GET /goals` и `GET /audit/events` нет ответа `400`.

- Конверт ошибки `{ code, message, field, request_id }`, `code` — из перечня `ErrorCode`.
- Версия записи передаётся через `ETag` / `If-Match`, устаревшая правка — `409 VERSION_CONFLICT`.
- `confirm` для `DELETE` передаётся в теле, как в `API.md`.
- Фильтр `result` в ленте применяется к уже полученной странице, поэтому страница может быть короче `limit`.
- Эндпоинт изображения аватара `GET /profiles/avatars/{avatar_id}` описан по ссылке `url` из списка аватаров.

## Пробелы в `docs/API.md`

Из ревью, в контракт не добавлены, потому что нужны решения по самому `API.md`:

- Нет выхода (`logout`, F_Auth_10 и F_Auth_11). Refresh-cookie `HttpOnly`, клиент удалить её не может, поэтому после перезагрузки сеанс восстановится.
- Нет подтверждения нового e-mail при смене адреса (F_Prof_4): `identity.email.confirm` относится к учётной записи. Не описан и ответ `PATCH /profiles/me` при смене адреса.
- Не описан CORS для локальной разработки (SPA на `:5173`, API на `:8080`), понадобится в задаче #6.
