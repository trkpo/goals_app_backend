# API-контракт

- `openapi.yaml` — спецификация OpenAPI 3.1 всех эндпоинтов `v1` из [`docs/API.md`](../docs/API.md).
- `client/` — типизированный клиент для frontend на [openapi-fetch](https://openapi-ts.dev/openapi-fetch/). Файл `client/schema.d.ts` генерируется из спецификации.

## Команды

Нужен Node.js 22.

```bash
cd api
npm ci
npm run lint       # валидация спецификации (Redocly)
npm run generate   # пересобрать client/schema.d.ts
npm run check      # всё сразу: lint, generate, typecheck
npm run mock       # mock-сервер на http://localhost:4010
```

После правки `openapi.yaml` запустите `npm run generate` и закоммитьте `schema.d.ts`: CI проверяет, что он не устарел.

## Моки

Mock-сервер [Prism](https://docs.stoplight.io/docs/prism) отвечает по спецификации: данные берутся из примеров, запросы проверяются по схемам. Им можно пользоваться для экранов, которые ещё не подключены к backend.

- Запрос без `Authorization` к защищённому эндпоинту получает `401`.
- Невалидное тело запроса получает `422`.
- Нужный ответ можно запросить заголовком `Prefer: code=409`, случайные данные вместо примеров — `Prefer: dynamic=true`.

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

- Конверт ошибки `{ code, message, field, request_id }`, `code` — из перечня `ErrorCode`.
- Версия записи передаётся через `ETag` / `If-Match`, устаревшая правка — `409 VERSION_CONFLICT`.
- `confirm` для `DELETE` передаётся в теле, как в `API.md`.
- Фильтр `result` в ленте применяется к уже полученной странице, поэтому страница может быть короче `limit`.
- Эндпоинт изображения аватара `GET /profiles/avatars/{avatar_id}` описан по ссылке `url` из списка аватаров.
