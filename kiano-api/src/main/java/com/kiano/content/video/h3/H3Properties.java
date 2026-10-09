package com.kiano.content.video.h3;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.content.video.h3.* — the H3 hook knobs: which backend mode is
 * configured, the shared clip length, and the provider-API and local-ComfyUI
 * settings (the local ones are consumed by Task 8b).
 */
@Component
@ConfigurationProperties(prefix = "kiano.content.video.h3")
public class H3Properties {

    /** Configured backend mode: LOCAL_FIRST prefers local, OFF disables H3. */
    public enum Mode {
        LOCAL_FIRST, LOCAL_ONLY, API_ONLY, OFF
    }

    /** Provider-API settings (MiniMax); the key comes from MINIMAX_API_KEY only. */
    public static class Api {
        private boolean enabled = false;
        private String baseUrl = "https://api.minimax.chat";
        private String apiKey = "";
        private String model = "hailuo-02";
        private String submitPath = "/v1/video_generation";
        private String queryPath = "/v1/query/video_generation";
        private String filePath = "/v1/files/retrieve";
        private String resolution = "1080P";
        private BigDecimal pricePerSecondUsd = BigDecimal.ZERO;
        private BigDecimal dailyBudgetUsd = new BigDecimal("10");
        private Duration pollInterval = Duration.ofSeconds(20);
        private Duration maxWait = Duration.ofMinutes(15);
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSubmitPath() {
            return submitPath;
        }

        public void setSubmitPath(String submitPath) {
            this.submitPath = submitPath;
        }

        public String getQueryPath() {
            return queryPath;
        }

        public void setQueryPath(String queryPath) {
            this.queryPath = queryPath;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public BigDecimal getPricePerSecondUsd() {
            return pricePerSecondUsd;
        }

        public void setPricePerSecondUsd(BigDecimal pricePerSecondUsd) {
            this.pricePerSecondUsd = pricePerSecondUsd;
        }

        public BigDecimal getDailyBudgetUsd() {
            return dailyBudgetUsd;
        }

        public void setDailyBudgetUsd(BigDecimal dailyBudgetUsd) {
            this.dailyBudgetUsd = dailyBudgetUsd;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** Local ComfyUI H3 settings, calibrated against 16 GB of VRAM (Task 8b). */
    public static class Local {
        private int width = 720;
        private int height = 1280;
        private int fps = 24;
        private Duration localMaxWait = Duration.ofHours(2);
        private int comfyTimeoutSeconds = 1800;

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public int getFps() {
            return fps;
        }

        public void setFps(int fps) {
            this.fps = fps;
        }

        public Duration getLocalMaxWait() {
            return localMaxWait;
        }

        public void setLocalMaxWait(Duration localMaxWait) {
            this.localMaxWait = localMaxWait;
        }

        public int getComfyTimeoutSeconds() {
            return comfyTimeoutSeconds;
        }

        public void setComfyTimeoutSeconds(int comfyTimeoutSeconds) {
            this.comfyTimeoutSeconds = comfyTimeoutSeconds;
        }
    }

    private Mode backend = Mode.LOCAL_FIRST;

    /** Clip length requested from both backends. */
    private int requestSeconds = 5;

    private Api api = new Api();
    private Local local = new Local();

    public Mode getBackend() {
        return backend;
    }

    public void setBackend(Mode backend) {
        this.backend = backend;
    }

    public int getRequestSeconds() {
        return requestSeconds;
    }

    public void setRequestSeconds(int requestSeconds) {
        this.requestSeconds = requestSeconds;
    }

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }
}