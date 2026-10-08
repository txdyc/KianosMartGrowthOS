import { expect, test } from "vitest";
import { applyPresetModel, providerRequestBody, routeWarning } from "./llmSettings";
import type { Preset, ProviderView } from "./types";

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
const deepSeekProvider: ProviderView = {
  id: 7,
  name: "DeepSeek",
  kind: "OPENAI_COMPATIBLE",
  baseUrl: "https://api.deepseek.com",
  hasKey: true,
  status: "ACTIVE",
  updatedAt: "2026-10-08T00:00:00Z",
};

test("providerRequestBody: editing keeps the provider's own kind, not the selected preset's", () => {
  const form = { name: " DeepSeek CN ", baseUrl: " https://api.deepseek.com ", apiKey: "" };

  // The add-form preset selector may still say "anthropic" while editing.
  expect(providerRequestBody(form, "ANTHROPIC", deepSeekProvider)).toEqual({
    name: "DeepSeek CN",
    kind: "OPENAI_COMPATIBLE",
    baseUrl: "https://api.deepseek.com",
    apiKey: "",
  });
});

test("providerRequestBody: adding uses the preset kind and sends null for a blank base URL", () => {
  const form = { name: "Claude", baseUrl: "  ", apiKey: "sk-ant-x" };

  expect(providerRequestBody(form, "ANTHROPIC", null)).toEqual({
    name: "Claude",
    kind: "ANTHROPIC",
    baseUrl: null,
    apiKey: "sk-ant-x",
  });
});
