package com.kiano.workerprotocol;

import java.util.Locale;

/**
 * Content type by object-key extension. The api and the worker agree on this
 * so presigned uploads and the reported OutputFile carry the right type for
 * png / jpeg / mp4 (videos arrive in Task 7+).
 */
public final class ContentTypes {

    private ContentTypes() {
    }

    public static String forKey(String objectKey) {
        String lower = objectKey.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        throw new IllegalArgumentException("Unknown content type for object key " + objectKey);
    }
}
