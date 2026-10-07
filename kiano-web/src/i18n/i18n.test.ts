import { expect, test } from "vitest";
import { ApiError } from "../lib/api";
import { en, type MessageKey } from "./en";
import { zh } from "./zh";
import { detectLocale, errorText, makeT, pickGuidance, stockText } from "./index";

test("zh and en have exactly the same keys and no empty values", () => {
  expect(Object.keys(zh).sort()).toEqual(Object.keys(en).sort());
  for (const [key, value] of Object.entries(en)) {
    expect(value.trim().length, `empty en value: ${key}`).toBeGreaterThan(0);
  }
  for (const [key, value] of Object.entries(zh)) {
    expect(value.trim().length, `empty zh value: ${key}`).toBeGreaterThan(0);
  }
});

test("dictionary covers every backend enum and error code", () => {
  const qcReasons = [
    "LOW_RESOLUTION",
    "BLURRY",
    "OVEREXPOSED",
    "UNDEREXPOSED",
    "NOT_PORTRAIT",
    "FPS_OUT_OF_RANGE",
    "DURATION_OUT_OF_RANGE",
  ];
  const shotStates = ["OK", "RESHOOT", "MISSING"];
  const tiers = ["HERO", "STANDARD"];
  const taskStatuses = ["QUEUED", "RUNNING", "SUCCEEDED", "FAILED"];
  const outcomes = ["IMPORTED", "DUPLICATE"];
  const stockStatuses = ["instock", "outofstock", "onbackorder"]; // WooCommerce stock_status values
  const errorCodes = [
    "UNAUTHENTICATED",
    "FORBIDDEN",
    "NOT_FOUND",
    "VALIDATION_FAILED",
    "INTERNAL_ERROR",
    "AUTH_INVALID_CREDENTIALS",
    "WOO_NOT_CONFIGURED",
    "WOO_AUTH_FAILED",
    "WOO_BLOCKED",
    "WOO_UNAVAILABLE",
    "NOT_TOP_LEVEL_PRODUCT",
    "INVALID_FILE_NAME",
    "UNSUPPORTED_FILE_TYPE",
    "UNKNOWN_SKU",
    "UNREADABLE_MEDIA",
  ];

  const required: MessageKey[] = [
    ...qcReasons.map((r) => `qc.${r}` as MessageKey),
    ...shotStates.map((s) => `state.${s}` as MessageKey),
    ...tiers.map((t) => `tier.${t}` as MessageKey),
    ...taskStatuses.map((s) => `task.${s}` as MessageKey),
    ...outcomes.map((o) => `outcome.${o}` as MessageKey),
    ...stockStatuses.map((s) => `stock.${s}` as MessageKey),
    ...errorCodes.map((c) => `error.${c}` as MessageKey),
    "error.UNSUPPORTED_FILE_TYPE.heic",
  ];

  for (const key of required) {
    expect(en[key], `missing en key: ${key}`).toBeDefined();
    expect(zh[key], `missing zh key: ${key}`).toBeDefined();
  }
});

test("t interpolates vars", () => {
  expect(makeT("en")("import.summary", { n: 3 })).toContain("3");
  expect(makeT("zh")("import.summary", { n: 3 })).toContain("3");
});

test("detectLocale", () => {
  expect(detectLocale("en", "zh-CN")).toBe("en"); // cookie wins
  expect(detectLocale(undefined, "zh-CN")).toBe("zh");
  expect(detectLocale(undefined, "en-GH")).toBe("en");
  expect(detectLocale("xx", "fr")).toBe("en"); // invalid cookie ignored
});

test("errorText falls back to message and handles heic", () => {
  const t = makeT("zh");

  // Unknown code falls back to the raw message.
  expect(errorText(t, new ApiError(500, "SOMETHING_NEW", "raw message"))).toBe("raw message");

  // Known code uses the dictionary.
  expect(errorText(t, new ApiError(401, "AUTH_INVALID_CREDENTIALS", "Invalid email or password"))).toBe(
    zh["error.AUTH_INVALID_CREDENTIALS"],
  );

  // heic gets the dedicated hint.
  expect(
    errorText(
      t,
      new ApiError(422, "UNSUPPORTED_FILE_TYPE", "unsupported", undefined, { extension: "heic" }),
    ),
  ).toBe(zh["error.UNSUPPORTED_FILE_TYPE.heic"]);
});

test("stockText localizes Woo stock statuses and falls back to the raw value", () => {
  expect(stockText(makeT("zh"), "instock")).toBe(zh["stock.instock"]);
  expect(stockText(makeT("en"), "outofstock")).toBe(en["stock.outofstock"]);
  expect(stockText(makeT("zh"), "some-plugin-status")).toBe("some-plugin-status");
  expect(stockText(makeT("zh"), null)).toBe("—");
});

test("pickGuidance returns the column for the locale", () => {
  const line = { guidanceEn: "Front, eye level", guidanceZh: "正面，平视" };
  expect(pickGuidance("zh", line)).toBe("正面，平视");
  expect(pickGuidance("en", line)).toBe("Front, eye level");
});
