package com.kiano.content.batch;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.batch.* — the batch orchestration knobs (C6): how often the
 * orchestrator ticks, how many pipelines and fact drafts may be in flight, the
 * minimum spacing between the publishes, and the optional night window that
 * batch GPU jobs can be restricted to. {@code enabled=false} stops the
 * scheduled orchestrator (tests call tick() directly).
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

    /** Whether the night window is open right now (batch night-only GPU work). */
    public boolean nightOpen(Instant now) {
        String[] bounds = nightWindow.split("-", 2);
        LocalTime start = LocalTime.parse(bounds[0].trim());
        LocalTime end = LocalTime.parse(bounds[1].trim());
        return NightWindow.isOpen(start, end, LocalTime.ofInstant(now, ZoneId.of(zone)));
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
}