"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey } from "@/i18n";
import { en } from "@/i18n/en";
import type { ContentTier, ReviewItem, VideoRenderStatus } from "@/lib/types";
import { renderNotice } from "@/lib/videoRender";

/** The three short formats of every HERO SKU, in backend order. */
const VIDEO_TYPES = ["demo", "problem", "unboxing"] as const;

/** Friendly labels for the VIDEO_PRECONDITIONS missing entries. */
const PRECOND_LABEL: Record<string, MessageKey> = {
  NOT_HERO: "error.NOT_HERO",
  FACTS_NOT_LOCKED: "error.FACTS_NOT_LOCKED",
  POLICY_BADGES_MISSING: "error.POLICY_BADGES_MISSING",
  MUSIC_LIBRARY_EMPTY: "error.MUSIC_LIBRARY_EMPTY",
  PAGE_MAIN: "videos.missing.pageMain",
  PAGE_SCENE: "videos.missing.pageScene",
};

/**
 * C5 short-video panel for a single HERO product: one card per type with the
 * script and video status, a script generation and a render button. A 409
 * VIDEO_PRECONDITIONS lists what is still missing, item by item.
 */
export function VideosPanel({ productId, tier }: { productId: number; tier: ContentTier }) {
  const { t } = useI18n();
  const [assets, setAssets] = useState<ReviewItem[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [preconditions, setPreconditions] = useState<string[] | null>(null);
  const [renderStatus, setRenderStatus] = useState<VideoRenderStatus | null>(null);

  const load = useCallback(() => {
    // planning runs in a background task: its outcome is loaded with the assets
    void apiFetch<VideoRenderStatus | undefined>(
      `/api/v1/content/products/${productId}/videos/render-status`,
    )
      .then((status) => setRenderStatus(status ?? null))
      .catch(() => setRenderStatus(null));
    return apiFetch<ReviewItem[]>(`/api/v1/content/assets?productId=${productId}`)
      .then(setAssets)
      .catch((err: unknown) => {
        setAssets([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [productId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const notice = useMemo(() => renderNotice(renderStatus), [renderStatus]);

  // while a background render is queued or running, refresh every 5 s
  useEffect(() => {
    if (notice?.kind !== "running") {
      return;
    }
    const timer = setInterval(() => void load(), 5000);
    return () => clearInterval(timer);
  }, [notice, load]);

  const scripts = useMemo(() => latestByType(assets ?? [], "VIDEO_SCRIPT"), [assets]);
  const videos = useMemo(() => latestByType(assets ?? [], "VIDEO_AD"), [assets]);

  async function generateScript() {
    setBusy("script");
    setError(null);
    setPreconditions(null);
    try {
      await apiFetch(`/api/v1/content/products/${productId}/videos/script`, { method: "POST" });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(null);
    }
  }

  async function generateVideos() {
    setBusy("video");
    setError(null);
    setPreconditions(null);
    try {
      await apiFetch(`/api/v1/content/products/${productId}/videos/render`, { method: "POST" });
      await load();
    } catch (err) {
      const api = err instanceof ApiError ? err : null;
      if (api?.code === "VIDEO_PRECONDITIONS" && Array.isArray(api.details?.missing)) {
        setPreconditions((api.details.missing as unknown[]) as string[]);
      } else {
        setError(api !== null ? errorText(t, api) : t("error.INTERNAL_ERROR"));
      }
    } finally {
      setBusy(null);
    }
  }

  if (tier !== "HERO") {
    return (
      <div className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
        <h2 className="text-base font-semibold">{t("videos.title")}</h2>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("videos.heroOnly")}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-semibold">{t("videos.title")}</h2>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => void generateScript()}
            disabled={busy !== null}
            className="rounded-md border border-zinc-300 px-3 py-1 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            {busy === "script" ? t("loading") : t("videos.generateScript")}
          </button>
          <button
            type="button"
            onClick={() => void generateVideos()}
            disabled={busy !== null}
            className="rounded-md bg-zinc-900 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {busy === "video" ? t("loading") : t("videos.generateVideo")}
          </button>
        </div>
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {notice?.kind === "running" ? (
        <p className="text-sm text-zinc-600 dark:text-zinc-400">{t("videos.renderRunning")}</p>
      ) : null}
      {notice?.kind === "failed" ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {t("videos.renderFailed")} {codeText(t, notice.code)}
        </p>
      ) : null}

      {preconditions !== null ? (
        <div className="flex flex-col gap-1 rounded-md border border-amber-300 bg-amber-50 p-2 text-sm dark:border-amber-700/50 dark:bg-amber-900/20">
          <p className="font-medium text-amber-800 dark:text-amber-300">
            {t("videos.preconditions")}
          </p>
          <ul className="list-inside list-disc text-amber-700 dark:text-amber-300">
            {preconditions.map((missing) => (
              <li key={missing} className="text-xs">
                {missingText(t, missing)}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <ul className="flex flex-col gap-1">
        {VIDEO_TYPES.map((type) => {
          const script = scripts.get(type) ?? null;
          const video = videos.get(type) ?? null;
          return (
            <li
              key={type}
              className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded border border-zinc-100 px-2 py-1.5 text-sm dark:border-zinc-800"
            >
              <span className="w-20 shrink-0 font-mono text-xs text-zinc-600 dark:text-zinc-400">
                {variantText(t, type)}
              </span>
              <span className={statusBadge(script?.status)}>
                {t("videos.script")}: {script === null ? t("videos.noScript") : statusText(t, script.status)}
              </span>
              <span className={statusBadge(video?.status)}>
                {t("videos.video")}: {video === null ? t("videos.notRendered") : statusText(t, video.status)}
              </span>
              {video !== null && video.durationS !== null ? (
                <span className="text-xs text-zinc-500 dark:text-zinc-400">
                  {Number(video.durationS).toFixed(1)}s
                </span>
              ) : null}
              {video !== null && video.aiRatio !== null ? (
                <span className="text-xs text-zinc-500 dark:text-zinc-400">
                  {t("video.aiRatio")} {(Number(video.aiRatio) * 100).toFixed(1)}%
                </span>
              ) : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** The newest asset of every variant of one spec code. */
function latestByType(assets: ReviewItem[], specCode: string): Map<string, ReviewItem> {
  const latest = new Map<string, ReviewItem>();
  for (const asset of assets) {
    if (asset.specCode !== specCode || asset.variant === null) {
      continue;
    }
    const current = latest.get(asset.variant);
    if (current === undefined || asset.version > current.version) {
      latest.set(asset.variant, asset);
    }
  }
  return latest;
}

function statusText(t: ReturnType<typeof useI18n>["t"], status: string): string {
  const key = `asset.${status}` as MessageKey;
  return key in en ? t(key) : status;
}

function statusBadge(status: string | undefined): string {
  if (status === "APPROVED" || status === "PUBLISHED") {
    return "rounded bg-emerald-100 px-1.5 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400";
  }
  if (status === "IN_REVIEW") {
    return "rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400";
  }
  return "rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400";
}

/** Localized video type; unknown variants are shown as-is. */
function variantText(t: ReturnType<typeof useI18n>["t"], variant: string): string {
  const key = `video.type.${variant}` as MessageKey;
  return key in en ? t(key) : variant;
}

/** Friendly label for one VIDEO_PRECONDITIONS missing entry. */
function missingText(t: ReturnType<typeof useI18n>["t"], missing: string): string {
  if (missing.startsWith("VIDEO_SCRIPT:")) {
    return `${t("videos.missing.script")}: ${variantText(t, missing.slice("VIDEO_SCRIPT:".length))}`;
  }
  const labeled = PRECOND_LABEL[missing];
  if (labeled !== undefined) {
    return t(labeled);
  }
  if (/^V\d$/.test(missing)) {
    return `${t("videos.missing.footage")}: ${missing}`;
  }
  return missing;
}
/** A backend error code as text: its error.* translation when one exists, else the code. */
function codeText(t: ReturnType<typeof useI18n>["t"], code: string): string {
  const key = `error.${code}`;
  return key in en ? t(key as MessageKey) : code;
}
