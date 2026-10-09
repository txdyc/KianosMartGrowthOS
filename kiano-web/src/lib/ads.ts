import type { AdCopyStatus, AdRenderStatus, ReviewItem } from "./types";

/**
 * C4a static ad helpers. The armed hooks and the 12-variant grid mirror the
 * backend AdRenderService exactly — keep AD_VARIANTS in the same order.
 */

/** The four copy hooks of a static ad, in backend render order. */
export const AD_HOOKS = ["pricehook", "problem", "demo", "trust"] as const;
export type AdHook = (typeof AD_HOOKS)[number];

/** Aspect ratios per hook, 1080x1080 → 1080x1350 → 1080x1920. */
const AD_SIZES = ["1080x1080", "1080x1350", "1080x1920"] as const;

/** The 12 variants as "pricehook-1080x1080", fixed order = AdRenderService.allVariants. */
export const AD_VARIANTS: string[] = AD_HOOKS.flatMap((hook) =>
  AD_SIZES.map((size) => `${hook}-${size}`),
);

/** Parse a variant into its hook and pixel size. Throws on anything else. */
export function adGrid(variant: string): { hook: string; width: number; height: number } {
  const match = /^([a-z]+)-(\d+)x(\d+)$/.exec(variant);
  if (match === null) {
    throw new Error(`Invalid ad variant: ${variant}`);
  }
  return { hook: match[1], width: Number(match[2]), height: Number(match[3]) };
}

/** The three variants (one per size) of a single hook, in render order. */
export function adHookVariants(hook: string): string[] {
  return AD_SIZES.map((size) => `${hook}-${size}`);
}

/** The three editable fields of an AD_COPY asset (backed by a JSON textBody). */
export const adCopyFields = ["overlay", "headline", "primaryText"] as const;
export type AdCopyField = (typeof adCopyFields)[number];

/** Char limits for each AD_COPY field, matching checkAdCopy on the backend. */
export const adCopyLimits = {
  overlay: 40,
  headline: 40,
  primaryText: 125,
} as const;

/** Limit of one AD_COPY field (review.ts charLimit style). */
export function adCopyFieldLimit(field: AdCopyField): number {
  return adCopyLimits[field];
}

/**
 * Characters over the limit for a field (0 when within it). The UI shows this
 * with the shared review.charOver `{n} / {limit}` message key.
 */
export function adCopyOverCount(field: AdCopyField, length: number): number {
  return Math.max(0, length - adCopyLimits[field]);
}

/** Safely parse an AD_COPY textBody JSON; malformed/empty input → all-empty. */
export function parseAdCopy(
  body: string | null,
): { overlay: string; headline: string; primaryText: string } {
  if (body === null || body.trim() === "") {
    return { overlay: "", headline: "", primaryText: "" };
  }
  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    return {
      overlay: typeof parsed.overlay === "string" ? parsed.overlay : "",
      headline: typeof parsed.headline === "string" ? parsed.headline : "",
      primaryText: typeof parsed.primaryText === "string" ? parsed.primaryText : "",
    };
  } catch {
    return { overlay: "", headline: "", primaryText: "" };
  }
}

/** Serialize the three fields back to the JSON textBody the backend expects. */
export function serializeAdCopy(copy: {
  overlay: string;
  headline: string;
  primaryText: string;
}): string {
  return JSON.stringify(copy);
}

/**
 * The hook an AD_COPY asset belongs to. AD_COPY assets carry a variant naming
 * either the bare hook ("pricehook") or a full variant ("pricehook-1080x1080");
 * anything else returns null. Non-AD_COPY items are never part of a hook.
 */
export function adHookOfAsset(asset: {
  specCode: string;
  variant: string | null;
}): string | null {
  if (asset.specCode !== "AD_COPY") {
    return null;
  }
  const variant = asset.variant ?? "";
  if (variant === "") {
    return null;
  }
  let hook: string;
  if (variant.includes("-")) {
    try {
      hook = adGrid(variant).hook;
    } catch {
      return null;
    }
  } else {
    hook = variant;
  }
  return AD_HOOKS.includes(hook as AdHook) ? hook : null;
}

/**
 * Latest AD_COPY asset per hook (preferring approved over in-review). Returns
 * the sparse set of hooks that actually have assets.
 */
export function adCopyStatuses(items: ReviewItem[]): AdCopyStatus[] {
  const perHook = new Map<string, ReviewItem>();
  for (const item of items) {
    if (item.specCode !== "AD_COPY") {
      continue;
    }
    const hook = adHookOfAsset(item);
    if (hook === null) {
      continue;
    }
    const current = perHook.get(hook);
    if (current === undefined || adRank(item) > adRank(current)) {
      perHook.set(hook, item);
    }
  }
  return [...perHook.entries()].map(([hook, asset]) => ({ hook, asset }));
}

/** APPROVED beats IN_REVIEW beats REJECTED; ties resolved by newest assetId. */
function adRank(item: ReviewItem): number {
  const statusRank = item.status === "APPROVED" ? 2 : item.status === "IN_REVIEW" ? 1 : 0;
  return statusRank * 1_000_000 + item.assetId;
}
/**
 * Approved AD_STATIC variants per product (the n of n/12). Distinct variants,
 * not rows: an older approved version of a variant must not count twice.
 */
export function approvedStaticCounts(rows: ReviewItem[]): Record<number, number> {
  const variants = new Map<number, Set<string>>();
  for (const row of rows) {
    if (row.specCode !== "AD_STATIC" || row.status !== "APPROVED" || row.variant === null) {
      continue;
    }
    const set = variants.get(row.productId) ?? new Set<string>();
    set.add(row.variant);
    variants.set(row.productId, set);
  }
  const counts: Record<number, number> = {};
  for (const [productId, set] of variants) {
    counts[productId] = set.size;
  }
  return counts;
}

/** One problem of the latest render: a skipped variant, or the whole task (variant null). */
export interface AdRenderIssue {
  variant: string | null;
  code: string;
}

/**
 * Problems to show after the product's latest AD_RENDER: skipped variants in
 * export order (UP_TO_DATE is not a problem), or the failed task's error code.
 */
export function adRenderIssues(status: AdRenderStatus | null): AdRenderIssue[] {
  if (status === null) {
    return [];
  }
  if (status.status === "FAILED") {
    const code = (status.lastError ?? "").split(":")[0].trim() || "INTERNAL_ERROR";
    return [{ variant: null, code }];
  }
  return AD_VARIANTS.filter(
    (variant) => status.skipped[variant] !== undefined && status.skipped[variant] !== "UP_TO_DATE",
  ).map((variant) => ({ variant, code: status.skipped[variant] }));
}
