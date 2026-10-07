"use client";

import { createContext, useCallback, useContext, useMemo, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { LOCALE_COOKIE, detectLocale, makeT } from "./shared";
import type { Locale, TFunction } from "./shared";

export {
  detectLocale,
  errorText,
  makeT,
  pickGuidance,
  stockText,
  LOCALE_COOKIE,
} from "./shared";
export type { Locale, MessageKey, TFunction } from "./shared";

// The locale lives in a tiny external store so that:
// - SSR and hydration render the cookie-derived locale (getServerSnapshot),
// - after mount the browser language can take over when no cookie is set.
let currentLocale: Locale | null = null;
const listeners = new Set<() => void>();

function readLocaleCookie(): string | undefined {
  const prefix = `${LOCALE_COOKIE}=`;
  const match = document.cookie.split(/;\s*/).find((c) => c.startsWith(prefix));
  return match?.slice(prefix.length);
}

function clientLocale(): Locale {
  if (currentLocale === null) {
    currentLocale = detectLocale(readLocaleCookie(), navigator.language);
  }
  return currentLocale;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

type I18nContextValue = {
  locale: Locale;
  t: TFunction;
  setLocale: (locale: Locale) => void;
};

const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({ initialLocale, children }: { initialLocale: Locale; children: ReactNode }) {
  const locale = useSyncExternalStore(subscribe, clientLocale, () => initialLocale);

  const setLocale = useCallback((next: Locale) => {
    document.cookie = `${LOCALE_COOKIE}=${next}; path=/; max-age=31536000; samesite=lax`;
    document.documentElement.lang = next === "zh" ? "zh-CN" : "en";
    currentLocale = next;
    for (const listener of listeners) {
      listener();
    }
  }, []);

  const value = useMemo(() => ({ locale, t: makeT(locale), setLocale }), [locale, setLocale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    throw new Error("useI18n must be used within an I18nProvider");
  }
  return ctx;
}

/** 中文 | English switch, shared by the login page and the app nav. */
export function LocaleSwitch() {
  const { locale, setLocale } = useI18n();
  const className = (active: boolean) =>
    `rounded px-2 py-1 text-sm ${
      active ? "font-semibold text-zinc-900 dark:text-zinc-100" : "text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100"
    }`;
  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        className={className(locale === "zh")}
        disabled={locale === "zh"}
        onClick={() => setLocale("zh")}
      >
        中文
      </button>
      <span aria-hidden className="text-zinc-300">
        |
      </span>
      <button
        type="button"
        className={className(locale === "en")}
        disabled={locale === "en"}
        onClick={() => setLocale("en")}
      >
        English
      </button>
    </div>
  );
}
