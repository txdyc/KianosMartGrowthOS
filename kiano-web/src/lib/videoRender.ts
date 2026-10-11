import type { VideoRenderStatus } from "./types";

/** What the videos panel says about the latest background render. */
export type RenderNotice = { kind: "running" } | { kind: "failed"; code: string };

/**
 * The planning of a video render runs in a background task (VIDEO_RENDER):
 * while it is queued or running the panel says so, and a failed task shows its
 * leading error code (e.g. VIDEO_SOURCE_TOO_SHORT) instead of failing silently.
 */
export function renderNotice(status: VideoRenderStatus | null): RenderNotice | null {
  if (status === null || status.status === "SUCCEEDED") {
    return null;
  }
  if (status.status === "QUEUED" || status.status === "RUNNING") {
    return { kind: "running" };
  }
  const code = (status.lastError ?? "").split(":")[0].trim() || "INTERNAL_ERROR";
  return { kind: "failed", code };
}
