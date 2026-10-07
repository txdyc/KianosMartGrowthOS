package com.kiano.content.qc;

/**
 * Why a media file failed quality control. Codes are stable and shown by the
 * web UI via its i18n dictionaries.
 */
public enum QcReason {
    LOW_RESOLUTION,
    BLURRY,
    OVEREXPOSED,
    UNDEREXPOSED,
    NOT_PORTRAIT,
    FPS_OUT_OF_RANGE,
    DURATION_OUT_OF_RANGE
}
