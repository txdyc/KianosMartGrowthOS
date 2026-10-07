"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { canOperate, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type {
  JobStatus,
  JobStep,
  PipelineJob,
  PipelineRun,
  ReviewItem,
  RunStatus,
  WorkerStatus,
} from "@/lib/types";

const jobBadge: Record<JobStatus, string> = {
  SUCCEEDED: "rounded bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400",
  FAILED: "rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400",
  WAITING_EXECUTOR: "rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400",
  LEASED: "rounded bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-900/40 dark:text-blue-400",
  QUEUED: "rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400",
  CANCELLED: "rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400",
};

const runBadge: Record<RunStatus, string> = {
  RUNNING: "rounded bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-900/40 dark:text-blue-400",
  DONE: "rounded bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400",
  PARTIAL: "rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400",
};

const secondaryButton =
  "rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";

/**
 * Pipeline controls for the product detail page: worker status, the generate
 * button (with a confirm step while shots are incomplete), the latest run with
 * per-step jobs and retry, and the link to the review board.
 */
export function PipelinePanel({ productId, shotsComplete }: { productId: number; shotsComplete: boolean }) {
  const { t } = useI18n();
  const me = useMe();
  const operate = me !== null && canOperate(me.role);

  const [workers, setWorkers] = useState<WorkerStatus[] | null>(null);
  // undefined = still loading, null = 204 (no run yet)
  const [run, setRun] = useState<PipelineRun | null | undefined>(undefined);
  const [reviewCount, setReviewCount] = useState<number | null>(null);
  const [starting, setStarting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [retryingJobId, setRetryingJobId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback((): Promise<void> => {
    // Both feeds are informational; a failed poll keeps the previous data.
    const workersDone = apiFetch<WorkerStatus[]>("/api/v1/content/workers")
      .then((rows) => {
        setWorkers(rows);
      })
      .catch(() => {});
    const runDone = apiFetch<PipelineRun | undefined>(
      `/api/v1/content/products/${productId}/image-pipeline`,
    )
      .then(async (current) => {
        let count: number | null = null;
        if (current !== undefined && (current.status === "DONE" || current.status === "PARTIAL")) {
          const pending = await apiFetch<ReviewItem[]>(
            `/api/v1/content/assets?productId=${productId}&status=IN_REVIEW`,
          );
          count = pending.length;
        }
        setRun(current ?? null);
        setReviewCount(count);
      })
      .catch(() => {});
    return Promise.all([workersDone, runDone]).then(() => undefined);
  }, [productId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Poll while the latest run is active.
  useEffect(() => {
    if (run?.status !== "RUNNING") {
      return;
    }
    const timer = setInterval(() => {
      void refresh();
    }, 3000);
    return () => clearInterval(timer);
  }, [run, refresh]);

  function requestStart() {
    if (!shotsComplete) {
      setConfirming(true);
      return;
    }
    void start();
  }

  async function start() {
    setConfirming(false);
    setStarting(true);
    setError(null);
    try {
      await apiFetch<{ runId: number }>(`/api/v1/content/products/${productId}/image-pipeline`, {
        method: "POST",
      });
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setStarting(false);
    }
  }

  async function retry(jobId: number) {
    setRetryingJobId(jobId);
    setError(null);
    try {
      await apiFetch(`/api/v1/content/jobs/${jobId}/retry`, { method: "POST" });
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setRetryingJobId(null);
    }
  }

  const onlineWorker = workers?.find((w) => w.online) ?? null;
  const lastSeenAt =
    workers !== null && workers.length > 0
      ? workers.map((w) => w.lastSeenAt).sort().at(-1) ?? null
      : null;
  const comfyReady =
    onlineWorker !== null &&
    onlineWorker.capabilities.includes("COMFYUI") &&
    !onlineWorker.unavailable.includes("COMFYUI");

  const groups = new Map<JobStep, PipelineJob[]>();
  if (run != null) {
    for (const job of run.jobs) {
      const list = groups.get(job.step);
      if (list === undefined) {
        groups.set(job.step, [job]);
      } else {
        list.push(job);
      }
    }
  }

  const finished = run?.status === "DONE" || run?.status === "PARTIAL";

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {workers === null ? null : onlineWorker !== null ? (
            <>
              <span className="font-medium text-emerald-600 dark:text-emerald-400">
                {t("pipeline.workerOnline")}
              </span>
              <span aria-hidden className="text-zinc-400">·</span>
              <span className={comfyReady ? "text-zinc-600 dark:text-zinc-400" : "font-medium text-red-600 dark:text-red-400"}>
                {comfyReady ? t("pipeline.comfyAvailable") : t("pipeline.comfyUnavailable")}
              </span>
            </>
          ) : (
            <>
              <span className="font-medium text-red-600 dark:text-red-400">{t("pipeline.workerOffline")}</span>
              {lastSeenAt !== null ? (
                <>
                  <span aria-hidden className="text-zinc-400">·</span>
                  <span className="text-zinc-500 dark:text-zinc-400">
                    {t("pipeline.lastSeen", { time: new Date(lastSeenAt).toLocaleString() })}
                  </span>
                </>
              ) : null}
            </>
          )}
        </div>

        <div className="flex items-center gap-3">
          {operate ? (
            <button type="button" onClick={requestStart} disabled={starting} className={secondaryButton}>
              {starting ? t("pipeline.starting") : t("pipeline.generate")}
            </button>
          ) : null}
          {finished ? (
            <Link
              href={`/review?productId=${productId}`}
              className="text-sm font-medium text-blue-600 transition-colors hover:underline dark:text-blue-400"
            >
              {t("pipeline.reviewLink", { n: reviewCount ?? 0 })}
            </Link>
          ) : null}
        </div>
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {run === undefined ? null : run === null ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("pipeline.noRun")}</p>
      ) : (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="text-zinc-500 dark:text-zinc-400">{t("pipeline.latestRun")}:</span>
            <span className={runBadge[run.status]}>{t(`run.${run.status}`)}</span>
            <span className="text-xs text-zinc-500 dark:text-zinc-400">
              {new Date(run.createdAt).toLocaleString()}
            </span>
          </div>

          {[...groups.entries()].map(([step, jobs]) => (
            <div key={step} className="flex flex-col gap-1">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
                {t(`step.${step}`)}
              </h3>
              <ul className="flex flex-col gap-1">
                {jobs.map((job) => (
                  <li key={job.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    {job.variant !== null ? <span className="font-mono text-xs">{job.variant}</span> : null}
                    <span className={jobBadge[job.status]}>{t(`job.${job.status}`)}</span>
                    <span className="text-xs text-zinc-500 dark:text-zinc-400">
                      {t("pipeline.attempts", { n: job.attempts, m: job.maxAttempts })}
                    </span>
                    {job.error !== null ? (
                      <span className="text-xs text-red-600 dark:text-red-400">{job.error}</span>
                    ) : null}
                    {operate && job.status === "FAILED" ? (
                      <button
                        type="button"
                        onClick={() => void retry(job.id)}
                        disabled={retryingJobId !== null}
                        className="rounded border border-zinc-300 px-2 py-0.5 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
                      >
                        {t("pipeline.retry")}
                      </button>
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}

      {confirming ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          role="dialog"
          aria-modal="true"
          aria-label={t("pipeline.confirmTitle")}
        >
          <div className="w-full max-w-sm rounded-lg bg-white p-5 shadow-lg dark:bg-zinc-900">
            <h2 className="text-base font-semibold">{t("pipeline.confirmTitle")}</h2>
            <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">{t("pipeline.confirmBody")}</p>
            <div className="mt-4 flex justify-end gap-2">
              <button type="button" onClick={() => setConfirming(false)} className={secondaryButton}>
                {t("pipeline.confirmCancel")}
              </button>
              <button
                type="button"
                onClick={() => void start()}
                className="rounded-md bg-red-600 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-red-700"
              >
                {t("pipeline.confirmGo")}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}
