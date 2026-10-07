package com.kiano.content.qc;

import com.kiano.platform.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a media file cannot be decoded or probed.
 */
public class UnreadableMediaException extends ApiException {

    public UnreadableMediaException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_MEDIA", message);
    }
}
