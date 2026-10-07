package com.kiano.content.shots;

import com.kiano.content.ContentTier;
import com.kiano.content.media.MediaKind;
import com.kiano.content.text.KeywordMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds the per-product shot checklist from the shot_requirement rules:
 * STANDARD rows apply to both tiers, HERO rows only to HERO, and a
 * category-specific row overrides the generic guidance of the same code.
 *
 * <p>A row's {@code category} is a keyword such as {@code air-fryer}. It
 * matches a product category slug first; only when no slug matches does it
 * fall back to the product name, because most KianosMart products sit in
 * coarse categories (kitchen-appliances, uncategorized). Matching is on whole
 * words after normalising case and separators, with an optional plural
 * suffix, so "6L Air Fryer" and "air-fryers" match {@code air-fryer} but
 * "Fantastic Speaker" does not match {@code fan}. Smallest sort_order wins.
 */
@Service
public class ShotRequirementService {

    private final JdbcTemplate jdbcTemplate;

    public ShotRequirementService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ShotRequirementView> requiredFor(ContentTier tier, List<String> categorySlugs,
            @Nullable String productName) {
        List<RequirementRow> rows = jdbcTemplate.query(
                "select code, kind, tier, category, required, guidance_en, guidance_zh, sort_order "
                        + "from shot_requirement",
                (rs, rowNum) -> new RequirementRow(rs.getString("code"),
                        MediaKind.valueOf(rs.getString("kind")), rs.getString("tier"),
                        rs.getString("category"), rs.getBoolean("required"),
                        rs.getString("guidance_en"), rs.getString("guidance_zh"),
                        rs.getInt("sort_order")));

        List<String> normalisedSlugs = KeywordMatcher.normalisedSlugs(categorySlugs);
        String normalisedName = KeywordMatcher.normaliseName(productName);

        Map<String, RequirementRow> genericByCode = new LinkedHashMap<>();
        Map<String, RequirementRow> categoryOverrideByCode = new HashMap<>();
        Map<String, RequirementRow> nameOverrideByCode = new HashMap<>();
        for (RequirementRow row : rows) {
            if (!appliesTo(row.tier(), tier)) {
                continue;
            }
            if (row.category() == null) {
                genericByCode.put(row.code(), row);
                continue;
            }
            KeywordMatcher.Match match = KeywordMatcher.matches(row.category(),
                    normalisedSlugs, normalisedName);
            if (match == KeywordMatcher.Match.CATEGORY) {
                categoryOverrideByCode.merge(row.code(), row, ShotRequirementService::lowerSortOrder);
            } else if (match == KeywordMatcher.Match.NAME) {
                nameOverrideByCode.merge(row.code(), row, ShotRequirementService::lowerSortOrder);
            }
        }

        List<ShotRequirementView> views = new ArrayList<>();
        genericByCode.values().stream()
                .sorted(Comparator.comparingInt(RequirementRow::sortOrder))
                .forEach(row -> {
                    RequirementRow source = categoryOverrideByCode.getOrDefault(row.code(),
                            nameOverrideByCode.getOrDefault(row.code(), row));
                    views.add(new ShotRequirementView(row.code(), row.kind(), row.required(),
                            source.guidanceEn(), source.guidanceZh()));
                });
        return views;
    }

    private static boolean appliesTo(String rowTier, ContentTier tier) {
        return "STANDARD".equals(rowTier) || tier == ContentTier.HERO;
    }

    private static RequirementRow lowerSortOrder(RequirementRow a, RequirementRow b) {
        return a.sortOrder() <= b.sortOrder() ? a : b;
    }

    /**
     * One shot_requirement row.
     */
    private record RequirementRow(String code, MediaKind kind, String tier, String category,
            boolean required, String guidanceEn, String guidanceZh, int sortOrder) {
    }
}
