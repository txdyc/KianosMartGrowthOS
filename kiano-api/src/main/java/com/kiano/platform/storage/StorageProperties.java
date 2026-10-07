package com.kiano.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.storage.* configuration.
 */
@Component
@ConfigurationProperties(prefix = "kiano.storage")
public class StorageProperties {

    /** S3 endpoint the API talks to (container network, e.g. http://minio:9000). */
    private String endpoint;

    /** Endpoint browsers use to fetch presigned URLs (e.g. http://localhost:9000). */
    private String publicEndpoint;

    private String bucket = "kiano";

    private String accessKey;

    private String secretKey;

    private String region = "us-east-1";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
