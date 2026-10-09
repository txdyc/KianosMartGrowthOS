package com.kiano.content.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review sessions (C6 Task 5): the two workbenches report their active time,
 * the service clamps it and aggregates it per SKU. A heartbeat adds at most
 * 20 s, and a delta sent after more than 60 s of silence counts as 0 - a
 * suspended tab or an idle reviewer can never inflate the metric
 * (Review Focus 5). {@link ReviewTimeStats} feeds the dashboard (Task 4) and
 * the two stations (Task 8), and the acceptance threshold is 180 s per SKU.
 */
@Service
public class ReviewSessionService {

    /** One SKU's review time as shown on the dashboard and the stations. */
    public record ReviewTimeStats(int skuCount, double avgFactSeconds, double avgAssetSeconds,
            double avgTotalSeconds, double p90TotalSeconds, int targetSeconds) {
    }

    /** One session as the workbench sees it after a beat or a finish. */
    public record SessionView(long id, double activeSeconds, @Nullable String outcome) {
    }

    public static final int TARGET_SECONDS = 180;
    private static final Set<String> KINDS = Set.of("FACT", "ASSETS");
    private static final Set<String> OUTCOMES =
            Set.of("LOCKED", "APPROVED_ALL", "SKIPPED", "LEFT");
    private static final double MAX_DELTA_SECONDS = 20;
    private static final long SILENCE_SECONDS = 60;

    private final ReviewSessionMapper mapper;
    private final ProductCatalog productCatalog;

    public ReviewSessionService(ReviewSessionMapper mapper, ProductCatalog productCatalog) {
        this.mapper = mapper;
        this.productCatalog = productCatalog;
    }

    /** Opens a session for one SKU; the workbench then sends heartbeats. */
    @Transactional
    public long start(CurrentUser user, long productId, @Nullable Long batchId, String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "kind must be FACT or ASSETS");
        }
        productCatalog.findById(user.tenantId(), productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        ReviewSessionEntity session = new ReviewSessionEntity();
        session.setTenantId(user.tenantId());
        session.setUserId(user.userId());
        session.setProductId(productId);
        session.setBatchId(batchId);
        session.setKind(kind);
        session.setActiveSeconds(0.0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        session.setStartedAt(now);
        session.setLastHeartbeatAt(now);
        mapper.insert(session);
        return session.getId();
    }

    /**
     * Adds the active seconds since the last beat: clamped to [0, 20], and 0
     * when more than 60 s passed since the last heartbeat.
     */
    @Transactional
    public SessionView heartbeat(CurrentUser user, long sessionId, double activeSecondsDelta) {
        ReviewSessionEntity session = owned(user, sessionId);
        session.setActiveSeconds(session.getActiveSeconds() + heartbeatDelta(session,
                activeSecondsDelta));
        session.setLastHeartbeatAt(OffsetDateTime.now(ZoneOffset.UTC));
        mapper.updateById(session);
        return view(session);
    }

    /** Closes the session; the final delta is clamped the same way. */
    @Transactional
    public SessionView finish(CurrentUser user, long sessionId, double finalDelta, String outcome) {
        if (outcome == null || !OUTCOMES.contains(outcome)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "outcome must be one of " + OUTCOMES);
        }
        ReviewSessionEntity session = owned(user, sessionId);
        session.setActiveSeconds(session.getActiveSeconds() + clamp(finalDelta));
        session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        session.setOutcome(outcome);
        mapper.updateById(session);
        return view(session);
    }

    /**
     * Per-SKU review time of a batch: finished sessions are summed per SKU,
     * and only SKUs that finished both a FACT and an ASSETS session count.
     */
    public ReviewTimeStats stats(long tenantId, long batchId) {
        Map<Long, Map<String, Double>> byProduct = new LinkedHashMap<>();
        for (ReviewSessionEntity session : mapper.selectList(
                Wrappers.<ReviewSessionEntity>lambdaQuery()
                        .eq(ReviewSessionEntity::getTenantId, tenantId)
                        .eq(ReviewSessionEntity::getBatchId, batchId)
                        .isNotNull(ReviewSessionEntity::getFinishedAt))) {
            byProduct.computeIfAbsent(session.getProductId(), key -> new LinkedHashMap<>())
                    .merge(session.getKind(), session.getActiveSeconds(), Double::sum);
        }
        List<Double> fact = new ArrayList<>();
        List<Double> assets = new ArrayList<>();
        List<Double> totals = new ArrayList<>();
        for (Map<String, Double> kinds : byProduct.values()) {
            if (kinds.containsKey("FACT") && kinds.containsKey("ASSETS")) {
                fact.add(kinds.get("FACT"));
                assets.add(kinds.get("ASSETS"));
                totals.add(kinds.get("FACT") + kinds.get("ASSETS"));
            }
        }
        return new ReviewTimeStats(totals.size(), average(fact), average(assets), average(totals),
                percentile90(totals), TARGET_SECONDS);
    }

    /** 404 when the session is missing or foreign; 409 when already finished. */
    private ReviewSessionEntity owned(CurrentUser user, long sessionId) {
        ReviewSessionEntity session = mapper.selectOne(Wrappers.<ReviewSessionEntity>lambdaQuery()
                .eq(ReviewSessionEntity::getTenantId, user.tenantId())
                .eq(ReviewSessionEntity::getId, sessionId));
        if (session == null || !session.getUserId().equals(user.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Review session not found");
        }
        if (session.getFinishedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_FINISHED",
                    "This review session has already finished");
        }
        return session;
    }

    private static double heartbeatDelta(ReviewSessionEntity session, double raw) {
        Instant last = session.getLastHeartbeatAt().toInstant();
        if (Duration.between(last, Instant.now()).getSeconds() > SILENCE_SECONDS) {
            // a suspended tab back-fills its delta: it does not count
            return 0;
        }
        return clamp(raw);
    }

    private static double clamp(double delta) {
        return Math.max(0, Math.min(MAX_DELTA_SECONDS, delta));
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /** Nearest-rank P90 of the per-SKU totals. */
    private static double percentile90(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(0.9 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static SessionView view(ReviewSessionEntity session) {
        return new SessionView(session.getId(), session.getActiveSeconds(), session.getOutcome());
    }
}