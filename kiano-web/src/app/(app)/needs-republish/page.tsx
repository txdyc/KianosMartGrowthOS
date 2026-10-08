"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { NeedsRepublishItem } from "@/lib/types";

/** Products whose published content is out of date (facts or policy changed). */
export default function NeedsRepublishPage() {
  const { t } = useI18n();
  const [items, setItems] = useState<NeedsRepublishItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<NeedsRepublishItem[]>("/api/v1/content/publications/needs-republish")
      .then((rows) => {
        if (!cancelled) setItems(rows);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [t]);

  if (error !== null) {
    return <p className="text-sm text-red-600 dark:text-red-400" role="alert">{error}</p>;
  }
  if (items === null) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold tracking-tight">{t("publish.needsRepublishTitle")}</h1>
      {items.length === 0 ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">—</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((item) => (
            <li
              key={item.productId}
              className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-zinc-200 px-3 py-2 dark:border-zinc-800"
            >
              <Link
                href={`/products/${item.productId}`}
                className="font-mono text-sm font-medium text-zinc-900 hover:underline dark:text-zinc-100"
              >
                {item.sku ?? item.productId}
              </Link>
              <span className="text-sm text-zinc-600 dark:text-zinc-400">
                {item.productName ?? ""}
              </span>
              <span className="flex gap-1">
                {item.reasons.map((reason) => (
                  <span
                    key={reason}
                    className="rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-400"
                  >
                    {t(`publish.reason.${reason}`)}
                  </span>
                ))}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}