package com.kiano.content.policy;

/**
 * The five policy sections rendered on the Woo product page and in
 * COPY_LONG. Title and body are English plain text (body line breaks become
 * paragraphs); all text is HTML-escaped before rendering.
 */
public enum PolicySection {
    DELIVERY,
    COD,
    MOMO,
    WARRANTY,
    RETURNS
}