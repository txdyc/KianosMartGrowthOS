"use client";

import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { isOwner, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { WooStatus } from "@/lib/types";

const inputClass =
  "w-full max-w-xl rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400";

type Env = "PRODUCTION" | "STAGING";

/** One Woo environment block: local state per form, shared on save/test. */
function EnvForm({
  env,
  member,
}: {
  env: Env;
  member: boolean;
}) {
  const { t } = useI18n();
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
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(() => {
    return apiFetch<WooStatus>(`/api/v1/integrations/woocommerce?environment=${env}`)
      .then((res) => {
        setStatus(res);
        setBaseUrl(res.baseUrl ?? "");
        setUsername(res.username ?? "");
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.code === "FORBIDDEN") {
          setForbidden(true);
        } else {
          setError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
        }
      })
      .finally(() => {
        setLoaded(true);
      });
  }, [env, t]);

  useEffect(() => {
    load();
  }, [load]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const res = await apiFetch<WooStatus>(`/api/v1/integrations/woocommerce?environment=${env}`, {
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
        `/api/v1/integrations/woocommerce/test?environment=${env}`,
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

  if (!loaded) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  if (!member || forbidden) {
    return (
      <p className="text-sm text-red-600 dark:text-red-400" role="alert">
        {t("error.FORBIDDEN")}
      </p>
    );
  }

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold">{t(`settings.env.${env}`)}</h2>
        {env === "STAGING" ? (
          <span className="text-xs text-zinc-500 dark:text-zinc-400">
            {t("settings.stagingHint")}
          </span>
        ) : (
          <span className="text-xs text-zinc-500 dark:text-zinc-400">
            {t("settings.productionHint")}
          </span>
        )}
        {status?.lastSyncAt != null ? (
          <span className="text-xs text-zinc-500 dark:text-zinc-400">
            {t("sync.last")}: {new Date(status.lastSyncAt).toLocaleString()}
          </span>
        ) : null}
      </div>

      <form onSubmit={save} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          {t("settings.baseUrl")}
          <input
            type="url"
            required
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder={env === "STAGING" ? "http://host.docker.internal:8080" : "https://example.com"}
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
    </section>
  );
}

export default function IntegrationsPage() {
  const { t } = useI18n();
  const me = useMe();

  if (me === null) {
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
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t("settings.heading")}</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{t("settings.guide")}</p>
      </div>
      <EnvForm env="PRODUCTION" member={isOwner(me.role)} />
      <EnvForm env="STAGING" member={isOwner(me.role)} />
    </div>
  );
}