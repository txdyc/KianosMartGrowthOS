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
        List<String> codes = service.requiredFor(ContentTier.STANDARD, List.of(), null).stream()
                .map(ShotRequirementView::code).toList();

        assertThat(codes).containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8");
    }

    @Test
    void hero_isP1toP9_V1toV3() {
        List<String> codes = service.requiredFor(ContentTier.HERO, List.of(), null).stream()
                .map(ShotRequirementView::code).toList();

        assertThat(codes).containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9",
                "V1", "V2", "V3");
    }

    private static final String GENERIC_V1_ZH = "操作演示 10–20 秒，保留原声";

    private String v1Zh(List<String> categorySlugs, String productName) {
        return service.requiredFor(ContentTier.HERO, categorySlugs, productName).stream()
                .filter(v -> v.code().equals("V1")).findFirst().orElseThrow().guidanceZh();
    }

    @Test
    void categoryOverride_kettle() {
        List<ShotRequirementView> views = service.requiredFor(ContentTier.HERO,
                List.of("electric-kettles"), "Electric 1.7L");

        ShotRequirementView v1 = views.stream().filter(v -> v.code().equals("V1")).findFirst()
                .orElseThrow();
        assertThat(v1.guidanceEn()).startsWith("Fill with water");
        assertThat(v1.guidanceZh()).isEqualTo("注水 → 烧开 → 自动断电");
    }

    @Test
    void noCategoryOrNameMatch_usesGeneric() {
        List<ShotRequirementView> views = service.requiredFor(ContentTier.HERO, List.of("laptops"),
                "Gaming Laptop 15 inch");

        ShotRequirementView v1 = views.stream().filter(v -> v.code().equals("V1")).findFirst()
                .orElseThrow();
        assertThat(v1.guidanceEn()).isEqualTo("Operation demo, 10–20 s, keep original sound");
        assertThat(v1.guidanceZh()).isEqualTo(GENERIC_V1_ZH);
    }

    // Real KianosMart data: most products sit in coarse categories such as
    // kitchen-appliances or uncategorized, so the product name is the fallback.
    @Test
    void nameKeyword_matchesWhenCategoryIsCoarse() {
        assertThat(v1Zh(List.of("kitchen-appliances"), "2in1 2.5L Blender"))
                .isEqualTo("辣椒、番茄、洋葱 → 打成酱");
        assertThat(v1Zh(List.of("uncategorized"), "8-in lithium battery fan metal model"))
                .isEqualTo("开机、调档、摇头");
        assertThat(v1Zh(List.of(), "Steam Iron 2200W")).isEqualTo("熨平一件衬衫");
    }

    @Test
    void nameKeyword_ignoresSpacingHyphensAndCase() {
        assertThat(v1Zh(List.of("uncategorized"), "6L Air Fryer")).isEqualTo("薯条或鸡翅，少油");
        assertThat(v1Zh(List.of("uncategorized"), "8L-Air-Fryer")).isEqualTo("薯条或鸡翅，少油");
        assertThat(v1Zh(List.of("uncategorized"), "ELECTRIC KETTLES 2L")).isEqualTo("注水 → 烧开 → 自动断电");
    }

    @Test
    void nameKeyword_matchesWholeWordsOnly() {
        assertThat(v1Zh(List.of(), "Fantastic Bluetooth Speaker")).isEqualTo(GENERIC_V1_ZH);
        assertThat(v1Zh(List.of(), "Environment Sensor")).isEqualTo(GENERIC_V1_ZH);
        assertThat(v1Zh(List.of(), null)).isEqualTo(GENERIC_V1_ZH);
    }

    // "cooker" alone is too broad: KianosMart also sells infrared/induction
    // cooktops and egg cookers, which must not get the rice demo script.
    @Test
    void cookerKeyword_onlyRiceAndPressureCookersGetRiceScript() {
        String rice = "放米 → 启动 → 煮好的米饭";
        assertThat(v1Zh(List.of(), "5L Rice cooker purple color")).isEqualTo(rice);
        assertThat(v1Zh(List.of("pressure-cookers"), "Micro Pressure Cooker")).isEqualTo(rice);
        assertThat(v1Zh(List.of("uncategorized"), "5L Pressure Cooker")).isEqualTo(rice);
        assertThat(v1Zh(List.of("kitchen-appliances"), "Electric Induction Cooker")).isEqualTo(GENERIC_V1_ZH);
        assertThat(v1Zh(List.of("kitchen-appliances"), "Ceramic Infrared Cooker-Double")).isEqualTo(GENERIC_V1_ZH);
        assertThat(v1Zh(List.of(), "Electric Egg Cooker")).isEqualTo(GENERIC_V1_ZH);
    }

    @Test
    void categoryMatch_winsOverNameMatch() {
        // blender (sort 101) would beat air-fryer (sort 107) if both counted equally.
        assertThat(v1Zh(List.of("air-fryers"), "Blender & Air Fryer Combo"))
                .isEqualTo("薯条或鸡翅，少油");
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
