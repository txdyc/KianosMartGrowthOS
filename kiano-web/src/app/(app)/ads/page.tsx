"use client";

import { useCallback, useEffect, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import { approvedStaticCounts } from "@/lib/ads";
import type { AdExportView, AdReplacement, ContentProductSummary, ReviewItem } from "@/lib/types";

const POLL_MS = 3000;
const POLL_TIMEOUT_MS = 120_000;

/**
 * Static-ad export centre: pick HERO products (or export all), trigger the
 * export, poll until it applies or fails, then download / inspect replacements.
 */
export default function AdsPage() {
  const { t } = useI18n();
  const [products, setProducts] = useState<ContentProductSummary[] | null>(null);
  // AD_STATIC APPROVED count per product (the n of n/12).
  const [counts, setCounts] = useState<Record<number, number>>({});
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [exportAll, setExportAll] = useState(false);
  const [exports, setExports] = useState<AdExportView[]>([]);
  const [replacements, setReplacements] = useState<AdReplacement[]>([]);
  const [exporting, setExporting] = useState(false);
  // publicationId whose export we are polling to completion.
  const [checking, setChecking] = useState<number | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadProducts = useCallback(() => {
    return apiFetch<ContentProductSummary[]>("/api/v1/content/products?tier=HERO")
      .then(setProducts)
      .catch((err: unknown) => {
        setProducts([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [t]);

  const loadCounts = useCallback(() => {
    return apiFetch<ReviewItem[]>("/api/v1/content/assets?kind=IMAGE&status=APPROVED")
      .then((rows) => {
        setCounts(approvedStaticCounts(rows));
      })
      .catch(() => {});
  }, []);

  const loadExports = useCallback(() => {
    return apiFetch<AdExportView[]>("/api/v1/content/ads/exports")
      .then(setExports)
      .catch(() => {});
  }, []);

  const loadReplacements = useCallback(() => {
    return apiFetch<AdReplacement[]>("/api/v1/content/ads/replacements")
      .then(setReplacements)
      .catch(() => {});
  }, []);

  useEffect(() => {
    void loadProducts();
    void loadCounts();
    void loadExports();
    void loadReplacements();
  }, [loadProducts, loadCounts, loadExports, loadReplacements]);

  // Poll the exports list every 3s while an export is being watched, stopping
  // once it leaves PENDING or the 120s timeout elapses. `deadline` is captured
  // per effect run so each poll closes over the timeout of its own export.
  useEffect(() => {
    if (checking === null) {
      return;
    }
    const deadline = Date.now() + POLL_TIMEOUT_MS;
    const timer = setInterval(() => {
      void (async () => {
        const rows = await apiFetch<AdExportView[]>("/api/v1/content/ads/exports").catch(
          () => null,
        );
        if (rows !== null) {
          setExports(rows);
        }
        const done = rows?.find((r) => r.publicationId === checking);
        if (done !== undefined && done.status !== "PENDING") {
          setChecking(null);
        } else if (Date.now() >= deadline) {
          setChecking(null);
        }
      })();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [checking]);

  const selectedCount = exportAll ? products?.length ?? 0 : selected.size;
  const exportDisabled = exporting || checking !== null || selectedCount === 0;

  async function doExport() {
    setError(null);
    setExporting(true);
    try {
      const res = await apiFetch<{ publicationId: number }>("/api/v1/content/ads/exports", {
        method: "POST",
        body: { productIds: exportAll ? [] : [...selected] },
      });
      await loadExports();
      setChecking(res.publicationId);
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setExporting(false);
    }
  }

  function toggle(productId: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(productId)) {
        next.delete(productId);
      } else {
        next.add(productId);
      }
      return next;
    });
  }

  async function copyFileName(name: string) {
    try {
      await navigator.clipboard.writeText(name);
      setCopied(name);
    } catch {
      setCopied(null);
    }
  }

  const heroProducts = products ?? [];
  const effectiveSelected = exportAll
    ? new Set(heroProducts.map((p) => p.productId))
    : selected;

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t("ads.title")}</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{t("ads.selectHero")}</p>
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {/* product selection */}
      <section className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={exportAll}
            onChange={(e) => setExportAll(e.target.checked)}
          />
          <span className="font-medium">{t("ads.selectHero")} — {t("tier.all")}</span>
        </label>

        {heroProducts.length === 0 ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">—</p>
        ) : (
          <ul className="grid grid-cols-1 gap-1 sm:grid-cols-2">
            {heroProducts.map((p) => {
              const n = counts[p.productId] ?? 0;
              const checked = exportAll || effectiveSelected.has(p.productId);
              return (
                <li key={p.productId}>
                  <label
                    className={
                      `flex cursor-pointer items-center gap-2 rounded-md border px-2 py-1.5 text-sm transition-colors ${
                        exportAll ? "opacity-60" : "hover:bg-zinc-50 dark:hover:bg-zinc-800/40"
                      } ${
                        checked
                          ? "border-zinc-900 dark:border-zinc-400"
                          : "border-zinc-200 dark:border-zinc-800"
                      }`
                    }
                  >
                    <input
                      type="checkbox"
                      disabled={exportAll}
                      checked={checked}
                      onChange={() => toggle(p.productId)}
                    />
                    <span className="font-mono text-xs">{p.sku}</span>
                    <span className="flex-1 truncate text-zinc-600 dark:text-zinc-400">{p.name}</span>
                    <span
                      className={
                        n >= 12
                          ? "rounded bg-emerald-100 px-1.5 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
                          : "rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
                      }
                    >
                      {n}/12
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        )}

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void doExport()}
            disabled={exportDisabled}
            className="rounded-md bg-zinc-900 px-4 py-1.5 text-sm font-medium text-zinc-50 transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {exporting || checking !== null ? t("loading") : t("ads.export")}
          </button>
          {selectedCount === 0 ? (
            <span className="text-xs text-zinc-500 dark:text-zinc-400">{t("ads.noCopy")}</span>
          ) : (
            <span className="text-sm text-zinc-500 dark:text-zinc-400">
              {t("import.summary", { n: selectedCount })}
            </span>
          )}
        </div>
      </section>

      {/* export history */}
      <section className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
        <h2 className="text-base font-semibold">{t("ads.exportHistory")}</h2>
        {exports.length === 0 ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">—</p>
        ) : (
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500 dark:border-zinc-800">
                <th className="px-2 py-1">ID</th>
                <th className="px-2 py-1">{t("col.status")}</th>
                <th className="px-2 py-1">Files</th>
                <th className="px-2 py-1">{t("publish.history")}</th>
                <th className="px-2 py-1" />
              </tr>
            </thead>
            <tbody>
              {exports.map((exp) => {
                const applied = exp.status === "APPLIED";
                const failed = exp.status === "FAILED";
                return (
                  <tr key={exp.publicationId} className="border-b border-zinc-100 dark:border-zinc-800/60">
                    <td className="px-2 py-1 font-mono text-xs">{exp.publicationId}</td>
                    <td className="px-2 py-1 text-xs">
                      <span
                        className={
                          applied
                            ? "rounded bg-emerald-100 px-1.5 py-0.5 font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
                            : failed
                              ? "rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
                              : "rounded bg-zinc-100 px-1.5 py-0.5 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
                        }
                      >
                        {exp.status === "APPLIED"
                          ? t("ads.applied")
                          : exp.status === "FAILED"
                            ? t("ads.failed")
                            : t("ads.pending")}
                      </span>
                    </td>
                    <td className="px-2 py-1 text-xs">
                      <span>{exp.fileCount}</span>
                      {exp.zipKey !== null ? (
                        <span className="ml-1 text-zinc-400" title={exp.zipKey}>
                          · {exp.zipKey.split("/").pop()}
                        </span>
                      ) : null}
                    </td>
                    <td className="px-2 py-1 text-xs text-zinc-500 dark:text-zinc-400">
                      {exp.publishedAt !== null ? new Date(exp.publishedAt).toLocaleString() : "—"}
                    </td>
                    <td className="px-2 py-1 text-right text-xs">
                      {applied ? (
                        <a
                          href={`/api/v1/content/ads/exports/${exp.publicationId}/download`}
                          className="inline-block rounded border border-zinc-300 px-2 py-0.5 transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
                        >
                          {t("ads.download")}
                        </a>
                      ) : failed ? (
                        <span className="text-red-600 dark:text-red-400">
                          {exp.error ?? t("ads.failed")}
                        </span>
                      ) : (
                        <span className="text-zinc-400">{t("ads.notReady")}</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>

      {/* replacements */}
      <section className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
        <h2 className="text-base font-semibold">{t("ads.replacements")}</h2>
        {replacements.length === 0 ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("ads.replacementsEmpty")}</p>
        ) : (
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500 dark:border-zinc-800">
                <th className="px-2 py-1">{t("col.sku")}</th>
                <th className="px-2 py-1">Variant</th>
                <th className="px-2 py-1">Old</th>
                <th className="px-2 py-1">New</th>
                <th className="px-2 py-1">{t("col.status")}</th>
                <th className="px-2 py-1"></th>
              </tr>
            </thead>
            <tbody>
              {replacements.map((r, i) => (
                <tr key={`${r.productId}-${r.variant}-${i}`} className="border-b border-zinc-100 align-top dark:border-zinc-800/60">
                  <td className="px-2 py-1 font-mono text-xs">{r.sku}</td>
                  <td className="px-2 py-1 font-mono text-xs">{r.variant}</td>
                  <td className="px-2 py-1 font-mono text-xs break-all">{r.oldFileName}</td>
                  <td className="px-2 py-1 font-mono text-xs break-all text-zinc-600 dark:text-zinc-400">
                    {r.newFileName}
                  </td>
                  <td className="px-2 py-1 text-xs">
                    {r.newStatus === null ? (
                      <span className="text-zinc-400">—</span>
                    ) : (
                      <span
                        className={
                          r.newStatus === "APPROVED"
                            ? "rounded bg-emerald-100 px-1.5 py-0.5 font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
                            : r.newStatus === "IN_REVIEW"
                              ? "rounded bg-amber-100 px-1.5 py-0.5 text-amber-700 dark:bg-amber-900/40 dark:text-amber-400"
                              : "rounded bg-zinc-100 px-1.5 py-0.5 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
                        }
                      >
                        {t(`asset.${r.newStatus}`)}
                      </span>
                    )}
                  </td>
                  <td className="px-2 py-1 text-right">
                    <button
                      type="button"
                      onClick={() => void copyFileName(r.newFileName)}
                      className="rounded border border-zinc-300 px-2 py-0.5 text-xs transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
                    >
                      {copied === r.newFileName ? "✓" : t("ads.copyFileName")}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}