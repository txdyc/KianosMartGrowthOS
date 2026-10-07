package com.kiano.content.shots;

import com.kiano.content.ContentTier;
import com.kiano.content.media.MediaKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds the per-product shot checklist from the shot_requirement rules:
 * STANDARD rows apply to both tiers, HERO rows only to HERO, and a
 * category-specific row overrides the generic guidance of the same code
 * when one of the product's category slugs contains the row's category
 * (case-insensitive; smallest sort_order wins).
 */
@Service
public class ShotRequirementService {

    private final JdbcTemplate jdbcTemplate;

    public ShotRequirementService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ShotRequirementView> requiredFor(ContentTier tier, List<String> categorySlugs) {
        List<RequirementRow> rows = jdbcTemplate.query(
                "select code, kind, tier, category, required, guidance_en, guidance_zh, sort_order "
                        + "from shot_requirement",
                (rs, rowNum) -> new RequirementRow(rs.getString("code"),
                        MediaKind.valueOf(rs.getString("kind")), rs.getString("tier"),
                        rs.getString("category"), rs.getBoolean("required"),
                        rs.getString("guidance_en"), rs.getString("guidance_zh"),
                        rs.getInt("sort_order")));

        Map<String, RequirementRow> genericByCode = new LinkedHashMap<>();
        Map<String, RequirementRow> overrideByCode = new HashMap<>();
        for (RequirementRow row : rows) {
            if (!appliesTo(row.tier(), tier)) {
                continue;
            }
            if (row.category() == null) {
                genericByCode.put(row.code(), row);
            } else if (matchesAnySlug(row.category(), categorySlugs)) {
                overrideByCode.merge(row.code(), row,
                        (a, b) -> a.sortOrder() <= b.sortOrder() ? a : b);
            }
        }

        List<ShotRequirementView> views = new ArrayList<>();
        genericByCode.values().stream()
                .sorted(Comparator.comparingInt(RequirementRow::sortOrder))
                .forEach(row -> {
                    RequirementRow source = overrideByCode.getOrDefault(row.code(), row);
                    views.add(new ShotRequirementView(row.code(), row.kind(), row.required(),
                            source.guidanceEn(), source.guidanceZh()));
                });
        return views;
    }

    private static boolean appliesTo(String rowTier, ContentTier tier) {
        return "STANDARD".equals(rowTier) || tier == ContentTier.HERO;
    }

    private static boolean matchesAnySlug(String category, List<String> categorySlugs) {
        if (categorySlugs == null) {
            return false;
        }
        for (String slug : categorySlugs) {
            if (slug != null
                    && slug.toLowerCase(Locale.ROOT).contains(category.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * One shot_requirement row.
     */
    private record RequirementRow(String code, MediaKind kind, String tier, String category,
            boolean required, String guidanceEn, String guidanceZh, int sortOrder) {
    }
}
