package com.kiano.content.pipeline;

import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.generation.GenerationRunStore.GenerationRun;
import com.kiano.content.generation.WorkerStatusStore;
import com.kiano.platform.auth.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pipeline endpoints: start a run (OPERATOR), watch its progress (VIEWER),
 * retry a failed job (OPERATOR) and list workers with online flags (VIEWER).
 */
@RestController
public class PipelineController {

    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(90);

    private final PipelineService pipeline;
    private final GenerationRunStore runs;
    private final GenerationJobStore jobs;
    private final WorkerStatusStore workers;

    public PipelineController(PipelineService pipeline, GenerationRunStore runs,
            GenerationJobStore jobs, WorkerStatusStore workers) {
        this.pipeline = pipeline;
        this.runs = runs;
        this.jobs = jobs;
        this.workers = workers;
    }

    @PostMapping("/api/v1/content/products/{id}/image-pipeline")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Long>> start(CurrentUser user, @PathVariable long id) {
        long runId = pipeline.start(user, id);
        return ResponseEntity.accepted().body(Map.of("runId", runId));
    }

    @GetMapping("/api/v1/content/products/{id}/image-pipeline")
    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<Map<String, Object>> progress(CurrentUser user, @PathVariable long id) {
        Optional<GenerationRun> latest = runs.latest(user.tenantId(), id);
        if (latest.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        GenerationRun run = latest.get();
        List<Map<String, Object>> jobViews = new ArrayList<>();
        for (GenerationJob job : jobs.findByRun(user.tenantId(), run.id())) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", job.id());
            view.put("step", job.step().name());
            view.put("variant", job.variant());
            view.put("executor", job.executor().name());
            view.put("status", job.status().name());
            view.put("attempts", job.attempts());
            view.put("maxAttempts", job.maxAttempts());
            view.put("error", job.error());
            view.put("gpuSeconds", job.gpuSeconds());
            view.put("createdAt", job.createdAt());
            view.put("finishedAt", job.finishedAt());
            jobViews.add(view);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id());
        body.put("status", run.status());
        body.put("createdAt", run.createdAt());
        body.put("finishedAt", run.finishedAt());
        body.put("jobs", jobViews);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v1/content/jobs/{id}/retry")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Void> retry(CurrentUser user, @PathVariable long id) {
        jobs.retry(user.tenantId(), id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/content/workers")
    @PreAuthorize("hasRole('VIEWER')")
    public List<Map<String, Object>> workers() {
        Instant cutoff = Instant.now().minus(ONLINE_WINDOW);
        List<Map<String, Object>> views = new ArrayList<>();
        for (WorkerStatusStore.WorkerStatusView worker : workers.list()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("workerId", worker.workerId());
            view.put("lastSeenAt", worker.lastSeenAt());
            view.put("online", worker.lastSeenAt().isAfter(cutoff));
            view.put("capabilities", worker.capabilities());
            view.put("unavailable", worker.unavailable());
            views.add(view);
        }
        return views;
    }
}
