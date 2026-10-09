package com.kiano.content.video;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.content.video.* — short-video knobs. Task 2 adds the mood per video
 * type used by the music picker; later tasks add the recipes, volume and H3
 * switches to the same class.
 */
@Component
@ConfigurationProperties(prefix = "kiano.content.video")
public class VideoProperties {

    /** Mood preferred by the music picker per video type (wire() → mood). */
    private Map<String, String> mood = Map.of(
            "demo", "upbeat",
            "problem", "uplifting",
            "unboxing", "upbeat");

    public Map<String, String> getMood() {
        return mood;
    }

    public void setMood(Map<String, String> mood) {
        this.mood = mood;
    }
}
