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

/**
 * GET/PUT /api/v1/integrations/woocommerce. An unconfigured GET returns only
 * {configured: false}; the other fields are then absent.
 */
export interface WooStatus {
  baseUrl?: string;
  username?: string;
  configured: boolean;
  lastSyncAt?: string | null;
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

/** Steps of the C2 image pipeline (kiano-api workerprotocol.JobStep). */
export type JobStep =
  | "CUTOUT"
  | "SCENE_INPUT"
  | "WHITE_MAIN"
  | "WHITE_ANGLE"
  | "INBOX"
  | "SCENE";

/** Lifecycle of a generation job (kiano-api content.generation.JobStatus). */
export type JobStatus =
  | "QUEUED"
  | "LEASED"
  | "WAITING_EXECUTOR"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";

/** Executors a worker can run (kiano-api workerprotocol.ExecutorType). */
export type ExecutorType = "COMFYUI" | "COMPOSITE";

/** Status of a generation run (generation_run.status). */
export type RunStatus = "RUNNING" | "DONE" | "PARTIAL";

/** Automatic precheck flags stored on an asset (content.asset.PrecheckFlag). */
export type PrecheckFlag =
  | "PRODUCT_MISMATCH"
  | "AI_TEXT"
  | "EDGE_NOT_WHITE"
  | "OCCUPANCY_OUT_OF_RANGE";

/** Human reject reasons on the review board (content.asset.RejectReason). */
export type RejectReason =
  | "PRODUCT_MISMATCH"
  | "AI_ARTIFACT"
  | "WRONG_FACT"
  | "TEXT_ERROR"
  | "STYLE"
  | "LOW_QUALITY"
  | "POLICY";

/** Lifecycle of an asset (content.asset.AssetStatus). */
export type AssetStatus = "IN_REVIEW" | "APPROVED" | "REJECTED";

/** One row of GET /api/v1/content/workers. */
export interface WorkerStatus {
  workerId: string;
  lastSeenAt: string;
  online: boolean;
  capabilities: ExecutorType[];
  unavailable: ExecutorType[];
}

/** One job of GET /api/v1/content/products/{id}/image-pipeline. */
export interface PipelineJob {
  id: number;
  step: JobStep;
  variant: string | null;
  executor: ExecutorType;
  status: JobStatus;
  attempts: number;
  maxAttempts: number;
  error: string | null;
  gpuSeconds: number | null;
  createdAt: string;
  finishedAt: string | null;
}

/** Latest run of GET /api/v1/content/products/{id}/image-pipeline (204 → undefined). */
export interface PipelineRun {
  runId: number;
  status: RunStatus;
  createdAt: string;
  finishedAt: string | null;
  jobs: PipelineJob[];
}

/** One row of GET /api/v1/content/assets. */
export interface ReviewItem {
  assetId: number;
  productId: number;
  sku: string | null;
  productName: string | null;
  specCode: string;
  variant: string | null;
  version: number;
  status: AssetStatus;
  flags: PrecheckFlag[];
  metrics: Record<string, unknown>;
  imageUrl: string | null;
  thumbUrl: string | null;
  sourceThumbUrl: string | null;
  sourceUrl: string | null;
  fileName: string | null;
}
