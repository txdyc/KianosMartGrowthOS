package com.kiano.content.video.h3;

import org.jspecify.annotations.Nullable;

/**
 * HTTP or transport failure while talking to the H3 API. {@code retryable}
 * mirrors the status mapping (429 and 5xx retry, other 4xx do not) and
 * {@code contentRejected} flags a moderation refusal so the coordinator can
 * escalate with {@code H3_CONTENT_REJECTED}. Messages are redacted: they never
 * contain the API key.
 */
public class H3ClientException extends RuntimeException {

    private final boolean retryable;
    private final boolean contentRejected;

    public H3ClientException(String message, boolean retryable, boolean contentRejected) {
        this(message, retryable, contentRejected, null);
    }

    public H3ClientException(String message, boolean retryable, boolean contentRejected,
            @Nullable Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.contentRejected = contentRejected;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean contentRejected() {
        return contentRejected;
    }
}