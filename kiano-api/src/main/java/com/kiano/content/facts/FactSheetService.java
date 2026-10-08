package com.kiano.content.facts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.AppUserEntity;
import com.kiano.platform.auth.AppUserMapper;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned product fact sheets with the G2 lock flow (spec §9.2): a DRAFT
 * is saved in place, LOCK requires all six confirmation items, non-empty
 * model and warranty, and the current draft version; the previous LOCKED
 * sheet becomes SUPERSEDED and a {@link FactLockedEvent} is published for
 * the downstream derivation.
 */
@Service
public class FactSheetService {

    /** Simplified view of a fact sheet row for the API. */
    public record FactSheetView(long id, long productId, int version, String status,
            FactsJson facts, Map<String, FieldSource> fieldSources, List<Long> sourceMediaIds,
            @Nullable Instant lockedAt, @Nullable String lockedBy) {
    }

    private static final List<String> REQUIRED_CONFIRMATIONS =
            List.of("model", "capacity", "powerW", "voltage", "warranty", "inBox");

    private final FactSheetMapper mapper;
    private final SourceMediaMapper sourceMediaMapper;
    private final AppUserMapper appUserMapper;
    private final ProductCatalog productCatalog;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public FactSheetService(FactSheetMapper mapper, SourceMediaMapper sourceMediaMapper,
            AppUserMapper appUserMapper, ProductCatalog productCatalog, AuditLog auditLog,
            ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.sourceMediaMapper = sourceMediaMapper;
        this.appUserMapper = appUserMapper;
        this.productCatalog = productCatalog;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    /** The DRAFT if one exists, otherwise the LOCKED sheet. */
    public Optional<FactSheetView> current(long tenantId, long productId) {
        List<FactSheetEntity> rows = find(tenantId, productId, "DRAFT");
        if (rows.isEmpty()) {
            rows = find(tenantId, productId, "LOCKED");
        }
        return rows.stream().map(this::toView).findFirst();
    }

    public Optional<FactSheetView> locked(long tenantId, long productId) {
        return find(tenantId, productId, "LOCKED").stream().map(this::toView).findFirst();
    }

    /** Creates version max+1 on first save, otherwise updates the draft in place. */
    @Transactional
    public FactSheetView saveDraft(CurrentUser user, long productId, FactsJson facts,
            Map<String, FieldSource> fieldSources) {
        requireProduct(user.tenantId(), productId);
        FactSheetEntity existing = find(user.tenantId(), productId, "DRAFT").stream().findFirst()
                .orElse(null);
        boolean creating = existing == null;
        FactSheetEntity row = existing != null ? existing : new FactSheetEntity();
        if (creating) {
            row.setTenantId(user.tenantId());
            row.setProductId(productId);
            row.setVersion(nextVersion(user.tenantId(), productId));
            row.setCreatedBy(user.userId());
        }
        row.setFactsJson(objectMapper.writeValueAsString(facts));
        row.setFieldSources(objectMapper.writeValueAsString(fieldMapToNames(fieldSources)));
        row.setStatus("DRAFT");
        if (creating) {
            row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "FACTS_DRAFT_SAVED", "product",
                String.valueOf(productId),
                Map.of("version", row.getVersion()), Map.of("fields", facts), null, "FACTS"));
        return current(user.tenantId(), productId).orElseThrow();
    }

