package com.kiano.content.shots;

import com.kiano.content.media.MediaKind;

/**
 * One required shot of the checklist, ordered by code.
 */
public record ShotRequirementView(String code, MediaKind kind, boolean required, String guidanceEn,
        String guidanceZh) {
}
