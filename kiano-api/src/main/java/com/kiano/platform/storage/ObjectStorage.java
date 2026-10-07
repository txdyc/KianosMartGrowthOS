package com.kiano.platform.storage;

import java.time.Duration;
import java.nio.file.Path;
import java.net.URI;

/**
 * Minimal S3-compatible object storage abstraction used for media files.
 */
public interface ObjectStorage {

    /** Uploads a file, replacing any existing object with the same key. */
    void put(String key, Path file, String contentType);

    /** Uploads in-memory bytes, replacing any existing object with the same key. */
    void put(String key, byte[] bytes, String contentType);

    /** Returns true if an object with the given key exists. */
    boolean exists(String key);

    /** Returns a presigned GET URL valid for the given duration (public-endpoint host). */
    URI presignGet(String key, Duration ttl);
}
