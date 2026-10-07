package com.kiano.commerce;

/**
 * A product category as exposed by the external commerce platform.
 */
public record CommerceCategory(long externalId, Long parentExternalId, String name, String slug) {
}
