package com.kiano.content.publish;

import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService.ReviewItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The required-content rule of a publish (C6 Task 3), shared by the publish
 * preconditions and the batch stage resolver so both always agree on what a
 * SKU is still missing.
 */
public final class PublishRules {

    /** Specs every publishable SKU needs at least once. */
    public static final Set<String> REQUIRED_SPECS =
            Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG");

    /** Distinct PAGE_ANGLE assets a publishable SKU needs. */
    public static final int MIN_ANGLES = 3;

    /** Asset statuses that count as publishable content. */
    public static final Set<String> LIVE_STATUSES = Set.of(AssetStatus.APPROVED.name(),
            AssetStatus.PUBLISHED.name());

    private PublishRules() {
    }

    /** Newest live version per spec/variant (APPROVED or PUBLISHED, else dropped). */
    public static List<ReviewItem> liveContent(List<ReviewItem> all) {
        Map<String, ReviewItem> latest = new LinkedHashMap<>();
        for (ReviewItem item : all) {
            if (!LIVE_STATUSES.contains(item.status())) {
                continue;
            }
            latest.merge(item.specCode() + "/" + item.variant(), item,
                    (a, b) -> a.version() >= b.version() ? a : b);
        }
        return new ArrayList<>(latest.values());
    }

    /** The required specs a live content set is still missing. */
    public static List<String> missingRequired(List<ReviewItem> liveContent) {
        Set<String> specs = new java.util.LinkedHashSet<>();
        long angles = 0;
        for (ReviewItem item : liveContent) {
            specs.add(item.specCode());
            if ("PAGE_ANGLE".equals(item.specCode())) {
                angles++;
            }
        }
        return missingRequired(specs, angles);
    }

    /**
     * The same rule over bare spec codes (the batch resolver reads assets in
     * one query and never builds review items).
     */
    public static List<String> missingRequired(Set<String> liveSpecCodes, long liveAngleCount) {
        List<String> missing = new ArrayList<>();
        for (String spec : REQUIRED_SPECS) {
            if (!liveSpecCodes.contains(spec)) {
                missing.add(spec);
            }
        }
        if (liveAngleCount < MIN_ANGLES) {
            missing.add("PAGE_ANGLE x" + MIN_ANGLES + " (have " + liveAngleCount + ")");
        }
        return missing;
    }
}