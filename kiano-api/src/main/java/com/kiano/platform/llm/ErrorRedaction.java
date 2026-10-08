package com.kiano.platform.llm;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Scrubs key material out of upstream error messages before they are written
 * to llm_call.error, the audit log or API responses (Review Focus 1): bearer
 * tokens, {@code sk-} prefixed keys and x-api-key header fragments, then
 * truncates to 500 characters.
 */
public final class ErrorRedaction {

    private static final int MAX_LENGTH = 500;
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+");
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern X_API_KEY = Pattern.compile("(?i)x-api-key\\s*[:=]?\\s*[^\"\\s,]+");

    private ErrorRedaction() {
    }

    /** Scrubbed copy of {@code raw}; null stays null. */
    public static @Nullable String clean(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String scrubbed = BEARER.matcher(raw).replaceAll("Bearer [REDACTED]");
        scrubbed = SK_KEY.matcher(scrubbed).replaceAll("sk-[REDACTED]");
        scrubbed = X_API_KEY.matcher(scrubbed).replaceAll("[REDACTED]");
        if (scrubbed.length() > MAX_LENGTH) {
            scrubbed = scrubbed.substring(0, MAX_LENGTH) + "...";
        }
        return scrubbed;
    }
}