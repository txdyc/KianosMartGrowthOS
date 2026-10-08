package com.kiano.content.ads;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * GH₵ currency formatting for ad statics (§7.3): integers render without
 * decimals (GH₵ 299), fractional amounts always with two (GH₵ 299.50), and
 * thousands are comma-separated (GH₵ 1,299).
 */
public final class GhsFormat {

    private static final DecimalFormat INTEGER_FORMAT = new DecimalFormat("#,##0",
            DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00",
            DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    private GhsFormat() {
    }

    public static String format(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        return normalized.scale() <= 0
                ? "GH₵ " + INTEGER_FORMAT.format(normalized)
                : "GH₵ " + DECIMAL_FORMAT.format(normalized);
    }
}