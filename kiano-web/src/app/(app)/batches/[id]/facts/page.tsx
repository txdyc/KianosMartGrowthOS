"use client";

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import { FactsFields } from "@/components/FactsFields";
import { createActiveTimer } from "@/lib/activeTimer";
import { formatDuration } from "@/lib/batch";
import { actionFor } from "@/lib/keys";
import {
  LIST_FIELDS,
  REQUIRED_CONFIRMATIONS,
  SCALAR_FIELDS,
  cloneFacts,
  factsComplete,
  textToListField,
} from "@/lib/facts";
import type { ListField, ScalarField } from "@/lib/facts";
import type {
  BatchProgress,
  FactQueueItem,
  FactSheetView,
  FactsJson,
  ReviewTimeStats,
} from "@/lib/types";

const primaryButton =
  "rounded-md bg-zinc-900 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";
const secondaryButton =
  "rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";

/**
 * The keyboard-first fact station (C6 Task 8): one SKU at a time, the P5
 * plate and promo photos on the left, the draft fields with their provenance
 * on the right. Enter saves the draft, locks all six confirmations and loads
 * the next SKU; S skips. The active timer beats every 15 s and the session is
 * finished with LOCKED/SKIPPED (idle time is not counted).
 */
export default function BatchFactsStationPage({ params }: PageProps<"/batches/[id]/facts">) {
  const { id } = use(params);
  const batchId = Number(id);
  const { t } = useI18n();

  const [queue, setQueue] = useState<FactQueueItem[] | null>(null);
  const [index, setIndex] = useState(0);
  const [form, setForm] = useState<FactsJson | null>(null);
  const [edited, setEdited] = useState<Set<string>>(new Set());
  const [fieldIndex, setFieldIndex] = useState(0);
  const [imageIndex, setImageIndex] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [stats, setStats] = useState<ReviewTimeStats | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const timerRef = useRef(createActiveTimer({ beatMs: 15_000 }));
  const sessionRef = useRef<number | null>(null);

  const current = queue !== null && index < queue.length ? queue[index] : null;
  const fieldKeys = useMemo<readonly (ScalarField | ListField)[]>(
    () => [...SCALAR_FIELDS, ...LIST_FIELDS],
    [],
  );
  const currentField = fieldKeys[fieldIndex % fieldKeys.length];
  const images =
    current === null ? [] : [...current.plateUrls, ...current.promoUrls];

  /**
   * Opens the FACT session of one SKU and resets the station for it; null
   * clears the form when the queue is exhausted.
   */
  const startSku = useCallback(
    (item: FactQueueItem | null) => {
      if (item === null) {
        setForm(null);
        return;
      }
      setForm(cloneFacts(item.draft.facts));
      setEdited(new Set());
      setFieldIndex(0);
      setImageIndex(0);
      setElapsed(0);
      setNotice(null);
      timerRef.current.setVisible(true);
      void apiFetch<{ id: number }>("/api/v1/content/review-sessions", {
        method: "POST",
        body: { productId: item.productId, batchId, kind: "FACT" },
      })
        .then((session) => {
          sessionRef.current = session.id;
        })
        .catch(() => {});
    },
    [batchId],
  );

  const loadQueue = useCallback(() => {
    return apiFetch<FactQueueItem[]>(`/api/v1/content/batches/${batchId}/fact-queue?limit=50`)
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

  // Heartbeat every 15 s: only the active seconds are reported. Hiding the
  // page beats right away: after 60 s of silence the server counts a beat as 0.
  useEffect(() => {
    const beat = () => {
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
    };
    const interval = window.setInterval(beat, 15_000);
    const onHide = () => {
      if (document.visibilityState === "hidden") {
        beat();
      }
    };
    document.addEventListener("visibilitychange", onHide);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener("visibilitychange", onHide);
    };
  }, []);

  // Keyboard, mouse and scroll count as activity; a hidden tab pauses.
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
      for (const url of [...next.plateUrls, ...next.promoUrls]) {
        const image = new window.Image();
        image.src = url;
      }
    }
    setIndex(index + 1);
    startSku(next ?? null);
    void loadStats();
  }, [queue, index, startSku, loadStats]);

  const lock = useCallback(async () => {
    if (current === null || form === null || busy || !factsComplete(form)) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // the edits must be saved first: the lock confirms the newest version
      const manual = Object.fromEntries([...edited].map((field) => [field, "MANUAL"]));
      const saved = await apiFetch<FactSheetView>(
        `/api/v1/content/products/${current.productId}/facts/draft`,
        {
          method: "PUT",
          body: { facts: form, fieldSources: { ...current.fieldSources, ...manual } },
        },
      );
      await apiFetch(`/api/v1/content/products/${current.productId}/facts/lock`, {
        method: "POST",
        body: {
          draftVersion: saved.version,
          confirmedFields: [...REQUIRED_CONFIRMATIONS],
        },
      });
      await finishSession("LOCKED");
      setNotice(t("batch.facts.locked"));
      advance();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }, [current, form, busy, edited, finishSession, advance, t]);

  const skip = useCallback(async () => {
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
      const action = actionFor(event, {
        station: "FACTS",
        factsComplete: form !== null && factsComplete(form),
      });
      if (action === null) {
        return;
      }
      if (action !== "CANCEL" && action !== "HELP") {
        event.preventDefault();
      }
      switch (action) {
        case "NEXT_FIELD":
          setFieldIndex((current) => (current + 1) % fieldKeys.length);
          break;
        case "PREV_FIELD":
          setFieldIndex((current) => (current - 1 + fieldKeys.length) % fieldKeys.length);
          break;
        case "EDIT_FIELD":
          document.getElementById(`facts-field-${currentField}`)?.focus();
          break;
        case "CONFIRM_ALL":
          void lock();
          break;
        case "SKIP_SKU":
          void skip();
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
  }, [fieldKeys, currentField, form, lock, skip]);

  const setScalar = (field: ScalarField, value: string) => {
    setForm((current) => {
      if (current === null) {
        return current;
      }
      const next = { ...current, [field]: value };
      if (field === "powerW") {
        next.powerW = value === "" ? null : Number(value);
      }
      return next;
    });
    setEdited((current) => new Set(current).add(field));
  };

  const setList = (field: ListField, text: string) => {
    setForm((current) => (current === null ? current : { ...current, [field]: textToListField(text) }));
    setEdited((current) => new Set(current).add(field));
  };

  if (queue === null) {
    return <p className="text-sm text-zinc-500">{t("batch.detail.loading")}</p>;
  }

  if (current === null || form === null) {
    return (
      <div className="space-y-3">
        <BackLink batchId={batchId}>{t("batch.detail.back")}</BackLink>
        <h1 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
          {t("batch.facts.stationTitle")}
        </h1>
        <p className="text-sm text-zinc-500">{t("batch.facts.done")}</p>
      </div>
    );
  }

  const complete = factsComplete(form);
  const selectedImage = images[Math.min(imageIndex, images.length - 1)];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <BackLink batchId={batchId}>{t("batch.detail.back")}</BackLink>
          <h1 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.facts.stationTitle")}
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
          {t("batch.facts.help")}
        </p>
      ) : null}
      {notice !== null ? (
        <p className="text-sm text-emerald-600 dark:text-emerald-400">{notice}</p>
      ) : null}
      {error !== null ? (
        <p className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">
          {error}
        </p>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[1fr_1.2fr]">
        <div className="space-y-2">
          <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            {t("batch.facts.sources")}
          </h2>
          {images.length === 0 || selectedImage === undefined ? (
            <p className="rounded-md border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500 dark:border-zinc-700">
              {t("batch.facts.noImage")}
            </p>
          ) : (
            <>
              <a
                href={selectedImage}
                target="_blank"
                rel="noopener noreferrer"
                className="block"
                title={t("batch.facts.sources")}
              >
                {/* eslint-disable-next-line @next/next/no-img-element -- presigned MinIO URLs */}
                <img
                  src={selectedImage}
                  alt={current.sku}
                  className="w-full rounded-lg border border-zinc-200 object-contain dark:border-zinc-800"
                />
              </a>
              <div className="flex flex-wrap gap-2">
                {images.map((url, position) => (
                  <button
                    key={url}
                    type="button"
                    onClick={() => setImageIndex(position)}
                    className={
                      position === imageIndex
                        ? "rounded border-2 border-zinc-900 p-0.5 dark:border-zinc-100"
                        : "rounded border border-zinc-200 p-0.5 dark:border-zinc-800"
                    }
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element -- presigned MinIO URLs */}
                    <img src={url} alt="" className="h-14 w-14 object-cover" />
                  </button>
                ))}
              </div>
            </>
          )}
        </div>

        <div className="space-y-4">
          <FactsFields
            facts={form}
            fieldSources={current.fieldSources}
            disabled={busy}
            highlight={currentField}
            onScalar={setScalar}
            onList={setList}
          />
          <div className="flex flex-wrap items-center gap-3 text-sm text-zinc-500">
            <span>{t("batch.facts.field", { current: fieldIndex + 1, total: fieldKeys.length })}</span>
            <button
              type="button"
              className={primaryButton}
              disabled={busy || !complete}
              onClick={() => void lock()}
            >
              {t("batch.facts.confirmLock")}
            </button>
            <button type="button" className={secondaryButton} disabled={busy} onClick={() => void skip()}>
              {t("batch.facts.skip")}
            </button>
            <span className="text-xs">{t("batch.facts.help")}</span>
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