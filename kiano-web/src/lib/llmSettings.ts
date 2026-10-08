import type { MessageKey } from "@/i18n";
import type { LlmPurpose, Preset, ProviderKind, ProviderView } from "./types";

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
/** The provider add/edit form fields. */
export interface ProviderForm {
  name: string;
  baseUrl: string;
  apiKey: string;
}

/**
 * Body for POST/PUT /api/v1/settings/llm/providers. When editing, the kind is
 * the edited provider's own kind: the add-form preset selector may still point
 * at another preset, and the backend rejects kind changes. A blank base URL is
 * sent as null; a blank key on edit means "keep the stored key".
 */
export function providerRequestBody(
  form: ProviderForm,
  presetKind: ProviderKind,
  editing: ProviderView | null,
): { name: string; kind: ProviderKind; baseUrl: string | null; apiKey: string } {
  return {
    name: form.name.trim(),
    kind: editing !== null ? editing.kind : presetKind,
    baseUrl: form.baseUrl.trim() || null,
    apiKey: form.apiKey,
  };
}
