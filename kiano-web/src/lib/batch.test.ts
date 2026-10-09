import { expect, test } from "vitest";
import {
  STAGE_ORDER,
  blockedReasonKey,
  formatDuration,
  funnel,
  reviewTimeVerdict,
  selectableForProduction,
  stageLabelKey,
} from "./batch";
import type { BatchItemRow, ReviewTimeStats } from "./types";
import { en } from "@/i18n/en";

test("funnel walks STAGE_ORDER, accumulating the reached items", () => {
  const rows = funnel({ WAITING_SHOTS: 2, IMAGES: 1, IN_REVIEW: 3, BLOCKED: 4 });

  expect(rows.map((row) => row.stage)).toEqual(STAGE_ORDER);
  // reached: 2 + 1 + 3 = 6, then 4, 3, 0 ...
  expect(rows[0]).toEqual({ stage: "WAITING_SHOTS", count: 2, cumulative: 6 });
  expect(rows[1]).toEqual({ stage: "IMAGES", count: 1, cumulative: 4 });
  expect(rows[5]).toEqual({ stage: "IN_REVIEW", count: 3, cumulative: 3 });
  expect(rows[6]).toEqual({ stage: "STAGING", count: 0, cumulative: 0 });
  // blocked items are listed separately: they never add to the funnel
  expect(rows.reduce((sum, row) => sum + row.count, 0)).toBe(6);
});

test("selectableForProduction only returns READY_FOR_PRODUCTION SKUs", () => {
  const item = (productId: number, stage: BatchItemRow["stage"]): BatchItemRow => ({
    productId,
    sku: `MG-${productId}`,
    name: null,
    stage,
    imageLane: "GENERATING",
    factLane: "GENERATING",
    blockedReason: null,
    blockedDetails: {},
    stagingStatus: null,
    productionStatus: null,
  });

  expect(
    selectableForProduction([
      item(1, "READY_FOR_PRODUCTION"),
      item(2, "STAGING"),
      item(3, "READY_FOR_PRODUCTION"),
      item(4, "BLOCKED"),
    ]),
  ).toEqual([1, 3]);
});

test("formatDuration boundaries", () => {
  expect(formatDuration(0)).toBe("0s");
  expect(formatDuration(45)).toBe("45s");
  expect(formatDuration(60)).toBe("1m");
  expect(formatDuration(65)).toBe("1m 5s");
  expect(formatDuration(900)).toBe("15m");
  expect(formatDuration(3600)).toBe("1h");
  expect(formatDuration(3600 + 60)).toBe("1h 1m");
  expect(formatDuration(8100)).toBe("2h 15m");
  expect(formatDuration(-5)).toBe("0s");
});

test("reviewTimeVerdict needs five SKUs and compares against the target", () => {
  const stats = (skuCount: number, avgTotalSeconds: number): ReviewTimeStats => ({
    skuCount,
    avgFactSeconds: 0,
    avgAssetSeconds: 0,
    avgTotalSeconds,
    p90TotalSeconds: 0,
    targetSeconds: 180,
  });

  expect(reviewTimeVerdict(stats(4, 100))).toBe("NOT_ENOUGH_DATA");
  expect(reviewTimeVerdict(stats(5, 180))).toBe("ON_TARGET");
  expect(reviewTimeVerdict(stats(5, 240))).toBe("OVER");
});

test("stage and blocked-reason keys exist in the dictionary", () => {
  for (const stage of [...STAGE_ORDER, "BLOCKED"] as const) {
    expect(en[stageLabelKey(stage)], `missing ${stage}`).toBeDefined();
  }
  const reasons = [
    "PRODUCT_GONE",
    "NOW_HERO",
    "SHOTS_RESHOOT",
    "PIPELINE_FAILED",
    "NO_FACT_SOURCES",
    "FACT_DRAFT_FAILED",
    "STAGING_PRODUCT_MISSING",
    "PUBLISH_FAILED",
    "POLICY_INCOMPLETE",
    "INTERNAL",
  ];
  for (const reason of reasons) {
    expect(en[blockedReasonKey(reason)], `missing ${reason}`).toBeDefined();
  }
});