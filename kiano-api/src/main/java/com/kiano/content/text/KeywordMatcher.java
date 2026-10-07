package com.kiano.content.text;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared keyword matching: normalise case and separators, match whole words
 * with an optional s/es plural suffix, category slugs before the product
 * name. Extracted from ShotRequirementService with unchanged behaviour.
 */
public final class KeywordMatcher {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private KeywordMatcher() {
    }

    /** How a keyword matched: category slug (preferred), product name, or not at all. */
    public enum Match {
        CATEGORY,
        NAME,
        NONE
    }

    public static Match matches(String keyword, List<String> categorySlugs, String productName) {
        Pattern pattern = keywordPattern(keyword);
        if (categorySlugs != null) {
            for (String slug : categorySlugs) {
                if (slug != null && pattern.matcher(normalise(slug)).find()) {
                    return Match.CATEGORY;
                }
            }
        }
        if (productName != null && pattern.matcher(normalise(productName)).find()) {
            return Match.NAME;
        }
        return Match.NONE;
    }

    /** Lower-case and collapse every run of non-alphanumerics to a single '-'. */
    public static String normalise(String text) {
        String dashed = NON_ALNUM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("-");
        return dashed.replaceAll("^-+|-+$", "");
    }

    /** Whole-word keyword (words joined by '-') with an optional plural suffix. */
    public static Pattern keywordPattern(String keyword) {
        return Pattern.compile("(^|-)" + Pattern.quote(normalise(keyword)) + "(s|es)?(-|$)");
    }

    /** Null-safe normalisation for name inputs. */
    public static String normaliseName(String text) {
        return text == null ? "" : normalise(text);
    }

    /** Convenience for slug lists. */
    public static List<String> normalisedSlugs(List<String> slugs) {
        return slugs == null ? List.of() : slugs.stream().filter(Objects::nonNull)
                .map(KeywordMatcher::normalise).toList();
    }
}
