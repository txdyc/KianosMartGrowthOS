package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Round-trip and Appendix-B file name cases. */
class AssetFileNameTest {

    @Test
    void format_pageMain() {
        assertThat(AssetFileName.format("MG-BL200", "page-main", "real", 1600, 1600, 1, "jpg"))
                .isEqualTo("MG-BL200_page-main_real_1600x1600_v1.jpg");
    }

    @Test
    void roundTrip_skuWithUnderscore() {
        String name = AssetFileName.format("MG_FAN_16", "page-scene2", "mixed", 1600, 1600, 3, "jpg");
        assertThat(name).isEqualTo("MG_FAN_16_page-scene2_mixed_1600x1600_v3.jpg");

        AssetFileName.Parsed parsed = AssetFileName.parse(name);
        assertThat(parsed.sku()).isEqualTo("MG_FAN_16");
        assertThat(parsed.angle()).isEqualTo("page-scene2");
        assertThat(parsed.type()).isEqualTo("mixed");
        assertThat(parsed.width()).isEqualTo(1600);
        assertThat(parsed.height()).isEqualTo(1600);
        assertThat(parsed.version()).isEqualTo(3);
        assertThat(parsed.ext()).isEqualTo("jpg");
    }

    @Test
    void parse_adName_fromAppendixB() {
        AssetFileName.Parsed parsed = AssetFileName.parse("MG-BL200_pricehook_real_1080x1350_v1.jpg");
        assertThat(parsed.sku()).isEqualTo("MG-BL200");
        assertThat(parsed.angle()).isEqualTo("pricehook");
        assertThat(parsed.type()).isEqualTo("real");
        assertThat(parsed.width()).isEqualTo(1080);
        assertThat(parsed.height()).isEqualTo(1350);
        assertThat(parsed.version()).isEqualTo(1);
        assertThat(parsed.ext()).isEqualTo("jpg");
    }
}
