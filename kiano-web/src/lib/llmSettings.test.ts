import { expect, test } from "vitest";
import { applyPresetModel, routeWarning } from "./llmSettings";
import type { Preset } from "./types";

const deepSeek: Preset = {
  code: "deepseek",
  name: "DeepSeek",
  kind: "OPENAI_COMPATIBLE",
  baseUrl: "https://api.deepseek.com",
  models: [
    {
      model: "deepseek-flash",
      supportsImages: true,
      pricing: { inputPerMtok: 0.3, outputPerMtok: 1.2, cacheReadPerMtok: 0.006 },
    },
    {
      model: "deepseek-v4-pro",
      supportsImages: false,
      pricing: { inputPerMtok: 1.32, outputPerMtok: 3.96, cacheReadPerMtok: 0.044 },
    },
  ],
};

test("applyPresetModel fills vision and pricing for deepseek-flash, null for unknown model", () => {
  expect(applyPresetModel(deepSeek, "deepseek-flash")).toEqual({
    supportsImages: true,
    inputPerMtok: 0.3,
    outputPerMtok: 1.2,
    cacheReadPerMtok: 0.006,
  });
  expect(applyPresetModel(deepSeek, "deepseek-v4-pro")).toEqual({
    supportsImages: false,
    inputPerMtok: 1.32,
    outputPerMtok: 3.96,
    cacheReadPerMtok: 0.044,
  });
  // a typed-in custom model keeps the user's own values
  expect(applyPresetModel(deepSeek, "my-custom-model")).toBeNull();
});

test("routeWarning only for FACT_DRAFT without vision", () => {
  expect(routeWarning("FACT_DRAFT", false)).toBe("llm.warning.visionRequired");
  expect(routeWarning("FACT_DRAFT", true)).toBeNull();
  expect(routeWarning("COPY", false)).toBeNull();
  expect(routeWarning("COPY", true)).toBeNull();
});