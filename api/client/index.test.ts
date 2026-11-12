import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, it } from "node:test";
import { createApiClient } from "./index.ts";

interface Seen {
  method: string;
  url: string;
  auth: string | undefined;
  body: string;
}

const goal = {
  title: "Научиться играть на гитаре",
  goal_text: "Играть для друзей",
  path_text: "## План",
  planned_end_at: "2030-01-01T00:00:00Z",
  expected_result: "первая песня",
};

describe("createApiClient: повтор после 401", () => {
  let server: Server;
  let baseUrl: string;
  let seen: Seen[];

  const readBody = async (req: IncomingMessage) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    return Buffer.concat(chunks).toString("utf8");
  };

  beforeEach(async () => {
    seen = [];
    server = createServer(async (req, res) => {
      const body = await readBody(req);
      seen.push({ method: req.method!, url: req.url!, auth: req.headers.authorization, body });
      const ok = req.headers.authorization === "Bearer new";
      res.setHeader("Content-Type", "application/json");
      if (!ok) {
        res.statusCode = 401;
        res.end(JSON.stringify({ code: "UNAUTHORIZED", message: "Сеанс завершён", field: null, request_id: "r1" }));
        return;
      }
      res.statusCode = 201;
      res.end(JSON.stringify({ goal_id: "7d0f3c1e-8a3b-4b0e-9a54-2f4b1d5c6e7f", status: "draft", created_at: "2026-10-07T12:00:00Z", planned_end_at: goal.planned_end_at, expected_result: goal.expected_result }));
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterEach(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it("повторяет POST с телом с новым токеном", async () => {
    let token = "old";
    const api = createApiClient({
      baseUrl,
      getAccessToken: () => token,
      onUnauthorized: async () => {
        token = "new";
        return true;
      },
    });

    const { data, error } = await api.POST("/api/v1/goals", { body: goal });

    assert.equal(error, undefined);
    assert.equal(data?.status, "draft");
    assert.equal(seen.length, 2);
    assert.equal(seen[0]?.auth, "Bearer old");
    assert.equal(seen[1]?.auth, "Bearer new");
    assert.deepEqual(JSON.parse(seen[1]!.body), goal);
  });

  it("обновляет токен один раз для параллельных запросов", async () => {
    let token = "old";
    let refreshes = 0;
    const api = createApiClient({
      baseUrl,
      getAccessToken: () => token,
      onUnauthorized: async () => {
        refreshes++;
        await new Promise((resolve) => setTimeout(resolve, 50));
        token = "new";
        return true;
      },
    });

    const results = await Promise.all([
      api.POST("/api/v1/goals", { body: goal }),
      api.POST("/api/v1/goals", { body: goal }),
      api.POST("/api/v1/goals", { body: goal }),
    ]);

    assert.equal(refreshes, 1);
    for (const { data, error } of results) {
      assert.equal(error, undefined);
      assert.equal(data?.status, "draft");
    }
  });

  it("не повторяет запрос, если обновить токен не удалось", async () => {
    const api = createApiClient({
      baseUrl,
      getAccessToken: () => "old",
      onUnauthorized: async () => false,
    });

    const { error, response } = await api.POST("/api/v1/goals", { body: goal });

    assert.equal(response.status, 401);
    assert.equal(error?.code, "UNAUTHORIZED");
    assert.equal(seen.length, 1);
  });

  it("не зацикливается, если и после обновления приходит 401", async () => {
    let refreshes = 0;
    const api = createApiClient({
      baseUrl,
      getAccessToken: () => "still-bad",
      onUnauthorized: async () => {
        refreshes++;
        return true;
      },
    });

    const { response } = await api.POST("/api/v1/goals", { body: goal });

    assert.equal(response.status, 401);
    assert.equal(refreshes, 1);
    assert.equal(seen.length, 2);
  });
});
