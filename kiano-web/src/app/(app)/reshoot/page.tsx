"use client";

import { useCallback, useEffect, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, pickGuidance, useI18n } from "@/i18n";
import type { ContentTier, ReshootLine } from "@/lib/types";

type TierFilter = "ALL" | ContentTier;

const cellClass = "px-3 py-2 text-sm";

export default function ReshootPage() {
  const { locale, t } = useI18n();
  const [lines, setLines] = useState<ReshootLine[] | null>(null);
  const [tierFilter, setTierFilter] = useState<TierFilter>("ALL");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    const params = new URLSearchParams();
    if (tierFilter !== "ALL") {
      params.set("tier", tierFilter);
    }
    const query = params.toString();
    return apiFetch<ReshootLine[]>(`/api/v1/content/reshoot-list${query ? `?${query}` : ""}`)
      .then((rows) => {
        setLines(rows);
      })
      .catch((err: unknown) => {
        setLines([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [tierFilter, t]);

  useEffect(() => {
    load();
  }, [load]);

  const groups = groupBySku(lines);
  // Top-level navigation: the Lax session cookie rides along to the API origin.
  const csvQuery = tierFilter === "ALL" ? "" : `?tier=${tierFilter}`;
  const csvHref = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? ""}/api/v1/content/reshoot-list.csv${csvQuery}`;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <select
          value={tierFilter}
          onChange={(e) => setTierFilter(e.target.value as TierFilter)}
          className="rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400"
        >
          <option value="ALL">{t("tier.all")}</option>
          <option value="HERO">{t("tier.HERO")}</option>
          <option value="STANDARD">{t("tier.STANDARD")}</option>
        </select>
        <a
          href={csvHref}
          className="rounded-md border border-zinc-300 px-3 py-1.5 text-sm transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          {t("reshoot.downloadCsv")}
        </a>
        <span className="text-xs text-zinc-500 dark:text-zinc-400">{t("reshoot.csvNote")}</span>
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {lines === null ? (
        <p className="text-sm text-zinc-500">{t("loading")}</p>
      ) : groups.length === 0 ? (
        <p className="text-sm text-zinc-500">{t("reshoot.empty")}</p>
      ) : (
        groups.map((group) => (
          <section key={group.sku} className="flex flex-col gap-2">
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <h2 className="font-mono text-base font-semibold">{group.sku}</h2>
              <span className="text-sm text-zinc-700 dark:text-zinc-300">{group.productName}</span>
              <span className="rounded border border-zinc-300 px-2 py-0.5 text-xs text-zinc-700 dark:border-zinc-700 dark:text-zinc-300">
                {t(`tier.${group.tier}`)}
              </span>
            </div>
            <table className="w-full border-collapse">
              <thead>
                <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500 dark:border-zinc-800">
                  <th className={`${cellClass} w-20`}>{t("col.shot")}</th>
                  <th className={`${cellClass} w-32`}>{t("col.state")}</th>
                  <th className={cellClass}>{t("col.guidance")}</th>
                </tr>
              </thead>
              <tbody>
                {group.lines.map((line) => (
                  <tr key={line.shotCode} className="border-b border-zinc-100 dark:border-zinc-800/60">
                    <td className={`${cellClass} font-mono text-xs`}>{line.shotCode}</td>
                    <td className={cellClass}>
                      <span
                        className={
                          line.state === "RESHOOT"
                            ? "rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
                            : "rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
                        }
                      >
                        {t(`state.${line.state}`)}
                      </span>
                      {line.reasons.length > 0 ? (
                        <span className="ml-2 text-xs text-red-600 dark:text-red-400">
                          {line.reasons.map((r) => t(`qc.${r}`)).join(" · ")}
                        </span>
                      ) : null}
                    </td>
                    <td className={`${cellClass} text-zinc-600 dark:text-zinc-400`}>
                      {pickGuidance(locale, line)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ))
      )}
    </div>
  );
}

function groupBySku(lines: ReshootLine[] | null) {
  const groups: {
    sku: string;
    productName: string;
    tier: ContentTier;
    lines: ReshootLine[];
  }[] = [];
  if (lines === null) {
    return groups;
  }
  const bySku = new Map<string, (typeof groups)[number]>();
  for (const line of lines) {
    let group = bySku.get(line.sku);
    if (group === undefined) {
      group = { sku: line.sku, productName: line.productName, tier: line.tier, lines: [] };
      bySku.set(line.sku, group);
      groups.push(group);
    }
    group.lines.push(line);
  }
  return groups;
}
