import { describe, expect, test } from "vitest";
import {
  AD_HOOKS,
  AD_VARIANTS,
  adCopyFieldLimit,
  adCopyLimits,
  adCopyOverCount,
  adCopyStatuses,
  adGrid,
  adHookOfAsset,
  adRenderIssues,
  approvedStaticCounts,
  parseAdCopy,
  serializeAdCopy,
} from "./ads";
import type { ReviewItem } from "./types";

function adAsset(
  assetId: number,
  hook: string,
  status: ReviewItem["status"] = "IN_REVIEW",
): ReviewItem {
  return {
    assetId,
    productId: 7,
    sku: "SKU-A",
    productName: "Product A",
    specCode: "AD_COPY",
    variant: hook,
    version: 1,
    status,
    flags: [],
    metrics: {},
    imageUrl: null,
    thumbUrl: null,
    sourceThumbUrl: null,
    sourceUrl: null,
    fileName: null,
    kind: "TEXT",
    textBody: null,
    charCount: null,
    factVersion: null,
  };
}

describe("AD_VARIANTS", () => {
  test("has 12 variants in the fixed hook/size order", () => {
    expect(AD_VARIANTS).toHaveLength(12);
    expect(AD_VARIANTS).toEqual([
      "pricehook-1080x1080",
      "pricehook-1080x1350",
      "pricehook-1080x1920",
      "problem-1080x1080",
      "problem-1080x1350",
      "problem-1080x1920",
      "demo-1080x1080",
      "demo-1080x1350",
      "demo-1080x1920",
      "trust-1080x1080",
      "trust-1080x1350",
      "trust-1080x1920",
    ]);
    // 4 hooks × 3 sizes, grouped by hook.
    expect(new Set(AD_VARIANTS).size).toBe(12);
    expect(
      AD_VARIANTS.every((v) => AD_HOOKS.some((h) => v.startsWith(`${h}-`))),
    ).toBe(true);
  });
});

describe("adGrid", () => {
  test("parses a variant into hook and pixel size", () => {
    expect(adGrid("pricehook-1080x1080")).toEqual({
      hook: "pricehook",
      width: 1080,
      height: 1080,
    });
    expect(adGrid("demo-1080x1920")).toEqual({
      hook: "demo",
      width: 1080,
      height: 1920,
    });
  });

  test("rejects malformed variants", () => {
    expect(() => adGrid("pricehook")).toThrow();
    expect(() => adGrid("1080x1080")).toThrow();
    expect(() => adGrid("Pricehook-1080x1080")).toThrow();
    expect(() => adGrid("pricehook-1080x")).toThrow();
    expect(() => adGrid("")).toThrow();
  });
});

describe("adCopyLimits", () => {
  test("exposes the backend limits per field", () => {
    expect(adCopyLimits.overlay).toBe(40);
    expect(adCopyLimits.headline).toBe(40);
    expect(adCopyLimits.primaryText).toBe(125);
    expect(adCopyFieldLimit("overlay")).toBe(40);
    expect(adCopyFieldLimit("headline")).toBe(40);
    expect(adCopyFieldLimit("primaryText")).toBe(125);
  });

  test("counts over-limit characters, zero when within", () => {
    expect(adCopyOverCount("overlay", 40)).toBe(0);
    expect(adCopyOverCount("overlay", 45)).toBe(5);
    expect(adCopyOverCount("headline", 40)).toBe(0);
    expect(adCopyOverCount("headline", 47)).toBe(7);
    expect(adCopyOverCount("primaryText", 125)).toBe(0);
    expect(adCopyOverCount("primaryText", 130)).toBe(5);
  });
});

describe("parseAdCopy", () => {
  test("tolerates null, empty and malformed bodies", () => {
    expect(parseAdCopy(null)).toEqual({ overlay: "", headline: "", primaryText: "" });
    expect(parseAdCopy("")).toEqual({ overlay: "", headline: "", primaryText: "" });
    expect(parseAdCopy("   ")).toEqual({ overlay: "", headline: "", primaryText: "" });
    expect(parseAdCopy("not json")).toEqual({ overlay: "", headline: "", primaryText: "" });
  });

  test("reads the three fields and drops unknown/typed-off values", () => {
    const body = JSON.stringify({
      overlay: "Flash Sale",
      headline: "Up to 50% off",
      primaryText: "Limited time only, while stocks last.",
    });
    expect(parseAdCopy(body)).toEqual({
      overlay: "Flash Sale",
      headline: "Up to 50% off",
      primaryText: "Limited time only, while stocks last.",
    });
    // wrong types → empty field
    expect(
      parseAdCopy(JSON.stringify({ overlay: 5, headline: "H", primaryText: null })),
    ).toEqual({ overlay: "", headline: "H", primaryText: "" });
  });

  test("serializeAdCopy round-trips", () => {
    const copy = { overlay: "A", headline: "B", primaryText: "C" };
    expect(parseAdCopy(serializeAdCopy(copy))).toEqual(copy);
  });
});

