import type { ReviewItem } from "./types";

/** One SKU section of the review board. */
export interface ReviewGroup {
  sku: string;
  productId: number;
  productName: string;
  items: ReviewItem[];
}

export type Decision = "APPROVE" | "REJECT" | "REGENERATE";

/**
 * Groups review assets by SKU. Group order and the order within a group both
 * follow the backend list (flagged first, then spec order), so interleaved
 * SKUs — possible because flagged assets of every SKU sort first — end up in
 * contiguous sections.
 */
export function groupBySku(items: ReviewItem[]): ReviewGroup[] {
  const groups: ReviewGroup[] = [];
  const bySku = new Map<string, ReviewGroup>();
  for (const item of items) {
    const key = item.sku ?? "";
    let group = bySku.get(key);
    if (group === undefined) {
      group = {
        sku: item.sku ?? "",
        productId: item.productId,
        productName: item.productName ?? "",
        items: [],
      };
      bySku.set(key, group);
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}

/**
 * Where the keyboard focus goes after a decision on `decidedId`: the next
 * pending asset of the same SKU group, else the first pending one of the
 * following groups, else null. `currentId` is the fallback anchor when the
 * decided asset already left the list (e.g. after a refresh).
 */
export function nextFocus(
  items: ReviewItem[],
  currentId: number | null,
  decidedId: number,
): number | null {
  const anchorId = items.some((x) => x.assetId === decidedId) ? decidedId : currentId;
  const groups = groupBySku(items);
  const groupIndex = groups.findIndex((g) => g.items.some((x) => x.assetId === anchorId));

  // Next pending asset in the anchor's own group.
  if (groupIndex !== -1) {
    const group = groups[groupIndex];
    const anchorIndex = group.items.findIndex((x) => x.assetId === anchorId);
    for (let i = anchorIndex + 1; i < group.items.length; i += 1) {
      if (group.items[i].status === "IN_REVIEW") {
        return group.items[i].assetId;
      }
    }
  }

  // First pending asset of the following groups — all of them when the anchor
  // is unknown (groupIndex -1), so a fresh board still yields a focus.
  for (let g = groupIndex + 1; g < groups.length; g += 1) {
    const pending = groups[g].items.find((x) => x.status === "IN_REVIEW");
    if (pending !== undefined) {
      return pending.assetId;
    }
  }
  return null;
}

/**
 * Keyboard shortcut → decision. Callers ignore keys while focus is in an
 * input. Only a/A map to APPROVE per the board spec; r and g are lowercase.
 */
export function keyToDecision(key: string): Decision | null {
  switch (key) {
    case "a":
    case "A":
      return "APPROVE";
    case "r":
      return "REJECT";
    case "g":
      return "REGENERATE";
    default:
      return null;
  }
}
