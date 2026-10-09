"use client";

import { useState } from "react";
import { useI18n } from "@/i18n";
import type { MessageKey } from "@/i18n";
import { en } from "@/i18n/en";
import { EdlEditor } from "@/components/EdlEditor";
import { MAX_AI_RATIO } from "@/lib/edl";
import type { ReviewItem } from "@/lib/types";

const secondaryButton =
  "rounded-md border border-zinc-300 px-2 py-1 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";
const primaryButton =
  "rounded-md bg-zinc-900 px-2 py-1 text-xs font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900";

/**
 * Review card of a VIDEO_AD (C5 T13): playback with a music / no-music
 * switch (the no-music cut is the TikTok version), the AI-ratio badge, the
 * precheck flags, the H3 outcome, the three decisions and the inline trim
 * editor for the real footage.
 */
export function VideoAssetCard({
  item,
  focused,
  busy,
  onFocus,
  onApprove,
  onReject,
  onRegenerate,
  onReload,
}: {
  item: ReviewItem;
  focused: boolean;
  busy: boolean;
  onFocus: () => void;
  onApprove: () => void;
  onReject: () => void;
  onRegenerate: () => void;
  onReload: () => Promise<void>;
}) {
  const { t } = useI18n();
  const [noMusic, setNoMusic] = useState(false);
  const [editingCut, setEditingCut] = useState(false);
  const ratio = item.aiRatio === null ? null : Number(item.aiRatio);
  const overRatio = ratio !== null && ratio > MAX_AI_RATIO;
  const src = noMusic ? item.noMusicUrl : item.videoUrl;

  return (
    <div
      id={`review-asset-${item.assetId}`}
      onClick={onFocus}
      className={`flex flex-col gap-2 rounded-lg border bg-white p-3 dark:bg-zinc-900 ${
        item.flags.length > 0 ? "border-red-500" : "border-zinc-200 dark:border-zinc-800"
      } ${focused ? "ring-2 ring-blue-500" : ""} ${busy ? "opacity-50" : ""}`}
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-zinc-600 dark:text-zinc-400">
        <span className="font-medium text-zinc-800 dark:text-zinc-200">
          {variantText(t, item.variant ?? "")}
        </span>
        <span>v{item.version}</span>
        {item.durationS !== null ? <span>{Number(item.durationS).toFixed(1)}s</span> : null}
        {ratio !== null ? (
          <span
            className={
              overRatio
                ? "rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
                : "rounded bg-zinc-100 px-1.5 py-0.5 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300"
            }
            title={t("video.aiRatioHint")}
          >
            {t("video.aiRatio")} {(ratio * 100).toFixed(1)}%
          </span>
        ) : null}
        {item.flags.map((flag) => (
          <span
            key={flag}
            className="rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
          >
            {t(`precheck.${flag}`)}
          </span>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => setNoMusic((prev) => !prev)}
          className={secondaryButton}
        >
          {noMusic ? t("video.withMusic") : t("video.noMusic")}
        </button>
        {noMusic ? (
          <span className="text-xs text-zinc-500 dark:text-zinc-400">{t("video.tiktokHint")}</span>
        ) : null}
      </div>

      {src !== null ? (
        <video
          controls
          preload="metadata"
          src={src}
          poster={item.posterUrl ?? undefined}
          className="aspect-[9/16] w-full max-w-[220px] rounded bg-black"
        />
      ) : null}

      {h3Text(t, item) !== null ? (
        <p className="text-xs text-amber-700 dark:text-amber-400">{h3Text(t, item)}</p>
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={onApprove} disabled={busy} className={primaryButton}>
          {t("video.approve")}
        </button>
        <button type="button" onClick={onReject} disabled={busy} className={secondaryButton}>
          {t("video.reject")}
        </button>
        <button type="button" onClick={onRegenerate} disabled={busy} className={secondaryButton}>
          {t("video.regenerate")}
        </button>
        {item.edl !== null ? (
          <button
            type="button"
            onClick={() => setEditingCut((prev) => !prev)}
            disabled={busy}
            className={secondaryButton}
          >
            {editingCut ? t("review.cancel") : t("video.editCut")}
          </button>
        ) : null}
      </div>

      {editingCut && item.edl !== null ? (
        <EdlEditor item={item} edl={item.edl} onSaved={onReload} />
      ) : null}
    </div>
  );
}

/** The H3 line: the backend attempts that led to this cut, or the fallback. */
function h3Text(t: ReturnType<typeof useI18n>["t"], item: ReviewItem): string | null {
  if (item.h3 !== null && item.h3.attempts.length > 0) {
    const chain = item.h3.attempts
      .map((attempt) => {
        const backend = attempt.backend === null ? "—" : backendText(t, attempt.backend);
        const outcome = outcomeText(t, attempt.outcome);
        return attempt.reason !== null
          ? `${backend} ${outcome} (${reasonText(t, attempt.reason)})`
          : `${backend} ${outcome}`;
      })
      .join(" → ");
    return `${t("video.h3")}: ${chain}`;
  }
  if (item.h3Fallback !== null) {
    return `${t("video.h3Fallback")}: ${reasonText(t, item.h3Fallback.reason)}`;
  }
  return null;
}

/** Localized video type; unknown variants are shown as-is. */
function variantText(t: ReturnType<typeof useI18n>["t"], variant: string): string {
  const key = `video.type.${variant}` as MessageKey;
  return key in en ? t(key) : variant;
}

/** Localized H3 backend name. */
function backendText(t: ReturnType<typeof useI18n>["t"], backend: string): string {
  const key = `h3.backend.${backend}` as MessageKey;
  return key in en ? t(key) : backend;
}

/** Localized H3 outcome; unknown outcomes are shown as-is. */
function outcomeText(t: ReturnType<typeof useI18n>["t"], outcome: string): string {
  const key = `h3.outcome.${outcome}` as MessageKey;
  return key in en ? t(key) : outcome;
}

/** Localized escalation/fallback reason; unknown codes are shown as-is. */
function reasonText(t: ReturnType<typeof useI18n>["t"], reason: string): string {
  const key = `h3.reason.${reason}` as MessageKey;
  return key in en ? t(key) : reason;
}