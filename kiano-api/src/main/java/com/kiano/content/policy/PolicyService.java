package com.kiano.content.policy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.copy.PlainText;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import com.samskivert.mustache.Mustache;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned store policy (C3 §7.1). Each save appends a new version; the
 * latest row is effective. A policy is "complete" only when all five section
 * bodies are non-empty - publishing requires it. renderBlock produces the
 * HTML block for COPY_LONG with every text escaped and line breaks as <p>.
 */
@Service
public class PolicyService {

    public record SectionText(String title, String body, @Nullable String badge) {
    }

    public record PolicyView(int version, Map<PolicySection, SectionText> sections,
            boolean complete, Instant updatedAt) {
    }

    private final StorePolicyMapper mapper;
    private final TemplateRegistry templates;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public PolicyService(StorePolicyMapper mapper, TemplateRegistry templates, AuditLog auditLog,
            ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.templates = templates;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    public Optional<PolicyView> current(long tenantId) {
        StorePolicyEntity latest = latestRow(tenantId);
        return latest == null ? Optional.empty() : Optional.of(toView(latest));
    }

    @Transactional
    public PolicyView save(CurrentUser user, Map<PolicySection, SectionText> sections) {
        Map<PolicySection, SectionText> normalized = normalize(sections);
        StorePolicyEntity latest = latestRow(user.tenantId());
        StorePolicyEntity row = new StorePolicyEntity();
        row.setTenantId(user.tenantId());
        row.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        row.setSectionsJson(objectMapper.writeValueAsString(normalized));
        row.setUpdatedBy(user.userId());
        row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        mapper.insert(row);
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "POLICY_UPDATED", "store_policy",
                String.valueOf(row.getVersion()), null, Map.of("version", row.getVersion()),
                null, "POLICY"));
        events.publishEvent(new PolicyChangedEvent(user.tenantId(), row.getVersion()));
        return toView(row);
    }

    /** Trims badges, blanks become null; rejects too-long or HTML badges. */
    private Map<PolicySection, SectionText> normalize(Map<PolicySection, SectionText> sections) {
        Map<PolicySection, SectionText> normalized = new EnumMap<>(PolicySection.class);
        for (Map.Entry<PolicySection, SectionText> entry : sections.entrySet()) {
            SectionText text = entry.getValue();
            String badge = text.badge() == null ? null : text.badge().trim();
            if (badge != null && badge.isEmpty()) {
                badge = null;
            }
            if (badge != null && badge.length() > 40) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                        "Policy badges must be at most 40 characters");
            }
            if (badge != null && PlainText.hasMarkup(badge)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                        "Policy badges cannot contain HTML");
            }
            normalized.put(entry.getKey(),
                    new SectionText(text.title(), text.body(), badge));
        }
        return normalized;
    }

    /**
     * The short badges the ad trust hook renders: COD, MoMo and delivery must
     * each carry one, otherwise 409 POLICY_BADGES_MISSING with the missing
     * sections listed.
     */
    public Map<PolicySection, String> requireAdBadges(long tenantId) {
        PolicyView view = current(tenantId).orElse(null);
        Map<PolicySection, String> badges = new EnumMap<>(PolicySection.class);
        List<String> missing = new ArrayList<>();
        for (PolicySection section : List.of(PolicySection.COD, PolicySection.MOMO,
                PolicySection.DELIVERY)) {
            SectionText text = view == null ? null : view.sections().get(section);
            String badge = text == null || text.badge() == null ? null
                    : text.badge().trim();
            if (badge == null || badge.isEmpty()) {
                missing.add(section.name());
            } else {
                badges.put(section, badge);
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "POLICY_BADGES_MISSING",
                    "COD, MoMo and delivery badges are required for the ad trust hook",
                    Map.of("missing", missing));
        }
        return badges;
    }

    /** Latest policy or 409 POLICY_INCOMPLETE listing the empty sections. */
    public PolicyView requireComplete(long tenantId) {
        PolicyView view = current(tenantId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "POLICY_INCOMPLETE",
                        "The store policy has not been set up",
                        Map.of("missing", PolicySection.values())));
        if (!view.complete()) {
            List<String> missing = new ArrayList<>();
            for (PolicySection section : PolicySection.values()) {
                if (view.sections().get(section) == null
                        || view.sections().get(section).body() == null
                        || view.sections().get(section).body().isBlank()) {
                    missing.add(section.name());
                }
            }
            throw new ApiException(HttpStatus.CONFLICT, "POLICY_INCOMPLETE",
                    "The store policy is incomplete; fill every section before publishing",
                    Map.of("missing", missing));
        }
        return view;
    }

    /**
     * Renders the POLICY_BLOCK template with all active sections. Text is
     * escaped by JMustache's default {{ }} and line breaks become <p>.
     */
    public String renderBlock(long tenantId) {
        String body = templates.latestApproved(tenantId, "POLICY_BLOCK")
                .map(template -> template.getBody())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                        "No approved POLICY_BLOCK template"));
        PolicyView view = current(tenantId).orElse(null);
        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        if (view != null) {
            List<PolicySection> order = List.of(PolicySection.values());
            view.sections().entrySet().stream()
                    .filter(entry -> entry.getValue() != null && entry.getValue().body() != null
                            && !entry.getValue().body().isBlank())
                    .sorted((a, b) -> Integer.compare(order.indexOf(a.getKey()),
                            order.indexOf(b.getKey())))
                    .forEach(entry -> {
                        Map<String, Object> section = new LinkedHashMap<>();
                        section.put("title", entry.getValue().title() == null
                                ? "" : entry.getValue().title());
                        section.put("paragraphs", paragraphs(entry.getValue().body()));
                        sections.add(section);
                    });
        }
        model.put("sections", sections);
        return Mustache.compiler().escapeHTML(true).compile(body).execute(model);
    }

    private static List<String> paragraphs(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private @Nullable StorePolicyEntity latestRow(long tenantId) {
        return mapper.selectOne(Wrappers.<StorePolicyEntity>lambdaQuery()
                .eq(StorePolicyEntity::getTenantId, tenantId)
                .orderByDesc(StorePolicyEntity::getVersion)
                .last("limit 1"));
    }

    private PolicyView toView(StorePolicyEntity row) {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        objectMapper.readTree(row.getSectionsJson()).properties().forEach(entry -> {
            String name = entry.getKey();
            String title = entry.getValue().path("title").asText("");
            String body = entry.getValue().path("body").asText("");
            String badge = entry.getValue().path("badge").isTextual()
                    ? entry.getValue().path("badge").asText() : null;
            sections.put(PolicySection.valueOf(name), new SectionText(title, body, badge));
        });
        boolean complete = true;
        for (PolicySection section : PolicySection.values()) {
            SectionText text = sections.get(section);
            if (text == null || text.body() == null || text.body().isBlank()) {
                complete = false;
                break;
            }
        }
        return new PolicyView(row.getVersion(), sections, complete,
                row.getCreatedAt().toInstant());
    }
}