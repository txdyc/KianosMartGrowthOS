"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api";
import { canOperate, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey, TFunction } from "@/i18n";
import { en } from "@/i18n/en";
import { groupBySku, keyToDecision, nextFocus } from "@/lib/review";
import type { Decision, ReviewGroup } from "@/lib/review";
import { RejectDialog } from "@/components/RejectDialog";
import type { RejectReason, ReviewItem } from "@/lib/types";

const secondaryButton =
  "rounded-md border border-zinc-300 px-3 py-1 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";
const primaryButton =
  "rounded-md bg-zinc-900 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";

export default function ReviewPage() {
  // useSearchParams needs a Suspense boundary for the static prerender.
  return (
    <Suspense fallback={null}>
      <ReviewBoard />
    </Suspense>
  );
}

function ReviewBoard() {
  const { t } = useI18n();
  const params = useSearchParams();
  const me = useMe();
  const operate = me !== null && canOperate(me.role);

  const productIdParam = params.get("productId");
  const productId = useMemo(() => {
    if (productIdParam === null || productIdParam === "") {
      return null;
    }
    const n = Number(productIdParam);
    return Number.isInteger(n) && n > 0 ? n : null;
  }, [productIdParam]);

  const [items, setItems] = useState<ReviewItem[] | null>(null);
  const [focusedId, setFocusedId] = useState<number | null>(null);
  // The asset waiting for reject details in the dialog.
  const [rejecting, setRejecting] = useState<ReviewItem | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  // Product whose "approve remaining" button is asking for confirmation.
  const [confirmingProductId, setConfirmingProductId] = useState<number | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    const query = new URLSearchParams({ status: "IN_REVIEW" });
    if (productId !== null) {
      query.set("productId", String(productId));
    }
    return apiFetch<ReviewItem[]>(`/api/v1/content/assets?${query.toString()}`)
      .then((rows) => {
        setItems(rows);
        // Keep the focus when the asset is still there; decided assets are
        // gone after a refresh, so fall back to the top of the board.
        setFocusedId((current) =>
          current !== null && rows.some((r) => r.assetId === current)
            ? current
            : (rows[0]?.assetId ?? null),
        );
      })
      .catch((err: unknown) => {
        setItems([]);
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [productId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo(() => (items === null ? [] : groupBySku(items)), [items]);
  // Rendered order: section by section — exactly what focus walks.
  const order = useMemo(() => groups.flatMap((g) => g.items), [groups]);

  const decide = useCallback(
    async (
      item: ReviewItem,
      decision: Decision,
      reasonCodes: RejectReason[] = [],
      comment: string | null = null,
    ) => {
      // Compute the next focus from the pre-refresh list: the decided asset
      // is still in it, so nextFocus can anchor on it.
      const next = items === null ? null : nextFocus(items, focusedId, item.assetId);
      setBusyId(item.assetId);
      setError(null);
      try {
        await apiFetch(`/api/v1/content/assets/${item.assetId}/review`, {
          method: "POST",
          body: { decision, reasonCodes, comment },
        });
        await load();
        setFocusedId(next);
      } catch (err) {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      } finally {
        setBusyId(null);
      }
    },
    [items, focusedId, load, t],
  );

  const approveRemaining = useCallback(
    async (group: ReviewGroup) => {
      // The whole group is being approved: anchor past its last asset.
      const last = group.items.at(-1);
      const next =
        items !== null && last !== undefined ? nextFocus(items, focusedId, last.assetId) : null;
      setBulkBusy(true);
      setError(null);
      setConfirmingProductId(null);
      try {
        await apiFetch(`/api/v1/content/products/${group.productId}/review/approve-remaining`, {
          method: "POST",
        });
        await load();
        setFocusedId(next);
      } catch (err) {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      } finally {
        setBulkBusy(false);
      }
    },
    [items, focusedId, load, t],
  );

  const moveFocus = useCallback(
    (delta: 1 | -1) => {
      if (order.length === 0) {
        return;
      }
      const index = order.findIndex((x) => x.assetId === focusedId);
      if (index === -1) {
        setFocusedId(order[0].assetId);
        return;
      }
      const nextIndex = Math.min(Math.max(index + delta, 0), order.length - 1);
      setFocusedId(order[nextIndex].assetId);
    },
    [order, focusedId],
  );

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (rejecting !== null || confirmingProductId !== null) {
        return; // the dialog / confirmation handles its own keys
      }
      const target = event.target as HTMLElement | null;
      if (
        target !== null &&
        (target.tagName === "INPUT" ||
          target.tagName === "TEXTAREA" ||
          target.tagName === "SELECT" ||
          target.isContentEditable)
      ) {
        return; // typing context — the caller ignores shortcut keys
      }
      if (event.ctrlKey || event.metaKey || event.altKey) {
        return;
      }
      if (event.key === "ArrowRight" || event.key === "ArrowDown") {
        event.preventDefault();
        moveFocus(1);
        return;
      }
      if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
        event.preventDefault();
        moveFocus(-1);
        return;
      }
      if (!operate || busyId !== null || bulkBusy) {
        return;
      }
      const decision = keyToDecision(event.key);
      if (decision === null) {
        return;
      }
      const focused = order.find((x) => x.assetId === focusedId);
      if (focused === undefined) {
        return;
      }
      event.preventDefault();
      if (decision === "REJECT") {
        setRejecting(focused);
        return;
      }
      void decide(focused, decision);
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    rejecting,
    confirmingProductId,
    operate,
    busyId,
    bulkBusy,
    order,
    focusedId,
    moveFocus,
    decide,
  ]);

  // Keep the focused card on screen when the keyboard moves the focus.
  useEffect(() => {
    if (focusedId === null) {
      return;
    }
    document.getElementById(`review-asset-${focusedId}`)?.scrollIntoView({ block: "nearest" });
  }, [focusedId]);

  return (
    <div className="flex flex-col gap-6">
      {operate && order.length > 0 ? (
        <p className="text-xs text-zinc-500 dark:text-zinc-400">{t("review.hint")}</p>
      ) : null}

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {items === null ? (
        <p className="text-sm text-zinc-500">{t("loading")}</p>
      ) : groups.length === 0 ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("review.empty")}</p>
      ) : (
        groups.map((group) => (
          <section key={group.items[0].assetId} className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <h2 className="font-mono text-base font-semibold text-zinc-900 dark:text-zinc-100">
                {group.sku}
              </h2>
              <span className="text-sm text-zinc-700 dark:text-zinc-300">{group.productName}</span>
              {operate ? (
                confirmingProductId === group.productId ? (
                  <span className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="text-zinc-600 dark:text-zinc-400">
                      {t("review.confirmApproveRemaining", { n: group.items.length })}
                    </span>
                    <button
                      type="button"
                      onClick={() => void approveRemaining(group)}
                      disabled={bulkBusy}
                      className={primaryButton}
                    >
                      {t("review.confirmGo")}
                    </button>
                    <button
                      type="button"
                      onClick={() => setConfirmingProductId(null)}
                      disabled={bulkBusy}
                      className={secondaryButton}
                    >
                      {t("review.cancel")}
                    </button>
                  </span>
                ) : (
                  <button
                    type="button"
                    onClick={() => setConfirmingProductId(group.productId)}
                    disabled={bulkBusy}
                    className={secondaryButton}
                  >
                    {t("review.approveRemaining")}
                  </button>
                )
              ) : null}
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {group.items.map((item) => (
                <ReviewCard
                  key={item.assetId}
                  item={item}
                  focused={item.assetId === focusedId}
                  busy={item.assetId === busyId}
                  onFocus={() => setFocusedId(item.assetId)}
                />
              ))}
            </div>
          </section>
        ))
      )}

      {rejecting !== null ? (
        <RejectDialog
          busy={busyId === rejecting.assetId}
          onCancel={() => setRejecting(null)}
          onSubmit={(reasonCodes, comment) => {
            const target = rejecting;
            setRejecting(null);
            void decide(target, "REJECT", reasonCodes, comment);
          }}
        />
      ) : null}
    </div>
  );
}

