"use client";

import { useState } from "react";
import type { ChangeEvent } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { filterImportable, runImports } from "@/lib/import";
import type { ImportRow } from "@/lib/import";
import { canOperate, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey } from "@/i18n";
import type { ImportResult } from "@/lib/types";

const cellClass = "px-3 py-2 text-sm";

/** webkitdirectory is not in React's types; passing it through verbatim. */
const directoryAttrs = { webkitdirectory: "" } as Record<string, string>;

export default function ImportPage() {
  const { t } = useI18n();
  const me = useMe();
  const operate = me !== null && canOperate(me.role);

  const [importable, setImportable] = useState<File[]>([]);
  const [skippedCount, setSkippedCount] = useState(0);
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function pick(event: ChangeEvent<HTMLInputElement>) {
    const { importable: ok, skipped } = filterImportable(Array.from(event.target.files ?? []));
    setImportable(ok);
    setSkippedCount(skipped.length);
    setRows([]);
    setError(null);
    // Allow re-picking the same folder in the same session.
    event.target.value = "";
  }

  function upload(file: File): Promise<ImportResult> {
    // The file name sent to the backend is always file.name, never the
    // relative path (that is display-only).
    const form = new FormData();
    form.append("file", file, file.name);
    return apiFetch<ImportResult>("/api/v1/content/source-media", { method: "POST", body: form });
  }

  async function start() {
    setRunning(true);
    setError(null);
    try {
      await runImports(importable, upload, 2, (row) => setRows((prev) => [...prev, row]));
    } finally {
      setRunning(false);
    }
  }

  const accepted = rows.filter((r) => r.ok && r.result?.outcome === "IMPORTED" && r.result.status === "ACCEPTED").length;
  const reshoot = rows.filter((r) => r.ok && r.result?.outcome === "IMPORTED" && r.result.status === "RESHOOT").length;
  const duplicates = rows.filter((r) => r.ok && r.result?.outcome === "DUPLICATE").length;
  const failed = rows.filter((r) => !r.ok).length;

  const displayName = (file: File) => file.webkitRelativePath || file.name;

  if (me !== null && !operate) {
    return <p className="text-sm text-red-600 dark:text-red-400">{t("error.FORBIDDEN")}</p>;
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <label className="rounded-md border border-zinc-300 px-3 py-1.5 text-sm transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800">
          {t("import.pickFolder")}
          <input type="file" multiple {...directoryAttrs} onChange={pick} className="hidden" />
        </label>
        <label className="rounded-md border border-zinc-300 px-3 py-1.5 text-sm transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800">
          {t("import.pickFiles")}
          <input type="file" multiple onChange={pick} className="hidden" />
        </label>
      </div>

      {error !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      {importable.length > 0 ? (
        <div className="flex flex-col gap-2">
          <p className="text-sm text-zinc-700 dark:text-zinc-300">
            {t("import.willImport", { n: importable.length, m: skippedCount })}
          </p>
          <ul className="max-h-40 overflow-y-auto rounded-md border border-zinc-200 p-2 font-mono text-xs text-zinc-600 dark:border-zinc-800 dark:text-zinc-400">
            {importable.map((file, index) => (
              // Index keys: the same file name may legitimately appear twice
              // (a copy selected alongside the original).
              <li key={index}>{displayName(file)}</li>
            ))}
          </ul>
          <button
            type="button"
            onClick={start}
            disabled={running}
            className="w-fit rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {running ? t("import.importing") : t("import.start")}
          </button>
        </div>
      ) : null}

      {rows.length > 0 ? (
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500 dark:border-zinc-800">
              <th className={cellClass}>{t("col.file")}</th>
              <th className={cellClass}>{t("col.sku")}</th>
              <th className={cellClass}>{t("col.shot")}</th>
              <th className={cellClass}>{t("col.result")}</th>
              <th className={cellClass}>{t("col.status")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              // Index keys: rows arrive in completion order and file names
              // are not unique (a duplicate copy keeps the original name).
              <tr key={index} className="border-b border-zinc-100 dark:border-zinc-800/60">
                <td className={`${cellClass} font-mono text-xs`}>{row.fileName}</td>
                <td className={`${cellClass} font-mono text-xs`}>{row.ok ? row.result?.sku : "—"}</td>
                <td className={cellClass}>{row.ok ? row.result?.shotCode : "—"}</td>
                <td className={cellClass}>
                  {row.ok
                    ? row.result?.outcome === "DUPLICATE"
                      ? t("outcome.DUPLICATE")
                      : t("outcome.IMPORTED")
                    : errorText(
                        t,
                        new ApiError(
                          0,
                          row.error?.code ?? "INTERNAL_ERROR",
                          row.error?.message ?? "",
                          undefined,
                          row.error?.details,
                        ),
                      )}
                </td>
                <td className={cellClass}>
                  {row.ok ? (
                    <span
                      className={
                        row.result?.status === "RESHOOT"
                          ? "text-red-600 dark:text-red-400"
                          : "text-emerald-600 dark:text-emerald-400"
                      }
                    >
                      {t(`status.${row.result?.status}` as MessageKey)}
                      {row.result && row.result.reasons.length > 0
                        ? ` (${row.result.reasons.map((r) => t(`qc.${r}`)).join(", ")})`
                        : ""}
                    </span>
                  ) : (
                    <span className="text-red-600 dark:text-red-400">{t("import.failed")}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {rows.length > 0 ? (
        <p className="text-sm text-zinc-700 dark:text-zinc-300">
          {t("import.accepted")}: {accepted} · {t("import.reshoot")}: {reshoot} · {t("import.duplicates")}:{" "}
          {duplicates} · {t("import.failed")}: {failed}
        </p>
      ) : null}
    </div>
  );
}
