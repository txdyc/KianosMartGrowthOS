"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { BatchCandidate, BatchView } from "@/lib/types";

const primaryButton =
  "rounded-md bg-zinc-900 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";
const secondaryButton =
  "rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";

/**
 * The batch list and the creation page (C6 Task 7): the left column lists the
 * batches, the right one builds a new batch from the STANDARD candidates -
 * filter by SKU/name and shot completeness, select all shown, night-only flag.
 */
export default function BatchesPage() {
  const { t } = useI18n();
  const [batches, setBatches] = useState<BatchView[] | null>(null);
  const [candidates, setCandidates] = useState<BatchCandidate[] | null>(null);
  const [name, setName] = useState("");
  const [nightOnly, setNightOnly] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [onlyComplete, setOnlyComplete] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    return Promise.all([
      apiFetch<BatchView[]>("/api/v1/content/batches"),
      apiFetch<BatchCandidate[]>("/api/v1/content/batches/candidates"),
    ])
      .then(([list, rows]) => {
        setBatches(list);
        setCandidates(rows);
      })
      .catch((err: unknown) => {
        setBatches([]);
        setCandidates([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const shown = useMemo(() => {
    const needle = keyword.trim().toLowerCase();
    return (candidates ?? []).filter(
      (row) =>
        (!onlyComplete || row.shotsComplete) &&
        (needle === "" ||
          row.sku.toLowerCase().includes(needle) ||
          row.name.toLowerCase().includes(needle)),
    );
  }, [candidates, keyword, onlyComplete]);

  const selectable = useMemo(
    () => shown.filter((row) => row.activeBatchId === null),
    [shown],
  );

  function toggle(productId: number, active: boolean) {
    if (active) {
      return;
    }
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(productId)) {
        next.delete(productId);
      } else {
        next.add(productId);
      }
      return next;
    });
  }

  async function create() {
    setBusy(true);
    setError(null);
    try {
      await apiFetch<BatchView>("/api/v1/content/batches", {
        method: "POST",
        body: { name, productIds: [...selected], nightOnly },
      });
      setName("");
      setNightOnly(false);
      setSelected(new Set());
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  const canCreate = name.trim().length > 0 && selected.size > 0 && !busy;

  return (
    <div className="grid gap-8 lg:grid-cols-[18rem_1fr]">
      <section>
        <h1 className="mb-4 text-lg font-semibold text-zinc-900 dark:text-zinc-100">
          {t("batch.list.title")}
        </h1>
        {batches === null ? (
          <p className="text-sm text-zinc-500">{t("batch.detail.loading")}</p>
        ) : batches.length === 0 ? (
          <p className="text-sm text-zinc-500">{t("batch.list.empty")}</p>
        ) : (
          <ul className="space-y-2">
            {batches.map((batch) => (
              <li key={batch.id}>
                <Link
                  href={`/batches/${batch.id}`}
                  className="block rounded-md border border-zinc-200 px-3 py-2 transition-colors hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-900"
                >
                  <span className="flex items-center justify-between gap-2">
                    <span className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                      {batch.name}
                    </span>
                    <span className="text-xs text-zinc-500">
                      {t(`batch.status.${batch.status}`)}
                    </span>
                  </span>
                  <span className="mt-1 flex items-center gap-3 text-xs text-zinc-500">
                    <span>{t("batch.list.items", { n: batch.itemCount })}</span>
                    {batch.nightOnly ? <span>{t("batch.list.nightOnly")}</span> : null}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h1 className="mb-4 text-lg font-semibold text-zinc-900 dark:text-zinc-100">
          {t("batch.create.title")}
        </h1>
        {error !== null ? (
          <p className="mb-3 rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">
            {error}
          </p>
        ) : null}
        <div className="mb-4 space-y-3">
          <label className="block text-sm">
            <span className="mb-1 block text-zinc-700 dark:text-zinc-300">
              {t("batch.create.name")}
            </span>
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              className="w-full rounded-md border border-zinc-300 px-3 py-1 text-sm dark:border-zinc-700 dark:bg-zinc-900"
            />
          </label>
          <label className="flex items-center gap-2 text-sm text-zinc-700 dark:text-zinc-300">
            <input
              type="checkbox"
              checked={nightOnly}
              onChange={(event) => setNightOnly(event.target.checked)}
            />
            {t("batch.create.nightOnly")}
          </label>
          <div className="flex flex-wrap items-center gap-3">
            <input
              value={keyword}
              placeholder={t("batch.create.filter")}
              onChange={(event) => setKeyword(event.target.value)}
              className="rounded-md border border-zinc-300 px-3 py-1 text-sm dark:border-zinc-700 dark:bg-zinc-900"
            />
            <label className="flex items-center gap-2 text-sm text-zinc-700 dark:text-zinc-300">
              <input
                type="checkbox"
                checked={onlyComplete}
                onChange={(event) => setOnlyComplete(event.target.checked)}
              />
              {t("batch.create.onlyComplete")}
            </label>
            <button
              type="button"
              className={secondaryButton}
              onClick={() => setSelected(new Set(selectable.map((row) => row.productId)))}
            >
              {t("batch.create.selectAll")}
            </button>
            <span className="text-sm text-zinc-500">
              {t("batch.create.selected", { n: selected.size })}
            </span>
          </div>
        </div>

        {shown.length === 0 ? (
          <p className="text-sm text-zinc-500">{t("batch.create.noCandidates")}</p>
        ) : (
          <div className="overflow-x-auto rounded-md border border-zinc-200 dark:border-zinc-800">
            <table className="w-full text-left text-sm">
              <thead className="bg-zinc-50 text-xs uppercase text-zinc-500 dark:bg-zinc-900">
                <tr>
                  <th className="px-3 py-2" />
                  <th className="px-3 py-2">SKU</th>
                  <th className="px-3 py-2">{t("nav.products")}</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {shown.map((row) => {
                  const inBatch = row.activeBatchId !== null;
                  return (
                    <tr
                      key={row.productId}
                      className="border-t border-zinc-100 dark:border-zinc-900"
                    >
                      <td className="px-3 py-2">
                        <input
                          type="checkbox"
                          checked={selected.has(row.productId)}
                          disabled={inBatch}
                          onChange={() => toggle(row.productId, inBatch)}
                          aria-label={row.sku}
                        />
                      </td>
                      <td className="px-3 py-2 font-medium text-zinc-900 dark:text-zinc-100">
                        {row.sku}
                      </td>
                      <td className="px-3 py-2 text-zinc-600 dark:text-zinc-400">{row.name}</td>
                      <td className="px-3 py-2 text-xs text-zinc-500">
                        {inBatch
                          ? t("batch.create.inBatch", { id: row.activeBatchId ?? 0 })
                          : row.shotsComplete
                            ? t("batch.create.shotsComplete")
                            : t("batch.create.shotsMissing")}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4">
          <button type="button" className={primaryButton} disabled={!canCreate} onClick={() => void create()}>
            {t("batch.create.submit")}
          </button>
        </div>
      </section>
    </div>
  );
}