function ReviewCard({
  item,
  focused,
  busy,
  onFocus,
}: {
  item: ReviewItem;
  focused: boolean;
  busy: boolean;
  onFocus: () => void;
}) {
  const { t } = useI18n();
  const flagged = item.flags.length > 0;
  return (
    <div
      id={`review-asset-${item.assetId}`}
      onClick={onFocus}
      className={`flex flex-col gap-2 rounded-lg border bg-white p-2 dark:bg-zinc-900 ${
        flagged ? "border-red-500" : "border-zinc-200 dark:border-zinc-800"
      } ${focused ? "ring-2 ring-blue-500" : ""} ${busy ? "opacity-50" : ""}`}
    >
      <div className="flex gap-2">
        <a href={item.imageUrl ?? undefined} target="_blank" rel="noreferrer" className="w-1/2">
          {item.thumbUrl !== null ? (
            // eslint-disable-next-line @next/next/no-img-element -- presigned storage URLs
            <img
              src={item.thumbUrl}
              alt={item.fileName ?? item.sku ?? item.specCode}
              className="aspect-[3/4] w-full rounded bg-zinc-50 object-contain dark:bg-zinc-800/50"
            />
          ) : (
            <div className="aspect-[3/4] w-full rounded bg-zinc-100 dark:bg-zinc-800" />
          )}
        </a>
        <a
          href={item.sourceUrl ?? undefined}
          target="_blank"
          rel="noreferrer"
          title={t("review.sourcePhoto")}
          className="w-1/2"
        >
          {item.sourceThumbUrl !== null ? (
            // eslint-disable-next-line @next/next/no-img-element -- presigned storage URLs
            <img
              src={item.sourceThumbUrl}
              alt=""
              className="aspect-[3/4] w-full rounded bg-zinc-50 object-contain dark:bg-zinc-800/50"
            />
          ) : (
            <div className="aspect-[3/4] w-full rounded bg-zinc-100 dark:bg-zinc-800" />
          )}
        </a>
      </div>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-zinc-600 dark:text-zinc-400">
        <span className="font-medium text-zinc-800 dark:text-zinc-200">{specText(t, item.specCode)}</span>
        {item.variant !== null ? <span className="font-mono">{item.variant}</span> : null}
        <span>v{item.version}</span>
        {item.flags.map((flag) => (
          <span
            key={flag}
            title={metricsTitle(item.metrics)}
            className="rounded bg-red-100 px-1.5 py-0.5 font-medium text-red-700 dark:bg-red-900/40 dark:text-red-400"
          >
            {t(`precheck.${flag}`)}
          </span>
        ))}
      </div>
    </div>
  );
}

/** Localized spec code; unknown codes are shown as-is (like stock statuses). */
function specText(t: TFunction, code: string): string {
  const key = `spec.${code}` as MessageKey;
  return key in en ? t(key) : code;
}

/** Precheck numbers for the flag badge tooltip, one "name: value" per line. */
function metricsTitle(metrics: Record<string, unknown>): string {
  return Object.entries(metrics)
    .map(([name, value]) => `${name}: ${typeof value === "number" ? Math.round(value * 1000) / 1000 : String(value)}`)
    .join("\n");
}
