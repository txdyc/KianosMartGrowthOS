package com.kiano.content.publish;

import com.kiano.commerce.ProductView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One publication as shown in the UI, plus whether the current user may roll
 * it back (latest APPLIED record for this product/environment with role).
 */
public record PublicationView(long id, long productId, String sku, String productName,
        String environment, String status, boolean needsAttention, @Nullable String error,
        List<Long> assetIds, @Nullable Instant publishedAt, @Nullable String publishedBy,
        boolean canRollback) {
}