    /** Creates the draft produced by an LLM fact-draft call (no draft may exist). */
    @Transactional
    public FactSheetView createDraftFromLlm(long tenantId, long productId, FactsJson facts,
            Map<String, FieldSource> fieldSources, List<Long> sourceMediaIds,
            @Nullable Long llmCallId, List<String> unreadable) {
        if (!find(tenantId, productId, "DRAFT").isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "FACT_DRAFT_EXISTS",
                    "A fact draft already exists; lock or discard it before generating again");
        }
        FactSheetEntity row = new FactSheetEntity();
        row.setTenantId(tenantId);
        row.setProductId(productId);
        row.setVersion(nextVersion(tenantId, productId));
        row.setFactsJson(objectMapper.writeValueAsString(facts));
        row.setFieldSources(objectMapper.writeValueAsString(fieldMapToNames(fieldSources)));
        row.setSourceRefs(objectMapper.writeValueAsString(sourceMediaIds == null
                ? List.of() : sourceMediaIds));
        row.setStatus("DRAFT");
        row.setLlmCallId(llmCallId);
        row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "FACT_DRAFT_EXISTS",
                    "A fact draft already exists");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", row.getVersion());
        after.put("unreadable", unreadable == null ? List.of() : unreadable);
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                "FACTS_DRAFT_FROM_AI", "product", String.valueOf(productId), null, after,
                null, "FACTS"));
        return toView(row);
    }

    /** Confirms and locks the draft (G2). Rules in the class javadoc. */
    @Transactional
    public FactSheetView lock(CurrentUser user, long productId, int draftVersion,
            Set<String> confirmedFields) {
        List<String> missing = REQUIRED_CONFIRMATIONS.stream()
                .filter(field -> !confirmedFields.contains(field)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FACTS_NOT_CONFIRMED",
                    "All six required facts must be confirmed before locking",
                    Map.of("missing", missing));
        }
        FactSheetEntity draft = find(user.tenantId(), productId, "DRAFT").stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "FACT_DRAFT_CHANGED",
                        "No fact draft exists to lock"));
        if (draft.getVersion() != draftVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "FACT_DRAFT_CHANGED",
                    "The draft has been modified by someone else; refresh and confirm again",
                    Map.of("expectedVersion", draftVersion, "actualVersion", draft.getVersion()));
        }
        FactsJson facts = parseFacts(draft.getFactsJson());
        if (isBlank(facts.model()) || isBlank(facts.warranty())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FACTS_INCOMPLETE",
                    "model and warranty must be filled before locking");
        }
        for (FactSheetEntity locked : find(user.tenantId(), productId, "LOCKED")) {
            locked.setStatus("SUPERSEDED");
            mapper.updateById(locked);
        }
        draft.setStatus("LOCKED");
        draft.setLockedBy(user.userId());
        draft.setLockedAt(OffsetDateTime.now(ZoneOffset.UTC));
        mapper.updateById(draft);
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "FACTS_LOCKED", "product",
                String.valueOf(productId),
                Map.of("version", draft.getVersion()),
                Map.of("model", facts.model(), "confirmedFields", new ArrayList<>(confirmedFields)),
                null, "FACTS"));
        events.publishEvent(new FactLockedEvent(user.tenantId(), productId, draft.getVersion()));
        return toView(draft);
    }

    private List<FactSheetEntity> find(long tenantId, long productId, String status) {
        return mapper.selectList(Wrappers.<FactSheetEntity>lambdaQuery()
                .eq(FactSheetEntity::getTenantId, tenantId)
                .eq(FactSheetEntity::getProductId, productId)
                .eq(FactSheetEntity::getStatus, status));
    }

    private int nextVersion(long tenantId, long productId) {
        FactSheetEntity latest = mapper.selectOne(Wrappers.<FactSheetEntity>lambdaQuery()
                .eq(FactSheetEntity::getTenantId, tenantId)
                .eq(FactSheetEntity::getProductId, productId)
                .orderByDesc(FactSheetEntity::getVersion)
                .last("limit 1"));
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    private void requireProduct(long tenantId, long productId) {
        productCatalog.findById(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
    }

    private FactSheetView toView(FactSheetEntity row) {
        FactsJson facts = parseFacts(row.getFactsJson());
        Map<String, FieldSource> sources = new LinkedHashMap<>();
        JsonNode sourceJson = objectMapper.readTree(row.getFieldSources());
        sourceJson.properties().forEach(entry -> sources.put(entry.getKey(),
                FieldSource.valueOf(entry.getValue().asString())));
        List<Long> mediaIds = new ArrayList<>();
        objectMapper.readTree(row.getSourceRefs()).forEach(node -> mediaIds.add(node.asLong()));
        String lockedBy = null;
        if (row.getLockedBy() != null) {
            AppUserEntity user = appUserMapper.selectById(row.getLockedBy());
            lockedBy = user == null ? null : user.getEmail();
        }
        return new FactSheetView(row.getId(), row.getProductId(), row.getVersion(),
                row.getStatus(), facts, sources, mediaIds,
                row.getLockedAt() == null ? null : row.getLockedAt().toInstant(), lockedBy);
    }

    /** Serializes sources as {field: "SOURCE_NAME"} for the jsonb column. */
    private static Map<String, String> fieldMapToNames(Map<String, FieldSource> fieldSources) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, FieldSource> entry : fieldSources.entrySet()) {
            names.put(entry.getKey(),
                    entry.getValue() == null ? FieldSource.NONE.name() : entry.getValue().name());
        }
        return names;
    }

    private FactsJson parseFacts(String json) {
        return objectMapper.readValue(json, FactsJson.class);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}