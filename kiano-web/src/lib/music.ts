import type { MessageKey } from "@/i18n";

/**
 * C5 music library helpers. The whitelists and limits mirror the backend
 * MusicTrackService; tracks that need attribution (CC-BY) are not accepted,
 * because an ad cannot carry a credit line.
 */

/** Licence types the backend accepts; attribution licences are not in it. */
export const LICENSE_TYPES = ["CC0", "ROYALTY_FREE_COMMERCIAL", "OWNED"] as const;
export type LicenseType = (typeof LICENSE_TYPES)[number];

/** Mood tags the picker rotates on. */
export const MOODS = ["upbeat", "uplifting", "calm", "energetic"] as const;
export type Mood = (typeof MOODS)[number];

/** Upload limits (MusicTrackService): mp3/m4a/wav, at most 20 MB. */
export const MUSIC_EXTENSIONS = ["mp3", "m4a", "wav"] as const;
export const MUSIC_MAX_BYTES = 20 * 1024 * 1024;

/** The upload form as the page holds it, plus the picked file. */
export interface MusicForm {
  title: string;
  artist: string;
  licenseType: string;
  licenseSource: string;
  licenseProof: string;
  moods: string[];
  fileName: string | null;
  fileSize: number | null;
}

/** The form fields that can fail validation. */
export type MusicFormField =
  | "title"
  | "licenseType"
  | "licenseSource"
  | "licenseProof"
  | "file";

/** Field-level errors of the upload form. */
export type MusicFormErrors = Partial<Record<MusicFormField, MessageKey>>;

/** A non-null file name with an accepted audio extension. */
export function hasMusicExtension(fileName: string): boolean {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0) {
    return false;
  }
  return (MUSIC_EXTENSIONS as readonly string[]).includes(
    fileName.slice(dot + 1).toLowerCase(),
  );
}

/**
 * Client-side form rules: the three licence fields and the title are required,
 * the file must be mp3/m4a/wav and at most 20 MB. Empty when the form can be
 * posted; the backend still re-validates everything.
 */
export function validateMusicForm(form: MusicForm): MusicFormErrors {
  const errors: MusicFormErrors = {};
  if (form.title.trim() === "") {
    errors.title = "music.error.titleRequired";
  }
  if (!(LICENSE_TYPES as readonly string[]).includes(form.licenseType)) {
    errors.licenseType = "music.error.licenseTypeRequired";
  }
  if (form.licenseSource.trim() === "") {
    errors.licenseSource = "music.error.sourceRequired";
  }
  if (form.licenseProof.trim() === "") {
    errors.licenseProof = "music.error.proofRequired";
  }
  if (form.fileName === null || form.fileName === "") {
    errors.file = "music.error.fileRequired";
  } else if (!hasMusicExtension(form.fileName)) {
    errors.file = "music.error.fileExtension";
  } else if ((form.fileSize ?? 0) > MUSIC_MAX_BYTES) {
    errors.file = "music.error.fileSize";
  }
  return errors;
}

/** Dictionary key of a licence type (values are the backend whitelist). */
export function licenseKey(licenseType: string): MessageKey {
  return `music.license.${licenseType}` as MessageKey;
}

/** Dictionary key of a mood tag (values are the backend whitelist). */
export function moodKey(mood: string): MessageKey {
  return `music.mood.${mood}` as MessageKey;
}

/** Track duration as m:ss for the list. */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}