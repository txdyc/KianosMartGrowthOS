package com.kiano.worker.ffmpeg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * kiano.worker.ffmpeg-* in the worker process: the ffmpeg executable that
 * composes VIDEO_COMPOSE jobs and the upper bound for one command. Shares the
 * kiano.worker prefix with WorkerProperties; each class ignores the other's
 * fields.
 */
@Component
@Profile("worker")
@ConfigurationProperties(prefix = "kiano.worker")
public class FfmpegProperties {

    /** Path to the ffmpeg executable used to compose videos. */
    private String ffmpegPath = "ffmpeg";

    /** Upper bound for one ffmpeg command; a hung compose is killed and fails. */
    private Duration ffmpegTimeout = Duration.ofMinutes(10);

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public Duration getFfmpegTimeout() {
        return ffmpegTimeout;
    }

    public void setFfmpegTimeout(Duration ffmpegTimeout) {
        this.ffmpegTimeout = ffmpegTimeout;
    }
}