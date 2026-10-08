package com.kiano.platform.llm;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A fully resolved route: provider connection info, model, vision capability
 * and price, with the plaintext API key in memory only. When no route is
 * configured the built-in env default (Anthropic + .env key) is returned and
 * {@code usingDefault=true}; {@code updatedAt} drives client cache invalidation.
 */
public record ResolvedRoute(LlmPurpose purpose, @Nullable Long providerId, ProviderKind kind,
        String providerName, @Nullable String baseUrl, String apiKey, String model,
        boolean supportsImages, Pricing pricing, Instant updatedAt, boolean usingDefault) {

    /** llm_call.provider column value; ANTHROPIC or OPENAI_COMPATIBLE:{name}. */
    public String providerLabel() {
        return kind == ProviderKind.ANTHROPIC ? "ANTHROPIC"
                : "OPENAI_COMPATIBLE:" + providerName;
    }
}