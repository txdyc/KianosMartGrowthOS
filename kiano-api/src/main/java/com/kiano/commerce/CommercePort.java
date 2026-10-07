package com.kiano.commerce;

import java.util.List;

/**
 * Read-only view of an external commerce platform (WooCommerce in C1).
 * Implementations are per tenant and created by the integration factory
 * with the tenant's stored credentials.
 */
public interface CommercePort {

    /** All product categories. */
    List<CommerceCategory> listCategories();

    /** All products (every page, status=any). */
    List<CommerceProduct> listProducts();

    /** Variations of a variable product (parent.type() == "variable"). */
    List<CommerceProduct> listVariations(CommerceProduct parent);

    /** Cheap connectivity/auth probe: GET products?per_page=1. */
    void ping();
}
