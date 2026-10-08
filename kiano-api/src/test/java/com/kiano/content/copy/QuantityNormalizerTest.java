package com.kiano.content.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.copy.QuantityNormalizer.Quantity;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * QuantityNormalizer (Review Focus 3): unit normalisation, ml/kW/year
 * conversion, range expansion and bare-number ignoring.
 */
class QuantityNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "1.5L,1.5,L",
            "1.5 L,1.5,L",
            "1500 ml,1.5,L",
            "'1,500ml',1.5,L",
            "350W,350,W",
            "350 watts,350,W",
            "1.2kW,1200,W",
            "12 months,12,month",
            "1 year,12,month",
            "1 month,1,month",
            "220V,220,V",
            "50Hz,50,Hz",
            "12 inch,12,in"
    })
    void normalises(String text, String expectedValue, String expectedUnit) {
        Set<Quantity> quantities = QuantityNormalizer.extract(text);
        assertThat(quantities).containsExactly(
                new Quantity(new BigDecimal(expectedValue), expectedUnit));
    }

    @Test
    void range_expandsBothEnds() {
        Set<Quantity> dash = QuantityNormalizer.extract("220-240V");
        assertThat(dash).containsExactlyInAnyOrder(
                new Quantity(new BigDecimal("220"), "V"),
                new Quantity(new BigDecimal("240"), "V"));

        Set<Quantity> enDash = QuantityNormalizer.extract("220–240 V");
        assertThat(enDash).containsExactlyInAnyOrder(
                new Quantity(new BigDecimal("220"), "V"),
                new Quantity(new BigDecimal("240"), "V"));

        Set<Quantity> word = QuantityNormalizer.extract("220 to 240V");
        assertThat(word).containsExactlyInAnyOrder(
                new Quantity(new BigDecimal("220"), "V"),
                new Quantity(new BigDecimal("240"), "V"));
    }

    @Test
    void bareNumbers_ignored() {
        assertThat(QuantityNormalizer.extract("2 in 1 blender with 3 speeds"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"comes with 3 lids", "4 modes", "store 2 inside", "6 wheels",
            "2 layers of protection", "5 levels", "10 velocity settings", "3 colours"})
    void unitLetters_atStartOfAWord_areNotUnits(String text) {
        assertThat(QuantityNormalizer.extract(text)).isEmpty();
    }

    @Test
    void unitFollowedByPunctuationOrEnd_stillMatches() {
        assertThat(QuantityNormalizer.extract("A 1.5L. jar, 350W) motor, 12\" fan, 2kg"))
                .containsExactlyInAnyOrder(
                        new Quantity(new BigDecimal("1.5"), "L"),
                        new Quantity(new BigDecimal("350"), "W"),
                        new Quantity(new BigDecimal("12"), "in"),
                        new Quantity(new BigDecimal("2"), "kg"));
    }

    @Test
    void mixedText_extractsKnownUnitsOnly() {
        Set<Quantity> quantities = QuantityNormalizer.extract(
                "A 1.5L kettle with a 350W element and 1-year warranty, 2 in 1");
        assertThat(quantities).containsExactlyInAnyOrder(
                new Quantity(new BigDecimal("1.5"), "L"),
                new Quantity(new BigDecimal("350"), "W"),
                new Quantity(new BigDecimal("12"), "month"));
    }
}