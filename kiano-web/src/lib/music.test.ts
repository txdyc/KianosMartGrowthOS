import { expect, test } from "vitest";
import {
  LICENSE_TYPES,
  MOODS,
  MUSIC_MAX_BYTES,
  formatDuration,
  hasMusicExtension,
  validateMusicForm,
  type MusicForm,
} from "./music";

const validForm: MusicForm = {
  title: "Upbeat Store Loop",
  artist: "Kiano",
  licenseType: "ROYALTY_FREE_COMMERCIAL",
  licenseSource: "Pixabay Content License",
  licenseProof: "https://pixabay.com/music/example",
  moods: ["upbeat"],
  fileName: "loop.mp3",
  fileSize: 1024 * 1024,
};

test("license types and moods mirror the backend whitelists", () => {
  expect([...LICENSE_TYPES]).toEqual(["CC0", "ROYALTY_FREE_COMMERCIAL", "OWNED"]);
  expect([...MOODS]).toEqual(["upbeat", "uplifting", "calm", "energetic"]);
});

test("validateMusicForm requires title, licence fields and a file", () => {
  const errors = validateMusicForm({
    ...validForm,
    title: "   ",
    licenseType: "",
    licenseSource: "",
    licenseProof: "",
    fileName: null,
    fileSize: null,
  });

  expect(errors.title).toBeDefined();
  expect(errors.licenseType).toBeDefined();
  expect(errors.licenseSource).toBeDefined();
  expect(errors.licenseProof).toBeDefined();
  expect(errors.file).toBeDefined();
});

test("validateMusicForm checks the file extension", () => {
  expect(validateMusicForm({ ...validForm, fileName: "track.flac" }).file).toBeDefined();
  expect(validateMusicForm({ ...validForm, fileName: "track.MP3" }).file).toBeUndefined();
  expect(hasMusicExtension("a.m4a")).toBe(true);
  expect(hasMusicExtension("a.WAV")).toBe(true);
  expect(hasMusicExtension("a.mp4")).toBe(false);
  expect(hasMusicExtension("noextension")).toBe(false);
});

test("validateMusicForm caps the file size at 20 MB", () => {
  expect(validateMusicForm({ ...validForm, fileSize: MUSIC_MAX_BYTES }).file).toBeUndefined();
  expect(validateMusicForm({ ...validForm, fileSize: MUSIC_MAX_BYTES + 1 }).file).toBeDefined();
});

test("a complete form has no errors", () => {
  expect(validateMusicForm(validForm)).toEqual({});
});

test("formatDuration renders m:ss for the track list", () => {
  expect(formatDuration(9.6)).toBe("0:10");
  expect(formatDuration(75)).toBe("1:15");
  expect(formatDuration(300)).toBe("5:00");
});