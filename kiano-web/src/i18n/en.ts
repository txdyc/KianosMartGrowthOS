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

  // import
  "import.summary": "Imported {n} files",

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
} as const;

export type MessageKey = keyof typeof en;
