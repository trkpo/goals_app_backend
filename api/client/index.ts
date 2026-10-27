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
   */
  onUnauthorized?: () => Promise<boolean>;
}

const REFRESH_PATH = "/api/v1/identity/auth/refresh";

export function createApiClient(options: ApiClientOptions) {
  // Refresh-токен лежит в HttpOnly-cookie, поэтому запросы идут с credentials.
  const client = createClient<paths>({ baseUrl: options.baseUrl, credentials: "include" });

  const auth: Middleware = {
    onRequest({ request }) {
      const token = options.getAccessToken?.();
      if (token && !request.headers.has("Authorization")) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      return request;
    },
    async onResponse({ request, response }) {
      if (
        response.status !== 401 ||
        !options.onUnauthorized ||
        new URL(request.url).pathname === REFRESH_PATH ||
        request.headers.has("X-Retried")
      ) {
        return response;
      }
      if (!(await options.onUnauthorized())) {
        return response;
      }
      const retry = new Request(request);
      retry.headers.set("X-Retried", "1");
      const token = options.getAccessToken?.();
      if (token) {
        retry.headers.set("Authorization", `Bearer ${token}`);
      }
      return fetch(retry);
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
