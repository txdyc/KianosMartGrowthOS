package com.kiano.content.pipeline;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Image pipeline planning knobs. */
@Component
@ConfigurationProperties(prefix = "kiano.content.pipeline")
public class PipelineProperties {

    /** Shot codes that get a WHITE_ANGLE page. */
    private List<String> angleShots = List.of("P2", "P3", "P4", "P6");

    /** First ACCEPTED shot of this list is the scene source. */
    private List<String> sceneSourceShots = List.of("P2", "P3", "P1");

    /** SCENE_INPUT jobs per tier. */
    private Map<String, Integer> sceneCount = Map.of("STANDARD", 2, "HERO", 4);

    private String scenePromptSuffix =
            ", Ghanaian home interior, photorealistic product photography, natural light, high detail";

    private String sceneNegative =
            "text, letters, watermark, logo, brand name, US plug, European plug, people, hands, "
                    + "deformed, blurry";

    public List<String> getAngleShots() {
        return angleShots;
    }

    public void setAngleShots(List<String> angleShots) {
        this.angleShots = angleShots;
    }

    public List<String> getSceneSourceShots() {
        return sceneSourceShots;
    }

    public void setSceneSourceShots(List<String> sceneSourceShots) {
        this.sceneSourceShots = sceneSourceShots;
    }

    public Map<String, Integer> getSceneCount() {
        return sceneCount;
    }

    public void setSceneCount(Map<String, Integer> sceneCount) {
        this.sceneCount = sceneCount;
    }

    public String getScenePromptSuffix() {
        return scenePromptSuffix;
    }

    public void setScenePromptSuffix(String scenePromptSuffix) {
        this.scenePromptSuffix = scenePromptSuffix;
    }

    public String getSceneNegative() {
        return sceneNegative;
    }

    public void setSceneNegative(String sceneNegative) {
        this.sceneNegative = sceneNegative;
    }
}
