package com.kiano.commerce.woo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.commerce.* — retry behaviour for outbound WooCommerce calls.
 */
@Component
@ConfigurationProperties(prefix = "kiano.commerce")
public class WooProperties {

    /** Total attempts (first try included) for retryable Woo failures. */
    private int maxAttempts = 3;

    /** Backoff between retries of 429/5xx responses or connection failures. */
    private Duration backoff = Duration.ofSeconds(2);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBackoff() {
        return backoff;
    }

    public void setBackoff(Duration backoff) {
        this.backoff = backoff;
    }
}
