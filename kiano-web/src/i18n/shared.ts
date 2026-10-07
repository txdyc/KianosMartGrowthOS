import { ApiError } from "@/lib/api";
import { en, type MessageKey } from "./en";
import { zh } from "./zh";

// Pure i18n helpers shared by the server (root layout reads the cookie) and
// the client. Keep this file free of "use client" and browser APIs.

export type Locale = "zh" | "en";
export type { MessageKey };

export type TFunction = (key: MessageKey, vars?: Record<string, string | number>) => string;

export const dictionaries: Record<Locale, Record<MessageKey, string>> = { en, zh };

export const LOCALE_COOKIE = "kiano_locale";

/**
 * The cookie wins; otherwise fall back to the browser language
 * (zh* → zh, everything else → en).
 */
export function detectLocale(cookieValue: string | undefined, navigatorLanguage?: string): Locale {
  if (cookieValue === "zh" || cookieValue === "en") {
    return cookieValue;
  }
  const nav = navigatorLanguage?.toLowerCase() ?? "";
  return nav.startsWith("zh") ? "zh" : "en";
}

export function makeT(locale: Locale): TFunction {
  return (key, vars) => {
    let text = dictionaries[locale][key];
    if (vars) {
      for (const [name, value] of Object.entries(vars)) {
        text = text.replaceAll(`{${name}}`, String(value));
      }
    }
    return text;
  };
}

/** Pick the guidance column for the locale, falling back to the other language. */
export function pickGuidance(
  locale: Locale,
  line: { guidanceEn: string | null; guidanceZh: string | null },
): string {
  const preferred = locale === "zh" ? line.guidanceZh : line.guidanceEn;
  const fallback = locale === "zh" ? line.guidanceEn : line.guidanceZh;
  return preferred ?? fallback ?? "";
}

/**
 * Map an ApiError to localized text: a code-specific entry wins (including the
 * dedicated heic hint), otherwise fall back to the raw server message.
 */
export function errorText(t: TFunction, err: ApiError): string {
  const extension = err.details?.extension;
  if (typeof extension === "string") {
    const specificKey = `error.${err.code}.${extension}` as MessageKey;
    if (specificKey in en) {
      return t(specificKey);
    }
  }
  const key = `error.${err.code}` as MessageKey;
  if (key in en) {
    return t(key);
  }
  return err.message;
}

/**
 * Localized WooCommerce stock_status; unknown values (e.g. added by a plugin)
 * are shown as-is rather than hidden.
 */
export function stockText(t: TFunction, stockStatus: string | null | undefined): string {
  if (!stockStatus) {
    return "—";
  }
  const key = `stock.${stockStatus}` as MessageKey;
  return key in en ? t(key) : stockStatus;
}
