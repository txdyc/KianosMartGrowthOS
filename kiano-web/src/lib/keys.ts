import type { RejectReason } from "./types";

/** The reject reasons in dialog order; 1–7 pick them on the review station. */
export const REJECT_REASONS: RejectReason[] = [
  "PRODUCT_MISMATCH",
  "AI_ARTIFACT",
  "WRONG_FACT",
  "TEXT_ERROR",
  "STYLE",
  "LOW_QUALITY",
  "POLICY",
];

/** Every keyboard action of the two batch workbenches (C6 Task 8). */
export type Action =
  | "HELP"
  | "CANCEL"
  | "CONFIRM_ALL"
  | "EDIT_FIELD"
  | "NEXT_FIELD"
  | "PREV_FIELD"
  | "SKIP_SKU"
  | "NEXT_ASSET"
  | "PREV_ASSET"
  | "APPROVE"
  | "REJECT"
  | "REGENERATE"
  | "APPROVE_REMAINING"
  | "NEXT_SKU"
  | `REJECT_REASON_${number}`;

export interface KeyContext {
  station: "FACTS" | "REVIEW";
  /** FACTS: all six required fields are filled — only then Enter locks. */
  factsComplete?: boolean;
  /** REVIEW: a reject reason is being picked, so digits 1–7 are live. */
  rejecting?: boolean;
}

/** The keyboard shape `actionFor` needs; a real KeyboardEvent satisfies it. */
export interface KeyEventLike {
  key: string;
  target: EventTarget | null;
}

/**
 * The shortcut map of one station. Unavailable actions are absent from the
 * map (plus Enter on an incomplete fact draft), so callers can treat a
 * missing key and a missing action the same.
 */
export function keymap(context: KeyContext): Record<string, Action> {
  const map: Record<string, Action> = { "?": "HELP", Escape: "CANCEL" };
  if (context.station === "FACTS") {
    map.j = "NEXT_FIELD";
    map.J = "NEXT_FIELD";
    map.k = "PREV_FIELD";
    map.K = "PREV_FIELD";
    map.e = "EDIT_FIELD";
    map.E = "EDIT_FIELD";
    map.s = "SKIP_SKU";
    map.S = "SKIP_SKU";
    if (context.factsComplete === true) {
      map.Enter = "CONFIRM_ALL";
    }
    return map;
  }
  map.ArrowRight = "NEXT_ASSET";
  map.ArrowLeft = "PREV_ASSET";
  map[" "] = "APPROVE";
  map.x = "REJECT";
  map.X = "REJECT";
  map.g = "REGENERATE";
  map.G = "REGENERATE";
  map.a = "APPROVE_REMAINING";
  map.A = "APPROVE_REMAINING";
  map.n = "NEXT_SKU";
  map.N = "NEXT_SKU";
  if (context.rejecting === true) {
    REJECT_REASONS.forEach((_, index) => {
      map[String(index + 1)] = `REJECT_REASON_${index + 1}`;
    });
  }
  return map;
}

/** The action of one key press; inside inputs only Esc still works. */
export function actionFor(event: KeyEventLike, context: KeyContext): Action | null {
  if (isEditable(event.target)) {
    return event.key === "Escape" ? "CANCEL" : null;
  }
  return keymap(context)[event.key] ?? null;
}

function isEditable(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (element === null || typeof element.tagName !== "string") {
    return false;
  }
  if (element.isContentEditable === true) {
    return true;
  }
  const tag = element.tagName.toUpperCase();
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
}