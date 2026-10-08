package com.kiano.platform.llm;

import java.util.Optional;

/**
 * Extracts the first complete JSON object from LLM output that may be wrapped
 * in Markdown code fences or prefixed/suffixed with prose (observed with
 * DeepSeek even in json_object mode). Braces are balanced while strings and
 * escapes inside them are skipped, so a literal "{" or "}" in a value never
 * mis-balances the scan.
 */
public final class JsonExtraction {

    private JsonExtraction() {
    }

    /**
     * The first complete JSON object in {@code content}, or empty when content
     * is null/blank or no complete object is present.
     */
    public static Optional<String> firstObject(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String stripped = stripCodeFence(content);
        int start = stripped.indexOf('{');
        if (start < 0) {
            return Optional.empty();
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return Optional.of(stripped.substring(start, i + 1));
            }
        }
        return Optional.empty();
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence);
            }
        }
        return trimmed;
    }
}