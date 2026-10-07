/**
 * English dictionary. Dot-named keys; the zh.ts mirror must keep exactly the
 * same key set (enforced by its Record<MessageKey, string> type).
 */
export const en = {
  // navigation
  "nav.products": "Products",
  "nav.import": "Import",
  "nav.reshoot": "Reshoot list",
  "nav.settings": "Settings",
  "nav.logout": "Logout",

  // login
  "login.title": "Sign in",
  "login.email": "Email",
  "login.password": "Password",
  "login.submit": "Sign in",

  // ShotState
  "state.OK": "OK",
  "state.RESHOOT": "Reshoot",
  "state.MISSING": "Missing",

  // ContentTier
  "tier.HERO": "HERO",
  "tier.STANDARD": "STANDARD",
  "tier.all": "All",

  // TaskStatus
  "task.QUEUED": "Queued",
  "task.RUNNING": "Running",
  "task.SUCCEEDED": "Succeeded",
  "task.FAILED": "Failed",
  "stock.instock": "In stock",
  "stock.outofstock": "Out of stock",
  "stock.onbackorder": "On backorder",

  // ImportResult.outcome
  "outcome.IMPORTED": "Imported",
  "outcome.DUPLICATE": "Duplicate",

  // QcReason
  "qc.LOW_RESOLUTION": "Low resolution",
  "qc.BLURRY": "Blurry",
  "qc.OVEREXPOSED": "Overexposed",
  "qc.UNDEREXPOSED": "Underexposed",
  "qc.NOT_PORTRAIT": "Not portrait",
  "qc.FPS_OUT_OF_RANGE": "Frame rate out of range",
  "qc.DURATION_OUT_OF_RANGE": "Duration out of range",

  // JobStep
  "step.CUTOUT": "Cutout",
  "step.SCENE_INPUT": "Scene input",
  "step.WHITE_MAIN": "Main on white",
  "step.WHITE_ANGLE": "Angle on white",
  "step.INBOX": "In-the-box",
  "step.SCENE": "Scene",

  // JobStatus
  "job.QUEUED": "Queued",
  "job.LEASED": "Running",
  "job.WAITING_EXECUTOR": "Waiting for executor",
  "job.SUCCEEDED": "Succeeded",
  "job.FAILED": "Failed",
  "job.CANCELLED": "Cancelled",

  // RunStatus
  "run.RUNNING": "Running",
  "run.DONE": "Done",
  "run.PARTIAL": "Partial",

  // PrecheckFlag
  "precheck.PRODUCT_MISMATCH": "Product differs from the source photo",
  "precheck.AI_TEXT": "Suspicious AI text",
  "precheck.EDGE_NOT_WHITE": "Edges are not pure white",
  "precheck.OCCUPANCY_OUT_OF_RANGE": "Product occupancy out of range",

  // RejectReason
  "reject.PRODUCT_MISMATCH": "Product mismatch",
  "reject.AI_ARTIFACT": "AI artifact",
  "reject.WRONG_FACT": "Wrong fact",
  "reject.TEXT_ERROR": "Text error",
  "reject.STYLE": "Style",
  "reject.LOW_QUALITY": "Low quality",
  "reject.POLICY": "Policy",

  // image pipeline panel
  "pipeline.generate": "Generate product images",
  "pipeline.starting": "Starting…",
  "pipeline.workerOnline": "Worker online",
  "pipeline.workerOffline": "Worker offline",
  "pipeline.lastSeen": "Last seen {time}",
  "pipeline.comfyAvailable": "ComfyUI available",
  "pipeline.comfyUnavailable": "ComfyUI unavailable",
  "pipeline.confirmTitle": "Shots not complete",
  "pipeline.confirmBody": "Some required shots are missing or need a reshoot. Start the image pipeline anyway?",
  "pipeline.confirmGo": "Generate anyway",
  "pipeline.confirmCancel": "Cancel",
  "pipeline.latestRun": "Latest run",
  "pipeline.noRun": "No pipeline run yet.",
  "pipeline.retry": "Retry",
  "pipeline.attempts": "{n}/{m} attempts",
  "pipeline.reviewLink": "Review ({n} pending)",

  // import
  "import.summary": "Imported {n} files",
  "import.pickFolder": "Choose a folder",
  "import.pickFiles": "Choose files",
  "import.willImport": "Will import {n}, skipped {m}",
  "import.start": "Start import",
  "import.importing": "Importing…",
  "import.accepted": "Accepted",
  "import.reshoot": "Needs reshoot",
  "import.duplicates": "Duplicates",
  "import.failed": "Failed",
  "col.file": "File",
  "col.shot": "Shot",
  "col.result": "Result",
  "col.status": "Status",
  "status.ACCEPTED": "Accepted",
  "status.RESHOOT": "Reshoot",
  "col.state": "State",
  "col.guidance": "Guidance",

  // products list
  "products.searchPlaceholder": "Search SKU or name",
  "sync.now": "Sync now",
  "sync.last": "Last sync",
  "sync.never": "Never synced",
  "col.image": "Image",
  "col.sku": "SKU",
  "col.name": "Name",
  "col.price": "Price",
  "col.stock": "Stock",
  "col.tier": "Tier",
  "col.shots": "Shots",

  // product detail
  "detail.back": "Back to products",
  "detail.complete": "Complete",
  "detail.incomplete": "Incomplete",

  // reshoot list
  "reshoot.downloadCsv": "Download CSV",
  "reshoot.csvNote": "The CSV includes the guidance in both Chinese and English.",
  "reshoot.empty": "No shots need a reshoot.",

  // integration settings
  "settings.heading": "WooCommerce integration",
  "settings.baseUrl": "Base URL",
  "settings.username": "Username",
  "settings.appPassword": "Application Password",
  "settings.passwordKeep": "Leave blank to keep the stored password.",
  "settings.save": "Save",
  "settings.saved": "Saved.",
  "settings.test": "Test connection",
  "settings.testing": "Testing…",
  "settings.testOk": "Connection OK.",
  "settings.testFailed": "Connection failed.",
  "settings.guide":
    "Create the password in WordPress: Users → your Shop Manager user → Application Passwords → Add New. If the site runs on plain http, set WP_ENVIRONMENT_TYPE=local in wp-config.php, otherwise WordPress refuses REST API authentication. When Kiano runs in Docker and the store runs on this same computer, use http://host.docker.internal:8080 as the Base URL — inside a container, localhost is the container itself.",

  // generic
  "loading": "Loading…",

  // error codes
  "error.UNAUTHENTICATED": "Please sign in to continue.",
  "error.FORBIDDEN": "You do not have permission to do this.",
  "error.NOT_FOUND": "Not found.",
  "error.VALIDATION_FAILED": "The request is invalid.",
  "error.INTERNAL_ERROR": "Something went wrong. Please try again.",
  "error.AUTH_INVALID_CREDENTIALS": "Invalid email or password.",
  "error.WOO_NOT_CONFIGURED": "Connect WooCommerce first (Settings → Integrations).",
  "error.WOO_AUTH_FAILED":
    "WooCommerce rejected the credentials. Check the Application Password (create it for a Shop Manager user). If the site is plain http, set WP_ENVIRONMENT_TYPE=local in wp-config.php.",
  "error.WOO_BLOCKED":
    "The request was blocked by the site firewall (WAF). Allow-list the server IP or disable the WAF rule for the REST API.",
  "error.WOO_UNAVAILABLE": "Cannot reach the WooCommerce site. Check the Base URL and that the site is online.",
  "error.NOT_TOP_LEVEL_PRODUCT": "Profiles apply to top-level products only.",
  "error.INVALID_FILE_NAME": "The file name must look like SKU_P1.jpg (shot code P1–P9, PROMO or V1–V3).",
  "error.UNSUPPORTED_FILE_TYPE": "This file type is not supported. Photos must be JPG, videos MP4 or MOV.",
  "error.UNSUPPORTED_FILE_TYPE.heic":
    "HEIC is not supported. Export as JPEG (iPhone: Settings › Camera › Formats › Most Compatible).",
  "error.UNKNOWN_SKU": "No product with this SKU. Sync products first or fix the file name.",
  "error.UNREADABLE_MEDIA": "The file cannot be read. It may be corrupt or not a real photo/video.",
  "error.PIPELINE_RUNNING": "A pipeline run is already in progress for this product.",
  "error.SHOTS_NOT_READY": "Import every required shot (ACCEPTED) before generating images.",
  "error.WORKFLOW_NOT_ACTIVE": "No active workflow version. Ask the owner to register and activate one.",
  "error.JOB_NOT_FAILED": "Only failed jobs can be retried.",
  "error.ASSET_NOT_IN_REVIEW": "This asset has already been reviewed.",
  "error.LEASE_LOST": "The job lease was lost; the worker stopped this job.",
  "error.OUTPUT_MISSING": "The worker did not upload the expected outputs.",
  "error.WORKFLOW_INVALID": "The workflow JSON does not satisfy the contract.",
} as const;

export type MessageKey = keyof typeof en;
