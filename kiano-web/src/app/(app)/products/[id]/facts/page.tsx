"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { TFunction } from "@/i18n";
import { FactsPanel } from "@/components/FactsPanel";
import {
  listFieldToText,
  missingConfirmations,
  REQUIRED_CONFIRMATIONS,
  textToListField,
} from "@/lib/facts";
import type {
  FactSheetResponse,
  FactSheetView,
  FactsJson,
} from "@/lib/types";

type ListField = "inBox" | "features" | "benefits";
type ScalarField =
  | "model"
  | "category"
  | "capacity"
  | "powerW"
  | "voltage"
  | "material"
  | "colour"
  | "warranty";

/** Default empty facts (all null) matches the backend contract for a new draft. */
function emptyFacts(): FactsJson {
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

export default function FactsPage({ params }: PageProps<"/products/[id]/facts">) {
  const { id } = use(params);
  const { t } = useI18n();
  const [data, setData] = useState<FactSheetResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<FactsJson | null>(null);
  const [saving, setSaving] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmed, setConfirmed] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<FactSheetResponse>(`/api/v1/content/products/${id}/facts`)
      .then((raw) => {
        if (cancelled) return;
        setData(raw);
        setForm(cloneFacts(raw.current?.facts ?? raw.locked?.facts ?? emptyFacts()));
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, t]);

  if (error !== null) {
    return (
      <div className="flex flex-col gap-4">
        <BackLink>{t("detail.back")}</BackLink>
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      </div>
    );
  }

  if (data === null || form === null) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  const isLocked = data.locked !== undefined;
  const isDraft = data.current?.status === "DRAFT";

  const setScalar = (field: ScalarField, value: string) => {
    setForm((f) => {
      if (!f) return f;
      const next = { ...f, [field]: value };
      if (field === "powerW") {
        next.powerW = value === "" ? null : Number(value);
      }
      return next;
    });
  };

  const setList = (field: ListField, text: string) => {
    setForm((f) => (f ? { ...f, [field]: textToListField(text) } : f));
  };

  const saveDraft = async () => {
    if (!form) return;
    setSaving(true);
    setNotice(null);
    try {
      const fieldSources = { model: "MANUAL", capacity: "MANUAL" } as Record<string, string>;
      await apiFetch<FactSheetView>(`/api/v1/content/products/${id}/facts/draft`, {
        method: "PUT",
        body: { facts: form, fieldSources },
      });
      setNotice(t("facts.draftSaved"));
      // refresh to pick up version bumps
      const raw = await apiFetch<FactSheetResponse>(`/api/v1/content/products/${id}/facts`);
      setData(raw);
    } catch (err: unknown) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setSaving(false);
    }
  };

  const generateDraft = async () => {
    setGenerating(true);
    setNotice(null);
    try {
      await apiFetch(`/api/v1/content/products/${id}/facts/draft-from-ai`, {
        method: "POST",
      });
      // Poll every 3s until the task finishes and the draft appears.
      const poll = async (attempt = 0) => {
        const raw = await apiFetch<FactSheetResponse>(`/api/v1/content/products/${id}/facts`);
        if (raw.current?.status === "DRAFT") {
          setData(raw);
          setForm(cloneFacts(raw.current.facts));
          setGenerating(false);
          return;
        }
        if (attempt > 20) {
          setGenerating(false);
          return;
        }
        setTimeout(() => void poll(attempt + 1), 3000);
      };
      await poll();
    } catch (err: unknown) {
      setGenerating(false);
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    }
  };

  const lock = async () => {
    const draft = data.current;
    if (!draft) return;
    try {
      await apiFetch<FactSheetView>(`/api/v1/content/products/${id}/facts/lock`, {
        method: "POST",
        body: { draftVersion: draft.version, confirmedFields: [...confirmed] },
      });
      setConfirmOpen(false);
      const raw = await apiFetch<FactSheetResponse>(`/api/v1/content/products/${id}/facts`);
      setData(raw);
      setForm(cloneFacts(raw.locked?.facts ?? raw.current?.facts ?? form));
    } catch (err: unknown) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    }
  };

  const missing = missingConfirmations(confirmed);

  return (
    <div className="flex flex-col gap-6">
      <BackLink>{t("detail.back")}</BackLink>

      <div className="flex items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">{t("nav.facts")}</h1>
        <FactsPanel productId={Number(id)} />
      </div>

      {notice !== null ? <p className="text-sm text-emerald-600 dark:text-emerald-400">{notice}</p> : null}

      <div className="grid gap-6 lg:grid-cols-[1.2fr_1fr]">
        {/* facts form */}
        <form
          className="flex flex-col gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            void saveDraft();
          }}
        >
          <h2 className="text-lg font-semibold">{t("facts.formTitle")}</h2>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {(Object.keys(fields()).filter((f) => f !== "inBox") as ScalarField[]).map((field) => (
              <FieldRow
                key={field}
                label={t(`facts.field.${field}`)}
                source={sourceFor(data, field)}
                disabled={isLocked}
              >
                <input
                  type={field === "powerW" ? "number" : "text"}
                  className={inputClass}
                  value={String(form[field] ?? "")}
                  disabled={isLocked}
                  onChange={(e) => setScalar(field, e.target.value)}
                />
              </FieldRow>
            ))}
          </div>

          {(["inBox", "features", "benefits"] as ListField[]).map((field) => (
            <FieldRow
              key={field}
              label={t(`facts.field.${field}`)}
              source={sourceFor(data, field)}
              disabled={isLocked}
            >
              <textarea
                className={inputClass}
                rows={3}
                value={listFieldToText(form[field])}
                disabled={isLocked}
                onChange={(e) => setList(field, e.target.value)}
              />
            </FieldRow>
          ))}

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="submit"
              disabled={isLocked || saving || !isDraft}
              className={primaryClass(isLocked || saving || !isDraft)}
            >
              {saving ? t("facts.saving") : t("facts.saveDraft")}
            </button>
            <button
              type="button"
              disabled={isLocked || generating || !isDraft}
              className={primaryClass(isLocked || generating || !isDraft)}
              onClick={() => void generateDraft()}
            >
              {generating ? t("facts.generating") : t("facts.generate")}
            </button>
            <button
              type="button"
              disabled={isLocked || !isDraft}
              title={missing.length > 0 ? t("facts.confirmBody") : undefined}
              className={primaryClass(isLocked || !isDraft)}
              onClick={() => setConfirmOpen(true)}
            >
              {t("facts.lock")}
            </button>
          </div>
          <span className="text-xs text-zinc-500 dark:text-zinc-400">{t("facts.hintLock")}</span>
        </form>

        {/* side-by-side photos */}
        <div className="flex flex-col gap-4">
          {(["P5", "PROMO"] as const).map((shot) =>
            data.sources[shot] ? (
              <a
                key={shot}
                href={data.sources[shot].url}
                target="_blank"
                rel="noopener noreferrer"
                className="group"
              >
                {/* eslint-disable-next-line @next/next/no-img-element -- presigned MinIO URLs */}
                <img
                  src={data.sources[shot].url}
                  alt={t(`facts.sources${shot}`)}
                  className="w-full rounded-lg border border-zinc-200 object-contain dark:border-zinc-800"
                />
                <span className="mt-1 block text-xs text-zinc-500 group-hover:text-zinc-900 dark:text-zinc-400 dark:group-hover:text-zinc-100">
                  {t(`facts.sources${shot}`)}
                </span>
              </a>
            ) : (
              <div key={shot} className="rounded-lg border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500 dark:border-zinc-700">
                {t(`facts.sources${shot}`)}
              </div>
            ),
          )}
        </div>
      </div>

      {confirmOpen && isDraft ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true">
          <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl dark:bg-zinc-900">
            <h3 className="text-lg font-semibold">{t("facts.confirmTitle")}</h3>
            <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{t("facts.confirmBody")}</p>
            <ul className="mt-4 flex flex-col gap-2">
              {REQUIRED_CONFIRMATIONS.map((field) => (
                <li key={field} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={confirmed.has(field)}
                    onChange={(e) => {
                      const next = new Set(confirmed);
                      if (e.target.checked) next.add(field);
                      else next.delete(field);
                      setConfirmed(next);
                    }}
                  />
                  <span className="font-mono">{field}</span>
                  <span className="text-zinc-500 dark:text-zinc-400">
                    {String(form[field] ?? listFieldToText(form[field]) ?? "")}
                  </span>
                </li>
              ))}
            </ul>
            <div className="mt-6 flex justify-end gap-2">
              <button type="button" className={secondaryClass} onClick={() => setConfirmOpen(false)}>
                {t("facts.cancel")}
              </button>
              <button
                type="button"
                disabled={missing.length > 0}
                className={primaryClass(missing.length > 0)}
                onClick={() => void lock()}
              >
                {t("facts.confirmGo")}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function cloneFacts(facts: FactsJson): FactsJson {
  return {
    ...facts,
    inBox: facts.inBox ? [...facts.inBox] : null,
    features: facts.features ? [...facts.features] : null,
    benefits: facts.benefits ? [...facts.benefits] : null,
    forbiddenClaims: facts.forbiddenClaims ? [...facts.forbiddenClaims] : null,
  };
}

function fields(): Record<string, string> {
  return {
    model: "",
    category: "",
    capacity: "",
    powerW: "",
    voltage: "",
    material: "",
    colour: "",
    warranty: "",
    inBox: "",
    features: "",
    benefits: "",
  };
}

function sourceFor(data: FactSheetResponse, field: string): string {
  const sheet = data.current ?? data.locked;
  const source = sheet?.fieldSources?.[field];
  return source ?? "NONE";
}

const SOURCE_LABEL_KEYS: Record<string, "facts.source.P5" | "facts.source.PROMO" | "facts.source.WOO_TEXT" | "facts.source.MANUAL" | "facts.source.NONE"> = {
  P5: "facts.source.P5",
  PROMO: "facts.source.PROMO",
  WOO_TEXT: "facts.source.WOO_TEXT",
  MANUAL: "facts.source.MANUAL",
  NONE: "facts.source.NONE",
};

function sourceLabel(source: string, t: TFunction): string {
  return t(SOURCE_LABEL_KEYS[source] ?? "facts.source.NONE");
}

function BackLink({ children }: { children: React.ReactNode }) {
  return (
    <Link
      href="/products"
      className="text-sm text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
    >
      ← {children}
    </Link>
  );
}

const inputClass =
  "w-full rounded-md border border-zinc-300 bg-white px-2.5 py-1.5 text-sm dark:border-zinc-700 dark:bg-zinc-950 disabled:opacity-60";

const primaryClass = (disabled: boolean) =>
  `rounded-md px-3 py-1.5 text-sm font-medium ${
    disabled
      ? "cursor-not-allowed bg-zinc-200 text-zinc-400 dark:bg-zinc-800 dark:text-zinc-600"
      : "bg-zinc-900 text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
  }`;

const secondaryClass =
  "rounded-md border border-zinc-300 px-3 py-1.5 text-sm dark:border-zinc-700";

/** One labelled input plus its provenance badge. */
function FieldRow({
  label,
  source,
  disabled,
  children,
}: {
  label: string;
  source: string;
  disabled: boolean;
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">{label}</label>
        {!disabled ? (
          <span
            className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
              source === "MANUAL"
                ? "bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300"
                : "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400"
            }`}
          >
            {sourceLabel(source, t)}
          </span>
        ) : null}
      </div>
      {children}
    </div>
  );
}