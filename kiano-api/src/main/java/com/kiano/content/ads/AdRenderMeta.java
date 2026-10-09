package com.kiano.content.ads;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Ad-static specifics for {@code AssetService.createImageFromBytes}: only the
 * pricehook variants depend on the price (the snapshot is what changed), and
 * the file-name type segment is real for PAGE_MAIN / V1 frames and mixed for
 * PAGE_SCENE bases.
 */
public record AdRenderMeta(boolean dependsOnPrice, @Nullable BigDecimal priceSnapshot,
        String fileType) {
}
