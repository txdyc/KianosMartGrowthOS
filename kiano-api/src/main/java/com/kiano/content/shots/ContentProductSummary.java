package com.kiano.content.shots;

import com.kiano.content.ContentTier;
import java.math.BigDecimal;

/**
 * One row of the content product list: catalog fields plus shot counts over
 * the required checklist (extra shots like PROMO are not counted).
 */
public record ContentProductSummary(long productId, String sku, String name, String type, String status,
        BigDecimal regularPrice, BigDecimal salePrice, BigDecimal price, String stockStatus, String imageUrl,
        ContentTier tier, int required, int ok, int reshoot, int missing, boolean complete) {
}
