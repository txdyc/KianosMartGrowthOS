"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey } from "@/i18n";
import { adCopyStatuses, adHookVariants, adRenderIssues } from "@/lib/ads";
import type { AdRenderStatus, ContentTier, ReviewItem } from "@/lib/types";

const hookTitleKey = (hook: string): MessageKey => `ads.hook.${hook}` as MessageKey;

/** Friendly label for one AD_PRECONDITIONS missing entry; unknown → raw code. */
const PRECOND_LABEL: Record<string, MessageKey> = {
  NOT_HERO: "error.NOT_HERO",
  FACTS_NOT_LOCKED: "error.FACTS_NOT_LOCKED",
  POLICY_BADGES_MISSING: "error.POLICY_BADGES_MISSING",
  PRICE_MISSING: "error.PRICE_MISSING",
  DEMO_FRAME_UNAVAILABLE: "error.DEMO_FRAME_UNAVAILABLE",
  AD_BASE_MISSING: "error.AD_BASE_MISSING",
  AD_COPY_MISSING: "error.AD_COPY_MISSING",
  TEMPLATE_NOT_FOUND: "error.TEMPLATE_NOT_FOUND",
};

/**
 * C4a static-ad panel for a single product: per-hook AD_COPY status, copy
 * generation (all or one hook) and image rendering.
 */
export function AdsPanel({ productId, tier }: { productId: number; sku: string; tier: ContentTier }) {
  const { t } = useI18n();
  const [assets, setAssets] = useState<ReviewItem[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [preconditions, setPreconditions] = useState<string[] | null>(null);
  const [renderStatus, setRenderStatus] = useState<AdRenderStatus | null>(null);

  const load = useCallback(() => {
    // the latest render outcome is reloaded with the assets so skipped images show
    void apiFetch<AdRenderStatus | undefined>(
      `/api/v1/content/products/${productId}/ads/render-status`,
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

  const statuses = useMemo(() => adCopyStatuses(assets ?? []), [assets]);
  const renderIssues = useMemo(() => adRenderIssues(renderStatus), [renderStatus]);

  async function generateAll() {
    setBusy("copy-all");
    setError(null);
    setPreconditions(null);
    try {
      await apiFetch(`/api/v1/content/products/${productId}/ads/copy`, { method: "POST" });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(null);
    }
  }

  async function regenerateHook(hook: string) {
    setBusy(`copy:${hook}`);
    setError(null);
    setPreconditions(null);
    try {
      await apiFetch(`/api/v1/content/products/${productId}/ads/copy`, {
        method: "POST",
        body: { onlyHook: hook },
      });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(null);
    }
  }

  async function renderHook(hook: string) {
    setBusy(`render:${hook}`);
    setError(null);
    setPreconditions(null);
    try {
      await apiFetch(`/api/v1/content/products/${productId}/ads/render`, {
        method: "POST",
        body: { variants: adHookVariants(hook) },
      });
      await load();
    } catch (err) {
      const api = err instanceof ApiError ? err : null;
      if (api?.code === "AD_PRECONDITIONS" && Array.isArray(api.details?.missing)) {
        setPreconditions((api.details.missing as unknown[]) as string[]);
      } else {
        setError(api ? errorText(t, api) : t("error.INTERNAL_ERROR"));
      }
    } finally {
      setBusy(null);
    }
  }

  if (tier !== "HERO") {
    return (
      <div className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
        <h2 className="text-base font-semibold">{t("ads.title")}</h2>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("ads.heroOnly")}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-semibold">{t("ads.title")}</h2>
        <button
          type="button"
          onClick={() => void generateAll()}
          disabled={busy !== null}
          className="rounded-md bg-zinc-900 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {busy === "copy-all" ? t("loading") : t("ads.generateCopy")}
        </button>
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {preconditions !== null ? (
        <div className="flex flex-col gap-1 rounded-md border border-amber-300 bg-amber-50 p-2 text-sm dark:border-amber-700/50 dark:bg-amber-900/20">
          <p className="font-medium text-amber-800 dark:text-amber-300">{t("ads.preconditions")}</p>
          <ul className="list-inside list-disc text-amber-700 dark:text-amber-300">
            {preconditions.map((missing) => (
              <li key={missing} className="font-mono text-xs">
                {preconditionText(t, missing)}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {renderIssues.length > 0 ? (
        <div className="flex flex-col gap-1 rounded-md border border-amber-300 bg-amber-50 p-2 text-sm dark:border-amber-700/50 dark:bg-amber-900/20">
          <p className="font-medium text-amber-800 dark:text-amber-300">{t("ads.renderIssues")}</p>
          <ul className="list-inside list-disc text-amber-700 dark:text-amber-300">
            {renderIssues.map((issue) => (
              <li key={`${issue.variant ?? "task"}:${issue.code}`} className="text-xs">
                {issue.variant !== null ? (
                  <span className="font-mono">{issue.variant} — </span>
                ) : null}
                {preconditionText(t, issue.code)}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <ul className="flex flex-col gap-1">
        {statuses.map(({ hook, asset }) => {
          const getting = busy === `copy:${hook}`;
          const rendering = busy === `render:${hook}`;
          return (
            <li
              key={hook}
              className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded border border-zinc-100 px-2 py-1.5 text-sm dark:border-zinc-800"
            >
              <span className="font-mono text-xs text-zinc-600 dark:text-zinc-400">
                {t(hookTitleKey(hook))}
              </span>
              <span className={statusBadge(asset)}>{statusText(t, asset)}</span>
              <span className="ml-auto flex gap-2">
                <button
                  type="button"
                  onClick={() => void regenerateHook(hook)}
                  disabled={busy !== null}
                  className="rounded border border-zinc-300 px-2 py-0.5 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
                >
                  {getting ? t("loading") : t("ads.regenerateHook")}
                </button>
                <button
                  type="button"
                  onClick={() => void renderHook(hook)}
                  disabled={busy !== null}
                  className="rounded border border-zinc-300 px-2 py-0.5 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
                >
                  {rendering ? t("loading") : t("ads.render")}
                </button>
              </span>
            </li>
          );
        })}
      </ul>
      {assets !== null && statuses.length === 0 ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("ads.missing")}</p>
      ) : null}
    </div>
  );
}

function statusText(
  t: ReturnType<typeof useI18n>["t"],
  asset: ReviewItem | null,
): string {
  if (asset === null) {
    return t("ads.missing");
  }
  if (asset.status === "APPROVED") {
    return t("ads.approved");
  }
  if (asset.status === "IN_REVIEW") {
    return t("ads.inReview");
  }
  return t("ads.generated");
}

function statusBadge(asset: ReviewItem | null): string {
  if (asset?.status === "APPROVED") {
    return "rounded bg-emerald-100 px-1.5 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400";
  }
  if (asset?.status === "IN_REVIEW") {
    return "rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400";
  }
  return "rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400";
}

function preconditionText(
  t: ReturnType<typeof useI18n>["t"],
  missing: string,
): string {
  const labeled = PRECOND_LABEL[missing];
  if (labeled !== undefined) {
    return t(labeled);
  }
  // e.g. "AD_COPY:pricehook", "PAGE_MAIN", "PAGE_SCENE", "V1"
  return missing;
}