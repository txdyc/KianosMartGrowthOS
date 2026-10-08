package com.kiano.commerce;

/**
 * Which WooCommerce site a publish targets. STAGING maps to provider
 * WOOCOMMERCE_STAGING (local Docker in development), PRODUCTION to the
 * existing WOOCOMMERCE provider (sync source + publish target).
 */
public enum PublishEnvironment {
    STAGING,
    PRODUCTION
}