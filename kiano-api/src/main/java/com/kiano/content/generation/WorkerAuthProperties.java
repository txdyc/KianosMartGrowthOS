package com.kiano.content.generation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.worker.* on the api side: presign TTL and the background monitors.
 */
@Component
@ConfigurationProperties(prefix = "kiano.worker")
public class WorkerAuthProperties {

    /** How long presigned input/output URLs stay valid. */
    private Duration urlTtl = Duration.ofMinutes(60);

    /** Lease reaper and executor availability monitor; disabled in tests. */
    private boolean monitorEnabled = true;

    public Duration getUrlTtl() {
        return urlTtl;
    }

    public void setUrlTtl(Duration urlTtl) {
        this.urlTtl = urlTtl;
    }

    public boolean isMonitorEnabled() {
        return monitorEnabled;
    }

    public void setMonitorEnabled(boolean monitorEnabled) {
        this.monitorEnabled = monitorEnabled;
    }
}
