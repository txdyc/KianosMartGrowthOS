package com.kiano.platform.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic {@link LlmGateway} for handler tests: records every request
 * (so tests can assert on images, effort and prompt) and by default returns
 * queued outputs for the requested output type. Tests queue outputs with
 * {@link #queue(Object)} or force a failure with {@link #failWith}.
 */
public class FakeLlmGateway implements LlmGateway {

    public static final String FAKE_MODEL = "claude-opus-5-5";

    private final List<RecordedRequest> recorded = new ArrayList<>();
    private final Deque<Object> outputs = new ArrayDeque<>();
    private volatile LlmException failNext;
    private volatile long llmCallId = 11L;

    @Override
    public <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException {
        recorded.add(new RecordedRequest(request.purpose(), request.system(), request.userText(),
                List.copyOf(request.images()), request.outputType(), request.effort(),
                request.maxTokens()));
        LlmException failure = failNext;
        if (failure != null) {
            failNext = null; // one-shot: only the next call fails
            throw failure;
        }
        @SuppressWarnings("unchecked")
        T output = outputs.isEmpty() ? null : (T) outputs.remove();
        return new LlmResult<>(output, FAKE_MODEL, 100, 50,
                new BigDecimal("0.001400"), llmCallId);
    }

    /** Pushes the next output to return. */
    public void queue(Object output) {
        outputs.add(output);
    }

    /** The very next call fails with this exception (then clears itself). */
    public void failWith(LlmException failure) {
        this.failNext = failure;
    }

    /** llm_call id the fake reports; tests tie it to a real inserted row. */
    public void setLlmCallId(long llmCallId) {
        this.llmCallId = llmCallId;
    }

    /** Clears queue, pending failure and recorded calls (per-test state). */
    public void reset() {
        outputs.clear();
        failNext = null;
        recorded.clear();
    }

    public int callCount() {
        return recorded.size();
    }

    public List<RecordedRequest> recorded() {
        return recorded;
    }

    public RecordedRequest last() {
        return recorded.get(recorded.size() - 1);
    }

    public record RecordedRequest(LlmPurpose purpose, String system, String userText,
            List<LlmImage> images, Class<?> outputType, LlmRequest.Effort effort, long maxTokens) {
    }
}