package com.kiano.worker.comfy;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * ComfyUI connection settings for the worker's ComfyUI executor.
 */
@Component
@Profile("worker")
@ConfigurationProperties(prefix = "kiano.worker.comfy")
public class ComfyProperties {

    private URI baseUrl = URI.create("http://127.0.0.1:8188");

    /** How long a prompt may run before the worker interrupts it. */
    private Duration timeout = Duration.ofMinutes(15);

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
