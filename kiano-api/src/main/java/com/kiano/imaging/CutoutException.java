package com.kiano.imaging;

/**
 * Raised when a cutout cannot be composited, e.g. because background removal
 * produced an empty alpha channel or kept the whole frame. The {@code code}
 * (CUTOUT_EMPTY / CUTOUT_FULL_FRAME) is a non-retryable executor error code.
 */
public class CutoutException extends RuntimeException {

    private final String code;

    public CutoutException(String code) {
        super(code);
        this.code = code;
    }

    public CutoutException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
