package com.kiano.content.shots;

import com.kiano.content.ContentTier;
import java.util.List;

/**
 * Full checklist of one product. complete means every required shot is OK.
 */
public record ProductShotStatus(long productId, String sku, String name, ContentTier tier,
        List<ShotStatusLine> lines, boolean complete) {
}
