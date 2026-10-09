"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { errorText, useI18n } from "@/i18n";
import type { MessageKey } from "@/i18n";
import { en } from "@/i18n/en";
import {
  applyTrims,
  aiRatio,
  limitsFor,
  totalSeconds,
  violations,
  type Edl,
  type Trim,
} from "@/lib/edl";
import type { ReviewItem } from "@/lib/types";

const secondaryButton =
  "rounded-md border border-zinc-300 px-2 py-1 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";
const primaryButton =
  "rounded-md bg-zinc-900 px-2 py-1 text-xs font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900";

/**
 * The manual in/out editor of one cut (C5 T10/T13): every REAL segment gets
 * two number inputs, a small preview follows the edited timecode, and the cut
 * is re-validated live with the same codes as the backend EdlValidator.
 * Saving PUTs the trims and the backend re-composes the video.
 */
export function EdlEditor({ item, edl, onSaved }: { item: ReviewItem; edl: Edl; onSaved: () => Promise<void> }) {
  const { t } = useI18n();
  const limits = useMemo(() => limitsFor(edl.videoType), [edl.videoType]);
  const [trims, setTrims] = useState<Trim[]>(() =>
    realSegments(edl).map((segment) => ({ role: segment.role, in: segment.in, out: segment.out })),
  );
  const [preview, setPreview] = useState<{ input: string; time: number } | null>(null);
  const [durations, setDurations] = useState<Record<string, number | undefined>>({});
  const [serverCodes, setServerCodes] = useState<string[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const previewRef = useRef<HTMLVideoElement | null>(null);

  // The real footage lengths back the SEGMENT_OUT_OF_SOURCE check; a URL that
  // does not answer metadata simply leaves the bound unknown, like the backend.
  useEffect(() => {
    const probes: HTMLVideoElement[] = [];
    for (const input of new Set(realSegments(edl).map((segment) => segment.input))) {
      const url = item.sourceUrls?.[input.toUpperCase()];
      if (url === undefined) {
        continue;
      }
      const probe = document.createElement("video");
      probe.preload = "metadata";
      probe.src = url;
      probe.onloadedmetadata = () =>
        setDurations((prev) => ({ ...prev, [input]: probe.duration }));
      probes.push(probe);
    }
    return () => {
      for (const probe of probes) {
        probe.src = "";
      }
    };
  }, [edl, item.sourceUrls]);

  useEffect(() => {
    if (previewRef.current !== null && preview !== null) {
      previewRef.current.currentTime = preview.time;
    }
  }, [preview]);

  const cut = useMemo(() => applyTrims(edl, trims), [edl, trims]);
  const codes = violations(cut, durations, limits);
  const shownCodes = serverCodes ?? codes;
  const previewUrl = preview === null ? null : (item.sourceUrls?.[preview.input.toUpperCase()] ?? null);

  function update(role: string, field: "in" | "out", value: number, input: string) {
    setTrims((prev) =>
      prev.map((trim) => (trim.role === role ? { ...trim, [field]: value } : trim)),
    );
    setPreview({ input, time: value });
    setServerCodes(null);
    setError(null);
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/content/assets/${item.assetId}/edl`, {
        method: "PUT",
        body: { segments: trims },
      });
      setServerCodes(null);
      await onSaved();
    } catch (err) {
      const api = err instanceof ApiError ? err : null;
      const violationsDetail = api?.details?.violations;
      if (parseViolations(violationsDetail).length > 0) {
        setServerCodes(parseViolations(violationsDetail));
      } else {
        setError(api !== null ? errorText(t, api) : t("error.INTERNAL_ERROR"));
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-zinc-200 p-2 dark:border-zinc-800">
      <div className="flex flex-wrap items-center gap-2 text-xs text-zinc-600 dark:text-zinc-400">
        <span>
          {t("video.total")}: <span className="font-medium">{totalSeconds(cut).toFixed(1)}s</span>
        </span>
        <span>
          {t("video.aiRatio")}: <span className="font-medium">{(aiRatio(cut) * 100).toFixed(1)}%</span>
        </span>
      </div>

      {realSegments(edl).map((segment) => {
        const trim = trims.find((x) => x.role === segment.role);
        const length = trim === undefined ? 0 : trim.out - trim.in;
        return (
          <div key={segment.role} className="flex flex-wrap items-center gap-2 text-xs">
            <span className="w-20 shrink-0 font-medium text-zinc-700 dark:text-zinc-300">
              {segRoleText(t, segment.role)}
            </span>
            <span className="w-10 shrink-0 font-mono text-zinc-500 dark:text-zinc-400">
              {segment.input.toUpperCase()}
            </span>
            <label className="flex items-center gap-1">
              {t("video.trimIn")}
              <input
                type="number"
                step="0.1"
                min="0"
                value={trim?.in ?? 0}
                onChange={(e) => update(segment.role, "in", Number(e.target.value), segment.input)}
                className="w-20 rounded border border-zinc-300 px-1 py-0.5 dark:border-zinc-700 dark:bg-zinc-900"
              />
            </label>
            <label className="flex items-center gap-1">
              {t("video.trimOut")}
              <input
                type="number"
                step="0.1"
                min="0"
                value={trim?.out ?? 0}
                onChange={(e) => update(segment.role, "out", Number(e.target.value), segment.input)}
                className="w-20 rounded border border-zinc-300 px-1 py-0.5 dark:border-zinc-700 dark:bg-zinc-900"
              />
            </label>
            <span className="text-zinc-400">{length.toFixed(1)}s</span>
          </div>
        );
      })}

      <div className="flex flex-wrap gap-3">
        {previewUrl !== null ? (
          <video
            key={preview?.input ?? "preview"}
            ref={previewRef}
            src={previewUrl}
            controls
            muted
            onLoadedMetadata={() => {
              if (previewRef.current !== null && preview !== null) {
                previewRef.current.currentTime = preview.time;
              }
            }}
            className="h-40 rounded bg-black"
          />
        ) : (
          <p className="text-xs text-zinc-500 dark:text-zinc-400">{t("video.editHint")}</p>
        )}
      </div>

      {shownCodes.length > 0 ? (
        <ul className="list-inside list-disc text-xs text-red-600 dark:text-red-400">
          {shownCodes.map((code) => (
            <li key={code}>{codeText(t, code)}</li>
          ))}
        </ul>
      ) : null}

      {error !== null ? (
        <p className="text-xs text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : null}

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => void save()}
          disabled={saving || shownCodes.length > 0}
          className={primaryButton}
        >
          {saving ? t("loading") : t("video.saveCut")}
        </button>
        <button
          type="button"
          onClick={() => {
            setTrims(
              realSegments(edl).map((segment) => ({
                role: segment.role,
                in: segment.in,
                out: segment.out,
              })),
            );
            setServerCodes(null);
          }}
          disabled={saving}
          className={secondaryButton}
        >
          {t("video.resetCut")}
        </button>
      </div>
    </div>
  );
}

/** The editable segments: REAL footage only (hook/body/captions). */
export function realSegments(edl: Edl): Edl["segments"] {
  return edl.segments.filter((segment) => segment.kind === "REAL");
}

/** Violation codes of a 422 details.violations payload. */
function parseViolations(details: unknown): string[] {
  if (!Array.isArray(details)) {
    return [];
  }
  return details
    .map((entry) =>
      typeof entry === "object" && entry !== null && "code" in entry
        ? String((entry as { code: unknown }).code)
        : null,
    )
    .filter((code): code is string => code !== null);
}

/** Localized segment role; unknown roles are shown as-is. */
function segRoleText(t: ReturnType<typeof useI18n>["t"], role: string): string {
  const key = `video.role.${role}` as MessageKey;
  return key in en ? t(key) : role;
}

/** Localized validation code; unknown codes are shown as-is. */
function codeText(t: ReturnType<typeof useI18n>["t"], code: string): string {
  const key = `error.${code}` as MessageKey;
  return key in en ? t(key) : code;
}