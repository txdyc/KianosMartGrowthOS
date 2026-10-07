package com.kiano.content.generation;

import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.CompleteRequest;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.FailRequest;
import com.kiano.workerprotocol.HeartbeatResponse;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.LeaseRequest;
import com.kiano.workerprotocol.LeaseResponse;
import com.kiano.workerprotocol.OutputFile;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Worker-facing endpoints behind the ROLE_WORKER security chain. Workers pull
 * jobs (lease), keep the lease alive (heartbeat) and report the outcome
 * (complete/fail). Presigned URLs carry the actual bytes; the api only ever
 * sees metadata.
 */
@RestController
@RequestMapping("/api/v1/worker")
public class WorkerController {

    private static final String PNG = "image/png";

    private final GenerationJobStore jobs;
    private final GenerationRunStore runs;
    private final WorkerStatusStore workerStatus;
    private final ObjectStorage storage;
    private final WorkerAuthProperties properties;
    private final ObjectProvider<JobCompletionListener> completionListener;

    public WorkerController(GenerationJobStore jobs, GenerationRunStore runs, WorkerStatusStore workerStatus,
            ObjectStorage storage, WorkerAuthProperties properties,
            ObjectProvider<JobCompletionListener> completionListener) {
        this.jobs = jobs;
        this.runs = runs;
        this.workerStatus = workerStatus;
        this.storage = storage;
        this.properties = properties;
        this.completionListener = completionListener;
    }

    @PostMapping("/lease")
    public ResponseEntity<LeaseResponse> lease(@RequestBody LeaseRequest request) {
        workerStatus.touch(request.workerId(), request.capabilities(), request.unavailable());
        if (request.unavailable() != null) {
            for (ExecutorType executor : request.unavailable()) {
                jobs.markWaiting(executor);
            }
        }
        if (request.capabilities() != null) {
            for (ExecutorType executor : request.capabilities()) {
                jobs.releaseWaiting(executor);
            }
        }
        // GPU work is single-concurrency: max is always treated as 1.
        Optional<GenerationJob> leased = jobs.lease(request.workerId(), request.capabilities());
        return leased.map(job -> ResponseEntity.ok(toLeaseResponse(job))).orElseGet(
                () -> ResponseEntity.noContent().build());
    }

    private LeaseResponse toLeaseResponse(GenerationJob job) {
        Map<String, URI> inputUrls = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : job.input().path("inputs").properties()) {
            inputUrls.put(entry.getKey(), storage.presignGet(entry.getValue().asString(), properties.getUrlTtl()));
        }
        Map<String, URI> outputUploadUrls = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : job.input().path("outputs").properties()) {
            outputUploadUrls.put(entry.getKey(),
                    storage.presignPut(entry.getValue().asString(), properties.getUrlTtl(), PNG));
        }
        JobPayload payload = new JobPayload(job.id(), job.step(), job.variant(), job.executor(), job.input());
        return new LeaseResponse(payload, inputUrls, outputUploadUrls);
    }

    @PostMapping("/jobs/{id}/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable long id, @RequestHeader("X-Worker-Id") String workerId) {
        if (!jobs.heartbeat(id, workerId)) {
            throw leaseLost(id);
        }
        return new HeartbeatResponse(true);
    }

    @PostMapping("/jobs/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable long id, @RequestHeader("X-Worker-Id") String workerId,
            @RequestBody CompleteRequest request) {
        GenerationJob job = jobs.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "JOB_NOT_FOUND", "Generation job " + id + " does not exist"));
        if (!outputsSatisfyContract(job, request)) {
            if (jobs.fail(id, workerId, "OUTPUT_MISSING: reported outputs do not match the job contract",
                    true, false).isEmpty()) {
                throw leaseLost(id);
            }
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTPUT_MISSING",
                    "Reported outputs do not match the job contract (names, object keys or missing objects)");
        }
        Map<String, Object> files = new LinkedHashMap<>();
        for (OutputFile output : request.outputs()) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("objectKey", output.objectKey());
            file.put("width", output.width());
            file.put("height", output.height());
            file.put("durationS", output.durationS());
            file.put("sha256", output.sha256());
            files.put(output.name(), file);
        }
        Optional<GenerationJob> completed = jobs.complete(id, workerId, Map.of("outputs", files),
                request.gpuSeconds());
        if (completed.isEmpty()) {
            throw leaseLost(id);
        }
        // After the completion is committed: fan out downstream work, then
        // recompute the run status (which sees jobs the listeners created).
        completionListener.orderedStream().forEach(listener -> listener.onSucceeded(completed.get()));
        runs.refreshStatus(job.runId());
        return ResponseEntity.ok().build();
    }

    /** Names match input_json.outputs, keys match the contract and exist in storage. */
    private boolean outputsSatisfyContract(GenerationJob job, CompleteRequest request) {
        JsonNode expected = job.input().path("outputs");
        Set<String> expectedNames = new HashSet<>();
        Map<String, String> expectedKeys = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : expected.properties()) {
            expectedNames.add(entry.getKey());
            expectedKeys.put(entry.getKey(), entry.getValue().asString());
        }
        Set<String> providedNames = new HashSet<>();
        for (OutputFile output : request.outputs() == null ? java.util.List.<OutputFile>of() : request.outputs()) {
            providedNames.add(output.name());
            String expectedKey = expectedKeys.get(output.name());
            if (expectedKey == null || !expectedKey.equals(output.objectKey()) || !storage.exists(output.objectKey())) {
                return false;
            }
        }
        return expectedNames.equals(providedNames);
    }

    @PostMapping("/jobs/{id}/fail")
    public ResponseEntity<Void> fail(@PathVariable long id, @RequestHeader("X-Worker-Id") String workerId,
            @RequestBody FailRequest request) {
        Optional<GenerationJob> failed = jobs.fail(id, workerId, request.error(), request.retryable(),
                request.executorUnavailable());
        if (failed.isEmpty()) {
            throw leaseLost(id);
        }
        runs.refreshStatus(failed.get().runId());
        return ResponseEntity.ok().build();
    }

    private static ApiException leaseLost(long jobId) {
        return new ApiException(HttpStatus.CONFLICT, "LEASE_LOST",
                "Lease on generation job " + jobId + " was lost or has expired");
    }
}
