import { afterEach, expect, test, vi } from "vitest";
import { ApiError, apiFetch } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.NEXT_PUBLIC_API_BASE_URL;
});

test("apiFetch sends credentials and JSON body", async () => {
  process.env.NEXT_PUBLIC_API_BASE_URL = "http://api.test";
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);

  await apiFetch("/api/v1/content/products", { method: "POST", body: { tier: "HERO" } });

  expect(fetchMock).toHaveBeenCalledTimes(1);
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  expect(url).toBe("http://api.test/api/v1/content/products");
  expect(init.credentials).toBe("include");
  expect(init.body).toBe('{"tier":"HERO"}');
  expect(new Headers(init.headers).get("content-type")).toBe("application/json");
});

test("apiFetch passes FormData through untouched", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);
  const form = new FormData();

  await apiFetch("/api/v1/content/source-media", { method: "POST", body: form });

  const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  expect(init.body).toBe(form);
  expect(new Headers(init.headers).get("content-type")).toBeNull();
});

test("apiFetch throws ApiError with code/traceId from error body", async () => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        code: "INVALID_FILE_NAME",
        message: "bad name",
        traceId: "tr-123",
        details: { fileName: "x.jpg" },
      }),
      { status: 422, headers: { "content-type": "application/json" } },
    ),
  );
  vi.stubGlobal("fetch", fetchMock);

  const error = (await apiFetch("/api/v1/content/source-media").catch((err) => err)) as ApiError;

  expect(error).toBeInstanceOf(ApiError);
  expect(error.status).toBe(422);
  expect(error.code).toBe("INVALID_FILE_NAME");
  expect(error.message).toBe("bad name");
  expect(error.traceId).toBe("tr-123");
  expect(error.details).toEqual({ fileName: "x.jpg" });
});

test("apiFetch falls back to status text on non-JSON errors", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response("gateway boom", { status: 502 }));
  vi.stubGlobal("fetch", fetchMock);

  const error = (await apiFetch("/api/v1/x").catch((err) => err)) as ApiError;

  expect(error).toBeInstanceOf(ApiError);
  expect(error.status).toBe(502);
  expect(error.code).toBe("INTERNAL_ERROR");
});

test("apiFetch returns undefined on 204", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);

  await expect(apiFetch("/api/v1/auth/logout", { method: "POST" })).resolves.toBeUndefined();
});

test("apiFetch parses a JSON body", async () => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ sku: "MG-BL200", ok: 3 }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetchMock);

  await expect(apiFetch("/api/v1/content/products")).resolves.toEqual({ sku: "MG-BL200", ok: 3 });
});
