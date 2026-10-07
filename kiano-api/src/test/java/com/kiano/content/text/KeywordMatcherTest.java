package com.kiano.content.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keyword matching rules (extracted from ShotRequirementService, behaviour
 * unchanged): normalise case/separators, whole words, optional s/es plural,
 * category slugs before product name.
 */
class KeywordMatcherTest {

    @Test
    void categorySlug_matchesWholeWordWithPlural() {
        assertThat(KeywordMatcher.matches("kettle", List.of("electric-kettles"), "Electric 1.7L"))
                .isEqualTo(KeywordMatcher.Match.CATEGORY);
        assertThat(KeywordMatcher.matches("pressure-cooker", List.of("pressure-cookers"),
                "Micro Pressure Cooker")).isEqualTo(KeywordMatcher.Match.CATEGORY);
        assertThat(KeywordMatcher.matches("air-fryer", List.of("air-fryers"),
                "Blender & Air Fryer Combo")).isEqualTo(KeywordMatcher.Match.CATEGORY);
    }

    @Test
    void categoryName_fallbackWhenSlugsDoNotMatch() {
        assertThat(KeywordMatcher.matches("blender", List.of("kitchen-appliances"),
                "2in1 2.5L Blender")).isEqualTo(KeywordMatcher.Match.NAME);
        assertThat(KeywordMatcher.matches("fan", List.of("uncategorized"),
                "8-in lithium battery fan metal model")).isEqualTo(KeywordMatcher.Match.NAME);
        assertThat(KeywordMatcher.matches("iron", List.of(), "Steam Iron 2200W"))
                .isEqualTo(KeywordMatcher.Match.NAME);
        assertThat(KeywordMatcher.matches("pressure-cooker", List.of("uncategorized"),
                "5L Pressure Cooker")).isEqualTo(KeywordMatcher.Match.NAME);
    }

    @Test
    void wholeWordsOnly_ignoresPartialWords() {
        // "Speaker" must not match "fan", "Environment" must not match "iron".
        assertThat(KeywordMatcher.matches("fan", List.of(), "Fantastic Bluetooth Speaker"))
                .isEqualTo(KeywordMatcher.Match.NONE);
        assertThat(KeywordMatcher.matches("rice-cooker", List.of("kitchen-appliances"),
                "Electric Induction Cooker")).isEqualTo(KeywordMatcher.Match.NONE);
        assertThat(KeywordMatcher.matches("rice-cooker", List.of(), "Electric Egg Cooker"))
                .isEqualTo(KeywordMatcher.Match.NONE);
    }

    @Test
    void normalisesCaseSeparatorsAndPlurals() {
        assertThat(KeywordMatcher.matches("air-fryer", List.of("uncategorized"), "6L Air Fryer"))
                .isEqualTo(KeywordMatcher.Match.NAME);
        assertThat(KeywordMatcher.matches("air-fryer", List.of("uncategorized"), "8L-Air-Fryer"))
                .isEqualTo(KeywordMatcher.Match.NAME);
        assertThat(KeywordMatcher.matches("kettle", List.of("uncategorized"), "ELECTRIC KETTLES 2L"))
                .isEqualTo(KeywordMatcher.Match.NAME);
        assertThat(KeywordMatcher.matches("rice-cooker", List.of(), "5L Rice cooker purple color"))
                .isEqualTo(KeywordMatcher.Match.NAME);
    }

    @Test
    void nullOrEmptyInputs_neverMatch() {
        assertThat(KeywordMatcher.matches("fan", List.of(), null))
                .isEqualTo(KeywordMatcher.Match.NONE);
        assertThat(KeywordMatcher.matches("fan", List.of(), ""))
                .isEqualTo(KeywordMatcher.Match.NONE);
    }
}
