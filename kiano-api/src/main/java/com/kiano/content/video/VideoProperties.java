package com.kiano.content.video;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.content.video.* — short-video knobs: the per-type recipe (hook/body/
 * captions/endcard seconds), total and AI-ratio limits, the mood per type for
 * the music picker, volume/fade settings and the portrait tolerance.
 */
@Component
@ConfigurationProperties(prefix = "kiano.content.video")
public class VideoProperties {

    /** Body recipe per video type (wire() → min/max seconds). */
    public static class Recipe {
        private double bodyMinSeconds = 8;
        private double bodyMaxSeconds = 15;

        public double getBodyMinSeconds() {
            return bodyMinSeconds;
        }

        public void setBodyMinSeconds(double bodyMinSeconds) {
            this.bodyMinSeconds = bodyMinSeconds;
        }

        public double getBodyMaxSeconds() {
            return bodyMaxSeconds;
        }

        public void setBodyMaxSeconds(double bodyMaxSeconds) {
            this.bodyMaxSeconds = bodyMaxSeconds;
        }
    }

    private double hookSeconds = 3;
    private double captionsSeconds = 5;
    private double endCardSeconds = 4;
    private double minTotal = 15;
    private double maxTotal = 30;
    private double maxAiRatio = 0.30;
    private int windowCandidates = 5;
    private double portraitTolerance = 0.02;
    private double originalVolume = 0.5;
    private double musicVolume = 0.35;
    private double musicFadeOut = 1.5;

    /** WhatsApp number shown on the end card; empty hides it. */
    private String whatsappDisplay = "";

    /** Mood preferred by the music picker per video type (wire() → mood). */
    private Map<String, String> mood = Map.of(
            "demo", "upbeat",
            "problem", "uplifting",
            "unboxing", "upbeat");

    private Map<String, Recipe> recipe = Map.of(
            "demo", recipe(8, 15),
            "problem", recipe(8, 12),
            "unboxing", recipe(8, 14));

    private static Recipe recipe(double min, double max) {
        Recipe r = new Recipe();
        r.setBodyMinSeconds(min);
        r.setBodyMaxSeconds(max);
        return r;
    }

    public Recipe recipe(VideoType type) {
        Recipe fallback = recipe.get("demo");
        return recipe.getOrDefault(type.wire(), fallback);
    }

    public double getHookSeconds() {
        return hookSeconds;
    }

    public void setHookSeconds(double hookSeconds) {
        this.hookSeconds = hookSeconds;
    }

    public double getCaptionsSeconds() {
        return captionsSeconds;
    }

    public void setCaptionsSeconds(double captionsSeconds) {
        this.captionsSeconds = captionsSeconds;
    }

    public double getEndCardSeconds() {
        return endCardSeconds;
    }

    public void setEndCardSeconds(double endCardSeconds) {
        this.endCardSeconds = endCardSeconds;
    }

    public double getMinTotal() {
        return minTotal;
    }

    public void setMinTotal(double minTotal) {
        this.minTotal = minTotal;
    }

    public double getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(double maxTotal) {
        this.maxTotal = maxTotal;
    }

    public double getMaxAiRatio() {
        return maxAiRatio;
    }

    public void setMaxAiRatio(double maxAiRatio) {
        this.maxAiRatio = maxAiRatio;
    }

    public int getWindowCandidates() {
        return windowCandidates;
    }

    public void setWindowCandidates(int windowCandidates) {
        this.windowCandidates = windowCandidates;
    }

    public double getPortraitTolerance() {
        return portraitTolerance;
    }

    public void setPortraitTolerance(double portraitTolerance) {
        this.portraitTolerance = portraitTolerance;
    }

    public double getOriginalVolume() {
        return originalVolume;
    }

    public void setOriginalVolume(double originalVolume) {
        this.originalVolume = originalVolume;
    }

    public double getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(double musicVolume) {
        this.musicVolume = musicVolume;
    }

    public double getMusicFadeOut() {
        return musicFadeOut;
    }

    public void setMusicFadeOut(double musicFadeOut) {
        this.musicFadeOut = musicFadeOut;
    }

    public String getWhatsappDisplay() {
        return whatsappDisplay;
    }

    public void setWhatsappDisplay(String whatsappDisplay) {
        this.whatsappDisplay = whatsappDisplay;
    }

    public Map<String, String> getMood() {
        return mood;
    }

    public void setMood(Map<String, String> mood) {
        this.mood = mood;
    }

    public Map<String, Recipe> getRecipe() {
        return recipe;
    }

    public void setRecipe(Map<String, Recipe> recipe) {
        this.recipe = recipe;
    }
}
