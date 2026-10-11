import { en } from "@/i18n/en";
import type { MessageKey } from "@/i18n/shared";
import type { BatchItemRow, BatchStage, ReviewTimeStats } from "./types";

/**
 * C6 batch helpers: the stage order mirrors kiano-api BatchStage (the funnel
 * and the dashboard walk this list), plus the label keys and the small pure
 * functions of the batch pages.
 */
export const STAGE_ORDER: BatchStage[] = [
  "WAITING_SHOTS",
  "IMAGES",
  "FACTS_DRAFTING",
  "AWAITING_FACT_LOCK",
  "GENERATING",
  "IN_REVIEW",
  "STAGING",
  "READY_FOR_PRODUCTION",
  "PRODUCTION",
  "DONE",
];

/** Default spacing between two batch publishes (kiano.batch.publish-interval). */
export const PUBLISH_INTERVAL_SECONDS = 10;

/** i18n key of one stage label (BLOCKED included). */
export function stageLabelKey(stage: BatchStage): MessageKey {
  return `batch.stage.${stage}` as MessageKey;
}

/**
 * i18n key of one blocked reason code (INTERNAL included). A failed automatic
 * action records the business error's own code (e.g. COPY_POLICY_OUTDATED):
 * its `error.*` text is shown, and a code without any text falls back to the
 * generic internal reason.
 */
export function blockedReasonKey(code: string): MessageKey {
  const reasonKey = `batch.blocked.${code}` as MessageKey;
  if (reasonKey in en) {
    return reasonKey;
  }
  const errorKey = `error.${code}` as MessageKey;
  return errorKey in en ? errorKey : "batch.blocked.INTERNAL";
}

/** "2h 15m", "15m 30s", "45s" — negative values clamp to "0s". */
export function formatDuration(seconds: number): string {
  const total = Number.isFinite(seconds) ? Math.max(0, Math.round(seconds)) : 0;
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  if (hours > 0) {
    return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  }
  if (minutes > 0) {
    return secs > 0 ? `${minutes}m ${secs}s` : `${minutes}m`;
  }
  return `${secs}s`;
}

/**
 * Funnel rows over STAGE_ORDER: the count currently in the stage and how many
 * items reached it (that stage or a later one). BLOCKED items are listed
 * separately, so they never add to the funnel.
 */
export function funnel(
  stageCounts: Partial<Record<BatchStage, number>>,
): { stage: BatchStage; count: number; cumulative: number }[] {
  const counts = STAGE_ORDER.map((stage) => stageCounts[stage] ?? 0);
  let cumulative = counts.reduce((sum, value) => sum + value, 0);
  return STAGE_ORDER.map((stage, index) => {
    const row = { stage, count: counts[index], cumulative };
    cumulative -= counts[index];
    return row;
  });
}

/** The SKUs the OWNER may select for production (ready, or a failed production retry). */
export function selectableForProduction(items: BatchItemRow[]): number[] {
  return items
    .filter(
      (item) =>
        item.stage === "READY_FOR_PRODUCTION" ||
        // a FAILED production publish is retried by the same OWNER action
        (item.stage === "BLOCKED" && item.blockedReason === "PRODUCTION_FAILED"),
    )
    .map((item) => item.productId);
}

export type ReviewTimeVerdict = "ON_TARGET" | "OVER" | "NOT_ENOUGH_DATA";

/** The acceptance verdict of the review-time card (needs 5 finished SKUs). */
export function reviewTimeVerdict(stats: ReviewTimeStats): ReviewTimeVerdict {
  if (stats.skuCount < 5) {
    return "NOT_ENOUGH_DATA";
  }
  return stats.avgTotalSeconds <= stats.targetSeconds ? "ON_TARGET" : "OVER";
}