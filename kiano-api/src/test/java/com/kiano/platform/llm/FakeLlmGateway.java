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
    private volatile LlmException failWith;

    @Override
    public <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException {
        recorded.add(new RecordedRequest(request.purpose(), request.system(), request.userText(),
                List.copyOf(request.images()), request.outputType(), request.effort(),
                request.maxTokens()));
        if (failWith != null) {
            throw failWith;
        }
        @SuppressWarnings("unchecked")
        T output = outputs.isEmpty() ? null : (T) outputs.remove();
        return new LlmResult<>(output, FAKE_MODEL, 100, 50,
                new BigDecimal("0.001400"), 11L);
    }

    /** Pushes the next output to return. */
    public void queue(Object output) {
        outputs.add(output);
    }

    /** Fail the next call with this exception (sticky until cleared). */
    public void failWith(LlmException failure) {
        this.failWith = failure;
    }

    public void clearFailure() {
        this.failWith = null;
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