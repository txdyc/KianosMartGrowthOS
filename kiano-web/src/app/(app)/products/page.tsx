"use client";

import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useRouter } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api";
import { formatGhs } from "@/lib/format";
import { canOperate, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { ContentProductSummary, ContentTier, TaskView } from "@/lib/types";

type TierFilter = "ALL" | ContentTier;

const cellClass = "px-3 py-2 text-sm";

export default function ProductsPage() {
  const { t } = useI18n();
  const router = useRouter();
  const me = useMe();
  const operate = me !== null && canOperate(me.role);

  const [products, setProducts] = useState<ContentProductSummary[] | null>(null);
  const [syncTask, setSyncTask] = useState<TaskView | null>(null);
  const [qInput, setQInput] = useState("");
  const [appliedQ, setAppliedQ] = useState("");
  const [tierFilter, setTierFilter] = useState<TierFilter>("ALL");
  const [error, setError] = useState<string | null>(null);

  const loadProducts = useCallback(() => {
    const params = new URLSearchParams();
    if (tierFilter !== "ALL") {
      params.set("tier", tierFilter);
    }
    if (appliedQ !== "") {
      params.set("q", appliedQ);
    }
    const query = params.toString();
    return apiFetch<ContentProductSummary[]>(`/api/v1/content/products${query ? `?${query}` : ""}`)
      .then((rows) => {
        setProducts(rows);
      })
      .catch((err: unknown) => {
        setProducts([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [appliedQ, tierFilter, t]);

  const loadSync = useCallback(() => {
    // 204 (no run yet) comes back as undefined; sync status is informational,
    // so failures just keep whatever we already have.
    return apiFetch<TaskView | undefined>("/api/v1/commerce/sync/latest")
      .then((task) => {
        setSyncTask(task ?? null);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    loadProducts();
  }, [loadProducts]);

  useEffect(() => {
    loadSync();
  }, [loadSync]);

  // Poll while a sync is queued or running.
  useEffect(() => {
    if (syncTask === null || (syncTask.status !== "QUEUED" && syncTask.status !== "RUNNING")) {
      return;
    }
    const timer = setInterval(loadSync, 3000);
    return () => clearInterval(timer);
  }, [syncTask, loadSync]);

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAppliedQ(qInput.trim());
  }

  async function triggerSync() {
    setError(null);
    try {
      await apiFetch("/api/v1/commerce/sync", { method: "POST" });
      await loadSync();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    }
  }

  async function changeTier(product: ContentProductSummary, tier: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/products/${product.productId}/profile`, {
        method: "PATCH",
        body: { contentTier: tier },
      });
      // The required checklist changes with the tier, so refresh the rows.
      await loadProducts();
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <span className="text-zinc-500 dark:text-zinc-400">{t("sync.last")}:</span>
        {syncTask === null ? (
          <span>{t("sync.never")}</span>
        ) : (
          <>
            <span className={syncTask.status === "FAILED" ? "font-medium text-red-600 dark:text-red-400" : ""}>
              {t(`task.${syncTask.status}`)}
            </span>
            {syncTask.finishedAt !== null ? (
              <span className="text-zinc-500 dark:text-zinc-400">
                {new Date(syncTask.finishedAt).toLocaleString()}
              </span>
            ) : null}
            {syncTask.status === "FAILED" && syncTask.lastError !== null ? (
              <span className="text-red-600 dark:text-red-400">{syncTask.lastError}</span>
            ) : null}
          </>
        )}
        {operate ? (
          <button
            type="button"
            onClick={triggerSync}
            className="rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            {t("sync.now")}
          </button>
        ) : null}
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <form onSubmit={submitSearch}>
          <input
            type="search"
            placeholder={t("products.searchPlaceholder")}
            value={qInput}
            onChange={(e) => setQInput(e.target.value)}
            className="w-64 rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400"
          />
        </form>
        <select
          value={tierFilter}
          onChange={(e) => setTierFilter(e.target.value as TierFilter)}
          className="rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400"
        >
          <option value="ALL">{t("tier.all")}</option>
          <option value="HERO">{t("tier.HERO")}</option>
          <option value="STANDARD">{t("tier.STANDARD")}</option>
        </select>
      </div>

      {products === null ? (
        <p className="text-sm text-zinc-500">{t("loading")}</p>
      ) : (
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500 dark:border-zinc-800">
              <th className={`${cellClass} w-14`}>{t("col.image")}</th>
              <th className={cellClass}>{t("col.sku")}</th>
              <th className={cellClass}>{t("col.name")}</th>
              <th className={cellClass}>{t("col.price")}</th>
              <th className={cellClass}>{t("col.stock")}</th>
              <th className={cellClass}>{t("col.tier")}</th>
              <th className={cellClass}>{t("col.shots")}</th>
            </tr>
          </thead>
          <tbody>
            {products.map((p) => (
              <tr
                key={p.productId}
                onClick={() => router.push(`/products/${p.productId}`)}
                className="cursor-pointer border-b border-zinc-100 transition-colors hover:bg-zinc-50 dark:border-zinc-800/60 dark:hover:bg-zinc-800/40"
              >
                <td className={cellClass}>
                  {p.imageUrl !== null ? (
                    // eslint-disable-next-line @next/next/no-img-element -- images come from arbitrary Woo hosts
                    <img src={p.imageUrl} alt={p.sku} className="h-10 w-10 rounded object-cover" />
                  ) : (
                    <div className="h-10 w-10 rounded bg-zinc-200 dark:bg-zinc-700" />
                  )}
                </td>
                <td className={`${cellClass} font-mono text-xs`}>{p.sku}</td>
                <td className={cellClass}>{p.name}</td>
                <td className={cellClass}>
                  {p.salePrice !== null && p.regularPrice !== null ? (
                    <span className="mr-1 text-xs text-zinc-400 line-through">{formatGhs(p.regularPrice)}</span>
                  ) : null}
                  {formatGhs(p.price)}
                </td>
                <td className={cellClass}>{p.stockStatus}</td>
                <td className={cellClass} onClick={(e) => e.stopPropagation()}>
                  {operate ? (
                    <select
                      value={p.tier}
                      onChange={(e) => changeTier(p, e.target.value)}
                      className="rounded border border-zinc-300 bg-white px-2 py-1 text-xs dark:border-zinc-700 dark:bg-zinc-900"
                    >
                      <option value="HERO">{t("tier.HERO")}</option>
                      <option value="STANDARD">{t("tier.STANDARD")}</option>
                    </select>
                  ) : (
                    t(`tier.${p.tier}`)
                  )}
                </td>
                <td className={cellClass}>
                  <span className={p.complete ? "font-medium text-emerald-600 dark:text-emerald-400" : ""}>
                    {p.complete ? "✓ " : ""}
                    {p.ok}/{p.required}
                  </span>
                  {p.reshoot > 0 ? (
                    <span className="ml-1 rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-700 dark:bg-red-900/40 dark:text-red-400">
                      {p.reshoot}
                    </span>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
