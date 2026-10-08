package com.kiano.platform.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.Usage;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Claude implementation of {@link LlmGateway} built on the official
 * anthropic-java SDK. Structured output is parsed straight into the requested
 * record class; images are sent base64-JPEG before the text block; thinking
 * depth is controlled with OutputConfig.effort only (Opus 5.5 rejects
 * ThinkingConfigDisabled and budget tokens); server-side fallback is enabled
 * via the anthropic-beta header and a "fallbacks":"default" body field.
 * Every call - success or failure - is written to llm_call by the
 * {@link LlmCallRecorder}. Failures after the SDK's own retries map to
 * LLM_UNAVAILABLE; 401/403/400 are configuration mistakes and never retried.
 */
@Component
public class AnthropicLlmGateway implements LlmGateway {

    static final String BETA_FALLBACK_HEADER = "server-side-fallback-2026-07-01";
    static final String BODY_FALLBACKS = "default";
    private static final int SDK_MAX_RETRIES = 2;
    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000L);

    private final LlmProperties properties;
    private final LlmCallRecorder recorder;
    private final @Nullable String baseUrl;

    private volatile @Nullable AnthropicClient client;

    @Autowired
    public AnthropicLlmGateway(LlmProperties properties, LlmCallRecorder recorder) {
        this(properties, recorder, null);
    }

    /** Package-private for tests: points the SDK at a WireMock server. */
    AnthropicLlmGateway(LlmProperties properties, LlmCallRecorder recorder,
            @Nullable String baseUrl) {
        this.properties = properties;
        this.recorder = recorder;
        this.baseUrl = baseUrl;
    }

    @Override
    public <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException {
        long started = System.nanoTime();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            int latencyMs = latencyMs(started);
            recorder.record(errorRow(request, latencyMs, null,
                    "LLM_NOT_CONFIGURED: ANTHROPIC_API_KEY is not set"));
            throw new LlmException("LLM_NOT_CONFIGURED",
                    "ANTHROPIC_API_KEY is not set; configure it in .env to enable Claude calls",
                    false);
        }
        try {
            StructuredMessageCreateParams<T> params = params(request);
            StructuredMessage<T> message = client().messages().create(params);
            int latencyMs = latencyMs(started);
            return finish(request, message, latencyMs);
        } catch (AnthropicServiceException ex) {
            int latencyMs = latencyMs(started);
            throw mapServiceError(request, ex, latencyMs);
        } catch (AnthropicInvalidDataException ex) {
            int latencyMs = latencyMs(started);
            recorder.record(errorRow(request, latencyMs, null, ex.getMessage()));
            throw new LlmException("LLM_INVALID_OUTPUT",
                    "Claude returned an output that does not match the requested schema: "
                            + ex.getMessage(), false);
        } catch (RuntimeException ex) {
            int latencyMs = latencyMs(started);
            recorder.record(errorRow(request, latencyMs, null, ex.getMessage()));
            throw new LlmException("LLM_UNAVAILABLE",
                    "Claude call failed: " + ex.getMessage(), true);
        }
    }

    /** stop_reason handling, usage/cost ledger and structured result. */
    private <T> LlmResult<T> finish(LlmRequest<T> request, StructuredMessage<T> message,
            int latencyMs) throws LlmException {
        StopReason stopReason = message.stopReason().orElse(null);
        String stopReasonText = stopReason == null ? null : stopReason.asString();
        Usage usage = message.usage();
        long input = usage.inputTokens();
        long output = usage.outputTokens();
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        long cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
        if (stopReason == StopReason.REFUSAL) {
            String category = message.stopDetails().flatMap(d -> d.category())
                    .map(c -> c.asString()).orElse(null);
            recorder.record(new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                    properties.getModel(), "REFUSED", input, output, cacheRead, cacheWrite,
                    null, latencyMs, stopReasonText, null));
            throw new LlmRefusedException("Claude refused the request"
                    + (category == null ? "" : " (category: " + category + ")"), category);
        }
        if (stopReason == StopReason.MAX_TOKENS) {
            recorder.record(new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                    properties.getModel(), "TRUNCATED", input, output, cacheRead, cacheWrite,
                    null, latencyMs, stopReasonText, null));
            throw new LlmTruncatedException(
                    "Claude hit max_tokens; retry with a doubled token budget");
        }
        T parsed = parse(request.outputType(), message);
        BigDecimal cost = cost(input, output, cacheRead);
        long callId = recorder.record(new LlmCallRecorder.LlmCallRow(request.tenantId(),
                request.purpose(), properties.getModel(), "OK", input, output, cacheRead,
                cacheWrite, cost, latencyMs, stopReasonText, null));
        return new LlmResult<>(parsed, properties.getModel(), input, output, cost, callId);
    }

    private <T> T parse(Class<T> outputType, StructuredMessage<T> message)
            throws LlmException {
        List<T> outputs = new ArrayList<>();
        for (var block : message.content()) {
            Optional<StructuredTextBlock<T>> text = block.text();
            if (text.isPresent() && text.get().text() != null) {
                outputs.add(text.get().text());
            }
        }
        if (outputs.isEmpty()) {
            throw new LlmException("LLM_INVALID_OUTPUT",
                    "Claude returned no parseable structured output", false);
        }
        return outputs.get(0);
    }

    private <T> StructuredMessageCreateParams<T> params(LlmRequest<T> request) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (LlmImage image : request.images()) {
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofBase64(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.getEncoder().encodeToString(image.jpeg()))
                            .build()))
                    .build()));
        }
        blocks.add(ContentBlockParam.ofText(request.userText()));
        return MessageCreateParams.builder()
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(toSdkEffort(request.effort()))
                        .format(request.outputType(), JsonSchemaLocalValidation.YES)
                        .build())
                .model(properties.getModel())
                .maxTokens(request.maxTokens())
                .system(request.system())
                .putAdditionalHeader("anthropic-beta", BETA_FALLBACK_HEADER)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from(BODY_FALLBACKS))
                .addUserMessageOfBlockParams(blocks)
                .build();
    }

    private static OutputConfig.Effort toSdkEffort(LlmRequest.Effort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
        };
    }

    private LlmException mapServiceError(LlmRequest<?> request, AnthropicServiceException ex,
            int latencyMs) {
        int status = ex.statusCode();
        if (status == 400) {
            recorder.record(errorRow(request, latencyMs, null, ex.getMessage()));
            return new LlmException("LLM_BAD_REQUEST",
                    "Claude rejected the request (HTTP 400): " + ex.getMessage(), false);
        }
        if (status == 401 || status == 403) {
            recorder.record(errorRow(request, latencyMs, null, ex.getMessage()));
            return new LlmException("LLM_CONFIG",
                    "Claude rejected the API key (HTTP " + status + "): " + ex.getMessage(),
                    false);
        }
        recorder.record(errorRow(request, latencyMs, null, ex.getMessage()));
        return new LlmException("LLM_UNAVAILABLE",
                "Claude is unavailable after " + SDK_MAX_RETRIES + " SDK retries (HTTP " + status
                        + "): " + ex.getMessage(),
                true);
    }

    /** Failure rows always use the ERROR status; the failing code is kept in error. */
    private LlmCallRecorder.LlmCallRow errorRow(LlmRequest<?> request, int latencyMs,
            @Nullable String stopReason, @Nullable String error) {
        return new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                properties.getModel(), "ERROR", 0, 0, 0, 0, null, latencyMs, stopReason, error);
    }

    /** USD cost = in×P_in + out×P_out + cacheRead×P_cache, per million tokens. */
    private @Nullable BigDecimal cost(long input, long output, long cacheRead) {
        LlmProperties.Pricing pricing = properties.getPricing().get(properties.getModel());
        if (pricing == null) {
            return null;
        }
        BigDecimal cost = BigDecimal.valueOf(input)
                .multiply(pricing.getInputPerMtok())
                .add(BigDecimal.valueOf(output).multiply(pricing.getOutputPerMtok()))
                .add(BigDecimal.valueOf(cacheRead).multiply(pricing.getCacheReadPerMtok()));
        return cost.divide(PER_MILLION, 6, RoundingMode.HALF_UP);
    }

    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                        .apiKey(properties.getApiKey())
                        .maxRetries(SDK_MAX_RETRIES);
                if (baseUrl != null) {
                    builder.baseUrl(baseUrl);
                }
                client = builder.build();
            }
            return client;
        }
    }

    private static int latencyMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }

    @PreDestroy
    public void close() {
        AnthropicClient current = client;
        if (current != null) {
            current.close();
        }
    }
}