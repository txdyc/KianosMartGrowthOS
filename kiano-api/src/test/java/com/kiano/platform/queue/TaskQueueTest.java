package com.kiano.platform.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TaskQueueTest.HandlerConfig.class})
class TaskQueueTest {

    static final AtomicInteger OK_CALLS = new AtomicInteger();
    static final AtomicReference<String> BOOM_MODE = new AtomicReference<>("retryable");

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfig {

        @Bean
        TaskHandler okHandler() {
            return new TaskHandler() {
                @Override
                public String type() {
                    return "ok";
                }

                @Override
                public Object handle(TaskContext ctx) {
                    OK_CALLS.incrementAndGet();
                    return Map.of("n", 1);
                }
            };
        }

        @Bean
        TaskHandler boomHandler() {
            return new TaskHandler() {
                @Override
                public String type() {
                    return "boom";
                }

                @Override
                public Object handle(TaskContext ctx) {
                    if ("non-retryable".equals(BOOM_MODE.get())) {
                        throw new NonRetryableTaskException("boom forever");
                    }
                    throw new IllegalStateException("boom");
                }
            };
        }
    }

    @Autowired
    private TaskQueue queue;

    @Autowired
    private TaskDispatcher dispatcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;

    @BeforeEach
    void clean() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        jdbcTemplate.update("delete from platform_task");
        OK_CALLS.set(0);
        BOOM_MODE.set("retryable");
    }

    @Test
    void enqueue_sameDedupeKeyWhileQueued_returnsEmpty() {
        Optional<Long> first = queue.enqueue(tenantId, "ok", Map.of("n", 1), "dedupe-1");
        Optional<Long> second = queue.enqueue(tenantId, "ok", Map.of("n", 2), "dedupe-1");
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void enqueue_afterPreviousSucceeded_allowsNew() {
        assertThat(queue.enqueue(tenantId, "ok", Map.of("n", 1), "dedupe-2")).isPresent();
        assertThat(dispatcher.pollOnce()).isTrue();
        assertThat(queue.enqueue(tenantId, "ok", Map.of("n", 2), "dedupe-2")).isPresent();
    }

    @Test
    void pollOnce_runsHandler_storesResult() {
        queue.enqueue(tenantId, "ok", Map.of("n", 1), null);
        boolean claimed = dispatcher.pollOnce();
        assertThat(claimed).isTrue();
        TaskView view = queue.latest(tenantId, "ok").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(view.attempts()).isEqualTo(1);
        assertThat(view.result().get("n").asInt()).isEqualTo(1);
        assertThat(view.finishedAt()).isNotNull();
        assertThat(OK_CALLS.get()).isEqualTo(1);
    }

    @Test
    void retryableFailure_requeuesWithBackoff() {
        queue.enqueue(tenantId, "boom", Map.of(), null);
        assertThat(dispatcher.pollOnce()).isTrue();
        TaskView view = queue.latest(tenantId, "boom").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(view.attempts()).isEqualTo(1);
        assertThat(view.lastError()).contains("boom");
        Timestamp runAfter = jdbcTemplate.queryForObject(
                "select run_after from platform_task where tenant_id = ? and type = 'boom'",
                Timestamp.class, tenantId);
        assertThat(runAfter.toInstant()).isAfter(Instant.now().plusSeconds(25));
    }

    @Test
    void successAfterEarlierFailedAttempt_clearsLastError() {
        jdbcTemplate.update(
                "insert into platform_task (tenant_id, type, payload, status, attempts, max_attempts, "
                        + "run_after, last_error) values (?, 'ok', '{}', 'QUEUED', 1, 3, now(), "
                        + "'WooCommerce is unavailable after 3 attempts')",
                tenantId);
        assertThat(dispatcher.pollOnce()).isTrue();
        TaskView view = queue.latest(tenantId, "ok").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(view.attempts()).isEqualTo(2);
        assertThat(view.lastError()).isNull();
    }

    @Test
    void retryableFailure_atMaxAttempts_fails() {
        queue.enqueue(tenantId, "boom", Map.of(), null);
        jdbcTemplate.update("update platform_task set max_attempts = 1 where type = 'boom'");
        assertThat(dispatcher.pollOnce()).isTrue();
        TaskView view = queue.latest(tenantId, "boom").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(view.attempts()).isEqualTo(1);
        assertThat(view.finishedAt()).isNotNull();
        assertThat(view.lastError()).contains("boom");
    }

    @Test
    void nonRetryable_failsImmediately() {
        BOOM_MODE.set("non-retryable");
        queue.enqueue(tenantId, "boom", Map.of(), null);
        assertThat(dispatcher.pollOnce()).isTrue();
        TaskView view = queue.latest(tenantId, "boom").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(view.attempts()).isEqualTo(1);
        assertThat(view.lastError()).contains("boom forever");
    }

    @Test
    void unknownType_failsWithNoHandler() {
        queue.enqueue(tenantId, "no.such.type", Map.of(), null);
        assertThat(dispatcher.pollOnce()).isTrue();
        TaskView view = queue.latest(tenantId, "no.such.type").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(view.lastError()).contains("no handler");
    }

    @Test
    void expiredLease_isReclaimed() {
        jdbcTemplate.update(
                "insert into platform_task (tenant_id, type, payload, status, attempts, max_attempts, "
                        + "run_after, locked_by, locked_until, started_at) values (?, 'ok', '{}', 'RUNNING', 1, 3, "
                        + "now() - interval '5 minutes', 'dead-worker', now() - interval '30 minutes', "
                        + "now() - interval '30 minutes')",
                tenantId);
        assertThat(dispatcher.pollOnce()).isTrue();
        TaskView view = queue.latest(tenantId, "ok").orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(view.attempts()).isEqualTo(2);
        assertThat(OK_CALLS.get()).isEqualTo(1);
    }

    @Test
    void concurrentClaims_neverClaimSameTask() throws Exception {
        int taskCount = 6;
        for (int i = 0; i < taskCount; i++) {
            queue.enqueue(tenantId, "ok", Map.of("i", i), null);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> drain());
            Future<Integer> second = pool.submit(() -> drain());
            int total = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);
            assertThat(total).isEqualTo(taskCount);
        } finally {
            pool.shutdownNow();
        }
        assertThat(OK_CALLS.get()).isEqualTo(taskCount);
        Integer maxAttempts = jdbcTemplate.queryForObject(
                "select max(attempts) from platform_task where tenant_id = ?", Integer.class, tenantId);
        assertThat(maxAttempts).isEqualTo(1);
    }

    private int drain() {
        int claimed = 0;
        while (dispatcher.pollOnce()) {
            claimed++;
        }
        return claimed;
    }

    @Test
    void pollOnce_whenEmpty_returnsFalse() {
        assertThat(dispatcher.pollOnce()).isFalse();
    }
}
