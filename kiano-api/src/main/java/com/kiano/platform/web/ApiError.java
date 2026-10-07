package com.kiano.platform.web;

import java.util.Map;

/**
 * Unified error response body: {@code { code, message, traceId, details }}.
 */
public record ApiError(String code, String message, String traceId, Map<String, Object> details) {
}
