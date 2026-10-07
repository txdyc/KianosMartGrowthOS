package com.kiano.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * kiano.worker.* in the worker process. The api binds url-ttl and
 * monitor-enabled under the same prefix in its own context; the two property
 * classes never coexist.
 */
@Component
@Profile("worker")
@ConfigurationProperties(prefix = "kiano.worker")
public class WorkerProperties {

    private String apiBaseUrl = "http://localhost:8081";
    private String token;
    private String id = defaultId();
    private Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "kiano-worker");
    private Duration heartbeatInterval = Duration.ofSeconds(30);

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public void setWorkDir(Path workDir) {
        this.workDir = workDir;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    private static String defaultId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "worker-" + UUID.randomUUID();
        }
    }
}
