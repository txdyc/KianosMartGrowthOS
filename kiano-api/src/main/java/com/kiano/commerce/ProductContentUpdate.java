package com.kiano.commerce;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The product content fields a publish writes. Images are uploaded first and
 * referenced by id in the desired gallery order. seoTitle/seoDescription may
 * be null when no COPY_SEO asset was approved - meta_data is then omitted.
 */
public record ProductContentUpdate(String name, String description, String shortDescription,
        List<Long> imageIdsInOrder, @Nullable String seoTitle, @Nullable String seoDescription) {

    /**
     * Writes a snapshot back exactly (failure restore and rollback). A Rank
     * Math value that was absent before is sent as "" so the meta written by
     * the publish is cleared; null would omit meta_data and leave it live.
     */
    public static ProductContentUpdate restoring(WooProductSnapshot before) {
        WooProductSnapshot.RankMath rankMath = before.rankMath();
        String title = rankMath == null || rankMath.title() == null ? "" : rankMath.title();
        String description = rankMath == null || rankMath.description() == null ? ""
                : rankMath.description();
        return new ProductContentUpdate(before.name(), before.description(),
                before.shortDescription(),
                before.images().stream().map(WooProductSnapshot.WooImageRef::id).toList(),
                title, description);
    }
}