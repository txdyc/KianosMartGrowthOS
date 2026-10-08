"use client";

import { useCallback, useEffect, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { canOperate, isOwner, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { TFunction } from "@/i18n";
import { en } from "@/i18n/en";
import type { MessageKey } from "@/i18n";
import type { FactSheetResponse, PublicationView, ReviewItem } from "@/lib/types";

/**
 * Per-product publish panel: checks the preconditions (policy complete, facts
 * locked, required assets approved), offers Staging (OPERATOR) and production
 * (OWNER, gated on a matching staging publish), lists the publication history
 * with rollback (WOO_CHANGED_SINCE_PUBLISH asks for force confirmation), and
 * polls while a publish is running.
 */
export function PublishPanel({ productId }: { productId: number }) {
  const { t } = useI18n();
  const me = useMe();
  const operate = me !== null && canOperate(me.role);
  const owner = me !== null && isOwner(me.role);

  const [facts, setFacts] = useState<FactSheetResponse | null>(null);
  const [assets, setAssets] = useState<ReviewItem[] | null>(null);
  const [policy, setPolicy] = useState<{ configured: boolean; policy?: { complete: boolean } } | null>(null);
  const [publications, setPublications] = useState<PublicationView[]>([]);
  const [busyEnv, setBusyEnv] = useState<"STAGING" | "PRODUCTION" | null>(null);
  const [rollingBack, setRollingBack] = useState<number | null>(null);
  const [forceTarget, setForceTarget] = useState<PublicationView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    return Promise.all([
      apiFetch<FactSheetResponse>(`/api/v1/content/products/${productId}/facts`),
      apiFetch<ReviewItem[]>(`/api/v1/content/assets?productId=${productId}`),
      apiFetch<{ configured: boolean; policy?: { complete: boolean } }>(
        "/api/v1/content/policy",
      ),
      apiFetch<PublicationView[]>(`/api/v1/content/products/${productId}/publications`),
    ])
      .then(([f, a, p, pubs]) => {
        setFacts(f);
        setAssets(a);
        setPolicy(p);
        setPublications(pubs);
      })
      .catch((err: unknown) => {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [productId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  // Poll every 3s while a publish is queued/running.
  useEffect(() => {
    if (busyEnv === null) {
      return;
    }
    const timer = setInterval(() => {
      void load();
    }, 3000);
    return () => clearInterval(timer);
  }, [busyEnv, load]);

  async function publish(environment: "STAGING" | "PRODUCTION") {
    setBusyEnv(environment);
    setError(null);
    try {
      await apiFetch(`/api/v1/content/products/${productId}/publish?environment=${environment}`, {
        method: "POST",
      });
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusyEnv(null);
      await load();
    }
  }

  async function rollback(publication: PublicationView, force: boolean) {
    setRollingBack(publication.id);
    setError(null);
    try {
      await apiFetch(
        `/api/v1/content/publications/${publication.id}/rollback?force=${force}`,
        { method: "POST" },
      );
      setForceTarget(null);
      await load();
    } catch (err) {
      const api = err instanceof ApiError ? err : null;
      if (api?.code === "WOO_CHANGED_SINCE_PUBLISH") {
        setForceTarget(publication);
      } else {
        setError(api ? errorText(t, api) : t("error.INTERNAL_ERROR"));
      }
    } finally {
      setRollingBack(null);
    }
  }

  // Precondition checks
  const policyComplete = policy?.policy?.complete === true;
  const factsLocked = facts?.locked !== undefined;
  const requiredApproved = requiredAssetsReady(assets);
  const isRunning = busyEnv !== null;

  // Production gate: latest staging APPLIED has the same asset id set.
  const latestStaging = publications.find(
    (p) => p.environment === "STAGING" && p.status === "APPLIED",
  );
  const productionReady =
    owner &&
    policyComplete &&
    factsLocked &&
    requiredApproved &&
    latestStaging !== undefined &&
    sameAssetSet(latestStaging.assetIds, assets);

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
      <h2 className="text-base font-semibold">{t("publish.heading")}</h2>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {/* preconditions */}
      <ul className="flex flex-col gap-1 text-sm">
        <Precondition label={t("publish.policy")} ok={policyComplete} />
        <Precondition label={t("publish.facts")} ok={factsLocked} />
        <Precondition label={t("publish.assets")} ok={requiredApproved} />
      </ul>

      {operate ? (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => void publish("STAGING")}
            disabled={isRunning || !policyComplete || !factsLocked || !requiredApproved}
            className={btnClass(isRunning || !policyComplete || !factsLocked || !requiredApproved)}
          >
            {busyEnv === "STAGING" ? t("publish.publishing") : t("publish.staging")}
          </button>
          <button
            type="button"
            onClick={() => void publish("PRODUCTION")}
            disabled={!productionReady || isRunning}
            title={!productionReady && owner ? t("publish.productionDisabled") : undefined}
            className={btnClass(!productionReady || isRunning)}
          >
            {busyEnv === "PRODUCTION" ? t("publish.publishing") : t("publish.production")}
          </button>
        </div>
      ) : null}

      {/* history */}
      <h3 className="mt-1 text-xs font-medium uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
        {t("publish.history")}
      </h3>
      {publications.length === 0 ? (
        <p className="text-xs text-zinc-500 dark:text-zinc-400">—</p>
      ) : (
        <ul className="flex flex-col gap-1 text-xs">
          {publications.map((pub) => (
            <PublicationRow
              key={pub.id}
              pub={pub}
              t={t}
              canOperate={operate}
              rollingBack={rollingBack === pub.id}
              onRollback={() => void rollback(pub, false)}
            />
          ))}
        </ul>
      )}

      {forceTarget !== null ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true">
          <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl dark:bg-zinc-900">
            <h3 className="text-lg font-semibold">{t("publish.forceRollbackTitle")}</h3>
            <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
              {t("publish.forceRollbackBody")}
            </p>
            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                className="rounded-md border border-zinc-300 px-3 py-1.5 text-sm dark:border-zinc-700"
                onClick={() => setForceTarget(null)}
              >
                {t("review.cancel")}
              </button>
              <button
                type="button"
                className="rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-red-500"
                onClick={() => void rollback(forceTarget, true)}
              >
                {t("publish.forceRollbackGo")}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function Precondition({ label, ok }: { label: string; ok: boolean }) {
  return (
    <li className="flex items-center gap-2">
      <span className={ok ? "text-emerald-600 dark:text-emerald-400" : "text-zinc-400"}>
        {ok ? "✓" : "✗"}
      </span>
      <span className={ok ? "text-zinc-700 dark:text-zinc-300" : "text-zinc-500 dark:text-zinc-400"}>
        {label}
      </span>
    </li>
  );
}

function PublicationRow({
  pub,
  t,
  canOperate,
  rollingBack,
  onRollback,
}: {
  pub: PublicationView;
  t: TFunction;
  canOperate: boolean;
  rollingBack: boolean;
  onRollback: () => void;
}) {
  const envKey = `settings.env.${pub.environment}` as MessageKey;
  const statusKey = `pubstatus.${pub.status}` as MessageKey;
  const envLabel = envKey in en ? t(envKey) : pub.environment;
  const statusLabel = statusKey in en ? t(statusKey) : pub.status;
  return (
    <li className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded border border-zinc-100 px-2 py-1 dark:border-zinc-800">
      <span className="font-medium">{envLabel}</span>
      <span
        className={
          pub.status === "APPLIED"
            ? "rounded bg-emerald-100 px-1.5 py-0.5 font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
            : pub.status === "FAILED"
              ? "rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
              : "rounded bg-zinc-100 px-1.5 py-0.5 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
        }
      >
        {statusLabel}
      </span>
      {pub.publishedAt ? (
        <span className="text-zinc-500 dark:text-zinc-400">
          {new Date(pub.publishedAt).toLocaleString()}
        </span>
      ) : null}
      {pub.publishedBy ? (
        <span className="text-zinc-500 dark:text-zinc-400">
          {t("publish.by", { name: pub.publishedBy })}
        </span>
      ) : null}
      {pub.needsAttention ? (
        <span className="rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400">
          {t("publish.needsAttention")}
        </span>
      ) : null}
      {pub.error && pub.status === "FAILED" ? (
        <span className="text-xs text-red-600 dark:text-red-400">{pub.error}</span>
      ) : null}
      {canOperate && pub.canRollback && (
        <button
          type="button"
          disabled={rollingBack}
          onClick={onRollback}
          className="rounded border border-zinc-300 px-1.5 py-0.5 text-zinc-600 transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
        >
          {t("publish.rollback")}
        </button>
      )}
    </li>
  );
}

function requiredAssetsReady(assets: ReviewItem[] | null): boolean {
  if (assets === null) {
    return false;
  }
  const specs = new Set(assets.filter((a) => a.status === "APPROVED").map((a) => a.specCode));
  const required = ["PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"];
  const missing = required.filter((s) => !specs.has(s));
  const angles = assets.filter(
    (a) => a.specCode === "PAGE_ANGLE" && a.status === "APPROVED",
  ).length;
  return missing.length === 0 && angles >= 3;
}

function sameAssetSet(published: number[], assets: ReviewItem[] | null): boolean {
  if (assets === null) {
    return false;
  }
  const publishedSet = new Set(published);
  // The gallery (latest approved per variant) plus the text specs form the publish set.
  const current = new Set(
    assets.filter((a) => a.status === "APPROVED").map((a) => a.assetId),
  );
  return publishedSet.size === current.size && [...publishedSet].every((id) => current.has(id));
}

function btnClass(disabled: boolean): string {
  return `rounded-md px-3 py-1.5 text-sm font-medium ${
    disabled
      ? "cursor-not-allowed bg-zinc-200 text-zinc-400 dark:bg-zinc-800 dark:text-zinc-600"
      : "bg-zinc-900 text-white transition-colors hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
  }`;
}