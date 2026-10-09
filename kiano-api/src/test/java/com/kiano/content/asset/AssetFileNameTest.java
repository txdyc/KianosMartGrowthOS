package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Export file names: the {SKU}_{angle}_{type}_{w}x{h}_v{n}.{ext} format and the
 * rendition suffix of the C5 no-music version.
 */
class AssetFileNameTest {

    @Test
    void formatAndParse_roundTrip() {
        String fileName = AssetFileName.format("MG-KTL17", "demo", "mixed", 1080, 1920, 3, "mp4");

        assertThat(fileName).isEqualTo("MG-KTL17_demo_mixed_1080x1920_v3.mp4");
        AssetFileName.Parsed parsed = AssetFileName.parse(fileName);
        assertThat(parsed.sku()).isEqualTo("MG-KTL17");
        assertThat(parsed.angle()).isEqualTo("demo");
        assertThat(parsed.type()).isEqualTo("mixed");
        assertThat(parsed.width()).isEqualTo(1080);
        assertThat(parsed.height()).isEqualTo(1920);
        assertThat(parsed.version()).isEqualTo(3);
        assertThat(parsed.ext()).isEqualTo("mp4");
    }

    @Test
    void rendition_appendsSuffixBeforeExtension() {
        assertThat(AssetFileName.rendition("MG-KTL17_demo_real_1080x1920_v1.mp4", "nomusic"))
                .isEqualTo("MG-KTL17_demo_real_1080x1920_v1_nomusic.mp4");
        assertThat(AssetFileName.rendition("no-extension", "nomusic"))
                .isEqualTo("no-extension_nomusic");
    }
}