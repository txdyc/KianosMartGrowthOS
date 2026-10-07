package com.kiano.platform.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.queue.* configuration.
 */
@Component
@ConfigurationProperties(prefix = "kiano.queue")
public class QueueProperties {

    /** Master switch for the polling scheduler (tests set this to false). */
    private boolean enabled = true;

    /** Delay between polls. */
    private Duration pollInterval = Duration.ofSeconds(2);

    /** How long a claimed task stays RUNNING before its lease can be reclaimed. */
    private Duration lease = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }
}
