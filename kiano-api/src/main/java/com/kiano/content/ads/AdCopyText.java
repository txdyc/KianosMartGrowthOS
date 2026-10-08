package com.kiano.content.ads;

import com.kiano.content.copy.PlainText;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * One hook's copy as stored in an AD_COPY asset's textBody: a JSON object with
 * overlay / headline / primaryText, each stripped of HTML by
 * {@link PlainText#strip}. The contentJson carries the hook and factVersion.
 */
public record AdCopyText(String overlay, String headline, String primaryText) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AdCopyText of(AdCopyDraft.HookCopy copy) {
        return new AdCopyText(PlainText.strip(copy.overlay()),
                PlainText.strip(copy.headline()), PlainText.strip(copy.primaryText()));
    }

    public static AdCopyText fromJson(String json) {
        try {
            return MAPPER.readValue(json, AdCopyText.class);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid ad copy JSON", ex);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "overlay", overlay == null ? "" : overlay,
                    "headline", headline == null ? "" : headline,
                    "primaryText", primaryText == null ? "" : primaryText));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Cannot serialize ad copy", ex);
        }
    }
}