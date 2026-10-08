import type { MessageKey } from "@/i18n";
import type { LlmPurpose, Preset } from "./types";

/** Values that applying a preset model fills into the route form. */
export interface PresetModelValues {
  supportsImages: boolean;
  inputPerMtok: number;
  outputPerMtok: number;
  cacheReadPerMtok: number;
}

/**
 * The vision support and prices carried by a preset model, or null when the
 * model is not in the preset (the user typed a custom model id) - in that case
 * the caller keeps what the user already filled in.
 */
export function applyPresetModel(
  preset: Preset,
  model: string,
): PresetModelValues | null {
  const entry = preset.models.find((m) => m.model === model);
  if (entry === undefined) {
    return null;
  }
  return {
    supportsImages: entry.supportsImages,
    inputPerMtok: entry.pricing.inputPerMtok,
    outputPerMtok: entry.pricing.outputPerMtok,
    cacheReadPerMtok: entry.pricing.cacheReadPerMtok,
  };
}

/**
 * FACT_DRAFT routes must use a vision-capable model; a warning message key is
 * returned when the checkbox is off so the UI can show it and disable saving.
 */
export function routeWarning(
  purpose: LlmPurpose,
  supportsImages: boolean,
): MessageKey | null {
  return purpose === "FACT_DRAFT" && !supportsImages
    ? "llm.warning.visionRequired"
    : null;
}