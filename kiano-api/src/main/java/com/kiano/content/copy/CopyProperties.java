package com.kiano.content.copy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.content.copy.* — text precheck knobs: the forbidden-claims list,
 * COPY_TITLE length cap and copy length caps (Review Focus on TOO_LONG).
 */
@Component
@ConfigurationProperties(prefix = "kiano.content.copy")
public class CopyProperties {

    private List<String> forbiddenClaims = List.of(
            "best in ghana", "100% guaranteed", "no.1", "number one",
            "cheapest", "lowest price", "guaranteed results");

    private int maxTitle = 120;
    private int maxGshop = 150;
    private int maxSeoTitle = 60;
    private int maxSeoDescription = 155;

    public List<String> getForbiddenClaims() {
        return forbiddenClaims;
    }

    public void setForbiddenClaims(List<String> forbiddenClaims) {
        this.forbiddenClaims = forbiddenClaims;
    }

    public int getMaxTitle() {
        return maxTitle;
    }

    public void setMaxTitle(int maxTitle) {
        this.maxTitle = maxTitle;
    }

    public int getMaxGshop() {
        return maxGshop;
    }

    public void setMaxGshop(int maxGshop) {
        this.maxGshop = maxGshop;
    }

    public int getMaxSeoTitle() {
        return maxSeoTitle;
    }

    public void setMaxSeoTitle(int maxSeoTitle) {
        this.maxSeoTitle = maxSeoTitle;
    }

    public int getMaxSeoDescription() {
        return maxSeoDescription;
    }

    public void setMaxSeoDescription(int maxSeoDescription) {
        this.maxSeoDescription = maxSeoDescription;
    }
}