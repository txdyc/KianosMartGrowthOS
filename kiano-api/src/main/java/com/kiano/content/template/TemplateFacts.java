package com.kiano.content.template;

import java.util.List;

/**
 * The locked product facts a template renders (spec §8 facts_json). C3 maps
 * the locked fact sheet onto this record; null/empty entries are omitted
 * from the rendered page rather than printed as "null".
 */
public record TemplateFacts(String productName, String model, String category,
        String capacity, Integer powerW, String voltage, String material,
        String colour, String warranty, List<String> inBox, List<String> features,
        List<String> benefits) {
}
