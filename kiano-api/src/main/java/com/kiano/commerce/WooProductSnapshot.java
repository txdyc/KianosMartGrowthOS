package com.kiano.commerce;

import java.util.List;

/**
 * A read-only snapshot of the Woo product content fields we manage: name,
 * descriptions, image order and the Rank Math SEO meta. Used as the before
 * snapshot for publish and rollback.
 */
public record WooProductSnapshot(long id, String sku, String name, String description,
        String shortDescription, List<WooImageRef> images, RankMath rankMath,
        java.time.Instant modifiedAt) {

    /** One gallery image of the product. */
    public record WooImageRef(long id, String src, int position, String alt) {
    }

    /** The two Rank Math keys we write; other meta is left untouched. */
    public record RankMath(String title, String description) {
    }
}