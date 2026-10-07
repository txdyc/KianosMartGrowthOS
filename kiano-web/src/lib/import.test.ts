import { describe, expect, test, vi } from "vitest";
import { ApiError } from "./api";
import { filterImportable, runImports } from "./import";
import type { ImportRow } from "./import";
import type { ImportResult } from "./types";

function f(name: string): File {
  return new File(["x"], name, { type: "image/jpeg" });
}

describe("filterImportable", () => {
  test("skips hidden and OS junk files", () => {
    const r = filterImportable([
      f("MG-BL200_P1.jpg"),
      f(".DS_Store"),
      f("._MG-BL200_P1.jpg"),
      f("Thumbs.db"),
      f("DESKTOP.INI"),
      f("notes.txt"),
    ]);
    expect(r.importable.map((x) => x.name)).toEqual(["MG-BL200_P1.jpg", "notes.txt"]);
    expect(r.skipped).toHaveLength(4);
  });
});

describe("runImports", () => {
  test("respects concurrency and continues after a failure", async () => {
    const files = ["A.jpg", "B.jpg", "C.jpg", "D.jpg", "E.jpg"].map(f);
    let active = 0;
    let peak = 0;
    const rows: ImportRow[] = [];
    const upload = vi.fn(async (file: File): Promise<ImportResult> => {
      active += 1;
      peak = Math.max(peak, active);
      await new Promise((resolve) => setTimeout(resolve, 5));
      active -= 1;
      if (file.name === "C.jpg") {
        throw new ApiError(400, "UNSUPPORTED_FILE_TYPE", "HEIC is not supported", undefined, { extension: "heic" });
      }
      return {
        fileName: file.name,
        outcome: "IMPORTED",
        mediaId: 1,
        productId: 1,
        sku: "MG-X",
        shotCode: "P1",
        status: "ACCEPTED",
        reasons: [],
      };
    });

    await runImports(files, upload, 2, (row) => rows.push(row));

    expect(peak).toBeLessThanOrEqual(2);
    expect(upload).toHaveBeenCalledTimes(5);
    expect(rows).toHaveLength(5);
    const failed = rows.filter((r) => !r.ok);
    expect(failed).toHaveLength(1);
    expect(failed[0].error?.code).toBe("UNSUPPORTED_FILE_TYPE");
    // details must survive so the UI can show the dedicated heic hint.
    expect(failed[0].error?.details).toEqual({ extension: "heic" });
    expect(rows.filter((r) => r.ok)).toHaveLength(4);
  });
});
