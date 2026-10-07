/**
 * Mirrors of the backend DTOs (Java records, camelCase as serialized by
 * Jackson). Keep the field names in sync with kiano-api.
 */

export type ContentTier = "HERO" | "STANDARD";
export type ShotState = "OK" | "RESHOOT" | "MISSING";
export type TaskStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
export type MediaKind = "PHOTO" | "VIDEO";
export type ImportOutcome = "IMPORTED" | "DUPLICATE";

export type QcReason =
  | "LOW_RESOLUTION"
  | "BLURRY"
  | "OVEREXPOSED"
  | "UNDEREXPOSED"
  | "NOT_PORTRAIT"
  | "FPS_OUT_OF_RANGE"
  | "DURATION_OUT_OF_RANGE";

/** One row of GET /api/v1/content/products. */
export interface ContentProductSummary {
  productId: number;
  sku: string;
  name: string;
  type: string;
  status: string;
  regularPrice: number | null;
  salePrice: number | null;
  price: number | null;
  stockStatus: string;
  imageUrl: string | null;
  tier: ContentTier;
  required: number;
  ok: number;
  reshoot: number;
  missing: number;
  complete: boolean;
}

/** One checklist line of GET /api/v1/content/products/{id}/shots. */
export interface ShotStatusLine {
  code: string;
  kind: MediaKind;
  required: boolean;
  state: ShotState;
  mediaId: number | null;
  reasons: QcReason[];
  guidanceEn: string | null;
  guidanceZh: string | null;
  thumbUrl: string | null;
  mediaUrl: string | null;
}

export interface ProductShotStatus {
  productId: number;
  sku: string;
  name: string;
  tier: ContentTier;
  lines: ShotStatusLine[];
  complete: boolean;
}

/** One imported file result of POST /api/v1/content/source-media. */
export interface ImportResult {
  fileName: string;
  outcome: ImportOutcome;
  mediaId: number;
  productId: number;
  sku: string;
  shotCode: string;
  status: string;
  reasons: QcReason[];
}

/** One entry of GET /api/v1/content/reshoot-list. */
export interface ReshootLine {
  sku: string;
  productName: string;
  tier: ContentTier;
  shotCode: string;
  state: ShotState;
  reasons: QcReason[];
  guidanceEn: string | null;
  guidanceZh: string | null;
}

/** Read-only view of a platform_task row (GET /api/v1/commerce/sync/latest). */
export interface TaskView {
  id: number;
  type: string;
  status: TaskStatus;
  attempts: number;
  result: unknown;
  lastError: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}
