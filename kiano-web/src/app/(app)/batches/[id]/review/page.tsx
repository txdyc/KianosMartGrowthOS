"use client";

import { use, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey } from "@/i18n";
import { createActiveTimer } from "@/lib/activeTimer";
import { formatDuration } from "@/lib/batch";
import { REJECT_REASONS, actionFor } from "@/lib/keys";
import type { BatchProgress, ReviewItem, ReviewQueueItem, ReviewTimeStats } from "@/lib/types";

const primaryButton =
  "rounded-md bg-zinc-900 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";
const secondaryButton =
  "rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";
const dangerButton =
  "rounded-md bg-red-600 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-red-700 disabled:opacity-50";

/**
 * The keyboard-first review station (C6 Task 8): one SKU per screen with all
 * of its assets, flagged ones highlighted. Space approves, X opens the 1–7
 * reject reasons, G regenerates, A approves the remaining ones, N skips to the
 * next SKU. The session (kind ASSETS) tracks the active time like the fact
 * station.
 */
export default function BatchReviewStationPage({ params }: PageProps<"/batches/[id]/review">) {
  const { id } = use(params);
  const batchId = Number(id);
  const { t } = useI18n();

  const [queue, setQueue] = useState<ReviewQueueItem[] | null>(null);
  const [index, setIndex] = useState(0);
  const [assets, setAssets] = useState<ReviewItem[]>([]);
  const [assetIndex, setAssetIndex] = useState(0);
  const [rejecting, setRejecting] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [stats, setStats] = useState<ReviewTimeStats | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const timerRef = useRef(createActiveTimer({ beatMs: 15_000 }));
  const sessionRef = useRef<number | null>(null);

  const current = queue !== null && index < queue.length ? queue[index] : null;
  const asset = assets[Math.min(assetIndex, Math.max(0, assets.length - 1))];

  /**
   * Adopts one SKU's assets and opens its ASSETS session; null clears them
   * when the queue is exhausted.
   */
  const startSku = useCallback(
    (item: ReviewQueueItem | null) => {
      if (item === null) {
        setAssets([]);
        return;
      }
      setAssets(item.assets);
      setAssetIndex(0);
      setRejecting(false);
      setElapsed(0);
      timerRef.current.setVisible(true);
      void apiFetch<{ id: number }>("/api/v1/content/review-sessions", {
        method: "POST",
        body: { productId: item.productId, batchId, kind: "ASSETS" },
      })
        .then((session) => {
          sessionRef.current = session.id;
        })
        .catch(() => {});
    },
    [batchId],
  );

  const loadQueue = useCallback(() => {
    return apiFetch<ReviewQueueItem[]>(`/api/v1/content/batches/${batchId}/review-queue?limit=50`)
      .then((rows) => {
        setQueue(rows);
        setIndex(0);
        startSku(rows[0] ?? null);
      })
      .catch((err: unknown) => {
        setQueue([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [batchId, t, startSku]);

  const loadStats = useCallback(() => {
    return apiFetch<BatchProgress>(`/api/v1/content/batches/${batchId}/progress`)
      .then((progress) => setStats(progress.reviewTime))
      .catch(() => {});
  }, [batchId]);

  useEffect(() => {
    void loadQueue();
    void loadStats();
  }, [loadQueue, loadStats]);

  useEffect(() => {
    const interval = window.setInterval(() => {
      const seconds = timerRef.current.tick();
      if (seconds <= 0) {
        return;
      }
      setElapsed((prev) => prev + seconds);
      const session = sessionRef.current;
      if (session !== null) {
        void apiFetch(`/api/v1/content/review-sessions/${session}/heartbeat`, {
          method: "POST",
          body: { activeSeconds: seconds },
        }).catch(() => {});
      }
    }, 15_000);
    return () => window.clearInterval(interval);
  }, []);

  useEffect(() => {
    const timer = timerRef.current;
    const mark = () => timer.activity();
    const onVisibility = () => timer.setVisible(document.visibilityState === "visible");
    window.addEventListener("keydown", mark);
    window.addEventListener("mousemove", mark);
    window.addEventListener("mousedown", mark);
    window.addEventListener("scroll", mark, true);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("keydown", mark);
      window.removeEventListener("mousemove", mark);
      window.removeEventListener("mousedown", mark);
      window.removeEventListener("scroll", mark, true);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);

  const finishSession = useCallback(async (outcome: string) => {
    const session = sessionRef.current;
    if (session === null) {
      return;
    }
    sessionRef.current = null;
    const seconds = timerRef.current.flush();
    setElapsed((prev) => prev + seconds);
    await apiFetch(`/api/v1/content/review-sessions/${session}/finish`, {
      method: "POST",
      body: { activeSeconds: seconds, outcome },
    }).catch(() => {});
  }, []);

  const advance = useCallback(() => {
    const next = queue !== null ? queue[index + 1] : undefined;
    if (next !== undefined) {
      for (const row of next.assets) {
        const url = row.thumbUrl ?? row.imageUrl;
        if (url !== null) {
          const image = new window.Image();
          image.src = url;
        }
      }
    }
    setIndex(index + 1);
    startSku(next ?? null);
    void loadStats();
  }, [queue, index, startSku, loadStats]);

  const decide = useCallback(
    async (decision: "APPROVE" | "REJECT" | "REGENERATE", reasonCodes: string[] = []) => {
      if (asset === undefined || busy) {
        return;
      }
      setBusy(true);
      setError(null);
      try {
        await apiFetch(`/api/v1/content/assets/${asset.assetId}/review`, {
          method: "POST",
          body: { decision, reasonCodes, comment: null },
        });
        const remaining = assets.filter((row) => row.assetId !== asset.assetId);
        setAssets(remaining);
        setRejecting(false);
        setAssetIndex((current) => Math.min(current, Math.max(0, remaining.length - 1)));
        if (remaining.length === 0) {
          await finishSession("APPROVED_ALL");
          advance();
        }
      } catch (err) {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      } finally {
        setBusy(false);
      }
    },
    [asset, busy, assets, finishSession, advance, t],
  );

  const approveRemaining = useCallback(async () => {
    if (current === null || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/content/products/${current.productId}/review/approve-remaining`, {
        method: "POST",
      });
      await finishSession("APPROVED_ALL");
      advance();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }, [current, busy, finishSession, advance, t]);

  const skipSku = useCallback(async () => {
    if (current === null || busy) {
      return;
    }
    setBusy(true);
    try {
      await finishSession("SKIPPED");
      advance();
    } finally {
      setBusy(false);
    }
  }, [current, busy, finishSession, advance]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const action = actionFor(event, { station: "REVIEW", rejecting });
      if (action === null) {
        return;
      }
      if (action !== "CANCEL" && action !== "HELP") {
        event.preventDefault();
      }
      if (action.startsWith("REJECT_REASON_")) {
        const position = Number(action.slice("REJECT_REASON_".length)) - 1;
        const reason = REJECT_REASONS[position];
        if (reason !== undefined) {
          void decide("REJECT", [reason]);
        }
        return;
      }
      switch (action) {
        case "NEXT_ASSET":
          setAssetIndex((current) => (assets.length === 0 ? 0 : (current + 1) % assets.length));
          break;
        case "PREV_ASSET":
          setAssetIndex((current) =>
            assets.length === 0 ? 0 : (current - 1 + assets.length) % assets.length,
          );
          break;
        case "APPROVE":
          void decide("APPROVE");
          break;
        case "REJECT":
          setRejecting(true);
          break;
        case "REGENERATE":
          void decide("REGENERATE");
          break;
        case "APPROVE_REMAINING":
          void approveRemaining();
          break;
        case "NEXT_SKU":
          void skipSku();
          break;
        case "CANCEL":
          setRejecting(false);
          break;
        case "HELP":
          setHelpOpen((open) => !open);
          break;
        default:
          break;
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [assets.length, rejecting, decide, approveRemaining, skipSku]);

  if (queue === null) {
    return <p className="text-sm text-zinc-500">{t("batch.detail.loading")}</p>;
  }

  if (current === null) {
    return (
      <div className="space-y-3">
        <BackLink batchId={batchId}>{t("batch.detail.back")}</BackLink>
        <h1 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
          {t("batch.review.stationTitle")}
        </h1>
        <p className="text-sm text-zinc-500">{t("batch.review.done")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <BackLink batchId={batchId}>{t("batch.detail.back")}</BackLink>
          <h1 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.review.stationTitle")}
            <span className="ml-2 text-sm font-normal text-zinc-500">
              {current.sku} · {current.name}
            </span>
          </h1>
        </div>
        <div className="flex flex-wrap items-center gap-4 text-sm text-zinc-600 dark:text-zinc-400">
          <span>{t("batch.station.elapsed", { duration: formatDuration(elapsed) })}</span>
          {stats !== null ? (
            <span>
              {t("batch.station.average", { duration: formatDuration(stats.avgTotalSeconds) })} ·{" "}
              {t("batch.station.target", { n: stats.targetSeconds })}
            </span>
          ) : null}
          <span>{t("batch.station.remaining", { n: Math.max(0, (queue?.length ?? 0) - index) })}</span>
          <button type="button" className={secondaryButton} onClick={() => setHelpOpen((open) => !open)}>
            ?
          </button>
        </div>
      </div>

      {helpOpen ? (
        <p className="rounded-md border border-zinc-200 px-3 py-2 text-sm text-zinc-600 dark:border-zinc-800 dark:text-zinc-400">
          {t("batch.review.help")}
        </p>
      ) : null}
      {error !== null ? (
        <p className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">
          {error}
        </p>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[1fr_1.2fr]">
        <div className="space-y-2">
          <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.review.asset", { current: assetIndex + 1, total: assets.length })}
          </h2>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
            {assets.map((row, position) => (
              <button
                key={row.assetId}
                type="button"
                onClick={() => setAssetIndex(position)}
                className={`relative rounded-md border p-1 text-left ${
                  position === assetIndex
                    ? "border-zinc-900 dark:border-zinc-100"
                    : "border-zinc-200 dark:border-zinc-800"
                } ${row.flags.length > 0 ? "ring-2 ring-amber-400" : ""}`}
              >
                {row.thumbUrl ?? row.imageUrl ? (
                  /* eslint-disable-next-line @next/next/no-img-element -- presigned MinIO URLs */
                  <img
                    src={(row.thumbUrl ?? row.imageUrl) as string}
                    alt={row.specCode}
                    className="h-20 w-full rounded object-cover"
                  />
                ) : (
                  <div className="flex h-20 w-full items-center justify-center rounded bg-zinc-100 text-xs text-zinc-500 dark:bg-zinc-900">
                    {t(`spec.${row.specCode}` as MessageKey)}
                  </div>
                )}
                <span className="mt-1 block truncate text-[11px] text-zinc-600 dark:text-zinc-400">
                  {t(`spec.${row.specCode}` as MessageKey)}
                </span>
                {row.flags.length > 0 ? (
                  <span className="absolute right-1 top-1 rounded bg-amber-400 px-1 text-[10px] font-medium text-zinc-900">
                    {t("batch.review.flags", { n: row.flags.length })}
                  </span>
                ) : null}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-3">
          {asset === undefined ? null : (
            <div className="rounded-md border border-zinc-200 p-3 dark:border-zinc-800">
              <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-zinc-500">
                <span className="font-mono">
                  {t(`spec.${asset.specCode}` as MessageKey)} · {asset.variant ?? ""} · v
                  {asset.version}
                </span>
                {asset.flags.length > 0 ? (
                  <span className="rounded bg-amber-100 px-1.5 py-0.5 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">
                    {asset.flags.map((flag) => t(`precheck.${flag}`)).join(" · ")}
                  </span>
                ) : null}
              </div>
              {asset.kind === "TEXT" ? (
                <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded bg-zinc-50 p-2 text-sm dark:bg-zinc-900">
                  {asset.textBody}
                </pre>
              ) : asset.imageUrl !== null ? (
                /* eslint-disable-next-line @next/next/no-img-element -- presigned MinIO URLs */
                <img
                  src={asset.imageUrl}
                  alt={asset.fileName ?? asset.specCode}
                  className="max-h-96 w-full rounded object-contain"
                />
              ) : null}
            </div>
          )}

          {rejecting ? (
            <div className="rounded-md border border-red-300 p-3 dark:border-red-900">
              <p className="mb-2 text-sm text-zinc-700 dark:text-zinc-300">
                {t("batch.review.rejectPick")}
              </p>
              <div className="flex flex-wrap gap-2">
                {REJECT_REASONS.map((reason, position) => (
                  <button
                    key={reason}
                    type="button"
                    className={dangerButton}
                    disabled={busy}
                    onClick={() => void decide("REJECT", [reason])}
                  >
                    {position + 1}. {t(`reject.${reason}`)}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className={primaryButton}
              disabled={busy || asset === undefined}
              onClick={() => void decide("APPROVE")}
            >
              {t("batch.review.approve")}
            </button>
            <button
              type="button"
              className={dangerButton}
              disabled={busy || asset === undefined}
              onClick={() => setRejecting(true)}
            >
              {t("batch.review.reject")}
            </button>
            <button
              type="button"
              className={secondaryButton}
              disabled={busy || asset === undefined}
              onClick={() => void decide("REGENERATE")}
            >
              {t("batch.review.regenerate")}
            </button>
            <button
              type="button"
              className={secondaryButton}
              disabled={busy}
              onClick={() => void approveRemaining()}
            >
              {t("batch.review.approveRemaining")}
            </button>
            <button
              type="button"
              className={secondaryButton}
              disabled={busy}
              onClick={() => void skipSku()}
            >
              {t("batch.review.next")}
            </button>
            <span className="text-xs text-zinc-500">{t("batch.review.help")}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function BackLink({ batchId, children }: { batchId: number; children: React.ReactNode }) {
  return (
    <Link
      href={`/batches/${batchId}`}
      className="text-sm text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
    >
      ← {children}
    </Link>
  );
}