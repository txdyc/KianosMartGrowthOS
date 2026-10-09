import { expect, test } from "vitest";
import {
  applyTrims,
  aiRatio,
  limitsFor,
  totalSeconds,
  violations,
  type Edl,
  type EdlOverlay,
  type EdlSegment,
} from "./edl";

// The same fixtures as the backend EdlValidatorTest (VideoProperties defaults).
const limits = limitsFor("demo");
const durations = { v1: 10, v2: 10, h3_hook: 3, endcard: 4 };

function seg(
  kind: EdlSegment["kind"],
  role: string,
  input: string,
  inPoint: number,
  outPoint: number,
): EdlSegment {
  return {
    kind,
    role,
    input,
    in: inPoint,
    out: outPoint,
    hasAudio: kind === "REAL",
    blurFill: false,
    sourceMediaId: null,
  };
}

function edl(segments: EdlSegment[], overlays: EdlOverlay[] = []): Edl {
  return {
    videoType: "demo",
    width: 1080,
    height: 1920,
    segments,
    overlays,
    musicInput: "music",
    audio: { originalVolume: 0.5, musicVolume: 0.35, musicFadeOut: 1.5 },
  };
}

test("aiRatio counts only H3 segments and rounds to three decimals", () => {
  const cut = edl([
    seg("H3", "hook", "h3_hook", 0, 3),
    seg("REAL", "body", "v1", 0, 2),
    seg("REAL", "captions", "v2", 0, 5),
    seg("STILL", "endcard", "endcard", 0, 4),
  ]);

  expect(totalSeconds(cut)).toBe(14);
  expect(aiRatio(cut)).toBe(0.214); // 3 / 14
  expect(aiRatio(edl([]))).toBe(0);
});

test("violations flags the AI ratio, body window, total and segment order", () => {
  const cut = edl([seg("H3", "hook", "h3_hook", 0, 3), seg("REAL", "body", "v1", 0, 2)]);

  expect(violations(cut, durations, limits)).toEqual(
    expect.arrayContaining([
      "AI_RATIO_EXCEEDED",
      "BODY_OUT_OF_RANGE",
      "TOTAL_OUT_OF_RANGE",
      "SEGMENT_ORDER",
    ]),
  );
});

test("violations reports out-of-source segments, duplicate roles and late overlays", () => {
  const cut = edl(
    [
      seg("REAL", "hook", "v1", 0, 3),
      seg("REAL", "body", "v1", 0, 12), // beyond the 10 s source
      seg("REAL", "captions", "v2", 8, 7), // in >= out
      seg("STILL", "endcard", "endcard", 0, 4),
      seg("STILL", "endcard", "endcard", 0, 4), // duplicate role
    ],
    [
      { input: "cap_hook", start: 0, end: 3 },
      { input: "late", start: 25, end: 30 },
    ],
  );

  const codes = violations(cut, durations, limits);
  expect(codes).toEqual(
    expect.arrayContaining([
      "SEGMENT_OUT_OF_SOURCE",
      "SEGMENT_ORDER",
      "OVERLAY_OUT_OF_TIMELINE",
    ]),
  );
});

test("a valid demo cut has no violations", () => {
  const cut = edl(
    [
      seg("REAL", "hook", "v1", 0, 3),
      seg("REAL", "body", "v1", 1, 9),
      seg("REAL", "captions", "v2", 2, 7),
      seg("STILL", "endcard", "endcard", 0, 4),
    ],
    [
      { input: "cap_hook", start: 0, end: 3 },
      { input: "cap_0", start: 3, end: 11 },
    ],
  );

  expect(violations(cut, durations, limits)).toEqual([]);
});

test("unknown source durations skip the source bound like the backend", () => {
  const cut = edl([seg("REAL", "hook", "v3", 0, 3), seg("REAL", "body", "v3", 0, 9)]);

  expect(violations(cut, {}, { ...limits, minTotal: 0 })).toEqual(["SEGMENT_ORDER"]);
});

test("applyTrims only edits REAL segments and re-derives the overlay times", () => {
  const cut = edl(
    [
      seg("REAL", "hook", "v1", 0, 3),
      seg("REAL", "body", "v1", 0, 15),
      seg("REAL", "captions", "v2", 0, 5),
      seg("STILL", "endcard", "endcard", 0, 4),
    ],
    [
      { input: "cap_hook", start: 0, end: 3 },
      { input: "cap_0", start: 3, end: 18 },
      { input: "cap_1", start: 18, end: 19.666666666666668 },
      { input: "cap_2", start: 19.666666666666668, end: 21.333333333333332 },
      { input: "cap_3", start: 21.333333333333332, end: 23 },
    ],
  );

  const trimmed = applyTrims(cut, [{ role: "body", in: 0, out: 9 }]);

  expect(totalSeconds(trimmed)).toBe(21);
  expect(trimmed.segments[1].out).toBe(9);
  expect(trimmed.segments[2]).toEqual(cut.segments[2]); // captions footage untouched
  expect(trimmed.segments[3]).toEqual(cut.segments[3]); // stills untouched
  expect(trimmed.overlays[0]).toEqual({ input: "cap_hook", start: 0, end: 3 });
  expect(trimmed.overlays[1]).toEqual({ input: "cap_0", start: 3, end: 12 });
  expect(trimmed.overlays[2].input).toBe("cap_1");
  expect(trimmed.overlays[2].start).toBe(12);
  expect(trimmed.overlays[2].end).toBeCloseTo(13.666666666666666, 9);
  expect(trimmed.overlays).toHaveLength(5);
  expect(violations(trimmed, durations, limits)).toEqual([]);
});