package com.kiano.content.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.template.TemplateFacts;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Fact sheet service: draft save (version 1 then in-place), the G2 lock flow
 * (six confirmations, non-empty model/warranty, stale draft 409, supersede +
 * event), and the FactsJson → TemplateFacts mapping.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class FactSheetServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FactSheetService service;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
    private long productId;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -3000, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'facts-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "facts-op@example.test");
    }

    private static FactsJson facts(String model) {
        return new FactsJson(model, "Electric Kettles", "1.7 L", 2000, "220-240V", "Stainless steel",
                "Silver", "1 year", List.of("Kettle", "Base"), List.of("Auto shut-off"),
                List.of("Boils quickly"), List.of());
    }

    private static Map<String, FieldSource> sources() {
        return Map.of("model", FieldSource.P5, "capacity", FieldSource.P5,
                "powerW", FieldSource.P5, "voltage", FieldSource.P5,
                "warranty", FieldSource.WOO_TEXT, "inBox", FieldSource.PROMO);
    }

    @Test
    void saveDraft_createsVersion1_thenUpdatesInPlace() {
        FactSheetView first = service.saveDraft(user, productId, facts("MG-KTL17"),
                sources());
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThat(first.facts().model()).isEqualTo("MG-KTL17");
        assertThat(first.fieldSources()).containsEntry("model", FieldSource.P5);

        FactSheetView second = service.saveDraft(user, productId,
                facts("MG-KTL17-B"), sources());
        assertThat(second.version()).isEqualTo(1);
        assertThat(second.facts().model()).isEqualTo("MG-KTL17-B");
        assertThat(service.current(tenantId, productId)).isPresent();
        assertThat(service.current(tenantId, productId).get().version()).isEqualTo(1);
    }

    @Test
    void lock_requiresAllSixConfirmations_422WithMissing() {
        service.saveDraft(user, productId, facts("MG-KTL17"), sources());

        assertThatThrownBy(() -> service.lock(user, productId, 1,
                Set.of("model", "powerW", "voltage", "warranty")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("FACTS_NOT_CONFIRMED");
                    assertThat(ex.getDetails().get("missing")).isEqualTo(
                            List.of("capacity", "inBox"));
                });
    }

    @Test
    void lock_emptyModel_422Incomplete() {
        service.saveDraft(user, productId, facts(null), sources());

        assertThatThrownBy(() -> service.lock(user, productId, 1, allConfirmed()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("FACTS_INCOMPLETE");
                });
    }

    @Test
    void lock_staleDraftVersion_409() {
        service.saveDraft(user, productId, facts("MG-KTL17"), sources());

        assertThatThrownBy(() -> service.lock(user, productId, 99, allConfirmed()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("FACT_DRAFT_CHANGED");
                });
    }

    @Test
    void lock_supersedesPrevious_andPublishesEventAfterCommit() {
        service.saveDraft(user, productId, facts("MG-KTL17"), sources());
        FactSheetView locked1 = service.lock(user, productId, 1, allConfirmed());
        assertThat(locked1.status()).isEqualTo("LOCKED");
        assertThat(locked1.lockedBy()).isEqualTo("facts-op@example.test");
        assertThat(locked1.lockedAt()).isNotNull();

        service.saveDraft(user, productId, facts("MG-KTL17-B"), sources());
        FactSheetView locked2 = service.lock(user, productId, 2, allConfirmed());

        assertThat(locked2.version()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from product_fact_sheet where product_id = ? and status = 'SUPERSEDED'",
                Integer.class, productId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from product_fact_sheet where product_id = ? and status = 'LOCKED'",
                Integer.class, productId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "select action from audit_log where tenant_id = ? and action = 'FACTS_LOCKED' order by id",
                String.class, tenantId)).hasSize(2);
    }

    @Test
    void newDraftAfterLock_isVersion2_lockedStillServed() {
        service.saveDraft(user, productId, facts("MG-KTL17"), sources());
        service.lock(user, productId, 1, allConfirmed());
        service.saveDraft(user, productId, facts("MG-KTL17-B"), sources());

        assertThat(service.current(tenantId, productId).get().version()).isEqualTo(2);
        assertThat(service.current(tenantId, productId).get().status()).isEqualTo("DRAFT");
        assertThat(service.locked(tenantId, productId).get().version()).isEqualTo(1);
    }

    @Test
    void factsJson_toTemplateFacts_mapsAllFields() {
        FactsJson facts = facts("MG-KTL17");
        TemplateFacts mapped = facts.toTemplateFacts("Morgan 1.7L Kettle");
        assertThat(mapped.productName()).isEqualTo("Morgan 1.7L Kettle");
        assertThat(mapped.model()).isEqualTo("MG-KTL17");
        assertThat(mapped.capacity()).isEqualTo("1.7 L");
        assertThat(mapped.powerW()).isEqualTo(2000);
        assertThat(mapped.voltage()).isEqualTo("220-240V");
        assertThat(mapped.warranty()).isEqualTo("1 year");
        assertThat(mapped.inBox()).containsExactly("Kettle", "Base");
    }

    private static Set<String> allConfirmed() {
        return Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox");
    }
}