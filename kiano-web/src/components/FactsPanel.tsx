"use client";

import { useEffect, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { FactSheetResponse, ReviewItem } from "@/lib/types";

const COPY_SPECS = [
  "COPY_TITLE",
  "COPY_SHORT",
  "COPY_LONG",
  "COPY_SEO",
  "COPY_GSHOP",
  "COPY_WA",
  "PAGE_INFO",
  "PAGE_SPEC",
] as const;

/**
 * Compact status readout on the product detail page: the fact-sheet state
 * (none / draft vN / locked vN) and, per derived spec, the newest version's
 * status plus a precheck-flag count. Refreshes on mount only; the facts page
 * itself owns the edit flow.
 */
export function FactsPanel({ productId }: { productId: number }) {
  const { t } = useI18n();
  const [facts, setFacts] = useState<FactSheetResponse | null>(null);
  const [assets, setAssets] = useState<ReviewItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      apiFetch<FactSheetResponse>(`/api/v1/content/products/${productId}/facts`),
      apiFetch<ReviewItem[]>(`/api/v1/content/assets?productId=${productId}`),
    ])
      .then(([factData, assetData]) => {
        if (cancelled) return;
        setFacts(factData);
        setAssets(assetData);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [productId, t]);

  if (error !== null) {
    return <p className="text-xs text-red-600 dark:text-red-400">{error}</p>;
  }

  const sheet = facts?.current ?? facts?.locked;
  const statusKey =
    sheet === undefined
      ? ("none" as const)
      : (sheet.status.toLowerCase() as "draft" | "locked");

  return (
    <div className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
          {t("nav.facts")}:
        </span>
        <span
          className={`rounded px-2 py-0.5 text-xs font-medium ${
            statusKey === "locked"
              ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
              : statusKey === "draft"
                ? "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-400"
                : "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400"
          }`}
        >
          {t(`facts.status.${statusKey}`, { version: sheet?.version ?? 0 })}
        </span>
      </div>

      <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
        {t("facts.copyProgress")}
      </span>
      <ul className="flex flex-col gap-1">
        {COPY_SPECS.map((spec) => {
          const item = newestFor(assets, spec);
          return (
            <li key={spec} className="flex items-center justify-between gap-2 text-xs">
              <span className="font-mono text-zinc-600 dark:text-zinc-400">
                {t(`spec.${spec}`)}
              </span>
              {item === null ? (
                <span className="text-zinc-400 dark:text-zinc-600">—</span>
              ) : (
                <span className="flex items-center gap-1.5">
                  {item.flags.length > 0 ? (
                    <span className="rounded bg-amber-100 px-1.5 py-0.5 font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400">
                      {t("facts.copyFlags", { n: item.flags.length })}
                    </span>
                  ) : null}
                  <span
                    className={`rounded px-1.5 py-0.5 ${
                      statusClass(item.status)
                    }`}
                  >
                    {t(`asset.${item.status}`)}
                  </span>
                </span>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function newestFor(assets: ReviewItem[] | null, specCode: string): ReviewItem | null {
  if (assets === null) {
    return null;
  }
  const hits = assets
    .filter((a) => a.specCode === specCode)
    .sort((a, b) => b.version - a.version);
  return hits[0] ?? null;
}

function statusClass(status: string): string {
  switch (status) {
    case "APPROVED":
      return "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400";
    case "IN_REVIEW":
      return "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-400";
    case "REJECTED":
      return "bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-400";
    default:
      return "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400";
  }
}