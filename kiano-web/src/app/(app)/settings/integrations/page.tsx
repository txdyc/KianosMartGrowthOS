"use client";

import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { isOwner, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { WooStatus } from "@/lib/types";

const inputClass =
  "w-full max-w-xl rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400";

export default function IntegrationsPage() {
  const { t } = useI18n();
  const me = useMe();

  const [status, setStatus] = useState<WooStatus | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [baseUrl, setBaseUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    return apiFetch<WooStatus>("/api/v1/integrations/woocommerce")
      .then((res) => {
        setStatus(res);
        setBaseUrl(res.baseUrl ?? "");
        setUsername(res.username ?? "");
      })
      .catch((err: unknown) => {
        setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      })
      .finally(() => {
        setLoaded(true);
      });
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const res = await apiFetch<WooStatus>("/api/v1/integrations/woocommerce", {
        method: "PUT",
        body: {
          baseUrl: baseUrl.trim(),
          username: username.trim(),
          // A blank password keeps the stored one (see the backend contract).
          applicationPassword: password,
        },
      });
      setStatus(res);
      setPassword("");
      setSaved(true);
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setSaving(false);
    }
  }

  async function testConnection() {
    setTesting(true);
    setError(null);
    setTestResult(null);
    try {
      // Tests the stored credentials, so save before testing.
      const res = await apiFetch<{ ok: boolean; code?: string; message?: string }>(
        "/api/v1/integrations/woocommerce/test",
        { method: "POST" },
      );
      if (res.ok) {
        setTestResult({ ok: true, text: t("settings.testOk") });
      } else {
        const err = new ApiError(0, res.code ?? "INTERNAL_ERROR", res.message ?? "");
        setTestResult({ ok: false, text: `${t("settings.testFailed")} ${errorText(t, err)}` });
      }
    } catch (err) {
      setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setTesting(false);
    }
  }

  if (me === null || !loaded) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  if (!isOwner(me.role)) {
    return (
      <p className="text-sm text-red-600 dark:text-red-400" role="alert">
        {t("error.FORBIDDEN")}
      </p>
    );
  }

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <h1 className="text-2xl font-semibold tracking-tight">{t("settings.heading")}</h1>
        {status?.lastSyncAt != null ? (
          <span className="text-zinc-500 dark:text-zinc-400">
            {t("sync.last")}: {new Date(status.lastSyncAt).toLocaleString()}
          </span>
        ) : null}
      </div>

      <form onSubmit={save} className="flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm">
          {t("settings.baseUrl")}
          <input
            type="url"
            required
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="https://example.com"
            className={inputClass}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          {t("settings.username")}
          <input
            type="text"
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className={inputClass}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          {t("settings.appPassword")}
          <input
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className={inputClass}
          />
          {status?.configured ? (
            <span className="text-xs text-zinc-500 dark:text-zinc-400">{t("settings.passwordKeep")}</span>
          ) : null}
        </label>

        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-zinc-900 px-4 py-1.5 text-sm font-medium text-zinc-50 transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {t("settings.save")}
          </button>
          <button
            type="button"
            onClick={testConnection}
            disabled={testing}
            className="rounded-md border border-zinc-300 px-4 py-1.5 text-sm transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            {testing ? t("settings.testing") : t("settings.test")}
          </button>
          {saved ? <span className="text-sm text-emerald-600 dark:text-emerald-400">{t("settings.saved")}</span> : null}
        </div>
      </form>

      {testResult !== null ? (
        <p
          className={
            testResult.ok
              ? "text-sm text-emerald-600 dark:text-emerald-400"
              : "text-sm text-red-600 dark:text-red-400"
          }
          role="status"
        >
          {testResult.text}
        </p>
      ) : null}

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      <p className="text-sm text-zinc-600 dark:text-zinc-400">{t("settings.guide")}</p>
    </div>
  );
}
