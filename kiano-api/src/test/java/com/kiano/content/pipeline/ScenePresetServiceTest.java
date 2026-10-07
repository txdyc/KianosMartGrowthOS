package com.kiano.content.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Room detection via scene_room_rule + KeywordMatcher, preset cycling. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ScenePresetServiceTest {

    private static final String SUFFIX =
            ", Ghanaian home interior, photorealistic product photography, natural light, high detail";
    private static final String NEGATIVE =
            "text, letters, watermark, logo, brand name, US plug, European plug, people, hands, "
                    + "deformed, blurry";

    @Autowired
    private ScenePresetService presets;

    @Test
    void blenderByName_kitchenPresets() {
        // Coarse category, the name carries the keyword.
        List<ScenePresetService.ScenePrompt> out = presets.presetsFor(
                List.of("uncategorized"), "2in1 2.5L Blender", 2);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).presetCode()).isEqualTo("KITCHEN-1");
        assertThat(out.get(0).positive()).isEqualTo(
                "on a clean tiled kitchen counter in a bright Accra apartment, morning light" + SUFFIX);
        assertThat(out.get(1).presetCode()).isEqualTo("KITCHEN-2");
        assertThat(out.get(0).negative()).isEqualTo(NEGATIVE);
        assertThat(out.get(1).negative()).isEqualTo(NEGATIVE);
    }

    @Test
    void unknown_generic() {
        List<ScenePresetService.ScenePrompt> out = presets.presetsFor(
                List.of("laptops"), "Gaming Laptop 15 inch", 2);
        assertThat(out).extracting(ScenePresetService.ScenePrompt::presetCode)
                .containsExactly("GENERIC-1", "GENERIC-2");
    }

    @Test
    void count4_cyclesWhenFewer() {
        // KITCHEN has 4 presets; asking for 6 cycles back to the first.
        List<ScenePresetService.ScenePrompt> out = presets.presetsFor(
                List.of("electric-kettles"), "Kettle 1.7L", 6);
        assertThat(out).extracting(ScenePresetService.ScenePrompt::presetCode)
                .containsExactly("KITCHEN-1", "KITCHEN-2", "KITCHEN-3", "KITCHEN-4",
                        "KITCHEN-1", "KITCHEN-2");
    }
}
