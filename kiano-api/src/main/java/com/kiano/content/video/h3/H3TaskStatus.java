package com.kiano.content.video.h3;

/**
 * Provider-side state of an H3 clip task. Anything that is neither Success nor
 * Fail maps to {@link Pending}, so unknown transitional states simply keep
 * polling.
 */
public sealed interface H3TaskStatus {

    record Pending() implements H3TaskStatus {
    }

    /** The clip is ready at the provider; {@code fileRef} feeds download(). */
    record Succeeded(String fileRef) implements H3TaskStatus {
    }

    /** The provider gave up; {@code contentRejected} marks a moderation stop. */
    record Failed(String reason, boolean retryable, boolean contentRejected) implements H3TaskStatus {
    }
}