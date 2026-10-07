export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId?: string;
  readonly details?: Record<string, unknown>;

  constructor(
    status: number,
    code: string,
    message: string,
    traceId?: string,
    details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.details = details;
  }
}

/** RequestInit, but the body may also be a plain object (auto JSON-encoded). */
export type ApiFetchInit = Omit<RequestInit, "body"> & {
  body?: BodyInit | Record<string, unknown> | null;
};

const BASE_URL = () => process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

export async function apiFetch<T>(path: string, init?: ApiFetchInit): Promise<T> {
  const headers = new Headers(init?.headers);
  let body = init?.body;
  if (body !== null && body !== undefined && typeof body === "object" && !(body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(body);
  }

  const response = await fetch(BASE_URL() + path, { ...init, headers, body, credentials: "include" });

  if (response.status === 204) {
    return undefined as T;
  }

  if (!response.ok) {
    let code = "INTERNAL_ERROR";
    let message = response.statusText || "Request failed";
    let traceId: string | undefined;
    let details: Record<string, unknown> | undefined;
    try {
      const parsed = (await response.json()) as Record<string, unknown>;
      if (typeof parsed.code === "string") code = parsed.code;
      if (typeof parsed.message === "string") message = parsed.message;
      if (typeof parsed.traceId === "string") traceId = parsed.traceId;
      if (parsed.details && typeof parsed.details === "object") {
        details = parsed.details as Record<string, unknown>;
      }
    } catch {
      // non-JSON error body, keep the fallbacks
    }
    if (response.status === 401 && typeof window !== "undefined" && window.location.pathname !== "/login") {
      // Hard navigation on purpose: this module lives outside React and a 401
      // must drop every piece of client state.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      window.location.assign("/login");
    }
    throw new ApiError(response.status, code, message, traceId, details);
  }

  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
