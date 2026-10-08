"use client";

import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { isOwner, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import { applyPresetModel, providerRequestBody, routeWarning } from "@/lib/llmSettings";
import type {
  LlmPurpose,
  LlmSettingsView,
  Preset,
  PresetModel,
  ProviderView,
  TestResult,
} from "@/lib/types";

const inputClass =
  "w-full max-w-xl rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400";
const smallInputClass =
  "w-28 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400";
const buttonClass =
  "rounded-md bg-zinc-900 px-4 py-1.5 text-sm font-medium text-zinc-50 transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";
const ghostButtonClass =
  "rounded-md border border-zinc-300 px-4 py-1.5 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";

/** The preset that matches a provider (same kind + base url), for model lists. */
function presetForProvider(presets: Preset[], provider: ProviderView): Preset | undefined {
  return presets.find(
    (p) =>
      p.kind === provider.kind &&
      (provider.baseUrl === p.baseUrl || (p.baseUrl === null && provider.kind === "ANTHROPIC")),
  );
}

/** One prompt model option of the datalist for a provider. */
function modelOptions(preset: Preset | undefined): PresetModel[] {
  return preset?.models ?? [];
}

function ProvidersSection({
  providers,
  presets,
  onChange,
}: {
  providers: ProviderView[];
  presets: Preset[];
  onChange: () => Promise<void>;
}) {
  const { t } = useI18n();
  const [adding, setAdding] = useState(false);
  const [presetCode, setPresetCode] = useState("anthropic");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState({ name: "", baseUrl: "", apiKey: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const selectedPreset = presets.find((p) => p.code === presetCode) ?? presets[0];

  function openAdd(preset: Preset) {
    setError(null);
    setSaved(false);
    setEditingId(null);
    setAdding(true);
    setPresetCode(preset.code);
    setForm({ name: "", baseUrl: preset.baseUrl ?? "", apiKey: "" });
  }

  function openEdit(provider: ProviderView) {
    setError(null);
    setSaved(false);
    setAdding(false);
    setEditingId(provider.id);
    setForm({ name: provider.name, baseUrl: provider.baseUrl ?? "", apiKey: "" });
  }

  function closeForm() {
    setAdding(false);
    setEditingId(null);
    setForm({ name: "", baseUrl: "", apiKey: "" });
  }

  async function submitProvider(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(false);
    const editing = providers.find((p) => p.id === editingId) ?? null;
    const body = providerRequestBody(form, selectedPreset.kind, editing);
    try {
      if (editingId !== null) {
        await apiFetch(`/api/v1/settings/llm/providers/${editingId}`, { method: "PUT", body });
      } else {
        await apiFetch("/api/v1/settings/llm/providers", { method: "POST", body });
      }
      setSaved(true);
      closeForm();
      await onChange();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  async function deleteProvider(provider: ProviderView) {
    if (!window.confirm(t("llm.deleteConfirm"))) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/settings/llm/providers/${provider.id}`, { method: "DELETE" });
      await onChange();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">{t("llm.providers")}</h2>
        <div className="flex flex-wrap gap-2">
          {presets.map((preset) => (
            <button
              key={preset.code}
              type="button"
              onClick={() => openAdd(preset)}
              className={ghostButtonClass}
            >
              {t("llm.add")}: {preset.name}
            </button>
          ))}
        </div>
      </div>

      {providers.length === 0 ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("llm.providers")} —</p>
      ) : null}
      {providers.map((provider) => (
        <div
          key={provider.id}
          className="flex flex-wrap items-center gap-3 rounded-md border border-zinc-200 px-3 py-2 text-sm dark:border-zinc-800"
        >
          <span className="font-medium">{provider.name}</span>
          <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">
            {t(`llm.kind.${provider.kind}`)}
          </span>
          {provider.baseUrl ? (
            <span className="text-xs text-zinc-500 dark:text-zinc-400">{provider.baseUrl}</span>
          ) : null}
          <span
            className={
              provider.hasKey
                ? "text-xs text-emerald-600 dark:text-emerald-400"
                : "text-xs text-amber-600 dark:text-amber-400"
            }
          >
            {provider.hasKey ? t("llm.hasKey") : t("llm.noKey")}
          </span>
          <div className="ml-auto flex gap-2">
            <button type="button" onClick={() => openEdit(provider)} className={ghostButtonClass}>
              {t("llm.edit")}
            </button>
            <button
              type="button"
              onClick={() => deleteProvider(provider)}
              className={ghostButtonClass}
            >
              {t("llm.delete")}
            </button>
          </div>
        </div>
      ))}

      {adding || editingId !== null ? (
        <form onSubmit={submitProvider} className="mt-1 flex flex-col gap-3 rounded-md border p-3">
          <label className="flex flex-col gap-1 text-sm">
            {t("llm.providerName")}
            <input
              type="text"
              required
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              className={inputClass}
            />
          </label>
          {selectedPreset.kind === "OPENAI_COMPATIBLE" ? (
            <label className="flex flex-col gap-1 text-sm">
              {t("llm.baseUrl")}
              <input
                type="url"
                required
                value={form.baseUrl}
                onChange={(e) => setForm({ ...form, baseUrl: e.target.value })}
                placeholder="https://api.deepseek.com"
                className={inputClass}
              />
            </label>
          ) : null}
          <label className="flex flex-col gap-1 text-sm">
            {t("llm.apiKey")}
            <input
              type="password"
              autoComplete="new-password"
              required={editingId === null}
              value={form.apiKey}
              onChange={(e) => setForm({ ...form, apiKey: e.target.value })}
              className={inputClass}
            />
            <span className="text-xs text-zinc-500 dark:text-zinc-400">
              {editingId === null ? t("llm.apiKeyAdd") : t("llm.apiKeyKeep")}
            </span>
          </label>
          <div className="flex flex-wrap items-center gap-3">
            <button type="submit" disabled={busy} className={buttonClass}>
              {t("llm.save")}
            </button>
            <button type="button" onClick={closeForm} className={ghostButtonClass}>
              {t("llm.cancel")}
            </button>
            {saved ? (
              <span className="text-sm text-emerald-600 dark:text-emerald-400">{t("llm.saved")}</span>
            ) : null}
          </div>
          {error !== null ? (
            <p className="text-sm text-red-600 dark:text-red-400" role="alert">
              {error}
            </p>
          ) : null}
        </form>
      ) : null}
    </section>
  );
}

function RouteRow({
  purpose,
  route,
  providers,
  presets,
  reload,
}: {
  purpose: LlmPurpose;
  route: { providerId: number | null; model: string | null; supportsImages: boolean; pricing: { inputPerMtok: number; outputPerMtok: number; cacheReadPerMtok: number } | null; usingDefault: boolean };
  providers: ProviderView[];
  presets: Preset[];
  reload: () => Promise<void>;
}) {
  const { t } = useI18n();
  const [providerId, setProviderId] = useState<number | "">(route.providerId ?? "");
  const [model, setModel] = useState(route.model ?? "");
  const [supportsImages, setSupportsImages] = useState(route.supportsImages);
  const [prices, setPrices] = useState({
    inputPerMtok: route.pricing?.inputPerMtok ?? 0,
    outputPerMtok: route.pricing?.outputPerMtok ?? 0,
    cacheReadPerMtok: route.pricing?.cacheReadPerMtok ?? 0,
  });
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const selectedProvider = providers.find((p) => p.id === providerId);
  const preset = selectedProvider ? presetForProvider(presets, selectedProvider) : undefined;
  const warning = routeWarning(purpose, supportsImages);
  const disabled = saving || warning !== null;

  function pickModel(nextModel: string) {
    setModel(nextModel);
    if (selectedProvider) {
      const presetModels = presetForProvider(presets, selectedProvider);
      const values = presetModels ? applyPresetModel(presetModels, nextModel) : null;
      if (values !== null) {
        setSupportsImages(values.supportsImages);
        setPrices({
          inputPerMtok: values.inputPerMtok,
          outputPerMtok: values.outputPerMtok,
          cacheReadPerMtok: values.cacheReadPerMtok,
        });
      }
    }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (providerId === "") {
      return;
    }
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      await apiFetch(`/api/v1/settings/llm/routes/${purpose}`, {
        method: "PUT",
        body: {
          providerId,
          model,
          supportsImages,
          inputPerMtok: prices.inputPerMtok,
          outputPerMtok: prices.outputPerMtok,
          cacheReadPerMtok: prices.cacheReadPerMtok,
        },
      });
      setSaved(true);
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setSaving(false);
    }
  }

  async function testConnection() {
    setTesting(true);
    setError(null);
    setTestResult(null);
    try {
      const res = await apiFetch<TestResult>(
        `/api/v1/settings/llm/routes/${purpose}/test`,
        { method: "POST" },
      );
      setTestResult(res);
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setTesting(false);
    }
  }

  async function resetRoute() {
    if (!window.confirm(t("llm.resetConfirm"))) {
      return;
    }
    setError(null);
    setSaved(false);
    try {
      await apiFetch(`/api/v1/settings/llm/routes/${purpose}`, { method: "DELETE" });
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-base font-semibold">{t(`llm.purpose.${purpose}`)}</h3>
        {route.usingDefault ? (
          <span className="text-xs text-zinc-500 dark:text-zinc-400">{t("llm.defaultRoute")}</span>
        ) : null}
      </div>

      <form onSubmit={save} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          {t("llm.provider")}
          <select
            value={providerId}
            onChange={(e) => {
              const next = e.target.value === "" ? "" : Number(e.target.value);
              setProviderId(next);
              setModel("");
            }}
            className={inputClass}
          >
            <option value="">{t("llm.defaultRoute")}</option>
            {providers.map((provider) => (
              <option key={provider.id} value={provider.id}>
                {provider.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          {t("llm.model")}
          <input
            type="text"
            required={providerId !== ""}
            value={model}
            onChange={(e) => pickModel(e.target.value)}
            list={`llm-models-${purpose}`}
            placeholder={t("llm.modelHint")}
            className={inputClass}
          />
          <datalist id={`llm-models-${purpose}`}>
            {modelOptions(preset).map((m) => (
              <option key={m.model} value={m.model} />
            ))}
          </datalist>
        </label>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={supportsImages}
            onChange={(e) => setSupportsImages(e.target.checked)}
          />
          {t("llm.supportsImages")}
        </label>

        <div className="flex flex-wrap gap-6">
          <label className="flex flex-col gap-1 text-sm">
            {t("llm.price.input")}
            <input
              type="number"
              min="0"
              step="0.000001"
              value={prices.inputPerMtok}
              onChange={(e) => setPrices({ ...prices, inputPerMtok: Number(e.target.value) })}
              className={smallInputClass}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            {t("llm.price.output")}
            <input
              type="number"
              min="0"
              step="0.000001"
              value={prices.outputPerMtok}
              onChange={(e) => setPrices({ ...prices, outputPerMtok: Number(e.target.value) })}
              className={smallInputClass}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            {t("llm.price.cache")}
            <input
              type="number"
              min="0"
              step="0.000001"
              value={prices.cacheReadPerMtok}
              onChange={(e) => setPrices({ ...prices, cacheReadPerMtok: Number(e.target.value) })}
              className={smallInputClass}
            />
          </label>
        </div>

        {warning !== null ? (
          <p className="text-sm text-red-600 dark:text-red-400" role="alert">
            {t(warning)}
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" disabled={disabled} className={buttonClass}>
            {t("llm.save")}
          </button>
          <button type="button" onClick={testConnection} disabled={testing} className={ghostButtonClass}>
            {testing ? t("llm.testing") : t("llm.test")}
          </button>
          <button type="button" onClick={resetRoute} className={ghostButtonClass}>
            {t("llm.reset")}
          </button>
          {saved ? (
            <span className="text-sm text-emerald-600 dark:text-emerald-400">{t("llm.saved")}</span>
          ) : null}
        </div>
      </form>

      {testResult !== null ? (
        <div className="flex flex-col gap-1" role="status">
          <p
            className={
              testResult.ok
                ? "text-sm text-emerald-600 dark:text-emerald-400"
                : "text-sm text-red-600 dark:text-red-400"
            }
          >
            {testResult.ok ? t("llm.testOk") : t("llm.testFailed")}
            {testResult.ok && testResult.model !== null
              ? ` ${t("llm.testDetail", {
                  model: testResult.model,
                  latency: testResult.latencyMs,
                  cost: testResult.costUsd?.toFixed(6) ?? "0",
                })}`
              : ""}
          </p>
          {!testResult.ok && testResult.code !== null ? (
            <p className="text-sm text-red-600 dark:text-red-400">
              {testResult.message ?? testResult.code}
            </p>
          ) : null}
        </div>
      ) : null}

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}

export default function LlmSettingsPage() {
  const { t } = useI18n();
  const me = useMe();
  const [view, setView] = useState<LlmSettingsView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);

  const reload = useCallback(
    () =>
      apiFetch<LlmSettingsView>("/api/v1/settings/llm")
        .then(setView)
        .catch((err: unknown) => {
          if (!(err instanceof ApiError && err.code === "FORBIDDEN")) {
            setPageError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
          }
        }),
    [t],
  );

  useEffect(() => {
    reload().finally(() => setLoaded(true));
  }, [reload]);

  if (me === null || !loaded) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  if (!isOwner(me.role)) {
    return (
      <p className="text-sm text-red-600 dark:text-red-400" role="alert">
        {t("error.FORBIDDEN")}
      </p>
    );
  }

  if (view === null) {
    return (
      <p className="text-sm text-red-600 dark:text-red-400" role="alert">
        {pageError ?? t("error.INTERNAL_ERROR")}
      </p>
    );
  }

  const routeOf = (purpose: LlmPurpose) => {
    const row = view.routes.find((r) => r.purpose === purpose);
    return {
      providerId: row?.providerId ?? null,
      model: row?.model ?? null,
      supportsImages: row?.supportsImages ?? false,
      pricing: row?.pricing ?? null,
      usingDefault: row?.usingDefault ?? true,
    };
  };

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t("llm.heading")}</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{t("llm.guide")}</p>
      </div>

      <ProvidersSection
        providers={view.providers}
        presets={view.presets}
        onChange={reload}
      />

      <section className="flex flex-col gap-4">
        <h2 className="text-lg font-semibold">{t("llm.routes")}</h2>
        <RouteRow
          purpose="FACT_DRAFT"
          route={routeOf("FACT_DRAFT")}
          providers={view.providers}
          presets={view.presets}
          reload={reload}
        />
        <RouteRow
          purpose="COPY"
          route={routeOf("COPY")}
          providers={view.providers}
          presets={view.presets}
          reload={reload}
        />
      </section>
    </div>
  );
}