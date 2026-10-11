import { describe, expect, test } from "vitest";
import { renderNotice } from "./videoRender";

describe("renderNotice", () => {
  test("nothing to say before the first render or after a successful one", () => {
    expect(renderNotice(null)).toBeNull();
    expect(
      renderNotice({ status: "SUCCEEDED", runId: 7, lastError: null, finishedAt: null }),
    ).toBeNull();
  });

  test("a queued or running render is in progress", () => {
    expect(renderNotice({ status: "QUEUED", runId: null, lastError: null })).toEqual({
      kind: "running",
    });
    expect(renderNotice({ status: "RUNNING", runId: null, lastError: null })).toEqual({
      kind: "running",
    });
  });

  test("a failed render carries its leading error code", () => {
    expect(
      renderNotice({
        status: "FAILED",
        runId: null,
        lastError: "VIDEO_SOURCE_TOO_SHORT: V1 is 6 s",
      }),
    ).toEqual({ kind: "failed", code: "VIDEO_SOURCE_TOO_SHORT" });
    expect(renderNotice({ status: "FAILED", runId: null, lastError: null })).toEqual({
      kind: "failed",
      code: "INTERNAL_ERROR",
    });
  });
});
