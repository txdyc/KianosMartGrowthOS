package com.kiano.platform.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import java.lang.annotation.Annotation;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * JSON Schema generation (Draft 2020-12) for LLM structured-output records,
 * cached per type. The JacksonModule maps @JsonPropertyDescription into
 * "description"; record components carrying JSpecify {@code @Nullable} are
 * optional, every other component is required. Used to steer OpenAI-compatible
 * providers whose schema is appended to the system prompt instead of sent via
 * a structured-output API.
 */
@Component
public class OutputSchemas {

    private final ConcurrentHashMap<Class<?>, String> cache = new ConcurrentHashMap<>();

    /** The JSON Schema for {@code type} as a compact JSON string. */
    public String schemaFor(Class<?> type) {
        return cache.computeIfAbsent(type, this::generate);
    }

    private String generate(Class<?> type) {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        builder.with(new JacksonModule());
        builder.forFields()
                .withRequiredCheck(field -> !isNullable(field));
        JsonNode schema = new SchemaGenerator(builder.build()).generateSchema(type);
        return schema.toString();
    }

    /** JSpecify @Nullable on the record component's annotated type. */
    private static boolean isNullable(FieldScope field) {
        for (Annotation annotation
                : field.getMember().getRawMember().getAnnotatedType().getAnnotations()) {
            if (annotation instanceof org.jspecify.annotations.Nullable) {
                return true;
            }
        }
        return false;
    }
}