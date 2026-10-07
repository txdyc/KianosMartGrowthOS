package com.kiano.content.shots;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ContentTier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shot requirement rules: STANDARD rows apply to both tiers, HERO rows only
 * to HERO, and category-specific guidance overrides the generic rows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShotRequirementServiceTest {

    @Autowired
    private ShotRequirementService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void standard_isP1toP8() {
        List<String> codes = service.requiredFor(ContentTier.STANDARD, List.of()).stream()
                .map(ShotRequirementView::code).toList();

        assertThat(codes).containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8");
    }

    @Test
    void hero_isP1toP9_V1toV3() {
        List<String> codes = service.requiredFor(ContentTier.HERO, List.of()).stream()
                .map(ShotRequirementView::code).toList();

        assertThat(codes).containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9",
                "V1", "V2", "V3");
    }

    @Test
    void categoryOverride_kettle() {
        List<ShotRequirementView> views = service.requiredFor(ContentTier.HERO,
                List.of("electric-kettles"));

        ShotRequirementView v1 = views.stream().filter(v -> v.code().equals("V1")).findFirst()
                .orElseThrow();
        assertThat(v1.guidanceEn()).startsWith("Fill with water");
        assertThat(v1.guidanceZh()).isEqualTo("注水 → 烧开 → 自动断电");
    }

    @Test
    void noCategoryMatch_usesGeneric() {
        List<ShotRequirementView> views = service.requiredFor(ContentTier.HERO, List.of("laptops"));

        ShotRequirementView v1 = views.stream().filter(v -> v.code().equals("V1")).findFirst()
                .orElseThrow();
        assertThat(v1.guidanceEn()).isEqualTo("Operation demo, 10–20 s, keep original sound");
        assertThat(v1.guidanceZh()).isEqualTo("操作演示 10–20 秒，保留原声");
    }

    @Test
    void everyRow_hasBothLanguages() {
        List<String> empty = jdbcTemplate.query(
                "select code || '/' || coalesce(category, '-') from shot_requirement "
                        + "where guidance_en is null or guidance_zh is null "
                        + "or length(trim(guidance_en)) = 0 or length(trim(guidance_zh)) = 0",
                (rs, rowNum) -> rs.getString(1));

        assertThat(empty).isEmpty();
    }
}
