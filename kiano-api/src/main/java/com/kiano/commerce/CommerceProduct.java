package com.kiano.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A product (or variation) as exposed by the external commerce platform.
 * externalId/parentExternalId refer to platform-side ids.
 */
public record CommerceProduct(long externalId, Long parentExternalId, String type, String sku,
        String brand, String name, String slug, BigDecimal regularPrice, BigDecimal salePrice,
        BigDecimal price, Integer stockQty, String stockStatus, String status, String permalink,
        String imageUrl, Instant modifiedAt, List<Long> categoryExternalIds) {
}
