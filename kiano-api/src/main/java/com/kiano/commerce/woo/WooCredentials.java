package com.kiano.commerce.woo;

/**
 * Connection details for one tenant's WooCommerce site.
 */
public record WooCredentials(String baseUrl, String username, String applicationPassword) {
}
