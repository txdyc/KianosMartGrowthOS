package com.kiano.content.media;

import com.kiano.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a file name does not follow the SKU_shotCode.ext convention.
 * code is INVALID_FILE_NAME (structure) or UNSUPPORTED_FILE_TYPE (extension
 * not allowed for the shot code). extension is the lower-cased extension
 * without the dot, or null when the name has none.
 */
public class InvalidShotFileNameException extends ApiException {

    private final String extension;

    public InvalidShotFileNameException(String code, String message, String extension) {
        this(code, message, extension, null);
    }

    InvalidShotFileNameException(String code, String message, String extension, String fileName) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details(extension, fileName));
        this.extension = extension;
    }

    private static Map<String, Object> details(String extension, String fileName) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (fileName != null) {
            details.put("fileName", fileName);
        }
        details.put("extension", extension == null ? "" : extension);
        return details;
    }

    public String getExtension() {
        return extension;
    }
}
