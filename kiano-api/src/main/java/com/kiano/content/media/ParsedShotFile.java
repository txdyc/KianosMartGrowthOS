package com.kiano.content.media;

/**
 * A shot file name split into its parts. sku keeps the original case as
 * typed by the photographer; shotCode is upper-cased; extension is
 * lower-cased without the leading dot.
 */
public record ParsedShotFile(String sku, String shotCode, MediaKind kind, String extension) {
}
