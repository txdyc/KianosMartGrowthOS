package com.kiano.content.ads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The four ad hooks: each gets its own overlay copy and three sizes.
 * {@code wire()} is the lowercase id used in asset variants and file names.
 */
public enum AdHook {

    @JsonProperty("pricehook") PRICEHOOK,
    @JsonProperty("problem") PROBLEM,
    @JsonProperty("demo") DEMO,
    @JsonProperty("trust") TRUST;

    /** Lowercase hook id, e.g. {@code pricehook}, used in variants and files. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    @JsonCreator
    public static AdHook fromWire(String value) {
        for (AdHook hook : values()) {
            if (hook.wire().equals(value)) {
                return hook;
            }
        }
        throw new IllegalArgumentException("Unknown ad hook " + value);
    }
}