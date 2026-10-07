package com.kiano.commerce;

import java.math.BigDecimal;

/**
 * Published (inside the sync transaction) whenever an existing product's
 * regular or sale price changed. C4 will consume it with a
 * {@code @TransactionalEventListener} to re-check affected content.
 */
public record ProductPriceChanged(long tenantId, long productId, String sku,
        BigDecimal oldRegular, BigDecimal newRegular, BigDecimal oldSale, BigDecimal newSale) {
}
