package com.kiano.content.publish;

import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService.ReviewItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders approved gallery assets for publishing (spec §7.1): MAIN → ANGLE →
 * SCENE → INBOX → INFO → SPEC with caps 1/4/2/1/1/1; the newest version per
 * (spec, variant) wins; ANGLE orders by variant P2→P3→P4→P6 and SCENE by its
 * numeric variant.
 */
public final class GallerySelector {

    /** spec → group order, type and per-group cap, in publish order. */
    private static final List<GalleryRule> RULES = List.of(
            new GalleryRule(0, "PAGE_MAIN", "MAIN", 1),
            new GalleryRule(1, "PAGE_ANGLE", "ANGLE", 4),
            new GalleryRule(2, "PAGE_SCENE", "SCENE", 2),
            new GalleryRule(3, "PAGE_INBOX", "INBOX", 1),
            new GalleryRule(4, "PAGE_INFO", "INFO", 1),
            new GalleryRule(5, "PAGE_SPEC", "SPEC", 1));

    private GallerySelector() {
    }

    /** Picks the publishable gallery from approved assets, ordered and capped. */
    public static List<ReviewItem> select(List<ReviewItem> approved) {
        List<ReviewItem> gallery = new ArrayList<>();
        for (GalleryRule rule : RULES) {
            Map<String, ReviewItem> latestPerVariant = new LinkedHashMap<>();
            for (ReviewItem item : approved) {
                if (rule.spec().equals(item.specCode())) {
                    latestPerVariant.merge(item.variant(), item, (older, newer) ->
                            compareVersions(older, newer) >= 0 ? older : newer);
                }
            }
            latestPerVariant.values().stream()
                    .sorted(variantOrder())
                    .limit(rule.cap())
                    .forEach(gallery::add);
        }
        return gallery;
    }

    private static int compareVersions(ReviewItem a, ReviewItem b) {
        return Integer.compare(a.version(), b.version());
    }

    /** ANGLE by variant P2/P3/P4/P6; SCENE by the numeric part of the variant. */
    private static Comparator<ReviewItem> variantOrder() {
        return (a, b) -> {
            if ("PAGE_ANGLE".equals(a.specCode())) {
                return Integer.compare(angleRank(a.variant()), angleRank(b.variant()));
            }
            return Integer.compare(sceneRank(a.variant()), sceneRank(b.variant()));
        };
    }

    private static int angleRank(String variant) {
        return switch (variant) {
            case "P2" -> 0;
            case "P3" -> 1;
            case "P4" -> 2;
            case "P6" -> 3;
            default -> 9;
        };
    }

    private static int sceneRank(String variant) {
        try {
            return Integer.parseInt(variant.replaceAll("\\D", ""));
        } catch (NumberFormatException ex) {
            return 9;
        }
    }

    private record GalleryRule(int order, String spec, String type, int cap) {
    }
}