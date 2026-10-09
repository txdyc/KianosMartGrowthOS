"use client";

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { MeProvider, isOwner, type Me } from "@/lib/me";
import { LocaleSwitch, useI18n } from "@/i18n";
import type { NeedsRepublishItem } from "@/lib/types";

type NavHref =
  | "/products"
  | "/import"
  | "/review"
  | "/reshoot"
  | "/ads"
  | "/settings/integrations"
  | "/settings/policy"
  | "/settings/llm";

export default function AppLayout({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const pathname = usePathname();
  const [me, setMe] = useState<Me | null>(null);
  const [needsRepublish, setNeedsRepublish] = useState(0);

  // apiFetch already redirects to /login when /me returns 401.
  useEffect(() => {
    apiFetch<Me>("/api/v1/auth/me")
      .then(setMe)
      .catch(() => {});
  }, []);

  // How many products wait for a re-publish; hidden when zero.
  useEffect(() => {
    if (pathname.startsWith("/products")) {
      return; // the products list could show it; keep the nav simple here
    }
    apiFetch<NeedsRepublishItem[]>("/api/v1/content/publications/needs-republish")
      .then((rows) => setNeedsRepublish(rows.length))
      .catch(() => {});
  }, [pathname]);

  const links: { href: NavHref; label: string }[] = [
    { href: "/products", label: t("nav.products") },
    { href: "/import", label: t("nav.import") },
    { href: "/review", label: t("nav.review") },
    { href: "/reshoot", label: t("nav.reshoot") },
    { href: "/ads", label: t("nav.ads") },
  ];
  if (me !== null && isOwner(me.role)) {
    links.push({ href: "/settings/integrations", label: t("nav.settings") });
    links.push({ href: "/settings/policy", label: t("nav.policy") });
    links.push({ href: "/settings/llm", label: t("nav.aiModels") });
  }

  // needs-republish badge goes right after the products link.
  const renderLinks: { href: string; label: string }[] = [];
  for (const link of links) {
    renderLinks.push(link);
    if (link.href === "/products" && needsRepublish > 0) {
      renderLinks.push({
        href: "/needs-republish",
        label: t("publish.needsRepublish", { n: needsRepublish }),
      });
    }
  }

  async function logout() {
    try {
      await apiFetch("/api/v1/auth/logout", { method: "POST" });
    } finally {
      // Hard navigation on purpose: a full reload drops all client state.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      window.location.assign("/login");
    }
  }

  return (
    <MeProvider value={me}>
      <header className="border-b border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-950">
        <nav className="mx-auto flex h-14 w-full max-w-6xl items-center gap-6 px-4">
          <span className="text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-100">KianosMart</span>
          {renderLinks.map((item) => {
            const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={
                  active
                    ? "text-sm font-medium text-zinc-900 dark:text-zinc-100"
                    : "text-sm text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
                }
              >
                {item.label}
              </Link>
            );
          })}
          <div className="ml-auto flex items-center gap-4">
            <LocaleSwitch />
            {me !== null ? <span className="text-sm text-zinc-700 dark:text-zinc-300">{me.name}</span> : null}
            <button
              type="button"
              onClick={logout}
              className="text-sm text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
            >
              {t("nav.logout")}
            </button>
          </div>
        </nav>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">{children}</main>
    </MeProvider>
  );
}
