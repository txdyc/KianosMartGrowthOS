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
}