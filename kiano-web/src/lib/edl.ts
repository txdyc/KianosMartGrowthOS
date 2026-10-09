/**
 * C5 EDL helpers. The arithmetic and the validation codes mirror the backend
 * EdlMath/EdlValidator and the editor rules of VideoEdlController, so the
 * review board can show live totals and block an invalid cut before saving.
 */

export interface EdlSegment {
  kind: "REAL" | "H3" | "STILL";
  role: string;
  input: string;
  in: number;
  out: number;
  hasAudio: boolean;
  blurFill: boolean;
  sourceMediaId: number | null;
}

export interface EdlOverlay {
  input: string;
  start: number;
  end: number;
}

export interface EdlAudio {
  originalVolume: number;
  musicVolume: number;
  musicFadeOut: number;
}

/** content.workerprotocol.Edl as sent in a VIDEO_AD's provenance. */
export interface Edl {
  videoType: string;
  width: number;
  height: number;
  segments: EdlSegment[];
  overlays: EdlOverlay[];
  musicInput: string | null;
  audio: EdlAudio;
}

/** One manual in/out change; only REAL segments are editable. */
export interface Trim {
  role: string;
  in: number;
  out: number;
}

/** Recipe and gates the editor validates against (VideoProperties). */
export interface EdlLimits {
  bodyMin: number;
  bodyMax: number;
  minTotal: number;
  maxTotal: number;
  maxAiRatio: number;
}

/** Body window per type (kiano.content.video.recipe); demo's is the fallback. */
const BODY_SECONDS: Record<string, { min: number; max: number }> = {
  demo: { min: 8, max: 15 },
  problem: { min: 8, max: 12 },
  unboxing: { min: 8, max: 14 },
};

/** The AI-ratio gate (kiano.content.video.max-ai-ratio). */
export const MAX_AI_RATIO = 0.3;

/** The limits of one video type, from the backend defaults. */
export function limitsFor(videoType: string): EdlLimits {
  const body = BODY_SECONDS[videoType] ?? BODY_SECONDS.demo;
  return {
    bodyMin: body.min,
    bodyMax: body.max,
    minTotal: 15,
    maxTotal: 30,
    maxAiRatio: MAX_AI_RATIO,
  };
}

/** Sum of every segment length. */
export function totalSeconds(edl: Edl): number {
  return edl.segments.reduce((total, segment) => total + length(segment), 0);
}

/** Seconds contributed by H3 (generated) segments. */
export function aiSeconds(edl: Edl): number {
  return edl.segments
    .filter((segment) => segment.kind === "H3")
    .reduce((total, segment) => total + length(segment), 0);
}

/** AI seconds ÷ total seconds, rounded to 3 decimals like EdlMath.aiRatio. */
export function aiRatio(edl: Edl): number {
  const total = totalSeconds(edl);
  if (total <= 0) {
    return 0;
  }
  return Math.round((aiSeconds(edl) / total) * 1000) / 1000;
}

/**
 * The trimmed cut: REAL segments take the new in/out points, everything else
 * is kept, and the caption overlays are re-derived from the new lengths
 * exactly like VideoEdlController (hook caption over the hook, the first
 * selling point over the body, the remaining captions splitting the captions
 * segment).
 */
export function applyTrims(edl: Edl, trims: Trim[]): Edl {
  const byRole = new Map(trims.map((trim) => [trim.role, trim]));
  const segments = edl.segments.map((segment) => {
    const trim = segment.kind === "REAL" ? byRole.get(segment.role) : undefined;
    return trim === undefined ? segment : { ...segment, in: trim.in, out: trim.out };
  });
  return { ...edl, segments, overlays: overlaysFor(edl, segments) };
}

/**
 * Violation codes of a cut, same set and conditions as the backend
 * EdlValidator. Unknown source durations skip the source-bound check, like the
 * backend does when a duration is missing.
 */
export function violations(
  edl: Edl,
  durations: Record<string, number | undefined>,
  limits: EdlLimits,
): string[] {
  const codes: string[] = [];
  const total = totalSeconds(edl);

  if (aiRatio(edl) > limits.maxAiRatio) {
    codes.push("AI_RATIO_EXCEEDED");
  }
  if (total < limits.minTotal || total > limits.maxTotal) {
    codes.push("TOTAL_OUT_OF_RANGE");
  }
  for (const segment of edl.segments) {
    const sourceSeconds = durations[segment.input];
    if (
      segment.in < 0 ||
      segment.in >= segment.out ||
      (sourceSeconds !== undefined && segment.out > sourceSeconds)
    ) {
      codes.push("SEGMENT_OUT_OF_SOURCE");
    }
    if (segment.role === "body") {
      const bodySeconds = length(segment);
      if (bodySeconds < limits.bodyMin || bodySeconds > limits.bodyMax) {
        codes.push("BODY_OUT_OF_RANGE");
      }
    }
  }
  for (const overlay of edl.overlays) {
    if (overlay.start < 0 || overlay.start >= overlay.end || overlay.end > total) {
      codes.push("OVERLAY_OUT_OF_TIMELINE");
    }
  }
  const roles = edl.segments.map((segment) => segment.role);
  if (roles.join(",") !== "hook,body,captions,endcard") {
    codes.push("SEGMENT_ORDER");
  }
  return codes;
}

function length(segment: EdlSegment): number {
  return segment.out - segment.in;
}

function overlaysFor(edl: Edl, segments: EdlSegment[]): EdlOverlay[] {
  const hook = length(segments[0]);
  const body = length(segments[1]);
  const captions = length(segments[2]);
  const slots = edl.overlays.filter(
    (overlay) => overlay.input.startsWith("cap_") && overlay.input !== "cap_hook" && overlay.input !== "cap_0",
  ).length;
  const count = Math.max(1, slots);
  const overlays: EdlOverlay[] = [
    { input: "cap_hook", start: 0, end: hook },
    { input: "cap_0", start: hook, end: hook + body },
  ];
  const slot = captions / count;
  for (let i = 1; i <= count; i += 1) {
    overlays.push({
      input: `cap_${i}`,
      start: hook + body + (i - 1) * slot,
      end: hook + body + i * slot,
    });
  }
  return overlays;
}