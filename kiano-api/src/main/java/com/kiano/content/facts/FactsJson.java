package com.kiano.content.facts;

import com.kiano.content.template.TemplateFacts;
import java.util.List;

/**
 * The structured facts of a product (spec §8 facts_json), also the class the
 * Claude fact-draft output is parsed into. null/empty list fields mean "not
 * read"; the LLM is told never to guess.
 */
public record FactsJson(String model, String category, String capacity, Integer powerW,
        String voltage, String material, String colour, String warranty, List<String> inBox,
        List<String> features, List<String> benefits, List<String> forbiddenClaims) {

    /** Maps onto the C2 template facts; null/empty entries are skipped in renders. */
    public TemplateFacts toTemplateFacts(String productName) {
        return new TemplateFacts(productName, model, category, capacity, powerW, voltage,
                material, colour, warranty, inBox, features, benefits);
    }
}