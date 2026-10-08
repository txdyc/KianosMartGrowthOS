package com.kiano.platform.llm.settings;

import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.llm.ErrorRedaction;
import com.kiano.platform.llm.LlmException;
import com.kiano.platform.llm.LlmImage;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.LlmRequest;
import com.kiano.platform.llm.LlmResult;
import com.kiano.platform.llm.LlmRouteStore;
import com.kiano.platform.llm.ResolvedRoute;
import com.kiano.platform.llm.RoutingLlmGateway;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Sends one small real request through the configured route for a purpose
 * (FACT_DRAFT or COPY; unconfigured purposes use the env default). Fact-draft
 * connections attach the vision-probe image and require the model to answer
 * "K", proving the model can actually see images. Failures return an ok=false
 * result with the redacted provider error; they never throw to the caller.
 * Test calls are ledged under the CONNECTION_TEST purpose.
 */
@Component
public class ConnectionTester {

    /** Minimal structured output of a connection test. */
    public record Ping(String answer) {
    }

    public record TestResult(boolean ok, @Nullable String model, long latencyMs,
            @Nullable BigDecimal costUsd, @Nullable String code, @Nullable String message) {
    }

    private static final byte[] VISION_PROBE = load("/llm/vision-probe.png");
    private static final String FACT_DRAFT_PROMPT =
            "Which capital letter is shown in the image? Answer with the letter only.";
    private static final String COPY_PROMPT = "Reply with \"pong\" and nothing else.";

    private final LlmRouteStore store;
    private final RoutingLlmGateway gateway;

    public ConnectionTester(LlmRouteStore store, RoutingLlmGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public TestResult test(CurrentUser user, LlmPurpose purpose) {
        ResolvedRoute route = store.resolve(user.tenantId(), purpose);
        boolean vision = purpose == LlmPurpose.FACT_DRAFT;
        List<LlmImage> images = vision
                ? List.of(new LlmImage(VISION_PROBE, "vision probe (capital K)"))
                : List.of();
        LlmRequest<Ping> request = new LlmRequest<>(user.tenantId(), LlmPurpose.CONNECTION_TEST,
                "You answer short, factual questions.", vision ? FACT_DRAFT_PROMPT : COPY_PROMPT,
                images, Ping.class, LlmRequest.Effort.LOW, 1024);
        long started = System.nanoTime();
        try {
            LlmResult<Ping> result = gateway.completeWith(request, route);
            long latencyMs = latencyMs(started);
            if (!vision) {
                return new TestResult(true, result.model(), latencyMs, result.costUsd(), null,
                        null);
            }
            String answer = result.output().answer();
            boolean containsK = answer != null
                    && answer.replaceAll("\\s", "").toUpperCase().contains("K");
            if (containsK) {
                return new TestResult(true, result.model(), latencyMs, result.costUsd(), null,
                        null);
            }
            return new TestResult(false, result.model(), latencyMs, result.costUsd(),
                    "LLM_VISION_CHECK_FAILED", "The model answered \"" + answer
                            + "\" instead of the letter K; it cannot read images");
        } catch (LlmException ex) {
            return new TestResult(false, null, latencyMs(started), null, ex.code(),
                    ErrorRedaction.clean(ex.getMessage()));
        }
    }

    private static int latencyMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static byte[] load(String path) {
        try (InputStream in = ConnectionTester.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + path, ex);
        }
    }
}