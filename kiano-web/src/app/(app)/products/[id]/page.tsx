"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, pickGuidance, useI18n } from "@/i18n";
import { PipelinePanel } from "@/components/PipelinePanel";
import { FactsPanel } from "@/components/FactsPanel";
import { PublishPanel } from "@/components/PublishPanel";
import type { ProductShotStatus, ShotStatusLine } from "@/lib/types";

export default function ProductDetailPage({ params }: PageProps<"/products/[id]">) {
  const { id } = use(params);
  const { t } = useI18n();
  const [status, setStatus] = useState<ProductShotStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<ProductShotStatus>(`/api/v1/content/products/${id}/shots`)
      .then((data) => {
        if (!cancelled) {
          setStatus(data);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(
            err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"),
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, t]);

  if (error !== null) {
    return (
      <div className="flex flex-col gap-4">
        <Link
          href="/products"
          className="text-sm text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
        >
          ← {t("detail.back")}
        </Link>
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      </div>
    );
  }

  if (status === null) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/products"
          className="text-sm text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
        >
          ← {t("detail.back")}
        </Link>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">{status.name}</h1>
        <span className="font-mono text-sm text-zinc-500 dark:text-zinc-400">{status.sku}</span>
        <span className="rounded border border-zinc-300 px-2 py-0.5 text-xs text-zinc-700 dark:border-zinc-700 dark:text-zinc-300">
          {t(`tier.${status.tier}`)}
        </span>
        <span
          className={
            status.complete
              ? "rounded bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
              : "rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
          }
        >
          {status.complete ? t("detail.complete") : t("detail.incomplete")}
        </span>
        <Link
          href={`/products/${id}/facts`}
          className="rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          {t("nav.facts")}
        </Link>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="flex flex-col gap-6">
          <PipelinePanel productId={status.productId} shotsComplete={status.complete} />

          <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4">
            {status.lines.map((line) => (
              <ShotCard key={line.code} line={line} />
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-4">
          <FactsPanel productId={status.productId} />
          <PublishPanel productId={status.productId} />
        </div>
      </div>
    </div>
  );
}

function ShotCard({ line }: { line: ShotStatusLine }) {
  const { locale, t } = useI18n();

  const stateBadge =
    line.state === "OK"
      ? "rounded bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
      : line.state === "RESHOOT"
        ? "rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
        : "rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400";

  return (
    <div className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
      <div className="flex items-center justify-between gap-2">
        <span className="font-mono text-sm font-medium">{line.code}</span>
        <span className={stateBadge}>{t(`state.${line.state}`)}</span>
      </div>

      {line.kind === "VIDEO" && line.mediaUrl !== null ? (
        <video preload="metadata" controls src={line.mediaUrl} className="w-full rounded bg-black" />
      ) : line.thumbUrl !== null && line.mediaUrl !== null ? (
        <a href={line.mediaUrl} target="_blank" rel="noopener noreferrer">
          {/* eslint-disable-next-line @next/next/no-img-element -- presigned MinIO URLs */}
          <img src={line.thumbUrl} alt={line.code} className="w-full rounded object-cover" />
        </a>
      ) : (
        <div className="flex aspect-[3/4] w-full items-center justify-center rounded bg-zinc-100 dark:bg-zinc-800" />
      )}

      <p className="text-xs text-zinc-600 dark:text-zinc-400">{pickGuidance(locale, line)}</p>

      {line.reasons.length > 0 ? (
        <ul className="flex flex-wrap gap-x-2 gap-y-1">
          {line.reasons.map((reason) => (
            <li key={reason} className="text-xs text-red-600 dark:text-red-400">
              {t(`qc.${reason}`)}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
