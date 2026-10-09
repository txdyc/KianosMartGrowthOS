"use client";

import { useCallback, useEffect, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { isOwner, useMe } from "@/lib/me";
import { errorText, useI18n } from "@/i18n";
import {
  LICENSE_TYPES,
  MOODS,
  formatDuration,
  licenseKey,
  moodKey,
  validateMusicForm,
  type MusicFormErrors,
} from "@/lib/music";
import type { MusicTrackView } from "@/lib/types";

const inputClass =
  "w-full max-w-xl rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400";
const buttonClass =
  "rounded-md bg-zinc-900 px-4 py-1.5 text-sm font-medium text-zinc-50 transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300";
const ghostButtonClass =
  "rounded-md border border-zinc-300 px-3 py-0.5 text-xs transition-colors hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800";

const previewUrl = (id: number) =>
  `${process.env.NEXT_PUBLIC_API_BASE_URL ?? ""}/api/v1/content/music/${id}/preview`;

const emptyForm = {
  title: "",
  artist: "",
  licenseType: LICENSE_TYPES[0] as string,
  licenseSource: "",
  licenseProof: "",
  moods: [] as string[],
};

/** C5 built-in music library: licensed tracks for the short videos' soundtracks. */
export default function MusicSettingsPage() {
  const { t } = useI18n();
  const me = useMe();
  const [tracks, setTracks] = useState<MusicTrackView[] | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [file, setFile] = useState<File | null>(null);
  // bumped after an upload so the uncontrolled file input resets
  const [fileInputKey, setFileInputKey] = useState(0);
  const [fieldErrors, setFieldErrors] = useState<MusicFormErrors>({});
  const [busy, setBusy] = useState(false);
  const [uploaded, setUploaded] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const reload = useCallback(() => {
    return apiFetch<MusicTrackView[]>("/api/v1/content/music")
      .then(setTracks)
      .catch((err: unknown) => {
        setTracks([]);
        setPageError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
      });
  }, [t]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const owner = me !== null && isOwner(me.role);

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError(null);
    setUploaded(false);
    const errors = validateMusicForm({
      ...form,
      fileName: file?.name ?? null,
      fileSize: file?.size ?? null,
    });
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0 || file === null) {
      return;
    }
    const body = new FormData();
    body.append("file", file);
    body.append("title", form.title.trim());
    if (form.artist.trim() !== "") {
      body.append("artist", form.artist.trim());
    }
    body.append("licenseType", form.licenseType);
    body.append("licenseSource", form.licenseSource.trim());
    body.append("licenseProof", form.licenseProof.trim());
    for (const mood of form.moods) {
      body.append("moods", mood);
    }
    setBusy(true);
    try {
      await apiFetch("/api/v1/content/music", { method: "POST", body });
      setUploaded(true);
      setForm(emptyForm);
      setFile(null);
      setFileInputKey((key) => key + 1);
      setFieldErrors({});
      await reload();
    } catch (err) {
      setFormError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  async function retire(track: MusicTrackView) {
    if (!window.confirm(t("music.retireConfirm"))) {
      return;
    }
    setPageError(null);
    setBusy(true);
    try {
      await apiFetch(`/api/v1/content/music/${track.id}/retire`, { method: "POST" });
      await reload();
    } catch (err) {
      setPageError(err instanceof ApiError ? errorText(t, err) : t("error.INTERNAL_ERROR"));
    } finally {
      setBusy(false);
    }
  }

  function pickFile(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    setFile(selected);
    setFieldErrors((prev) => ({ ...prev, file: undefined }));
  }

  function toggleMood(mood: string) {
    setForm((prev) => ({
      ...prev,
      moods: prev.moods.includes(mood)
        ? prev.moods.filter((m) => m !== mood)
        : [...prev.moods, mood],
    }));
  }

  if (me === null || tracks === null) {
    return <p className="text-sm text-zinc-500">{t("loading")}</p>;
  }

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t("music.title")}</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{t("music.guide")}</p>
      </div>

      {pageError !== null ? (
        <p className="text-sm text-red-600 dark:text-red-400" role="alert">
          {pageError}
        </p>
      ) : null}

      <section className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
        <h2 className="text-base font-semibold">{t("music.tracks")}</h2>
        {tracks.length === 0 ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("music.empty")}</p>
        ) : (
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500 dark:border-zinc-800">
                <th className="px-2 py-1">{t("music.titleField")}</th>
                <th className="px-2 py-1">{t("music.duration")}</th>
                <th className="px-2 py-1">{t("music.moods")}</th>
                <th className="px-2 py-1">{t("music.licenseType")}</th>
                <th className="px-2 py-1">{t("music.usedCount")}</th>
                <th className="px-2 py-1">{t("col.status")}</th>
                <th className="px-2 py-1">{t("music.preview")}</th>
                <th className="px-2 py-1" />
              </tr>
            </thead>
            <tbody>
              {tracks.map((track) => (
                <tr
                  key={track.id}
                  className="border-b border-zinc-100 align-top dark:border-zinc-800/60"
                >
                  <td className="px-2 py-1.5 text-sm">
                    <span className="font-medium">{track.title}</span>
                    {track.artist !== null && track.artist !== "" ? (
                      <span className="ml-1 text-xs text-zinc-500 dark:text-zinc-400">
                        {track.artist}
                      </span>
                    ) : null}
                  </td>
                  <td className="px-2 py-1.5 text-xs">{formatDuration(track.durationSeconds)}</td>
                  <td className="px-2 py-1.5 text-xs">
                    {track.moods.length === 0
                      ? "—"
                      : track.moods.map((mood) => t(moodKey(mood))).join(", ")}
                  </td>
                  <td className="px-2 py-1.5 text-xs">
                    <div>{t(licenseKey(track.licenseType))}</div>
                    <div className="text-zinc-500 dark:text-zinc-400" title={track.licenseProof}>
                      {track.licenseSource}
                    </div>
                  </td>
                  <td className="px-2 py-1.5 text-xs">{track.usedCount}</td>
                  <td className="px-2 py-1.5 text-xs">
                    <span
                      className={
                        track.status === "ACTIVE"
                          ? "rounded bg-emerald-100 px-1.5 py-0.5 font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-400"
                          : "rounded bg-zinc-100 px-1.5 py-0.5 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400"
                      }
                    >
                      {t(`music.status.${track.status}`)}
                    </span>
                  </td>
                  <td className="px-2 py-1.5">
                    <audio controls preload="none" src={previewUrl(track.id)} className="h-8 w-48" />
                  </td>
                  <td className="px-2 py-1.5 text-right">
                    {owner && track.status === "ACTIVE" ? (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void retire(track)}
                        className={ghostButtonClass}
                      >
                        {t("music.retire")}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {owner ? (
        <section className="flex flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
          <h2 className="text-base font-semibold">{t("music.upload")}</h2>
          <p className="text-sm text-amber-700 dark:text-amber-400">{t("music.licenseNotice")}</p>

          <form onSubmit={upload} className="flex flex-col gap-3">
            <label className="flex flex-col gap-1 text-sm">
              {t("music.titleField")}
              <input
                type="text"
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
                className={inputClass}
              />
              {fieldErrors.title !== undefined ? (
                <span className="text-xs text-red-600 dark:text-red-400">
                  {t(fieldErrors.title)}
                </span>
              ) : null}
            </label>

            <label className="flex flex-col gap-1 text-sm">
              {t("music.artist")}
              <input
                type="text"
                value={form.artist}
                onChange={(e) => setForm({ ...form, artist: e.target.value })}
                className={inputClass}
              />
            </label>

            <label className="flex flex-col gap-1 text-sm">
              {t("music.licenseType")}
              <select
                value={form.licenseType}
                onChange={(e) => setForm({ ...form, licenseType: e.target.value })}
                className={inputClass}
              >
                {LICENSE_TYPES.map((license) => (
                  <option key={license} value={license}>
                    {t(licenseKey(license))}
                  </option>
                ))}
              </select>
              {fieldErrors.licenseType !== undefined ? (
                <span className="text-xs text-red-600 dark:text-red-400">
                  {t(fieldErrors.licenseType)}
                </span>
              ) : null}
            </label>

            <label className="flex flex-col gap-1 text-sm">
              {t("music.licenseSource")}
              <input
                type="text"
                value={form.licenseSource}
                onChange={(e) => setForm({ ...form, licenseSource: e.target.value })}
                placeholder="Pixabay Content License"
                className={inputClass}
              />
              {fieldErrors.licenseSource !== undefined ? (
                <span className="text-xs text-red-600 dark:text-red-400">
                  {t(fieldErrors.licenseSource)}
                </span>
              ) : null}
            </label>

            <label className="flex flex-col gap-1 text-sm">
              {t("music.licenseProof")}
              <input
                type="text"
                value={form.licenseProof}
                onChange={(e) => setForm({ ...form, licenseProof: e.target.value })}
                placeholder="https://pixabay.com/music/…"
                className={inputClass}
              />
              {fieldErrors.licenseProof !== undefined ? (
                <span className="text-xs text-red-600 dark:text-red-400">
                  {t(fieldErrors.licenseProof)}
                </span>
              ) : null}
            </label>

            <div className="flex flex-col gap-1 text-sm">
              <span>{t("music.moods")}</span>
              <div className="flex flex-wrap gap-3">
                {MOODS.map((mood) => (
                  <label key={mood} className="flex items-center gap-1 text-sm">
                    <input
                      type="checkbox"
                      checked={form.moods.includes(mood)}
                      onChange={() => toggleMood(mood)}
                    />
                    {t(moodKey(mood))}
                  </label>
                ))}
              </div>
            </div>

            <label className="flex flex-col gap-1 text-sm">
              {t("music.file")}
              <input
                key={fileInputKey}
                type="file"
                accept=".mp3,.m4a,.wav,audio/mpeg,audio/mp4,audio/wav"
                onChange={pickFile}
                className="text-sm"
              />
              <span className="text-xs text-zinc-500 dark:text-zinc-400">
                {t("music.fileHint")}
              </span>
              {fieldErrors.file !== undefined ? (
                <span className="text-xs text-red-600 dark:text-red-400">
                  {t(fieldErrors.file)}
                </span>
              ) : null}
            </label>

            <div className="flex flex-wrap items-center gap-3">
              <button type="submit" disabled={busy} className={buttonClass}>
                {t("music.upload")}
              </button>
              {uploaded ? (
                <span className="text-sm text-emerald-600 dark:text-emerald-400">
                  {t("music.uploaded")}
                </span>
              ) : null}
            </div>

            {formError !== null ? (
              <p className="text-sm text-red-600 dark:text-red-400" role="alert">
                {formError}
              </p>
            ) : null}
          </form>
        </section>
      ) : (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{t("music.onlyOwner")}</p>
      )}
    </div>
  );
}