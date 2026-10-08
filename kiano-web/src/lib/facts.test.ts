import { describe, expect, test } from "vitest";
import {
  listFieldToText,
  missingConfirmations,
  REQUIRED_CONFIRMATIONS,
  textToListField,
} from "./facts";

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