package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Scrubbing key material out of upstream error messages before they land in
 * llm_call.error, audit log or API responses.
 */
class ErrorRedactionTest {

    @Test
    void removesBearerSkKeysAndTruncates() {
        String longSuffix = "x".repeat(600);
        String raw = "401 {\"message\":\"Authorization: Bearer sk-abcdefgh12345678"
                + " and x-api-key: sk-zzzzzz99999999\"}" + longSuffix;

        String cleaned = ErrorRedaction.clean(raw);

        assertThat(cleaned).doesNotContain("sk-abcdefgh12345678");
        assertThat(cleaned).doesNotContain("sk-zzzzzz99999999");
        assertThat(cleaned).doesNotContain("Bearer sk-");
        assertThat(cleaned.length()).isLessThanOrEqualTo(500 + "...".length());
        assertThat(cleaned).endsWith("...");
    }

    @Test
    void removesTheKnownSecret_evenWithoutARecognisablePrefix() {
        String key = "3f9a1234abcd5678ef00";
        String raw = "401 {\"error\":\"Invalid API key: " + key + "\"}";

        String cleaned = ErrorRedaction.clean(raw, key);

        assertThat(cleaned).doesNotContain(key).contains("[REDACTED]");
    }

    @Test
    void blankOrNullSecrets_areIgnored() {
        assertThat(ErrorRedaction.clean("plain message", "", null)).isEqualTo("plain message");
    }
}