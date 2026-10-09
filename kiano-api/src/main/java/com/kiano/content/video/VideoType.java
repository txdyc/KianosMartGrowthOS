package com.kiano.content.video;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/**
 * The three short-video formats produced per HERO SKU. {@code wire()} is the
 * lowercase id used as the VIDEO_SCRIPT asset variant and the type segment in
 * file names.
 */
public enum VideoType {

    @JsonProperty("demo") DEMO,
    @JsonProperty("problem") PROBLEM,
    @JsonProperty("unboxing") UNBOXING;

    /** Lowercase type id, e.g. {@code demo}, used in variants and files. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static VideoType fromWire(String value) {
        for (VideoType type : values()) {
            if (type.wire().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown video type " + value);
    }
}
