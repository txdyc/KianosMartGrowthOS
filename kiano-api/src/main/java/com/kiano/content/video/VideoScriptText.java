package com.kiano.content.video;

import com.kiano.content.copy.PlainText;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * One video type's script as stored in a VIDEO_SCRIPT asset's textBody: a JSON
 * object with hook and captions, each stripped of HTML by {@link PlainText#strip}.
 * The contentJson carries the type and factVersion.
 */
public record VideoScriptText(String hook, List<String> captions) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static VideoScriptText of(VideoScriptDraft.VideoScript script) {
        List<String> captions = script.captions() == null ? List.of()
                : script.captions().stream().map(PlainText::strip).toList();
        return new VideoScriptText(PlainText.strip(script.hook()), captions);
    }

    public static VideoScriptText fromJson(String json) {
        try {
            return MAPPER.readValue(json, VideoScriptText.class);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid video script JSON", ex);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "hook", hook == null ? "" : hook,
                    "captions", captions == null ? List.of() : captions));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Cannot serialize video script", ex);
        }
    }
}
