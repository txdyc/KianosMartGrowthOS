package com.kiano.workerprotocol;

import java.net.URI;
import java.util.Map;

/**
 * Presigned URLs for the leased job: GET for inputs, PUT for outputs.
 */
public record LeaseResponse(JobPayload job, Map<String, URI> inputUrls, Map<String, URI> outputUploadUrls) {
}
