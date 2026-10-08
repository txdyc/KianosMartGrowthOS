package com.kiano.content.facts;

import com.kiano.content.template.TemplateFacts;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structured facts of a product (spec §8 facts_json), also the class the
 * Claude fact-draft output is parsed into. null/empty list fields mean "not
 * read"; the LLM is told never to guess. The eight scalar fields are @Nullable;
 * the four list fields stay required (may be empty lists) - the OpenAI-compatible
 * JSON schema and {@code OutputValidator} rely on this split.
 */
public record FactsJson(@Nullable String model, @Nullable String category,
        @Nullable String capacity, @Nullable Integer powerW, @Nullable String voltage,
        @Nullable String material, @Nullable String colour, @Nullable String warranty,
        List<String> inBox, List<String> features, List<String> benefits,
        List<String> forbiddenClaims) {

    /** Maps onto the C2 template facts; null/empty entries are skipped in renders. */
    public TemplateFacts toTemplateFacts(String productName) {
        return new TemplateFacts(productName, model, category, capacity, powerW, voltage,
                material, colour, warranty, inBox, features, benefits);
    }
}