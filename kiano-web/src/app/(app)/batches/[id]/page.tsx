"use client";

import { use, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import { isOwner, useMe } from "@/lib/me";
import {
  PUBLISH_INTERVAL_SECONDS,
  blockedReasonKey,
  formatDuration,
  funnel,
  reviewTimeVerdict,
  selectableForProduction,
  stageLabelKey,
} from "@/lib/batch";
import type { BatchProgress, ProductionResult } from "@/lib/types";

const primaryButton =
  "rounded-md bg-zinc-900 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";
const secondaryButton =
  "rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";

/**
 * The batch dashboard (C6 Task 7): polls the progress snapshot every 10 s and
 * shows the stage funnel, the three ETAs, the AI cost and GPU time used, the
 * review-time card (target 180 s), the blocked SKUs with their reason and the
 * pause/resume/cancel actions. Only the OWNER sees the bulk production block:
 * the READY SKUs are selected and confirmed with the estimated duration.
 */
export default function BatchDetailPage({ params }: PageProps<"/batches/[id]">) {
  const { id } = use(params);
  const batchId = Number(id);
  const { t, locale } = useI18n();
  const me = useMe();
  const owner = me !== null && isOwner(me.role);

  const [progress, setProgress] = useState<BatchProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [productionSelection, setProductionSelection] = useState<Set<number>>(new Set());
  const [confirmingProduction, setConfirmingProduction] = useState(false);
  const [productionResult, setProductionResult] = useState<ProductionResult | null>(null);

  const load = useCallback(() => {
    return apiFetch<BatchProgress>(`/api/v1/content/batches/${batchId}/progress`)
      .then(setProgress)
      .catch((err: unknown) => {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [batchId, t]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), 10_000);
    return () => clearInterval(timer);
  }, [load]);

  const funnelRows = useMemo(
    () => (progress === null ? [] : funnel(progress.stageCounts)),
    [progress],
  );
  const blockedItems = useMemo(
    () => (progress === null ? [] : progress.items.filter((item) => item.blockedReason !== null)),
    [progress],
  );
  const selectable = useMemo(
    () => (progress === null ? [] : selectableForProduction(progress.items)),
    [progress],
  );
  const chosen = useMemo(
    () => selectable.filter((productId) => productionSelection.has(productId)),
    [selectable, productionSelection],
  );

  async function act(path: string, body?: Record<string, unknown>) {
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/content/batches/${batchId}${path}`, { method: "POST", body });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  async function publishProduction() {
    setBusy(true);
    setError(null);
    try {
      const result = await apiFetch<ProductionResult>(
        `/api/v1/content/batches/${batchId}/publish-production`,
        { method: "POST", body: { productIds: chosen } },
      );
      setProductionResult(result);
      setConfirmingProduction(false);
      setProductionSelection(new Set());
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  if (progress === null) {
    return (
      <div>
        <Link href="/batches" className="text-sm text-zinc-500 hover:text-zinc-900">
          {t("batch.detail.back")}
        </Link>
        <p className="mt-4 text-sm text-zinc-500">{t("batch.detail.loading")}</p>
        {error !== null ? <p className="mt-2 text-sm text-red-600">{error}</p> : null}
      </div>
    );
  }

  const batch = progress.batch;
  const verdict = reviewTimeVerdict(progress.reviewTime);
  const verdictColor =
    verdict === "ON_TARGET"
      ? "text-emerald-600 dark:text-emerald-400"
      : verdict === "OVER"
        ? "text-red-600 dark:text-red-400"
        : "text-zinc-500";

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link href="/batches" className="text-sm text-zinc-500 hover:text-zinc-900">
            {t("batch.detail.back")}
          </Link>
          <h1 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
            {batch.name}
            <span className="ml-2 text-sm font-normal text-zinc-500">
              {t(`batch.status.${batch.status}`)}
            </span>
            <span className="ml-2 text-sm font-normal text-zinc-500">
              {t("batch.list.items", { n: batch.itemCount })}
            </span>
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <Link href={`/batches/${batchId}/facts`} className={secondaryButton}>
            {t("batch.detail.factsStation")}
          </Link>
          <Link href={`/batches/${batchId}/review`} className={secondaryButton}>
            {t("batch.detail.reviewStation")}
          </Link>
          {batch.status === "RUNNING" ? (
            <button
              type="button"
              className={secondaryButton}
              disabled={busy}
              onClick={() => void act("/pause")}
            >
              {t("batch.detail.pause")}
            </button>
          ) : null}
          {batch.status === "PAUSED" ? (
            <button
              type="button"
              className={secondaryButton}
              disabled={busy}
              onClick={() => void act("/resume")}
            >
              {t("batch.detail.resume")}
            </button>
          ) : null}
          {batch.status === "RUNNING" || batch.status === "PAUSED" ? (
            confirmingCancel ? (
              <>
                <button
                  type="button"
                  className={primaryButton}
                  disabled={busy}
                  onClick={() => void act("/cancel")}
                >
                  {t("batch.detail.cancel")}
                </button>
                <button
                  type="button"
                  className={secondaryButton}
                  onClick={() => setConfirmingCancel(false)}
                >
                  {t("batch.detail.productionCancel")}
                </button>
              </>
            ) : (
              <button
                type="button"
                className={secondaryButton}
                onClick={() => setConfirmingCancel(true)}
              >
                {t("batch.detail.cancel")}
              </button>
            )
          ) : null}
        </div>
      </div>

      {error !== null ? (
        <p className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">
          {error}
        </p>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-3">
        <section className="rounded-md border border-zinc-200 p-4 dark:border-zinc-800">
          <h2 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.detail.funnel")}
          </h2>
          <ul className="space-y-1">
            {funnelRows.map((row) => (
              <li key={row.stage} className="flex items-center justify-between gap-2 text-sm">
                <span className="text-zinc-700 dark:text-zinc-300">
                  {t(stageLabelKey(row.stage))}
                </span>
                <span className="flex items-baseline gap-2">
                  <span className="font-medium text-zinc-900 dark:text-zinc-100">{row.count}</span>
                  <span className="text-xs text-zinc-500">
                    {t("batch.detail.reached", { n: row.cumulative })}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section className="rounded-md border border-zinc-200 p-4 dark:border-zinc-800">
          <h2 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.detail.etaGpu")}
          </h2>
          <dl className="space-y-2 text-sm">
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.etaGpu")}</dt>
              <dd className="font-medium text-zinc-900 dark:text-zinc-100">
                {formatDuration(progress.eta.gpuSeconds)}
              </dd>
            </div>
            {batch.nightOnly ? (
              <div className="flex justify-between gap-2">
                <dt className="text-zinc-500">{t("batch.detail.etaGpuFinish")}</dt>
                <dd className="text-zinc-900 dark:text-zinc-100">
                  {new Date(progress.eta.gpuFinishAt).toLocaleString(
                    locale === "zh" ? "zh-CN" : "en-GB",
                  )}
                </dd>
              </div>
            ) : null}
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.etaLlm")}</dt>
              <dd className="text-zinc-900 dark:text-zinc-100">
                {formatDuration(progress.eta.llmSeconds)}
              </dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.etaHuman")}</dt>
              <dd className="text-zinc-900 dark:text-zinc-100">
                {formatDuration(progress.eta.humanSeconds)}
              </dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.llmCost")}</dt>
              <dd className="text-zinc-900 dark:text-zinc-100">
                US$ {Number(progress.llmCostUsd).toFixed(2)}
              </dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.gpuUsed")}</dt>
              <dd className="text-zinc-900 dark:text-zinc-100">
                {formatDuration(progress.gpuSecondsUsed)}
              </dd>
            </div>
          </dl>
        </section>

        <section className="rounded-md border border-zinc-200 p-4 dark:border-zinc-800">
          <h2 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.detail.reviewTitle")}
          </h2>
          <dl className="space-y-2 text-sm">
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.reviewAvg")}</dt>
              <dd className="font-medium text-zinc-900 dark:text-zinc-100">
                {formatDuration(progress.reviewTime.avgTotalSeconds)}
              </dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">{t("batch.detail.reviewP90")}</dt>
              <dd className="text-zinc-900 dark:text-zinc-100">
                {formatDuration(progress.reviewTime.p90TotalSeconds)}
              </dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt className="text-zinc-500">
                {t("batch.detail.reviewTarget", { n: progress.reviewTime.targetSeconds })}
              </dt>
              <dd className={`font-medium ${verdictColor}`}>
                {t(`batch.detail.reviewVerdict.${verdict}`)}
              </dd>
            </div>
            <div className="text-xs text-zinc-500">{progress.reviewTime.skuCount} SKU</div>
          </dl>
        </section>
      </div>

      <section className="rounded-md border border-zinc-200 p-4 dark:border-zinc-800">
        <h2 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
          {t("batch.detail.blockedTitle")}
        </h2>
        {blockedItems.length === 0 ? (
          <p className="text-sm text-zinc-500">—</p>
        ) : (
          <ul className="space-y-2">
            {blockedItems.map((item) => (
              <li
                key={item.productId}
                className="flex flex-wrap items-start justify-between gap-2 rounded-md border border-zinc-100 px-3 py-2 dark:border-zinc-900"
              >
                <div>
                  <div className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                    {item.sku ?? item.productId}
                  </div>
                  <div className="text-sm text-zinc-600 dark:text-zinc-400">
                    {item.blockedReason === null ? null : t(blockedReasonKey(item.blockedReason))}
                  </div>
                  <div className="text-xs text-zinc-500">
                    {Object.entries(item.blockedDetails ?? {})
                      .filter(([, value]) => typeof value === "string" || Array.isArray(value))
                      .map(([key, value]) =>
                        `${key}: ${Array.isArray(value) ? value.join(", ") : String(value)}`,
                      )
                      .join(" · ")}
                  </div>
                </div>
                {item.stagingStatus === "FAILED" ? (
                  <button
                    type="button"
                    className={secondaryButton}
                    disabled={busy}
                    onClick={() => void act(`/items/${item.productId}/retry-staging`)}
                  >
                    {t("batch.detail.retryStaging")}
                  </button>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>

      {owner ? (
        <section className="rounded-md border border-zinc-200 p-4 dark:border-zinc-800">
          <h2 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.detail.productionTitle")}
          </h2>
          {selectable.length === 0 ? (
            <p className="text-sm text-zinc-500">—</p>
          ) : (
            <>
              <p className="mb-2 text-sm text-zinc-500">{t("batch.detail.productionSelect")}</p>
              <ul className="mb-3 grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
                {progress.items
                  .filter((item) => selectable.includes(item.productId))
                  .map((item) => (
                    <li key={item.productId} className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={productionSelection.has(item.productId)}
                        onChange={() =>
                          setProductionSelection((current) => {
                            const next = new Set(current);
                            if (next.has(item.productId)) {
                              next.delete(item.productId);
                            } else {
                              next.add(item.productId);
                            }
                            return next;
                          })
                        }
                      />
                      {item.sku ?? item.productId}
                    </li>
                  ))}
              </ul>
              {confirmingProduction ? (
                <div className="flex flex-wrap items-center gap-3">
                  <span className="text-sm text-zinc-700 dark:text-zinc-300">
                    {t("batch.detail.productionConfirm", {
                      n: chosen.length,
                      duration: formatDuration(chosen.length * PUBLISH_INTERVAL_SECONDS),
                    })}
                  </span>
                  <button
                    type="button"
                    className={primaryButton}
                    disabled={busy || chosen.length === 0}
                    onClick={() => void publishProduction()}
                  >
                    {t("batch.detail.productionSubmit")}
                  </button>
                  <button
                    type="button"
                    className={secondaryButton}
                    onClick={() => setConfirmingProduction(false)}
                  >
                    {t("batch.detail.productionCancel")}
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  className={primaryButton}
                  disabled={busy || chosen.length === 0}
                  onClick={() => setConfirmingProduction(true)}
                >
                  {t("batch.detail.productionSubmit")}
                </button>
              )}
              {productionResult !== null ? (
                <p className="mt-3 text-sm text-zinc-600 dark:text-zinc-400">
                  {t("batch.detail.productionQueued", { n: productionResult.queued.length })} ·{" "}
                  {t("batch.detail.productionRejected", {
                    n: Object.keys(productionResult.rejected).length,
                  })}
                </p>
              ) : null}
            </>
          )}
        </section>
      ) : null}
    </div>
  );
}