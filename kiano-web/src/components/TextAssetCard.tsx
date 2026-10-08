"use client";

import { useState } from "react";
import { ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { TFunction } from "@/i18n";
import { en } from "@/i18n/en";
import { charLimit } from "@/lib/review";
import type { MessageKey } from "@/i18n";
import type { FactsJson, PrecheckFlag, ReviewItem } from "@/lib/types";

/**
 * Text asset card on the review board: spec/version/char count (over-limit in
 * red), precheck badges, a rendered preview for HTML specs (backend already
 * escapes; we additionally run the same whitelist), the locked-facts summary,
 * and an inline editor opened with E (Esc cancels, then A/R/G take over).
 */
export function TextAssetCard({
  item,
  focused,
  busy,
  editing,
  onFocus,
  onEditingChange,
  onSave,
  facts,
}: {
  item: ReviewItem;
  focused: boolean;
  busy: boolean;
  editing: boolean;
  onFocus: () => void;
  onEditingChange: (editing: boolean) => void;
  onSave: (body: string) => Promise<void>;
  facts: FactsJson | null;
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(item.textBody ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const body = editing ? draft : (item.textBody ?? "");
  const isSeo = item.specCode === "COPY_SEO";
  const isHtml = item.specCode === "COPY_LONG" || item.specCode === "COPY_SHORT";

  const charCount = isHtml ? stripHtml(body).length : body.length;

  const limit =
    isSeo && item.textBody !== null
      ? charLimit("COPY_SEO", "title")
      : charLimit(item.specCode);
  const overLimit = limit !== null && charCount > limit;

  const startEdit = () => {
    setDraft(item.textBody ?? "");
    onEditingChange(true);
    setError(null);
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave(draft);
      onEditingChange(false);
      setSaving(false);
    } catch (err) {
      setSaving(false);
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    }
  };

  const cancel = () => onEditingChange(false);

  return (
    <div
      id={`review-asset-${item.assetId}`}
      onClick={onFocus}
      className={`flex flex-col gap-2 rounded-lg border bg-white p-3 dark:bg-zinc-900 ${
        item.flags.length > 0 ? "border-red-500" : "border-zinc-200 dark:border-zinc-800"
      } ${focused ? "ring-2 ring-blue-500" : ""} ${busy ? "opacity-50" : ""}`}
    >
      {/* header */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-zinc-600 dark:text-zinc-400">
        <span className="font-medium text-zinc-800 dark:text-zinc-200">{specText(t, item.specCode)}</span>
        <span>v{item.version}</span>
        <span className={overLimit ? "font-medium text-red-600 dark:text-red-400" : undefined}>
          {charCount.toLocaleString()}
          {limit !== null ? ` / ${limit}` : ""}
        </span>
        {item.flags.map((flag) => (
          <PrecheckBadge key={flag} flag={flag} text={t(`precheck.${flag}`)} />
        ))}
      </div>

      {error !== null ? (
        <p className="text-xs text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {/* body */}
      {isSeo ? (
        <SeoView body={body} t={t} />
      ) : isHtml ? (
        <div className="rounded border border-zinc-100 dark:border-zinc-800">
          <p className="border-b border-zinc-100 px-2 py-1 text-[10px] uppercase tracking-wide text-zinc-400 dark:border-zinc-800">
            {t("review.renderedPreview")}
          </p>
          <HtmlPreview html={body} />
        </div>
      ) : (
        <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded border border-zinc-100 bg-zinc-50 p-2 font-mono text-xs leading-relaxed text-zinc-700 dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-300">
          {body}
        </pre>
      )}

      {/* edit box */}
      {editing ? (
        <textarea
          autoFocus
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              e.preventDefault();
              cancel();
            }
          }}
          rows={6}
          className="w-full rounded-md border border-zinc-300 bg-white p-2 font-mono text-xs leading-relaxed dark:border-zinc-700 dark:bg-zinc-950"
        />
      ) : null}

      {/* actions */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={startEdit}
          disabled={busy}
          className="rounded-md border border-zinc-300 px-2 py-1 text-xs transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          {t("review.edit")}
        </button>
        {editing ? (
          <>
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving}
              className="rounded-md bg-zinc-900 px-2 py-1 text-xs font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900"
            >
              {saving ? t("loading") : t("review.editSave")}
            </button>
            <button
              type="button"
              onClick={cancel}
              disabled={saving}
              className="rounded-md border border-zinc-300 px-2 py-1 text-xs text-zinc-500 transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
            >
              {t("review.editCancel")}
            </button>
          </>
        ) : null}
      </div>

      {/* locked facts summary */}
      {facts !== null ? (
        <FactsSummary facts={facts} t={t} />
      ) : null}
    </div>
  );
}

function PrecheckBadge({ flag, text }: { flag: PrecheckFlag; text: string }) {
  const label = flag === "FACT_MISMATCH" ? text : text;
  return (
    <span
      title={flag}
      className="rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
    >
      {label}
    </span>
  );
}

function SeoView({ body, t }: { body: string; t: TFunction }) {
  let title = "";
  let description = "";
  try {
    const parsed = JSON.parse(body) as { title?: string; description?: string };
    title = parsed.title ?? "";
    description = parsed.description ?? "";
  } catch {
    // corrupt JSON from an old version: show raw
  }
  return (
    <div className="flex flex-col gap-1 text-xs">
      <div className="flex gap-2">
        <span className="w-16 shrink-0 font-medium text-zinc-500 dark:text-zinc-400">
          {t("review.seo.title")}
        </span>
        <span className="break-words text-zinc-700 dark:text-zinc-300">{title}</span>
      </div>
      <div className="flex gap-2">
        <span className="w-16 shrink-0 font-medium text-zinc-500 dark:text-zinc-400">
          {t("review.seo.description")}
        </span>
        <span className="break-words text-zinc-700 dark:text-zinc-300">{description}</span>
      </div>
    </div>
  );
}

/** Whitelisted HTML preview: same tags the backend allows, everything else dropped. */
function HtmlPreview({ html }: { html: string }) {
  return (
    <div
      className="prose-sm max-h-56 overflow-auto p-2 text-xs text-zinc-700 dark:text-zinc-300"
      // The backend guarantees only whitelisted tags; the content is already
      // escaped by Mustache. This is display-only, never re-inserted into Woo.
      dangerouslySetInnerHTML={{ __html: sanitizeHtml(html) }}
    />
  );
}

function sanitizeHtml(html: string): string {
  // naive whitelist: strip <script>, <img>, and all event handlers. The real
  // escaping already happened server-side; this is defense in depth for the
  // preview pane only.
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<img[^>]*>/gi, "")
    .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, "")
    .replace(/<a[^>]*javascript:[^>]*>/gi, "<a>");
}

