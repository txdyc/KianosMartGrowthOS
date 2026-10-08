package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.copy.CopyDraft;
import com.kiano.content.facts.FactDraftResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Schema output for the LLM structured-output records: Draft 2020-12 JSON
 * Schema generated from the record classes, with @JsonPropertyDescription
 * carried into "description" and @Nullable components left out of "required".
 */
class OutputSchemasTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OutputSchemas schemas = new OutputSchemas();

    @Test
    void copyDraft_schemaHasDescriptionsAndAllRequired() throws Exception {
        JsonNode schema = MAPPER.readTree(schemas.schemaFor(CopyDraft.class));

        assertThat(schema.path("properties").path("title").path("description").asText())
                .contains("Product title");
        assertThat(schema.path("properties").path("waMessage").path("description").asText())
                .contains("{{price}}");

        List<String> required = allRequiredFields(schema);
        assertThat(required).contains("title", "shortBullets", "whyBuy", "faq", "seoTitle",
                "seoDescription", "gshopTitle", "waMessage");
    }

    @Test
    void factDraftResult_nullableFactFieldsNotRequired_listsRequired() throws Exception {
        JsonNode schema = MAPPER.readTree(schemas.schemaFor(FactDraftResult.class));

        List<String> required = allRequiredFields(schema);
        // the four facts lists are non-nullable -> required; the scalar fact
        // fields (model, category, capacity, ...) are @Nullable -> optional
        assertThat(required).contains("inBox", "features", "benefits", "forbiddenClaims");
        assertThat(required).doesNotContain("capacity", "model", "powerW", "warranty");
    }

    /** Every scalar value of every "required" array anywhere in the schema. */
    private static List<String> allRequiredFields(JsonNode schema) {
        List<String> fields = new ArrayList<>();
        collectRequired(schema, fields);
        return fields;
    }

    private static void collectRequired(JsonNode node, List<String> out) {
        if (node instanceof tools.jackson.databind.node.ObjectNode object) {
            object.properties().forEach(entry -> {
                String name = entry.getKey();
                if (name.equals("required") && entry.getValue().isArray()) {
                    entry.getValue().forEach(element -> out.add(element.asText()));
                } else {
                    collectRequired(entry.getValue(), out);
                }
            });
        }
    }
}