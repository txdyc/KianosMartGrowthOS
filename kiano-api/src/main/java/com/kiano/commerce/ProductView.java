package com.kiano.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One product (or variation) of the local catalog. categorySlugs is ordered.
 */
public record ProductView(long id, Long parentId, String type, String sku, String name,
        BigDecimal regularPrice, BigDecimal salePrice, BigDecimal price, Integer stockQty,
        String stockStatus, String status, String imageUrl, List<String> categorySlugs,
        @Nullable Instant saleFromAt, @Nullable Instant saleToAt) {
}
