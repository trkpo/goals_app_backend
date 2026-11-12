import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./schema";

export type { components, paths };
export type Schemas = components["schemas"];
export type ApiError = Schemas["Error"];
export type ErrorCode = Schemas["ErrorCode"];

export interface ApiClientOptions {
  /** Адрес backend, например `https://stage.goals.example.com`. Для моков — `http://localhost:4010`. */
  baseUrl: string;
  /** Текущий access-токен; `null`, если пользователь не вошёл. */
  getAccessToken?: () => string | null;
  /**
   * Вызывается при 401 на защищённом запросе. Должен обновить токен через
   * `POST /api/v1/identity/auth/refresh` и вернуть `true`, если это удалось.
   * Параллельные запросы, получившие 401, ждут один общий вызов.
   */
  onUnauthorized?: () => Promise<boolean>;
}

const REFRESH_PATH = "/api/v1/identity/auth/refresh";

/** Копия запроса до отправки: после `fetch` тело оригинала уже прочитано и повторить его нельзя. */
interface Pending {
  copy: Request;
  token: string | null;
}

export function createApiClient(options: ApiClientOptions) {
  // Refresh-токен лежит в HttpOnly-cookie, поэтому запросы идут с credentials.
  const client = createClient<paths>({ baseUrl: options.baseUrl, credentials: "include" });

  const pending = new WeakMap<Request, Pending>();
  let refreshing: Promise<boolean> | null = null;

  const refresh = (): Promise<boolean> => {
    refreshing ??= options.onUnauthorized!().finally(() => {
      refreshing = null;
    });
    return refreshing;
  };

  const auth: Middleware = {
    onRequest({ request }) {
      const token = options.getAccessToken?.() ?? null;
      if (token && !request.headers.has("Authorization")) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      if (options.onUnauthorized && new URL(request.url).pathname !== REFRESH_PATH) {
        pending.set(request, { copy: request.clone(), token });
      }
      return request;
    },
    async onResponse({ request, response }) {
      const sent = pending.get(request);
      pending.delete(request);
      if (response.status !== 401 || !sent || !options.onUnauthorized) {
        return response;
      }
      // Если токен уже обновил соседний запрос, повторяем с новым без лишнего refresh.
      const current = options.getAccessToken?.() ?? null;
      if (current === sent.token && !(await refresh())) {
        return response;
      }
      const token = options.getAccessToken?.();
      if (token) {
        sent.copy.headers.set("Authorization", `Bearer ${token}`);
      }
      // Повтор идёт мимо middleware, поэтому второй раз на 401 не зациклится.
      return fetch(sent.copy);
    },
  };

  client.use(auth);
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;

/** Сообщение об ошибке для конкретного поля формы (F_Common_4). */
export function fieldError(error: ApiError | undefined, field: string): string | undefined {
  return error?.field === field ? error.message : undefined;
}
