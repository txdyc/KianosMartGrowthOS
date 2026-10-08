package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Retrieving the first complete JSON object from LLM output that may be fenced
 * in Markdown or prefixed with prose (DeepSeek behaviour), balancing braces
 * while skipping strings and escapes.
 */
class JsonExtractionTest {

    @Test
    void plainJson() {
        String content = "{\"a\":1,\"b\":{\"c\":2}} trailing";
        assertThat(JsonExtraction.firstObject(content))
                .hasValue("{\"a\":1,\"b\":{\"c\":2}}");
    }

    @Test
    void fencedJson() {
        String content = "```json\n{\"a\":1}\n```";
        assertThat(JsonExtraction.firstObject(content)).hasValue("{\"a\":1}");
    }

    @Test
    void prefixedText() {
        String content = "Here is the result:\n{\"a\":1} done";
        assertThat(JsonExtraction.firstObject(content)).hasValue("{\"a\":1}");
    }

    @Test
    void braceInsideString_handled() {
        String content = "{\"a\":\"x } y\",\"b\":2}";
        assertThat(JsonExtraction.firstObject(content)).hasValue(content);
    }

    @Test
    void emptyOrNoObject_empty() {
        assertThat(JsonExtraction.firstObject("")).isEmpty();
        assertThat(JsonExtraction.firstObject("no json here")).isEmpty();
        assertThat(JsonExtraction.firstObject("```json\n```")).isEmpty();
    }
}