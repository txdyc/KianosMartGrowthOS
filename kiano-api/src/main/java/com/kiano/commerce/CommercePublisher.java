package com.kiano.commerce;

import java.util.Optional;

/**
 * Write port for publishing product content to a WooCommerce site: product
 * lookup by SKU, media upload/deletion and content updates. Implemented by
 * {@code WooPublisherAdapter}; tests inject a fake.
 */
public interface CommercePublisher {

    Optional<WooProductSnapshot> findBySku(String sku);

    WooProductSnapshot get(long productId);

    WooMedia uploadMedia(String fileName, byte[] bytes, String contentType, String altText);

    /** Rewrites name/descriptions/images (in order) and Rank Math meta. */
    WooProductSnapshot updateContent(long productId, ProductContentUpdate update);

    void deleteMedia(long mediaId);
}