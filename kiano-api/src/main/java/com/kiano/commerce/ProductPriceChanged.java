package com.kiano.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Published (inside the sync transaction) whenever an existing product's
 * regular/sale price or promotion end date changed. C4 consumes it with a
 * {@code @TransactionalEventListener} to re-check affected content.
 */
public record ProductPriceChanged(long tenantId, long productId, String sku,
        BigDecimal oldRegular, BigDecimal newRegular, BigDecimal oldSale, BigDecimal newSale,
        @Nullable Instant oldSaleToAt, @Nullable Instant newSaleToAt) {
}
