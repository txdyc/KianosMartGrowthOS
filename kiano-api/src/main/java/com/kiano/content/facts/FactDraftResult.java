package com.kiano.content.facts;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Map;

/**
 * Structured output of the fact-draft LLM call (Task 3): the facts read from
 * the rating plate + promo image, the field→source mapping and the fields the
 * model could not read. Field descriptions travel in the JSON schema so the
 * model knows the rules (never guess, keep original units, sources restricted
 * to the P5/PROMO/WOO_TEXT/NONE vocabulary).
 */
public record FactDraftResult(
        @JsonPropertyDescription("Product facts. Values not clearly visible must be null - never guess.") FactsJson facts,
        @JsonPropertyDescription("Field -> source. Only P5, PROMO, WOO_TEXT or NONE are allowed values.") Map<String, FieldSource> sources,
        @JsonPropertyDescription("Fields that could not be read clearly from the photos; a human must check them.") List<String> unreadable) {
}