package com.kiano.content.qc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.content.qc.* configuration. Defaults mirror application.yml so that
 * plain unit tests can construct the properties without a Spring context.
 */
@Component
@ConfigurationProperties(prefix = "kiano.content.qc")
public class QcProperties {

    private final Photo photo = new Photo();
    private final Video video = new Video();

    public Photo getPhoto() {
        return photo;
    }

    public Video getVideo() {
        return video;
    }

    public static class Photo {

        /** Display short side below this flags LOW_RESOLUTION. */
        private int minShortSide = 2000;

        /** Images are analysed at roughly this long side via subsampling. */
        private int analysisLongSide = 1024;

        /** Laplacian variance below this flags BLURRY. */
        private double blurMinVariance = 60;

        /** Luminance >= clipHigh counts as over-exposed pixel. */
        private int clipHigh = 250;

        /** Luminance <= clipLow counts as under-exposed pixel. */
        private int clipLow = 5;

        /** Over-exposed pixel ratio above this flags OVEREXPOSED. */
        private double maxOverRatio = 0.30;

        /** Under-exposed pixel ratio above this flags UNDEREXPOSED. */
        private double maxUnderRatio = 0.30;

        public int getMinShortSide() {
            return minShortSide;
        }

        public void setMinShortSide(int minShortSide) {
            this.minShortSide = minShortSide;
        }

        public int getAnalysisLongSide() {
            return analysisLongSide;
        }

        public void setAnalysisLongSide(int analysisLongSide) {
            this.analysisLongSide = analysisLongSide;
        }

        public double getBlurMinVariance() {
            return blurMinVariance;
        }

        public void setBlurMinVariance(double blurMinVariance) {
            this.blurMinVariance = blurMinVariance;
        }

        public int getClipHigh() {
            return clipHigh;
        }

        public void setClipHigh(int clipHigh) {
            this.clipHigh = clipHigh;
        }

        public int getClipLow() {
            return clipLow;
        }

        public void setClipLow(int clipLow) {
            this.clipLow = clipLow;
        }

        public double getMaxOverRatio() {
            return maxOverRatio;
        }

        public void setMaxOverRatio(double maxOverRatio) {
            this.maxOverRatio = maxOverRatio;
        }

        public double getMaxUnderRatio() {
            return maxUnderRatio;
        }

        public void setMaxUnderRatio(double maxUnderRatio) {
            this.maxUnderRatio = maxUnderRatio;
        }
    }

    public static class Video {

        /** Display short side below this flags LOW_RESOLUTION. */
        private int minShortSide = 1080;

        /** fps outside [fpsMin, fpsMax] flags FPS_OUT_OF_RANGE. */
        private double fpsMin = 29.0;
        private double fpsMax = 31.0;

        /** V1 duration outside [v1MinSeconds, v1MaxSeconds] flags DURATION_OUT_OF_RANGE. */
        private double v1MinSeconds = 10;
        private double v1MaxSeconds = 20;

        public int getMinShortSide() {
            return minShortSide;
        }

        public void setMinShortSide(int minShortSide) {
            this.minShortSide = minShortSide;
        }

        public double getFpsMin() {
            return fpsMin;
        }

        public void setFpsMin(double fpsMin) {
            this.fpsMin = fpsMin;
        }

        public double getFpsMax() {
            return fpsMax;
        }

        public void setFpsMax(double fpsMax) {
            this.fpsMax = fpsMax;
        }

        public double getV1MinSeconds() {
            return v1MinSeconds;
        }

        public void setV1MinSeconds(double v1MinSeconds) {
            this.v1MinSeconds = v1MinSeconds;
        }

        public double getV1MaxSeconds() {
            return v1MaxSeconds;
        }

        public void setV1MaxSeconds(double v1MaxSeconds) {
            this.v1MaxSeconds = v1MaxSeconds;
        }
    }
}
