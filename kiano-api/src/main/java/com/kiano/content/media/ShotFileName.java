package com.kiano.content.media;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses shot file names of the form SKU_shotCode.ext. The directory part is
 * ignored, shot codes and extensions are case-insensitive, and the SKU is
 * split at the last underscore so SKUs may themselves contain underscores.
 * Allowed types: JPG/JPEG for P* and PROMO shots, MP4/MOV for V* shots.
 */
public final class ShotFileName {

    private static final Pattern SHOT_CODE = Pattern.compile("^(P[1-9]|V[1-3]|PROMO)$");
    private static final Set<String> PHOTO_EXTENSIONS = Set.of("jpg", "jpeg");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov");
    private static final Set<String> HEIC_EXTENSIONS = Set.of("heic", "heif");
    private static final String HEIC_MESSAGE =
            "HEIC is not supported. Export as JPEG (iPhone: Settings › Camera › Formats › Most Compatible).";
    private static final String INVALID_NAME_MESSAGE =
            "File name must be SKU_shotCode.ext, e.g. MG-BL200_P5.jpg, MG_FAN_16_V1.mp4 or MG-BL200_PROMO.jpg.";
    private static final String UNSUPPORTED_TYPE_MESSAGE =
            "Photos and promo images must be JPG or JPEG; videos must be MP4 or MOV.";

    private ShotFileName() {
    }

    public static ParsedShotFile parse(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.startsWith(".")) {
            throw invalid(name, null);
        }

        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            throw invalid(name, null);
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String base = name.substring(0, dot);

        int underscore = base.lastIndexOf('_');
        if (underscore < 0) {
            throw invalid(name, extension);
        }
        String sku = base.substring(0, underscore).trim();
        String shotCode = base.substring(underscore + 1).trim().toUpperCase(Locale.ROOT);
        if (sku.isEmpty() || !SHOT_CODE.matcher(shotCode).matches()) {
            throw invalid(name, extension);
        }

        boolean video = shotCode.startsWith("V");
        Set<String> allowed = video ? VIDEO_EXTENSIONS : PHOTO_EXTENSIONS;
        if (!allowed.contains(extension)) {
            if (HEIC_EXTENSIONS.contains(extension)) {
                throw new InvalidShotFileNameException("UNSUPPORTED_FILE_TYPE", HEIC_MESSAGE, extension, fileName);
            }
            throw new InvalidShotFileNameException("UNSUPPORTED_FILE_TYPE", UNSUPPORTED_TYPE_MESSAGE, extension,
                    fileName);
        }

        MediaKind kind = video ? MediaKind.VIDEO
                : shotCode.equals("PROMO") ? MediaKind.PROMO_IMAGE : MediaKind.PHOTO;
        return new ParsedShotFile(sku, shotCode, kind, extension);
    }

    private static InvalidShotFileNameException invalid(String fileName, String extension) {
        return new InvalidShotFileNameException("INVALID_FILE_NAME", INVALID_NAME_MESSAGE, extension, fileName);
    }
}
