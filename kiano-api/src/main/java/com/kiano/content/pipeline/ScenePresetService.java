package com.kiano.content.pipeline;

import com.kiano.content.text.KeywordMatcher;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Scene presets: the room is derived from scene_room_rule keywords (category
 * slugs first, then the product name, GENERIC as fallback); presets are
 * returned in sort_order and cycle when fewer exist than requested.
 */
@Service
public class ScenePresetService {

    private final JdbcTemplate jdbcTemplate;
    private final PipelineProperties properties;

    public ScenePresetService(JdbcTemplate jdbcTemplate, PipelineProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<ScenePrompt> presetsFor(List<String> categorySlugs, @Nullable String productName,
            int count) {
        String room = roomFor(categorySlugs, productName);
        List<PresetRow> presets = jdbcTemplate.query(
                "select code, prompt_en from scene_preset where room = ? order by sort_order",
                (rs, rowNum) -> new PresetRow(rs.getString("code"), rs.getString("prompt_en")),
                room);
        List<ScenePrompt> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PresetRow preset = presets.get(i % presets.size());
            out.add(new ScenePrompt(preset.code(),
                    preset.promptEn() + properties.getScenePromptSuffix(),
                    properties.getSceneNegative()));
        }
        return out;
    }

    /** First CATEGORY hit wins over any NAME hit; GENERIC when nothing matches. */
    private String roomFor(List<String> categorySlugs, @Nullable String productName) {
        String nameRoom = null;
        for (RuleRow rule : jdbcTemplate.query(
                "select keyword, room from scene_room_rule",
                (rs, rowNum) -> new RuleRow(rs.getString("keyword"), rs.getString("room")))) {
            KeywordMatcher.Match match = KeywordMatcher.matches(rule.keyword(),
                    categorySlugs, productName);
            if (match == KeywordMatcher.Match.CATEGORY) {
                return rule.room();
            }
            if (match == KeywordMatcher.Match.NAME && nameRoom == null) {
                nameRoom = rule.room();
            }
        }
        return nameRoom == null ? "GENERIC" : nameRoom;
    }

    /** One scene prompt for the generation input. */
    public record ScenePrompt(String presetCode, String positive, String negative) {
    }

    private record RuleRow(String keyword, String room) {
    }

    private record PresetRow(String code, String promptEn) {
    }
}
