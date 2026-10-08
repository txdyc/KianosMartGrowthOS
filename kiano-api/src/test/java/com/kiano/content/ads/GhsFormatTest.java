package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GhsFormatTest {

    @Test
    void format_integerDecimalAndThousands() {
        assertThat(GhsFormat.format(new BigDecimal("299"))).isEqualTo("GH₵ 299");
        assertThat(GhsFormat.format(new BigDecimal("299.50"))).isEqualTo("GH₵ 299.50");
        assertThat(GhsFormat.format(new BigDecimal("1299"))).isEqualTo("GH₵ 1,299");
        assertThat(GhsFormat.format(new BigDecimal("1299.50"))).isEqualTo("GH₵ 1,299.50");
        assertThat(GhsFormat.format(new BigDecimal("59.50"))).isEqualTo("GH₵ 59.50");
        // stored prices are scale 2; a zero-fraction amount renders without decimals
        assertThat(GhsFormat.format(new BigDecimal("299.00"))).isEqualTo("GH₵ 299");
    }

    @Test
    void null_throws() {
        assertThatThrownBy(() -> GhsFormat.format(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}