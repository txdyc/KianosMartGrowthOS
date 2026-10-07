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

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }
}