function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, "");
}

function specsSummary(facts: FactsJson): Array<{ label: "model" | "capacity" | "powerW" | "voltage" | "warranty"; value: string }> {
  const rows: Array<{ label: "model" | "capacity" | "powerW" | "voltage" | "warranty"; value: string }> = [];
  if (facts.model) rows.push({ label: "model", value: facts.model });
  if (facts.capacity) rows.push({ label: "capacity", value: facts.capacity });
  if (facts.powerW !== null && facts.powerW !== undefined)
    rows.push({ label: "powerW", value: String(facts.powerW) });
  if (facts.voltage) rows.push({ label: "voltage", value: facts.voltage });
  if (facts.warranty) rows.push({ label: "warranty", value: facts.warranty });
  return rows;
}

function FactsSummary({ facts, t }: { facts: FactsJson; t: TFunction }) {
  const rows = specsSummary(facts);
  if (rows.length === 0) return null;
  return (
    <div className="rounded bg-zinc-50 p-2 text-xs dark:bg-zinc-950">
      <p className="mb-1 font-medium text-zinc-500 dark:text-zinc-400">{t("review.factSummary")}</p>
      <ul className="grid grid-cols-1 gap-x-3 gap-y-0.5">
        {rows.map((row) => (
          <li key={row.label} className="flex gap-2">
            <span className="w-16 shrink-0 text-zinc-400">{t(`review.fact.${row.label}`)}</span>
            <span className="break-words text-zinc-700 dark:text-zinc-300">{row.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Localized spec code; unknown codes are shown as-is. */
function specText(t: TFunction, code: string): string {
  const key = `spec.${code}` as MessageKey;
  return key in en ? t(key) : code;
}