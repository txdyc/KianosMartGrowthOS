package com.kiano.content.asset;

import java.util.Arrays;

/**
 * Export file names: {SKU}_{angle}_{type}_{w}x{h}_v{n}.{ext}. Parsing takes
 * the four fixed segments from the right, so SKUs may contain underscores.
 */
public final class AssetFileName {

    private AssetFileName() {
    }

    public static String format(String sku, String angle, String type, int width, int height,
            int version, String ext) {
        return sku + "_" + angle + "_" + type + "_" + width + "x" + height
                + "_v" + version + "." + ext;
    }

    public static Parsed parse(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("File name has no extension: " + fileName);
        }
        String ext = fileName.substring(dot + 1);
        String[] segments = fileName.substring(0, dot).split("_", -1);
        if (segments.length < 5) {
            throw new IllegalArgumentException("File name has too few segments: " + fileName);
        }
        String versionPart = segments[segments.length - 1];
        String dims = segments[segments.length - 2];
        int x = dims.indexOf('x');
        if (!versionPart.startsWith("v") || x < 0) {
            throw new IllegalArgumentException("File name is not in export format: " + fileName);
        }
        String sku = String.join("_",
                Arrays.copyOfRange(segments, 0, segments.length - 4));
        return new Parsed(sku, segments[segments.length - 4], segments[segments.length - 3],
                Integer.parseInt(dims.substring(0, x)), Integer.parseInt(dims.substring(x + 1)),
                Integer.parseInt(versionPart.substring(1)), ext);
    }

    /** The segments of a parsed export file name. */
    public record Parsed(String sku, String angle, String type, int width, int height, int version,
            String ext) {
    }
}
