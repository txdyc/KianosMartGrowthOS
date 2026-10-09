/**
 * Fact-sheet helpers for the G2 review page. These are pure functions the
 * frontend uses to model the six mandatory confirmations and the multi-line
 * list fields of FactsJson.
 */

import type { FactsJson } from "./types";

/** The six facts that must be confirmed before a draft can be locked (G2). */
export const REQUIRED_CONFIRMATIONS = [
  "model",
  "capacity",
  "powerW",
  "voltage",
  "warranty",
  "inBox",
] as const;

/** Editable scalar fields of a draft, in form order (C2/C3). */
export const SCALAR_FIELDS = [
  "model",
  "category",
  "capacity",
  "powerW",
  "voltage",
  "material",
  "colour",
  "warranty",
] as const;

/** Editable multi-line list fields of a draft. */
export const LIST_FIELDS = ["inBox", "features", "benefits"] as const;

export type ScalarField = (typeof SCALAR_FIELDS)[number];
export type ListField = (typeof LIST_FIELDS)[number];

/** A draft with every field empty, matching the backend contract. */
export function emptyFacts(): FactsJson {
  return {
    model: null,
    category: null,
    capacity: null,
    powerW: null,
    voltage: null,
    material: null,
    colour: null,
    warranty: null,
    inBox: null,
    features: null,
    benefits: null,
    forbiddenClaims: null,
  };
}

/** A deep copy of the list fields, so edits never touch the fetched object. */
export function cloneFacts(facts: FactsJson): FactsJson {
  return {
    ...facts,
    inBox: facts.inBox ? [...facts.inBox] : null,
    features: facts.features ? [...facts.features] : null,
    benefits: facts.benefits ? [...facts.benefits] : null,
    forbiddenClaims: facts.forbiddenClaims ? [...facts.forbiddenClaims] : null,
  };
}

/**
 * True when every required confirmation field carries a value; the fact
 * station only locks with Enter once this holds.
 */
export function factsComplete(facts: FactsJson): boolean {
  return REQUIRED_CONFIRMATIONS.every((field) => {
    const value = facts[field];
    if (value === null || value === undefined) {
      return false;
    }
    if (typeof value === "string") {
      return value.trim().length > 0;
    }
    if (typeof value === "number") {
      return true;
    }
    return Array.isArray(value) && value.length > 0;
  });
}

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