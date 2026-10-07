package com.kiano.commerce;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the synchronized product catalog (Woo data as stored
 * locally by the product sync).
 */
public interface ProductCatalog {

    Optional<ProductView> findById(long tenantId, long productId);

    /** Trimmed, case-insensitive; excludes status='missing'. */
    Optional<ProductView> findBySku(long tenantId, String sku);

    /** parent_id is null and status <> 'missing', ordered by sku. */
    List<ProductView> listTopLevel(long tenantId);
}
