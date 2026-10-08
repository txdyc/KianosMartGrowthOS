package com.kiano.content.copy;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts measured quantities from free text with unit normalisation
 * (Review Focus 3): "1.5L", "1.5 L", "1500 ml" and "1,500ml" all become
 * {@code 1.5 L}; "350W" and "350 watts" become {@code 350 W}; ranges like
 * "220-240V", "220–240 V" and "220 to 240V" expand to both endpoints; bare
 * numbers without a unit are ignored ("2 in 1", "3 speeds").
 */
public final class QuantityNormalizer {

    /** One measured value in its canonical unit (e.g. 1.5 L, 350 W, 240 V). */
    public record Quantity(BigDecimal value, String unit) {
    }

    private static final Pattern RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:-|–|—|to)\\s*(\\d+(?:\\.\\d+)?)\\s*"
                    + "(litres?|liters?|ml|watts?|kW|volts?|Hz|inches?|cm|kg|months?|mo|years?|year|L|l|W|V|in|\")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SINGLE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:-|–)?\\s*"
                    + "(litres?|liters?|ml|watts?|kW|volts?|Hz|inches?|cm|kg|months?|mo|years?|year|L|l|W|V|in|\")"
                    + "(?!\\s*\\d)",
            Pattern.CASE_INSENSITIVE);

    private QuantityNormalizer() {
    }

    public static Set<Quantity> extract(String text) {
        Set<Quantity> quantities = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return quantities;
        }
        // Strip thousand separators from numbers ("1,500ml" → "1500ml").
        String normalized = text.replaceAll("(?<=\\d),(?=\\d)", "");
        if (normalized.isBlank()) {
            return quantities;
        }
        Matcher range = RANGE.matcher(normalized);
        while (range.find()) {
            quantities.add(convert(range.group(1), range.group(3)));
            quantities.add(convert(range.group(2), range.group(3)));
        }
        Matcher single = SINGLE.matcher(normalized);
        while (single.find()) {
            quantities.add(convert(single.group(1), single.group(2)));
        }
        return quantities;
    }

    /** Converts a raw amount + unit token into the canonical unit and scale. */
    private static Quantity convert(String amount, String unitToken) {
        BigDecimal value = new BigDecimal(amount);
        String unit = unitToken.toLowerCase(Locale.ROOT);
        Quantity converted = switch (unit) {
            case "litre", "litres", "liter", "liters", "l" -> new Quantity(value, "L");
            case "ml" -> new Quantity(value.divide(BigDecimal.valueOf(1000)), "L");
            case "watt", "watts", "w" -> new Quantity(value, "W");
            case "kw" -> new Quantity(value.multiply(BigDecimal.valueOf(1000)), "W");
            case "volt", "volts", "v" -> new Quantity(value, "V");
            case "hz" -> new Quantity(value, "Hz");
            case "inch", "inches", "in", "\"" -> new Quantity(value, "in");
            case "cm" -> new Quantity(value, "cm");
            case "kg" -> new Quantity(value, "kg");
            case "month", "months", "mo" -> new Quantity(value, "month");
            case "year", "years" -> new Quantity(value.multiply(BigDecimal.valueOf(12)), "month");
            default -> throw new IllegalStateException("Unknown unit token: " + unitToken);
        };
        return new Quantity(normalise(converted.value()), converted.unit());
    }

    /** Drops trailing zeros so 1200.0, 1.500 and 12.0 compare as 1200, 1.5, 12. */
    private static BigDecimal normalise(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}