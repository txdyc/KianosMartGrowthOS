package com.kiano.commerce;

/**
 * A successfully uploaded media item on Woo (wp/v2/media).
 */
public record WooMedia(long id, String src) {
}