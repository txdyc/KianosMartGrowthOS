"use client";

import { useCallback, useEffect, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { isOwner, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey, TFunction } from "@/i18n";
import { en } from "@/i18n/en";

const SECTIONS = ["DELIVERY", "COD", "MOMO", "WARRANTY", "RETURNS"] as const;
type Section = (typeof SECTIONS)[number];

interface SectionText {
  title: string;
  body: string;
}

interface PolicyView {
  version: number;
  sections: Record<Section, SectionText | null>;
  complete: boolean;
  updatedAt: string;
}

const inputClass =
  "w-full rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400";

/** OWNER-only store policy editor: 5 sections, versioned, completeness shown. */
export default function PolicyPage() {
  const { t } = useI18n();
  const me = useMe();
  const [policy, setPolicy] = useState<PolicyView | null>(null);
  const [configured, setConfigured] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [draft, setDraft] = useState<Record<Section, SectionText>>(emptyDraft());
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    return apiFetch<{ configured: boolean; policy?: PolicyView }>(
      "/api/v1/content/policy",
    )
      .then((res) => {
        setConfigured(res.configured);
        if (res.policy) {
          setPolicy(res.policy);
          const next = emptyDraft();
          for (const section of SECTIONS) {
            const existing = res.policy.sections[section];
            next[section] = {
              title: existing?.title ?? "",
              body: existing?.body ?? "",
            };
          }
          setDraft(next);
        }
      })
      .catch((err: unknown) => {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      })
      .finally(() => {
        setLoaded(true);
      });
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  async function save() {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const res = await apiFetch<PolicyView>("/api/v1/content/policy", {
        method: "PUT",
        body: draft,
      });
      setPolicy(res);
      setNotice(t("policy.saved", { n: res.version }));
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setSaving(false);
    }
  }

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

  const missing = SECTIONS.filter(
    (s) => !draft[s].body || draft[s].body.trim().length === 0,
  );

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t("policy.heading")}</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{t("policy.note")}</p>
        {configured && policy ? (
          <p className="mt-2 flex items-center gap-2 text-sm">
            <span className="text-zinc-500 dark:text-zinc-400">
              {t("policy.version", { n: policy.version })}
            </span>
            <span
              className={
                policy.complete
                  ? "rounded bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
                  : "rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400"
              }
            >
              {policy.complete ? t("policy.complete") : t("policy.incomplete")}
            </span>
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-5">
        {SECTIONS.map((section) => (
          <SectionEditor
            key={section}
            section={section}
            value={draft[section]}
            onChange={(next) =>
              setDraft((d) => ({ ...d, [section]: next }))
            }
          />
        ))}
      </div>

      {missing.length > 0 ? (
        <p className="text-sm text-amber-600 dark:text-amber-400">{t("policy.incompleteWarn")}</p>
      ) : null}

      {notice !== null ? (
        <p className="text-sm text-emerald-600 dark:text-emerald-400">{notice}</p>
      ) : null}
      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      <div>
        <button
          type="button"
          onClick={() => void save()}
          disabled={saving}
          className="rounded-md bg-zinc-900 px-4 py-1.5 text-sm font-medium text-zinc-50 transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {saving ? t("loading") : t("policy.save")}
        </button>
      </div>
    </div>
  );
}

function SectionEditor({
  section,
  value,
  onChange,
}: {
  section: Section;
  value: SectionText;
  onChange: (next: SectionText) => void;
}) {
  const { t } = useI18n();
  return (
    <fieldset className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <legend className="px-1 text-sm font-semibold">
        {sectionTitle(t, section)}
      </legend>
      <label className="flex flex-col gap-1 text-sm">
        {t("policy.titleHint")}
        <input
          type="text"
          value={value.title}
          onChange={(e) => onChange({ ...value, title: e.target.value })}
          className={inputClass}
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        {t("policy.bodyHint")}
        <textarea
          rows={4}
          value={value.body}
          onChange={(e) => onChange({ ...value, body: e.target.value })}
          className={inputClass}
        />
      </label>
    </fieldset>
  );
}

function emptyDraft(): Record<Section, SectionText> {
  const out = {} as Record<Section, SectionText>;
  for (const section of SECTIONS) {
    out[section] = { title: "", body: "" };
  }
  return out;
}

function sectionTitle(t: TFunction, section: Section): string {
  const key = `policy.section.${section}` as MessageKey;
  return key in en ? t(key) : section;
}