/**
 * Fact-sheet helpers for the G2 review page. These are pure functions the
 * frontend uses to model the six mandatory confirmations and the multi-line
 * list fields of FactsJson.
 */

/** The six facts that must be confirmed before a draft can be locked (G2). */
export const REQUIRED_CONFIRMATIONS = [
  "model",
  "capacity",
  "powerW",
  "voltage",
  "warranty",
  "inBox",
] as const;

/**
 * Required confirmations the user has not checked yet, in the fixed order.
 * The lock button stays disabled until this is empty.
 */
export function missingConfirmations(confirmed: Set<string>): string[] {
  return REQUIRED_CONFIRMATIONS.filter((field) => !confirmed.has(field));
}

/**
 * Multi-line text area ↔ list field. Empty lines and surrounding whitespace
 * are dropped, so pasting comma-based or blank-separated lists behaves.
 */
export function textToListField(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

export function listFieldToText(list: string[] | null | undefined): string {
  if (!list || list.length === 0) {
    return "";
  }
  return list.join("\n");
}