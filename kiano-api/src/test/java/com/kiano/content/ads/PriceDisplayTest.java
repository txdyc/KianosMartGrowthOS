package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.commerce.ProductView;
import com.kiano.platform.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PriceDisplay: current price always shown, strikethrough + "Ends d MMM" only
 * while a dated promotion is running at render time.
 */
class PriceDisplayTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Test
    void noSale_currentOnly() {
        PriceDisplay display = PriceDisplay.of(product(
                new BigDecimal("299"), null, null, null), NOW);

        assertThat(display.current()).isEqualTo("GH₵ 299");
        assertThat(display.strike()).isNull();
        assertThat(display.endsLabel()).isNull();
        assertThat(display.snapshot()).isEqualByComparingTo("299");
    }

    @Test
    void realSaleWithEndDate_strikeAndEnds() {
        Instant saleTo = NOW.plusSeconds(3L * 24 * 3600); // 2026-10-23
        // during the sale Woo reports price == sale price
        PriceDisplay display = PriceDisplay.of(product(
                new BigDecimal("249"), new BigDecimal("249"), new BigDecimal("299"),
                saleTo), NOW);

        assertThat(display.current()).isEqualTo("GH₵ 249");
        assertThat(display.strike()).isEqualTo("GH₵ 299");
        assertThat(display.endsLabel()).isEqualTo("Ends 23 Oct");
        assertThat(display.snapshot()).isEqualByComparingTo("249");
    }

    @Test
    void saleWithoutEndDate_noStrike() {
        PriceDisplay display = PriceDisplay.of(product(
                new BigDecimal("249"), new BigDecimal("249"), new BigDecimal("299"),
                null), NOW);

        assertThat(display.strike()).isNull();
        assertThat(display.endsLabel()).isNull();
    }

    @Test
    void expiredSale_noStrike_evenBeforeSync() {
        // Review Focus 1: the promotion ended; even though Woo has not synced
        // the price back yet, the render must not draw strikethrough.
        Instant saleTo = NOW.minusSeconds(60);
        PriceDisplay display = PriceDisplay.of(product(
                new BigDecimal("249"), new BigDecimal("249"), new BigDecimal("299"),
                saleTo), NOW);

        assertThat(display.strike()).isNull();
        assertThat(display.endsLabel()).isNull();
    }

    @Test
    void salePriceNotLower_noStrike() {
        PriceDisplay display = PriceDisplay.of(product(
                new BigDecimal("299"), new BigDecimal("299"), new BigDecimal("299"),
                NOW.plusSeconds(3600)), NOW);

        assertThat(display.strike()).isNull();
        assertThat(display.endsLabel()).isNull();
    }

    @Test
    void scheduledSaleNotStartedYet_noStrike() {
        // sale_price and dates are set ahead, but Woo still reports price == regular
        ProductView scheduled = new ProductView(1L, null, "simple", "MG-BL200", "Blender",
                new BigDecimal("299"), new BigDecimal("249"), new BigDecimal("299"), 10,
                "instock", "publish", null, List.of(),
                NOW.plusSeconds(2L * 24 * 3600), NOW.plusSeconds(9L * 24 * 3600));

        PriceDisplay display = PriceDisplay.of(scheduled, NOW);

        assertThat(display.current()).isEqualTo("GH₵ 299");
        assertThat(display.strike()).isNull();
        assertThat(display.endsLabel()).isNull();
    }

    @Test
    void saleWindowOpenButCurrentPriceNotLowered_noStrike() {
        // dates say the sale is on, but the price Woo charges is still the regular one
        ProductView stale = new ProductView(1L, null, "simple", "MG-BL200", "Blender",
                new BigDecimal("299"), new BigDecimal("249"), new BigDecimal("299"), 10,
                "instock", "publish", null, List.of(),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        assertThat(PriceDisplay.of(stale, NOW).strike()).isNull();
    }

    @Test
    void fallsBackToRegular_whenPriceNull() {
        PriceDisplay display = PriceDisplay.of(product(
                new BigDecimal("299"), null, new BigDecimal("299"), null), NOW);

        assertThat(display.current()).isEqualTo("GH₵ 299");
        assertThat(display.snapshot()).isEqualByComparingTo("299");
    }

    @Test
    void missingPrice_409() {
        assertThatThrownBy(() -> PriceDisplay.of(product(null, null, null, null), NOW))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("PRICE_MISSING");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
    }

    private static ProductView product(BigDecimal price, BigDecimal sale, BigDecimal regular,
            Instant saleTo) {
        BigDecimal regularOrPrice = regular != null ? regular : price;
        return new ProductView(1L, null, "simple", "MG-BL200", "Blender", regularOrPrice,
                sale, price, 10, "instock", "publish", null, List.of(), null, saleTo);
    }
}