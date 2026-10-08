package com.kiano.content.ads;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Structured LLM output of the ad-copy call: one entry per hook. Only the
 * short overlay line, headline and primary text come from the LLM; price,
 * COD, MoMo, delivery and warranty are rendered by templates from data.
 */
public record AdCopyDraft(List<HookCopy> hooks) {

    public record HookCopy(AdHook hook,
            @JsonPropertyDescription("Short line printed on the ad image, max 40 characters, no price") String overlay,
            @JsonPropertyDescription("Ad headline, max 40 characters, no price") String headline,
            @JsonPropertyDescription("Ad primary text, max 125 characters, no price") String primaryText) {
    }
}