describe("adHookOfAsset", () => {
  test("resolves bare hooks, full variants, and rejects others", () => {
    expect(adHookOfAsset({ specCode: "AD_COPY", variant: "pricehook" })).toBe("pricehook");
    expect(adHookOfAsset({ specCode: "AD_COPY", variant: "demo-1080x1350" })).toBe("demo");
    expect(adHookOfAsset({ specCode: "AD_COPY", variant: null })).toBeNull();
    expect(adHookOfAsset({ specCode: "AD_STATIC", variant: "trust-1080x1080" })).toBeNull();
    expect(adHookOfAsset({ specCode: "AD_COPY", variant: "bad!" })).toBeNull();
  });
});

describe("adCopyStatuses", () => {
  test("keeps the latest approved/in-review asset per hook", () => {
    const items = [
      adAsset(1, "pricehook", "REJECTED"),
      adAsset(2, "pricehook", "IN_REVIEW"),
      adAsset(3, "pricehook", "APPROVED"),
      adAsset(4, "trust", "APPROVED"),
    ];
    const statuses = adCopyStatuses(items);
    expect(statuses).toHaveLength(2);
    const price = statuses.find((s) => s.hook === "pricehook");
    expect(price?.asset?.assetId).toBe(3); // APPROVED beats IN_REVIEW
    const trust = statuses.find((s) => s.hook === "trust");
    expect(trust?.asset?.assetId).toBe(4);
  });

  test("ignores non-AD_COPY assets and hooks without assets", () => {
    const items = [
      adAsset(1, "pricehook", "APPROVED"),
      { ...adAsset(2, "demo", "IN_REVIEW"), specCode: "AD_STATIC" },
    ];
    const statuses = adCopyStatuses(items);
    expect(statuses).toHaveLength(1);
    expect(statuses[0].hook).toBe("pricehook");
  });
});
describe("approvedStaticCounts", () => {
  function staticAsset(assetId: number, productId: number, variant: string): ReviewItem {
    return { ...adAsset(assetId, "pricehook", "APPROVED"), productId, specCode: "AD_STATIC", variant };
  }

  test("counts distinct approved variants, not approved rows", () => {
    const rows = [
      staticAsset(1, 7, "pricehook-1080x1080"),
      // an older approved version of the same variant must not count twice
      staticAsset(2, 7, "pricehook-1080x1080"),
      staticAsset(3, 7, "trust-1080x1920"),
      staticAsset(4, 8, "demo-1080x1350"),
      { ...adAsset(5, "pricehook", "APPROVED"), productId: 7 }, // AD_COPY ignored
    ];
    expect(approvedStaticCounts(rows)).toEqual({ 7: 2, 8: 1 });
  });
});

describe("adRenderIssues", () => {
  test("lists skipped variants except up-to-date ones, in variant order", () => {
    expect(
      adRenderIssues({
        status: "SUCCEEDED",
        rendered: ["pricehook-1080x1080"],
        skipped: {
          "trust-1080x1080": "POLICY_BADGES_MISSING",
          "demo-1080x1080": "DEMO_FRAME_UNAVAILABLE",
          "pricehook-1080x1350": "UP_TO_DATE",
        },
        lastError: null,
      }),
    ).toEqual([
      { variant: "demo-1080x1080", code: "DEMO_FRAME_UNAVAILABLE" },
      { variant: "trust-1080x1080", code: "POLICY_BADGES_MISSING" },
    ]);
  });

  test("a failed task reports its error code; no status means no issues", () => {
    expect(
      adRenderIssues({
        status: "FAILED",
        rendered: [],
        skipped: {},
        lastError: "TEMPLATE_NOT_FOUND: No approved template for AD_DEMO",
      }),
    ).toEqual([{ variant: null, code: "TEMPLATE_NOT_FOUND" }]);
    expect(adRenderIssues(null)).toEqual([]);
  });
});
