import { describe, expect, test } from "vitest";
import {
  cloneFacts,
  emptyFacts,
  factsComplete,
  listFieldToText,
  missingConfirmations,
  REQUIRED_CONFIRMATIONS,
  textToListField,
} from "./facts";
import type { FactsJson } from "./types";

describe("missingConfirmations", () => {
  test("lists unchecked required fields in order", () => {
    expect(missingConfirmations(new Set())).toEqual(REQUIRED_CONFIRMATIONS);
    expect(missingConfirmations(new Set(["model", "warranty", "inBox"]))).toEqual([
      "capacity",
      "powerW",
      "voltage",
    ]);
    expect(
      missingConfirmations(new Set(REQUIRED_CONFIRMATIONS)),
    ).toEqual([]);
  });
});

describe("list field round-trip", () => {
  test("trims and drops empty lines", () => {
    expect(textToListField("  Kettle  \n\n  Base \n\t")).toEqual([
      "Kettle",
      "Base",
    ]);
    expect(textToListField("  ")).toEqual([]);
    expect(listFieldToText(["Kettle", "Base"])).toBe("Kettle\nBase");
    expect(listFieldToText([])).toBe("");
    expect(listFieldToText(null)).toBe("");
  });
});

describe("factsComplete", () => {
  const complete: FactsJson = {
    ...emptyFacts(),
    model: "Kiano K1",
    capacity: "1.7L",
    powerW: 1800,
    voltage: "220-240V",
    warranty: "1 year",
    inBox: ["Kettle", "Base"],
  };

  test("needs all six confirmations filled", () => {
    expect(factsComplete(complete)).toBe(true);
    expect(factsComplete({ ...complete, model: "  " })).toBe(false);
    expect(factsComplete({ ...complete, powerW: null })).toBe(false);
    expect(factsComplete({ ...complete, inBox: [] })).toBe(false);
  });

  test("cloneFacts copies the list fields", () => {
    const copy = cloneFacts(complete);
    copy.inBox?.push("Manual");
    expect(complete.inBox).toEqual(["Kettle", "Base"]);
  });
});