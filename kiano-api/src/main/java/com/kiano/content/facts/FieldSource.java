package com.kiano.content.facts;

/**
 * Where a fact field value came from (spec §8 field_sources). P5 = reading
 * plate/back photo, PROMO = marketing image, WOO_TEXT = existing Woo
 * description, MANUAL = edited by a human, NONE = not read.
 */
public enum FieldSource {
    P5,
    PROMO,
    WOO_TEXT,
    MANUAL,
    NONE
}