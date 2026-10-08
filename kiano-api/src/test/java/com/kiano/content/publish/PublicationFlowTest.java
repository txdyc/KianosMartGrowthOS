package com.kiano.content.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.CommercePublisherFactory;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooMedia;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.WooProductSnapshot.RankMath;
import com.kiano.commerce.WooProductSnapshot.WooImageRef;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.queue.TaskDispatcher;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end publishing against a real database: request() gates, the
 * PUBLISH_PRODUCT task run through the real queue, asset status transitions
 * and rollback. Only Woo is faked (stateful in-memory product + media).
 * Covers the C3 review findings: exact text selection from the recorded
 * asset set, copy marked PUBLISHED, republish after PUBLISHED, rollback with
 * publication ids above the Long cache, Rank Math cleared on rollback,
 * atomic status updates, published_by, plus the staging gate and Woo change
 * detection (plan Review Focus 2 and 5).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicationFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PublicationService service;
    @Autowired private PolicyService policyService;
    @Autowired private TaskDispatcher dispatcher;
    @Autowired private ObjectStorage storage;

    @MockitoBean private CommercePublisherFactory publisherFactory;
    @MockitoSpyBean private AuditLog auditLog;

    private FakeWoo staging;
    private FakeWoo production;
    private long tenantId;
    private long productId;
    private CurrentUser owner;
    private CurrentUser operator;
    private int policyVersion;
    private long mainId;
    private long titleId;
    private long shortId;
    private long longId;

    @BeforeEach
    void seed() {
        for (String table : List.of("platform_task", "publication", "asset_review", "asset",
                "store_policy", "generation_job", "generation_run", "source_media",
                "product_fact_sheet", "llm_call", "product_profile", "product_category",
                "product", "category", "store", "app_user")) {
            jdbc.update("delete from " + table);
        }
        tenantId = jdbc.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        long storeId = jdbc.queryForObject("insert into store (tenant_id, platform, base_url) "
                + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id", Long.class, tenantId);
        productId = jdbc.queryForObject("insert into product (tenant_id, store_id, external_id, "
                + "type, sku, name, status, synced_at) values (?, ?, -9100, 'simple', 'MG-BL200', "
                + "'Morgan Blender', 'publish', now()) returning id", Long.class, tenantId, storeId);
        long ownerId = user("owner@flow.test", "OWNER");
        long operatorId = user("op@flow.test", "OPERATOR");
        owner = new CurrentUser(ownerId, tenantId, Role.OWNER, "owner@flow.test");
        operator = new CurrentUser(operatorId, tenantId, Role.OPERATOR, "op@flow.test");

        policyVersion = savePolicy("Accra & Tema: 1-3 days").version();

        mainId = image("PAGE_MAIN", "main", 1, "APPROVED");
        image("PAGE_ANGLE", "P2", 1, "APPROVED");
        image("PAGE_ANGLE", "P3", 1, "APPROVED");
        image("PAGE_ANGLE", "P4", 1, "APPROVED");
        titleId = text("COPY_TITLE", 1, "APPROVED", "Morgan MG-BL200 Blender", null);
        shortId = text("COPY_SHORT", 1, "APPROVED", "<ul><li>Fast</li></ul>", null);
        longId = text("COPY_LONG", 1, "APPROVED", "<p>Long v1</p>", policyVersion);

        staging = new FakeWoo(new RankMath("Old SEO", "Old SEO desc"));
        production = new FakeWoo(new RankMath("Old SEO", "Old SEO desc"));
        when(publisherFactory.forEnvironment(tenantId, PublishEnvironment.STAGING)).thenReturn(staging);
        when(publisherFactory.forEnvironment(tenantId, PublishEnvironment.PRODUCTION))
                .thenReturn(production);
    }

    // ---- fixtures ----

    private long user(String email, String role) {
        return jdbc.queryForObject("insert into app_user (tenant_id, email, name, password_hash, role) "
                + "values (?, ?, 'U', 'x', ?) returning id", Long.class, tenantId, email, role);
    }

    private PolicyService.PolicyView savePolicy(String deliveryBody) {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        for (PolicySection section : PolicySection.values()) {
            sections.put(section, new SectionText(section.name(),
                    section == PolicySection.DELIVERY ? deliveryBody : "Body " + section, null));
        }
        PolicyService.PolicyView view = policyService.save(owner, sections);
        // A policy save enqueues POLICY_RERENDER (covered by PolicyRerenderTest);
        // drop it so these tests control exactly which COPY_LONG versions exist.
        jdbc.update("delete from platform_task where type = 'POLICY_RERENDER'");
        return view;
    }

    private long image(String spec, String variant, int version, String status) {
        String key = "t" + tenantId + "/assets/" + productId + "/" + spec + "/" + variant + "/v"
                + version + ".jpg";
        storage.put(key, new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}, "image/jpeg");
        return jdbc.queryForObject("insert into asset (tenant_id, product_id, spec_code, variant, "
                + "version, kind, object_key, width, height, status, precheck_json, provenance_json, "
                + "file_name, created_at) values (?, ?, ?, ?, ?, 'IMAGE', ?, 1600, 1600, ?, '{}', '{}', "
                + "?, now()) returning id", Long.class, tenantId, productId, spec, variant, version,
                key, status, "MG-BL200_" + variant + "_real_1600x1600_v" + version + ".jpg");
    }

    private long text(String spec, int version, String status, String body,
            Integer contentPolicyVersion) {
        String content = contentPolicyVersion == null ? null
                : "{\"policyVersion\":" + contentPolicyVersion + "}";
        return jdbc.queryForObject("insert into asset (tenant_id, product_id, spec_code, variant, "
                + "version, kind, text_body, content_json, status, precheck_json, provenance_json, "
                + "fact_version, created_at) values (?, ?, ?, 'default', ?, 'TEXT', ?, ?::jsonb, ?, "
                + "'{}', '{}', 1, now()) returning id", Long.class, tenantId, productId, spec,
                version, body, content, status);
    }

    private String status(long assetId) {
        return jdbc.queryForObject("select status from asset where id = ?", String.class, assetId);
    }

    private PublicationEntityView publish(CurrentUser user, PublishEnvironment env) {
        long id = service.request(user, productId, env);
        int ran = 0;
        while (dispatcher.pollOnce()) {
            ran++;
        }
        assertThat(ran).isPositive();
        return PublicationEntityView.load(jdbc, id);
    }

    // ---- request gates (plan Review Focus 5) ----

    @Test
    void production_withoutStaging_409StagingRequired() {
        assertThatThrownBy(() -> service.request(owner, productId, PublishEnvironment.PRODUCTION))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("STAGING_REQUIRED"));
    }

    @Test
    void production_assetSetChangedSinceStaging_409WithDiff() {
        publish(operator, PublishEnvironment.STAGING);
        long newTitle = text("COPY_TITLE", 2, "APPROVED", "Morgan MG-BL200 Blender 2", null);
        jdbc.update("update asset set status = 'ARCHIVED' where id = ?", titleId);

        assertThatThrownBy(() -> service.request(owner, productId, PublishEnvironment.PRODUCTION))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("STAGING_REQUIRED");
                    @SuppressWarnings("unchecked")
                    Map<String, List<Long>> diff = (Map<String, List<Long>>) ex.getDetails().get("diff");
                    assertThat(diff.get("added")).containsExactly(newTitle);
                    assertThat(diff.get("removed")).containsExactly(titleId);
                });
    }

    @Test
    void production_requiresOwner_403() {
        assertThatThrownBy(() -> service.request(operator, productId, PublishEnvironment.PRODUCTION))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void copyRenderedAgainstOlderPolicy_409() {
        savePolicy("Accra & Tema: same day");
        assertThatThrownBy(() -> service.request(operator, productId, PublishEnvironment.STAGING))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COPY_POLICY_OUTDATED"));
    }

    @Test
    void missingAngles_422Preconditions() {
        jdbc.update("update asset set status = 'REJECTED' where spec_code = 'PAGE_ANGLE' "
                + "and variant = 'P4'");
        assertThatThrownBy(() -> service.request(operator, productId, PublishEnvironment.STAGING))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PUBLISH_PRECONDITIONS"));
    }

    // ---- review finding 1: exact text from the recorded set ----

    @Test
    void olderApprovedCopyVersion_isNeitherRecordedNorPublished() {
        // A policy change leaves v1 APPROVED next to an auto-approved v2.
        int newPolicy = savePolicy("Accra & Tema: same day").version();
        long longV2 = text("COPY_LONG", 2, "APPROVED", "<p>Long v2</p>", newPolicy);

        PublicationEntityView pub = publish(operator, PublishEnvironment.STAGING);

        assertThat(pub.status()).isEqualTo("APPLIED");
        assertThat(pub.assetIds()).contains(longV2).doesNotContain(longId);
        assertThat(staging.product.description()).isEqualTo("<p>Long v2</p>");
    }

    // ---- review finding 2: copy marked PUBLISHED ----

    @Test
    void production_marksCopyAssetsPublishedToo() {
        publish(operator, PublishEnvironment.STAGING);
        PublicationEntityView pub = publish(owner, PublishEnvironment.PRODUCTION);

        assertThat(pub.status()).isEqualTo("APPLIED");
        assertThat(List.of(mainId, titleId, shortId, longId))
                .allSatisfy(id -> assertThat(status(id)).isEqualTo("PUBLISHED"));
    }

    // ---- review finding 3: republish after PUBLISHED ----

    @Test
    void republish_afterNewCopyVersion_usesLivePublishedAssets() {
        publish(operator, PublishEnvironment.STAGING);
        publish(owner, PublishEnvironment.PRODUCTION);
        long longV2 = text("COPY_LONG", 2, "APPROVED", "<p>Long v2</p>", policyVersion);

        PublicationEntityView restage = publish(operator, PublishEnvironment.STAGING);
        PublicationEntityView reprod = publish(owner, PublishEnvironment.PRODUCTION);

        assertThat(restage.status()).isEqualTo("APPLIED");
        assertThat(reprod.status()).isEqualTo("APPLIED");
        assertThat(reprod.assetIds()).contains(mainId, titleId, longV2).doesNotContain(longId);
        assertThat(production.product.description()).isEqualTo("<p>Long v2</p>");
        assertThat(status(longV2)).isEqualTo("PUBLISHED");
        assertThat(status(longId)).isEqualTo("ARCHIVED");
    }

    // ---- review finding 4: rollback with ids above the Long cache ----

    @Test
    void rollback_worksForPublicationIdsAbove127() {
        jdbc.execute("alter table publication alter column id restart with 500");
        PublicationEntityView pub = publish(operator, PublishEnvironment.STAGING);
        assertThat(pub.id()).isGreaterThan(127);

        assertThat(service.list(operator, productId).get(0).canRollback()).isTrue();
        assertThat(service.rollback(operator, pub.id(), false).status()).isEqualTo("ROLLED_BACK");
    }

    // ---- rollback behaviour (plan Review Focus 2) ----

    @Test
    void rollback_restoresBefore_deletesMedia_revertsStatuses() {
        publish(operator, PublishEnvironment.STAGING);
        PublicationEntityView pub = publish(owner, PublishEnvironment.PRODUCTION);

        service.rollback(owner, pub.id(), false);

        assertThat(production.product.name()).isEqualTo("Old name");
        assertThat(production.product.images()).extracting(WooImageRef::id).containsExactly(900L);
        assertThat(production.deleted).containsExactlyInAnyOrderElementsOf(pub.uploadedMediaIds());
        assertThat(List.of(mainId, titleId, longId))
                .allSatisfy(id -> assertThat(status(id)).isEqualTo("APPROVED"));
    }

    @Test
    void rollback_afterManualWooEdit_409UnlessForce() {
        PublicationEntityView pub = publish(operator, PublishEnvironment.STAGING);
        staging.manualEdit("Edited by hand");

        assertThatThrownBy(() -> service.rollback(operator, pub.id(), false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WOO_CHANGED_SINCE_PUBLISH"));
        assertThat(staging.product.name()).isEqualTo("Edited by hand");

        service.rollback(operator, pub.id(), true);
        assertThat(staging.product.name()).isEqualTo("Old name");
    }

    @Test
    void rollback_notLatest_409() {
        PublicationEntityView first = publish(operator, PublishEnvironment.STAGING);
        publish(operator, PublishEnvironment.STAGING);

        assertThatThrownBy(() -> service.rollback(operator, first.id(), false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NOT_LATEST_PUBLICATION"));
    }

    // ---- review finding 5: Rank Math cleared when it was empty before ----

    @Test
    void rollback_clearsRankMathWrittenByPublish_whenBeforeHadNone() {
        staging.product = staging.withRankMath(new RankMath(null, null));
        text("COPY_SEO", 1, "APPROVED", "{\"title\":\"Kiano SEO\",\"description\":\"Kiano desc\"}", null);
        PublicationEntityView pub = publish(operator, PublishEnvironment.STAGING);
        assertThat(staging.product.rankMath().title()).isEqualTo("Kiano SEO");

        service.rollback(operator, pub.id(), false);

        assertThat(staging.product.rankMath().title()).isNullOrEmpty();
        assertThat(staging.product.rankMath().description()).isNullOrEmpty();
    }

    @Test
    void failedPublishRestore_clearsRankMathWrittenByThatPublish_whenBeforeHadNone() {
        staging.product = staging.withRankMath(new RankMath(null, null));
        text("COPY_SEO", 1, "APPROVED", "{\"title\":\"Kiano SEO\",\"description\":\"Kiano desc\"}", null);
        doThrow(new IllegalStateException("audit down")).when(auditLog)
                .record(argThat((AuditEntry e) -> e != null && "STAGING_APPLIED".equals(e.action())));

        PublicationEntityView pub = publish(operator, PublishEnvironment.STAGING);

        assertThat(pub.status()).isEqualTo("FAILED");
        assertThat(staging.product.name()).isEqualTo("Old name");
        assertThat(staging.product.rankMath().title()).isNullOrEmpty();
        assertThat(staging.product.rankMath().description()).isNullOrEmpty();
    }

    // ---- review finding 10: status updates are atomic ----

    @Test
    void failureAfterWooUpdate_leavesNoAssetPublished_andRestoresWoo() {
        publish(operator, PublishEnvironment.STAGING);
        doThrow(new IllegalStateException("audit down")).when(auditLog)
                .record(argThat((AuditEntry e) -> e != null && "PUBLISHED".equals(e.action())));

        PublicationEntityView pub = publish(owner, PublishEnvironment.PRODUCTION);

        assertThat(pub.status()).isEqualTo("FAILED");
        assertThat(production.product.name()).isEqualTo("Old name");
        assertThat(List.of(mainId, titleId, longId))
                .allSatisfy(id -> assertThat(status(id)).isEqualTo("APPROVED"));
    }

    // ---- published_by recorded ----

    @Test
    void publication_recordsWhoPublished() {
        publish(operator, PublishEnvironment.STAGING);
        assertThat(service.list(operator, productId).get(0).publishedBy()).isEqualTo("op@flow.test");
    }

    // ---- helpers ----

    /** Read-only projection of a publication row. */
    record PublicationEntityView(long id, String status, List<Long> assetIds,
            List<Long> uploadedMediaIds) {
        static PublicationEntityView load(JdbcTemplate jdbc, long id) {
            return jdbc.queryForObject("select id, status, asset_ids::text, uploaded_media_ids::text "
                    + "from publication where id = ?", (rs, n) -> new PublicationEntityView(
                            rs.getLong(1), rs.getString(2), ids(rs.getString(3)),
                            ids(rs.getString(4))), id);
        }

        private static List<Long> ids(String json) {
            List<Long> out = new ArrayList<>();
            MAPPER.readTree(json).forEach(node -> out.add(node.asLong()));
            return out;
        }
    }

    /** Stateful Woo product: omitted SEO keeps the stored value, "" clears it. */
    static final class FakeWoo implements CommercePublisher {
        WooProductSnapshot product;
        final List<Long> deleted = new ArrayList<>();
        private long nextMediaId = 1000;
        private long clock = 0;

        FakeWoo(RankMath rankMath) {
            product = new WooProductSnapshot(500L, "MG-BL200", "Old name", "Old desc", "Old short",
                    List.of(new WooImageRef(900L, "http://woo/900.jpg", 0, "old")), rankMath, tick());
        }

        private Instant tick() {
            return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(clock++);
        }

        WooProductSnapshot withRankMath(RankMath rankMath) {
            return new WooProductSnapshot(product.id(), product.sku(), product.name(),
                    product.description(), product.shortDescription(), product.images(), rankMath,
                    product.modifiedAt());
        }

        void manualEdit(String name) {
            product = new WooProductSnapshot(product.id(), product.sku(), name, product.description(),
                    product.shortDescription(), product.images(), product.rankMath(), tick());
        }

        @Override
        public Optional<WooProductSnapshot> findBySku(String sku) {
            return Optional.of(product);
        }

        @Override
        public WooProductSnapshot get(long productId) {
            return product;
        }

        @Override
        public WooMedia uploadMedia(String fileName, byte[] bytes, String contentType, String altText) {
            long id = nextMediaId++;
            return new WooMedia(id, "http://woo/" + id + ".jpg");
        }

        @Override
        public WooProductSnapshot updateContent(long productId, ProductContentUpdate update) {
            RankMath old = product.rankMath();
            String title = update.seoTitle() != null ? update.seoTitle() : old.title();
            String description = update.seoDescription() != null ? update.seoDescription()
                    : old.description();
            List<WooImageRef> images = new ArrayList<>();
            int position = 0;
            for (Long id : update.imageIdsInOrder()) {
                images.add(new WooImageRef(id, "http://woo/" + id + ".jpg", position++, ""));
            }
            product = new WooProductSnapshot(productId, product.sku(), update.name(),
                    update.description(), update.shortDescription(), images,
                    new RankMath(title, description), tick());
            return product;
        }

        @Override
        public void deleteMedia(long mediaId) {
            deleted.add(mediaId);
        }
    }
}
