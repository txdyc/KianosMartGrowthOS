package com.kiano.content.qc;

import java.util.List;

/**
 * Photo QC outcome. width and height are display dimensions (already
 * rotated for EXIF orientation); the numeric metrics come from the
 * downsampled analysis image.
 */
public record PhotoQcResult(
        int width,
        int height,
        int exifOrientation,
        double blurVariance,
        double overRatio,
        double underRatio,
        List<QcReason> reasons,
        byte[] thumbnailJpeg) {
}
