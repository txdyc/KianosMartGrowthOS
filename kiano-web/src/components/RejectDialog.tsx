"use client";

import { useState } from "react";
import type { FormEvent } from "react";
import { useI18n } from "@/i18n";
import type { RejectReason } from "@/lib/types";

const REASONS: RejectReason[] = [
  "PRODUCT_MISMATCH",
  "AI_ARTIFACT",
  "WRONG_FACT",
  "TEXT_ERROR",
  "STYLE",
  "LOW_QUALITY",
  "POLICY",
];

/**
 * Modal behind the R shortcut: pick at least one reject reason and an
 * optional comment. Mounted only while open, so the state resets per asset.
 */
export function RejectDialog({
  busy,
  onCancel,
  onSubmit,
}: {
  busy: boolean;
  onCancel: () => void;
  onSubmit: (reasonCodes: RejectReason[], comment: string | null) => void;
}) {
  const { t } = useI18n();
  const [selected, setSelected] = useState<RejectReason[]>([]);
  const [comment, setComment] = useState("");

  function toggle(reason: RejectReason) {
    setSelected((prev) =>
      prev.includes(reason) ? prev.filter((r) => r !== reason) : [...prev, reason],
    );
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selected.length === 0 || busy) {
      return;
    }
    const trimmed = comment.trim();
    onSubmit(selected, trimmed === "" ? null : trimmed);
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t("review.rejectTitle")}
      onKeyDown={(e) => {
        if (e.key === "Escape" && !busy) {
          onCancel();
        }
      }}
      className="fixed inset-0 z-10 flex items-center justify-center bg-black/40 p-4"
    >
      <form
        onSubmit={submit}
        className="w-full max-w-md rounded-lg bg-white p-4 shadow-lg dark:bg-zinc-900"
      >
        <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
          {t("review.rejectTitle")}
        </h2>

        <fieldset className="mt-3 flex flex-col gap-1.5">
          <legend className="text-sm text-zinc-600 dark:text-zinc-400">
            {t("review.rejectReasons")}
          </legend>
          {REASONS.map((reason, index) => (
            <label key={reason} className="flex items-center gap-2 text-sm text-zinc-800 dark:text-zinc-200">
              <input
                type="checkbox"
                checked={selected.includes(reason)}
                onChange={() => toggle(reason)}
                autoFocus={index === 0}
                disabled={busy}
              />
              {t(`reject.${reason}`)}
            </label>
          ))}
        </fieldset>

        <label className="mt-3 flex flex-col gap-1 text-sm">
          <span className="text-zinc-600 dark:text-zinc-400">{t("review.comment")}</span>
          <textarea
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            rows={2}
            disabled={busy}
            className="rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400"
          />
        </label>

        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            {t("review.cancel")}
          </button>
          <button
            type="submit"
            disabled={selected.length === 0 || busy}
            className="rounded-md bg-red-600 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-red-700 disabled:opacity-50"
          >
            {t("review.rejectSubmit")}
          </button>
        </div>
      </form>
    </div>
  );
}
