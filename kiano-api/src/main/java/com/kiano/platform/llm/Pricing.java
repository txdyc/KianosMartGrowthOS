package com.kiano.platform.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-model price in US dollars per million tokens, carried on the route so
 * two tasks may use the same model at different prices. Cost formula complies
 * with the spec: cache hits are priced separately and deducted from the input
 * count; cacheRead is truncated to no more than the input tokens.
 */
public record Pricing(BigDecimal inputPerMtok, BigDecimal outputPerMtok,
        BigDecimal cacheReadPerMtok) {

    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000L);

    public BigDecimal cost(long input, long output, long cacheRead) {
        long cache = Math.min(cacheRead, input);
        long freshInput = input - cache;
        BigDecimal cost = BigDecimal.valueOf(freshInput).multiply(inputPerMtok)
                .add(BigDecimal.valueOf(cache).multiply(cacheReadPerMtok))
                .add(BigDecimal.valueOf(output).multiply(outputPerMtok));
        return cost.divide(PER_MILLION, 6, RoundingMode.HALF_UP);
    }
}