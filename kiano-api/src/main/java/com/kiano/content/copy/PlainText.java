package com.kiano.content.copy;

import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jspecify.annotations.Nullable;

/**
 * Plain-text copy specs (title, SEO, Google Shopping, WhatsApp) are written
 * verbatim to Woo or sent as messages. WordPress stores the product name raw
 * for Shop Managers and themes print it unescaped, so these fields must never
 * carry markup. Text without markup is returned unchanged (keeps line breaks,
 * "&" and a lone "<").
 */
public final class PlainText {

    /** A tag, end tag, comment or declaration: "<" followed by a letter, "/", "!" or "?". */
    private static final Pattern MARKUP = Pattern.compile("<[a-zA-Z/!?]");

    private PlainText() {
    }

    public static boolean hasMarkup(String text) {
        return MARKUP.matcher(text).find();
    }

    /** Drops tags (and script/style contents) from text that contains markup. */
    public static @Nullable String strip(@Nullable String text) {
        if (text == null || !hasMarkup(text)) {
            return text;
        }
        return Jsoup.parse(text).text();
    }
}
