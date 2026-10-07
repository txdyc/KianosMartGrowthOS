import { ApiError } from "./api";
import type { ImportResult } from "./types";

/** Outcome of one uploaded file: ok rows carry the ImportResult, failures carry the error. */
export type ImportRow = {
  fileName: string;
  ok: boolean;
  result?: ImportResult;
  error?: { code: string; message: string; details?: Record<string, unknown> };
};

const OS_JUNK_NAMES = new Set(["thumbs.db", "desktop.ini"]);

/**
 * Skip hidden files (leading dot) and OS junk; everything else is handed to
 * the backend, which owns the file-name rules (no duplication here).
 */
export function filterImportable(files: File[]): { importable: File[]; skipped: File[] } {
  const importable: File[] = [];
  const skipped: File[] = [];
  for (const file of files) {
    if (file.name.startsWith(".") || OS_JUNK_NAMES.has(file.name.toLowerCase())) {
      skipped.push(file);
    } else {
      importable.push(file);
    }
  }
  return { importable, skipped };
}

/**
 * Upload files with at most `concurrency` requests in flight; a failed file
 * never stops the others. Each finished file calls onRow exactly once.
 */
export async function runImports(
  files: File[],
  upload: (file: File) => Promise<ImportResult>,
  concurrency: number,
  onRow: (row: ImportRow) => void,
): Promise<void> {
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < files.length) {
      const file = files[next];
      next += 1;
      try {
        const result = await upload(file);
        onRow({ fileName: file.name, ok: true, result });
      } catch (err) {
        if (err instanceof ApiError) {
          // Keep details: the dedicated heic hint relies on details.extension.
          onRow({
            fileName: file.name,
            ok: false,
            error: { code: err.code, message: err.message, details: err.details },
          });
        } else {
          onRow({
            fileName: file.name,
            ok: false,
            error: { code: "INTERNAL_ERROR", message: err instanceof Error ? err.message : "Upload failed" },
          });
        }
      }
    }
  };
  const workers: Promise<void>[] = [];
  for (let i = 0; i < Math.min(concurrency, files.length); i += 1) {
    workers.push(worker());
  }
  await Promise.all(workers);
}
