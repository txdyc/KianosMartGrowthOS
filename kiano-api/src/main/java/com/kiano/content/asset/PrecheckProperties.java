package com.kiano.content.asset;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Thresholds for automatic asset prechecks. */
@Component
@ConfigurationProperties(prefix = "kiano.content.precheck")
public class PrecheckProperties {

    private double minOccupancy = 0.80;
    private double maxOccupancy = 0.85;
    private double ssimMin = 0.90;
    private @Nullable String tesseractPath;

    public double getMinOccupancy() {
        return minOccupancy;
    }

    public void setMinOccupancy(double minOccupancy) {
        this.minOccupancy = minOccupancy;
    }

    public double getMaxOccupancy() {
        return maxOccupancy;
    }

    public void setMaxOccupancy(double maxOccupancy) {
        this.maxOccupancy = maxOccupancy;
    }

    public double getSsimMin() {
        return ssimMin;
    }

    public void setSsimMin(double ssimMin) {
        this.ssimMin = ssimMin;
    }

    public @Nullable String getTesseractPath() {
        return tesseractPath;
    }

    public void setTesseractPath(@Nullable String tesseractPath) {
        this.tesseractPath = tesseractPath;
    }
}
