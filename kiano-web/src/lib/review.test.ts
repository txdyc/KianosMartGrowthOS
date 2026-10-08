import { describe, expect, test } from "vitest";
import { groupBySku, keyToDecision, nextFocus } from "./review";
import type { ReviewItem } from "./types";

function item(assetId: number, sku: string, status: ReviewItem["status"] = "IN_REVIEW"): ReviewItem {
  return {
    assetId,
    productId: 7,
    sku,
    productName: `Product ${sku}`,
    specCode: "PAGE_MAIN",
    variant: null,
    version: 1,
    status,
    flags: [],
    metrics: {},
    imageUrl: null,
    thumbUrl: null,
    sourceThumbUrl: null,
    sourceUrl: null,
    fileName: null,
  };
}

describe("groupBySku", () => {
  test("keeps backend order and groups", () => {
    const groups = groupBySku([
      item(1, "SKU-B"),
      item(2, "SKU-A"),
      item(3, "SKU-B"),
      item(4, "SKU-A"),
    ]);
    expect(groups.map((g) => g.sku)).toEqual(["SKU-B", "SKU-A"]);
    expect(groups[0].items.map((i) => i.assetId)).toEqual([1, 3]);
    expect(groups[1].items.map((i) => i.assetId)).toEqual([2, 4]);
    expect(groups[0].productId).toBe(7);
    expect(groups[0].productName).toBe("Product SKU-B");
  });
});

describe("nextFocus", () => {
  test("moves within group then to next group, null at end", () => {
    const items = [
      item(1, "SKU-A"),
      item(2, "SKU-A"),
      item(3, "SKU-A"),
      item(4, "SKU-B"),
      item(5, "SKU-B"),
    ];
    // The list is not refreshed yet: the decided asset still shows IN_REVIEW.
    expect(nextFocus(items, 1, 1)).toBe(2);
    items[0].status = "APPROVED";
    expect(nextFocus(items, 2, 2)).toBe(3);
    items[1].status = "APPROVED";
    // SKU-A is exhausted → the first pending asset of SKU-B.
    expect(nextFocus(items, 3, 3)).toBe(4);
    items[3].status = "REJECTED";
    // The last pending asset overall → nothing left to focus.
    expect(nextFocus(items, 5, 5)).toBeNull();
  });

  test("follows SKU groups, not the flat flagged-first order", () => {
    // Backend order: flagged SKU-B first, then unflagged SKU-A and SKU-B.
    const items = [
      item(4, "SKU-B"),
      item(1, "SKU-A"),
      item(2, "SKU-A"),
      item(5, "SKU-B"),
    ];
    // Groups: SKU-B [4, 5], SKU-A [1, 2]. Deciding 5 exhausts SKU-B, so the
    // focus moves to the next group (SKU-A) instead of falling off the list.
    expect(nextFocus(items, 5, 5)).toBe(1);
  });
});

describe("keyToDecision", () => {
  test("maps a/A, r, g and ignores others", () => {
    expect(keyToDecision("a")).toBe("APPROVE");
    expect(keyToDecision("A")).toBe("APPROVE");
    expect(keyToDecision("r")).toBe("REJECT");
    expect(keyToDecision("g")).toBe("REGENERATE");
    expect(keyToDecision("x")).toBeNull();
    expect(keyToDecision("ArrowLeft")).toBeNull();
    expect(keyToDecision("")).toBeNull();
  });
});
