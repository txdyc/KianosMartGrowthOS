package com.kiano.platform.llm;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Local structural validation of LLM structured output against the record it
 * must conform to, mirroring the "required" rules of {@link OutputSchemas}
 * without a schema engine: a record component not annotated with JSpecify
 * {@code @Nullable} must be present and non-null; list and map fields are only
 * forbidden from being null (empty is fine); record elements inside lists are
 * validated recursively. Violations are reported as dotted paths, e.g.
 * {@code "facts.inBox is required"}.
 */
public final class OutputValidator {

    private OutputValidator() {
    }

    /** Structural violations of {@code value}, empty when it is valid. */
    public static List<String> validate(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        validateRecord(value, "", violations);
        return violations;
    }

    private static void validateRecord(Object value, String path, List<String> violations) {
        if (!(value instanceof Record record)) {
            return;
        }
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object componentValue = componentValue(record, component);
            String componentPath = path.isEmpty() ? component.getName()
                    : path + "." + component.getName();
            if (componentValue == null) {
                if (!isNullable(component)) {
                    violations.add(componentPath + " is required");
                }
                continue;
            }
            if (componentValue instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Record element) {
                        validateRecord(element, componentPath + "[" + i + "]", violations);
                    }
                }
            } else if (componentValue instanceof Record nested) {
                validateRecord(nested, componentPath, violations);
            }
        }
    }

    private static Object componentValue(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot read record component " + component.getName(),
                    ex);
        }
    }

    /** True when the component carries JSpecify @Nullable (type-use or declaration). */
    private static boolean isNullable(RecordComponent component) {
        for (Annotation annotation : component.getAnnotatedType().getAnnotations()) {
            if (annotation instanceof org.jspecify.annotations.Nullable) {
                return true;
            }
        }
        for (Annotation annotation : component.getAnnotations()) {
            if (annotation instanceof org.jspecify.annotations.Nullable) {
                return true;
            }
        }
        return false;
    }
}