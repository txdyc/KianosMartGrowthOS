package com.kiano.content.qc;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Rule checks for probed videos: portrait orientation, 1080p minimum short
 * side, a ~30fps window and the V1-only 10-20s duration window.
 */
@Component
public class VideoQc {

    private final QcProperties properties;

    public VideoQc(QcProperties properties) {
        this.properties = properties;
    }

    public List<QcReason> evaluate(String shotCode, VideoInfo info) {
        QcProperties.Video config = properties.getVideo();
        List<QcReason> reasons = new ArrayList<>();
        if (Math.min(info.width(), info.height()) < config.getMinShortSide()) {
            reasons.add(QcReason.LOW_RESOLUTION);
        }
        if (info.height() <= info.width()) {
            reasons.add(QcReason.NOT_PORTRAIT);
        }
        if (info.fps() < config.getFpsMin() || info.fps() > config.getFpsMax()) {
            reasons.add(QcReason.FPS_OUT_OF_RANGE);
        }
        if ("V1".equals(shotCode)
                && (info.durationSeconds() < config.getV1MinSeconds()
                        || info.durationSeconds() > config.getV1MaxSeconds())) {
            reasons.add(QcReason.DURATION_OUT_OF_RANGE);
        }
        return reasons;
    }
}
