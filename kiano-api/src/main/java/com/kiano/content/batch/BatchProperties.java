package com.kiano.content.batch;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.batch.* — the batch orchestration knobs (C6): how often the
 * orchestrator ticks, how many pipelines and fact drafts may be in flight, the
 * minimum spacing between the publishes, the optional night window that batch
 * GPU jobs can be restricted to, and the per-step GPU seconds used by the ETA
 * when a step has no history yet. {@code enabled=false} stops the scheduled
 * orchestrator (tests call tick() directly).
 */
@Component
@ConfigurationProperties(prefix = "kiano.batch")
public class BatchProperties {

    private boolean enabled = true;
    private Duration tick = Duration.ofSeconds(30);
    private int maxActivePipelines = 3;
    private int maxQueuedFactDrafts = 5;
    private Duration publishInterval = Duration.ofSeconds(10);
    /** "22:00-07:00" in {@link #zone}; equal ends mean always open. */
    private String nightWindow = "22:00-07:00";
    private String zone = "Africa/Accra";
    /** Step name → seconds one job of that step takes without history. */
    private Map<String, Double> defaultGpuSeconds = new LinkedHashMap<>(Map.of(
            "CUTOUT", 40.0, "SCENE_INPUT", 10.0, "WHITE_MAIN", 20.0, "WHITE_ANGLE", 20.0,
            "INBOX", 20.0, "SCENE", 60.0, "H3_CLIP_API", 20.0, "H3_CLIP_LOCAL", 60.0,
            "VIDEO_COMPOSE", 20.0));

    /** Whether the night window is open right now (batch night-only GPU work). */
    public boolean nightOpen(Instant now) {
        NightWindowSpec window = nightWindowSpec();
        return NightWindow.isOpen(window.start(), window.end(),
                LocalTime.ofInstant(now, window.zone()));
    }

    /** The configured night window as a value (the ETA spans it across nights). */
    public NightWindowSpec nightWindowSpec() {
        String[] bounds = nightWindow.split("-", 2);
        return new NightWindowSpec(LocalTime.parse(bounds[0].trim()),
                LocalTime.parse(bounds[1].trim()), ZoneId.of(zone));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTick() {
        return tick;
    }

    public void setTick(Duration tick) {
        this.tick = tick;
    }

    public int getMaxActivePipelines() {
        return maxActivePipelines;
    }

    public void setMaxActivePipelines(int maxActivePipelines) {
        this.maxActivePipelines = maxActivePipelines;
    }

    public int getMaxQueuedFactDrafts() {
        return maxQueuedFactDrafts;
    }

    public void setMaxQueuedFactDrafts(int maxQueuedFactDrafts) {
        this.maxQueuedFactDrafts = maxQueuedFactDrafts;
    }

    public Duration getPublishInterval() {
        return publishInterval;
    }

    public void setPublishInterval(Duration publishInterval) {
        this.publishInterval = publishInterval;
    }

    public String getNightWindow() {
        return nightWindow;
    }

    public void setNightWindow(String nightWindow) {
        this.nightWindow = nightWindow;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public Map<String, Double> getDefaultGpuSeconds() {
        return defaultGpuSeconds;
    }

    public void setDefaultGpuSeconds(Map<String, Double> defaultGpuSeconds) {
        this.defaultGpuSeconds = defaultGpuSeconds;
    }
}