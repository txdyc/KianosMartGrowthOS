package com.kiano.worker;

import com.kiano.workerprotocol.CompleteRequest;
import com.kiano.workerprotocol.FailRequest;
import com.kiano.workerprotocol.LeaseRequest;
import com.kiano.workerprotocol.LeaseResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * WorkerClient over the api's REST endpoints: bearer worker token and
 * X-Worker-Id on every call. 204 on lease means nothing to do, 409 on
 * heartbeat/complete/fail means the lease was lost.
 */
@Component
@Profile("worker")
public class HttpWorkerClient implements WorkerClient {

    private final WorkerProperties properties;
    private final RestClient rest;
    private final ObjectMapper mapper;

    public HttpWorkerClient(WorkerProperties properties) {
        this.properties = properties;
        this.rest = RestClient.builder().baseUrl(properties.getApiBaseUrl()).build();
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public Optional<LeaseResponse> lease(LeaseRequest request) {
        return rest.post().uri("/api/v1/worker/lease")
                .headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((req, response) -> {
                    if (response.getStatusCode().value() == 204) {
                        return Optional.<LeaseResponse>empty();
                    }
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return Optional.of(mapper.readValue(response.getBody(), LeaseResponse.class));
                    }
                    throw failure("POST", "/api/v1/worker/lease", response);
                });
    }

    @Override
    public boolean heartbeat(long jobId) {
        try {
            rest.post().uri("/api/v1/worker/jobs/{id}/heartbeat", jobId)
                    .headers(this::authorize)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            return rethrowUnlessLeaseLost(ex);
        }
    }

    @Override
    public boolean complete(long jobId, CompleteRequest request) {
        try {
            rest.post().uri("/api/v1/worker/jobs/{id}/complete", jobId)
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            return rethrowUnlessLeaseLost(ex);
        }
    }

    @Override
    public boolean fail(long jobId, FailRequest request) {
        try {
            rest.post().uri("/api/v1/worker/jobs/{id}/fail", jobId)
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            return rethrowUnlessLeaseLost(ex);
        }
    }

    @Override
    public void download(URI url, Path target) throws IOException {
        byte[] body = rest.get().uri(url).retrieve().body(byte[].class);
        Files.write(target, body == null ? new byte[0] : body);
    }

    @Override
    public void upload(URI url, Path file, String contentType) {
        rest.put().uri(url)
                .contentType(MediaType.parseMediaType(contentType))
                .body(readAllBytes(file))
                .retrieve()
                .toBodilessEntity();
    }

    /** 409 means the lease was lost; anything else is a real error. */
    private static boolean rethrowUnlessLeaseLost(RestClientResponseException ex) {
        if (ex.getStatusCode().value() == 409) {
            return false;
        }
        throw ex;
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(properties.getToken());
        headers.set("X-Worker-Id", properties.getId());
    }

    private static RestClientResponseException failure(String method, String path, ClientHttpResponse response)
            throws IOException {
        return new RestClientResponseException(method + " " + path + " -> " + response.getStatusCode(),
                response.getStatusCode().value(), method + " " + path, response.getHeaders(),
                StreamUtils.copyToByteArray(response.getBody()), null);
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
