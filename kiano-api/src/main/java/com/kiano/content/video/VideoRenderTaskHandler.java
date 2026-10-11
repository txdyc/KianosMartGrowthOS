package com.kiano.content.video;

import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.web.ApiException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Queue handler for VIDEO_RENDER: the heavy part of a video render - footage
 * download, probing and window ranking, overlay rendering, music pick and the
 * H3/compose job creation - runs here instead of inside the HTTP request or the
 * review transaction that asked for it. Payload: {@code productId}, {@code types}
 * (wire names), {@code candidate} and the requesting {@code userId}. A business
 * error (preconditions changed meanwhile, footage too short, ...) fails the task
 * without retries; the ads-style render-status endpoint shows it.
 */
@Component
public class VideoRenderTaskHandler implements TaskHandler {

    public static final String TYPE = "VIDEO_RENDER";

    private final VideoRenderService renderService;

    public VideoRenderTaskHandler(VideoRenderService renderService) {
        this.renderService = renderService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) {
        JsonNode payload = ctx.payload();
        long productId = payload.path("productId").asLong();
        Set<VideoType> types = EnumSet.noneOf(VideoType.class);
        payload.path("types").forEach(node -> types.add(VideoType.fromWire(node.asString())));
        int candidate = payload.path("candidate").asInt(0);
        Long userId = payload.path("userId").isNumber() ? payload.path("userId").asLong() : null;
        try {
            long runId = renderService.render(ctx.tenantId(), userId, productId, types, candidate);
            return Map.of("runId", runId);
        } catch (ApiException ex) {
            if (ex.getStatus().is4xxClientError()) {
                throw new NonRetryableTaskException(ex.getCode() + ": " + ex.getMessage());
            }
            throw ex;
        }
    }
}
