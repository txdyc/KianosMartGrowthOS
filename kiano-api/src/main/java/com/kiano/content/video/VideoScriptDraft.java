package com.kiano.content.video;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Structured LLM output of the video-script call: one entry per video type.
 * The hook line and the 3-4 selling-point captions come from the LLM; price,
 * COD, MoMo and delivery are rendered by the templates from data.
 */
public record VideoScriptDraft(List<VideoScript> videos) {

    public record VideoScript(VideoType type,
            @JsonPropertyDescription("Opening hook line shown in the first 3 seconds, max 40 characters, no price") String hook,
            @JsonPropertyDescription("3 or 4 selling-point captions, each max 42 characters, no price, only locked facts") List<String> captions) {
    }
}
