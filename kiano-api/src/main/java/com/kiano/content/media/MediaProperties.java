package com.kiano.content.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.media.* configuration.
 */
@Component
@ConfigurationProperties(prefix = "kiano.media")
public class MediaProperties {

    /** Path to the ffprobe executable used for video probing. */
    private String ffprobePath = "ffprobe";

    /** Path to the ffmpeg executable used to extract V1 demo frames. */
    private String ffmpegPath = "ffmpeg";

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /** Upper bound for one ffmpeg frame extraction; a hung process is killed after it. */
    private java.time.Duration ffmpegTimeout = java.time.Duration.ofSeconds(60);

    public java.time.Duration getFfmpegTimeout() {
        return ffmpegTimeout;
    }

    public void setFfmpegTimeout(java.time.Duration ffmpegTimeout) {
        this.ffmpegTimeout = ffmpegTimeout;
    }
}
