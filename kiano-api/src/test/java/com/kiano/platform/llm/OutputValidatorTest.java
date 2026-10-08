package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.copy.CopyDraft;
import com.kiano.content.copy.CopyDraft.Faq;
import com.kiano.content.facts.FactDraftResult;
import com.kiano.content.facts.FactsJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Local structural validation of LLM structured output against the record
 * classes: record components without JSpecify {@code @Nullable} must be
 * present and non-null; record elements inside lists are checked recursively.
 */
class OutputValidatorTest {

    @Test
    void validCopyDraft_noViolations() {
        CopyDraft draft = new CopyDraft("Morgan MG-1500 ...", List.of("b1", "b2", "b3"),
                List.of("w1", "w2", "w3"), List.of(new Faq("q", "a")), "seo title",
                "seo description", "GS title", "text {{price}}");

        assertThat(OutputValidator.validate(draft)).isEmpty();
    }

    @Test
    void missingTitle_violation() {
        CopyDraft draft = new CopyDraft(null, List.of("b1"), List.of("w1"), List.of(),
                "seo", "seo", "gshop", "text {{price}}");

        assertThat(OutputValidator.validate(draft)).containsExactly("title is required");
    }

    @Test
    void nestedNullList_violationWithPath() {
        FactsJson facts = new FactsJson("MG-1500", "Blender", null, 350, null, null, null, null,
                null, List.of(), List.of(), List.of());
        FactDraftResult result = new FactDraftResult(facts, Map.of(), List.of());

        assertThat(OutputValidator.validate(result)).containsExactly("facts.inBox is required");
    }

    @Test
    void nullableFactField_null_isFine() {
        FactsJson facts = new FactsJson("MG-1500", "Blender", null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of());
        FactDraftResult result = new FactDraftResult(facts, Map.of(), List.of());

        assertThat(OutputValidator.validate(result)).isEmpty();
    }
}