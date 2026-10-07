package com.kiano.content.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ShotFileName.parse: SKU_shotCode.ext convention with case-insensitive
 * shot codes and extensions, and actionable errors for HEIC files.
 */
class ShotFileNameTest {

    @ParameterizedTest
    @CsvSource({
        "MG-BL200_P5.jpg, MG-BL200, P5, PHOTO",
        "mg-bl200_p1.JPG, mg-bl200, P1, PHOTO",
        "MG-BL200_P1.jpeg, MG-BL200, P1, PHOTO",
        "MG_FAN_16_V1.mp4, MG_FAN_16, V1, VIDEO",
        "MG-BL200_v2.MOV, MG-BL200, V2, VIDEO",
        "MG-BL200_PROMO.jpg, MG-BL200, PROMO, PROMO_IMAGE",
        "photos/HERO/MG-BL200_P3.jpg, MG-BL200, P3, PHOTO"
    })
    void parses(String input, String sku, String shotCode, MediaKind kind) {
        ParsedShotFile parsed = ShotFileName.parse(input);

        assertThat(parsed.sku()).isEqualTo(sku);
        assertThat(parsed.shotCode()).isEqualTo(shotCode);
        assertThat(parsed.kind()).isEqualTo(kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MG-BL200.jpg", "MG-BL200_P10.jpg", "MG-BL200_X1.jpg", "_P1.jpg",
        "._MG-BL200_P1.jpg", ".DS_Store", "MG-BL200_P1 (2).jpg"
    })
    void rejectsInvalidName(String name) {
        assertThatThrownBy(() -> ShotFileName.parse(name))
                .isInstanceOfSatisfying(InvalidShotFileNameException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_FILE_NAME"));
    }

    @Test
    void rejectsVideoExtensionForPhotoShot() {
        assertThatThrownBy(() -> ShotFileName.parse("MG-BL200_P1.mp4"))
                .isInstanceOfSatisfying(InvalidShotFileNameException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE");
                    assertThat(e.getExtension()).isEqualTo("mp4");
                });
    }

    @Test
    void heic_hasActionableMessage() {
        assertThatThrownBy(() -> ShotFileName.parse("MG-BL200_P1.heic"))
                .isInstanceOfSatisfying(InvalidShotFileNameException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE");
                    assertThat(e.getExtension()).isEqualTo("heic");
                    assertThat(e.getMessage()).isEqualTo(
                            "HEIC is not supported. Export as JPEG (iPhone: Settings › Camera › Formats › Most Compatible).");
                });
    }
}
