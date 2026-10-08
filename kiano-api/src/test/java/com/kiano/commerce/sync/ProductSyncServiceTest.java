package com.kiano.commerce.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.CommerceCategory;
import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommercePort;
import com.kiano.commerce.CommerceProduct;
import com.kiano.commerce.ProductPriceChanged;
import com.kiano.commerce.woo.CommercePortFactory;
import com.kiano.platform.queue.TaskDispatcher;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.queue.TaskStatus;
import com.kiano.platform.queue.TaskView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * ProductSyncService against a fake CommercePort: full snapshot upsert,
 * idempotent second sync, price-change audit + event, missing marking and
 * restoration, mid-sync rollback, and non-retryable task failure.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class ProductSyncServiceTest {

    private static final String AUTH_FAILED_MESSAGE =
            "WooCommerce rejected the credentials. Check the username and Application Password; "
                    + "on an http:// site WordPress also needs WP_ENVIRONMENT_TYPE=local.";

    @Autowired
    private ProductSyncService syncService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskQueue taskQueue;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private CommercePortFactory portFactory;

    private FakePort port;
    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from platform_task");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test')",
                tenantId);
        port = new FakePort();
        given(portFactory.forTenant(tenantId)).willReturn(port);
    }

    @Test
    void firstSync_insertsEverything() {
        seedCatalog();

        SyncResult result = syncService.syncAll(tenantId);

        assertThat(result).isEqualTo(new SyncResult(2, 3, 2, 0, 0));
        assertThat(count("category")).isEqualTo(2);
        assertThat(count("product")).isEqualTo(5);

        Long variableId = jdbcTemplate.queryForObject(
                "select id from product where external_id = 101", Long.class);
        List<Long> variationParentIds = jdbcTemplate.queryForList(
                "select parent_id from product where type = 'variation'", Long.class);
        assertThat(variationParentIds).hasSize(2).containsOnly(variableId);
        assertThat(count("product_category")).isEqualTo(4);

        assertThat(statusOf(202)).isEqualTo("publish");
    }

    @Test
    void secondIdenticalSync_isNoOp() {
        seedCatalog();
        syncService.syncAll(tenantId);
        long productsBefore = count("product");
        long linksBefore = count("product_category");

        SyncResult second = syncService.syncAll(tenantId);

        assertThat(second.priceChanges()).isZero();
        assertThat(second.markedMissing()).isZero();
        assertThat(count("product")).isEqualTo(productsBefore);
        assertThat(count("product_category")).isEqualTo(linksBefore);
        assertThat(count("audit_log")).isZero();
    }

    @Test
    void saleEndDateChange_onlyDateChanged_firesPriceChanged() {
        seedCatalog();
        syncService.syncAll(tenantId);
        applicationEvents.clear();

        Instant newEnd = Instant.parse("2026-10-30T23:59:59Z");
        port.products.replaceAll(p -> p.externalId() == 201 ? withSaleTo(p, newEnd) : p);

        SyncResult result = syncService.syncAll(tenantId);

        assertThat(result.priceChanges()).isEqualTo(1);
        List<ProductPriceChanged> events = applicationEvents.stream(ProductPriceChanged.class).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).sku()).isEqualTo("MG-BL200");
        assertThat(events.get(0).oldSaleToAt()).isNull();
        assertThat(events.get(0).newSaleToAt()).isEqualTo(newEnd);
    }

    @Test
    void unchangedSaleDates_noEvent() {
        seedCatalog();
        Instant end = Instant.parse("2026-10-20T23:59:59Z");
        port.products.replaceAll(p -> p.externalId() == 201 ? withSaleTo(p, end) : p);
        syncService.syncAll(tenantId);
        applicationEvents.clear();

        SyncResult second = syncService.syncAll(tenantId);

        assertThat(second.priceChanges()).isZero();
        assertThat(applicationEvents.stream(ProductPriceChanged.class)).isEmpty();
    }

    @Test
    void priceChange_auditsAndPublishesEvent() {
        seedCatalog();
        syncService.syncAll(tenantId);
        replaceProduct(product(201, "simple", "MG-BL200", "Bladeless Fan",
                new BigDecimal("279"), null, new BigDecimal("279"), List.of(11L), "publish"));

        SyncResult second = syncService.syncAll(tenantId);

        assertThat(second.priceChanges()).isEqualTo(1);
        List<ProductPriceChanged> fired = applicationEvents.stream(ProductPriceChanged.class)
                .toList();
        assertThat(fired).hasSize(1);
        ProductPriceChanged event = fired.get(0);
        assertThat(event.sku()).isEqualTo("MG-BL200");
        assertThat(event.oldRegular()).isEqualByComparingTo("299");
        assertThat(event.newRegular()).isEqualByComparingTo("279");

        Integer auditRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'PRODUCT_PRICE_CHANGED'",
                Integer.class);
        assertThat(auditRows).isEqualTo(1);
    }

    @Test
    void absentProduct_markedMissing_thenRestored() {
        seedCatalog();
        syncService.syncAll(tenantId);
        port.products.removeIf(p -> p.externalId() == 202);

        SyncResult second = syncService.syncAll(tenantId);

        assertThat(second.markedMissing()).isEqualTo(1);
        assertThat(statusOf(202)).isEqualTo("missing");
        assertThat(count("audit_log")).isZero();

        port.products.add(product(202, "simple", "MG-LAMP1", "Night Lamp",
                new BigDecimal("59"), null, new BigDecimal("59"), List.of(12L), "publish"));
        SyncResult third = syncService.syncAll(tenantId);

        assertThat(third.markedMissing()).isZero();
        assertThat(statusOf(202)).isEqualTo("publish");
    }

    @Test
    void portFailureMidSync_rollsBackEverything() {
        seedCatalog();
        syncService.syncAll(tenantId);
        long productsBefore = count("product");
        long categoriesBefore = count("category");
        long linksBefore = count("product_category");
        port.products.removeIf(p -> p.externalId() == 202);
        port.failVariations = true;

        assertThatThrownBy(() -> syncService.syncAll(tenantId))
                .isInstanceOf(CommerceException.class);

        assertThat(count("product")).isEqualTo(productsBefore);
        assertThat(count("category")).isEqualTo(categoriesBefore);
        assertThat(count("product_category")).isEqualTo(linksBefore);
        assertThat(statusOf(202)).isEqualTo("publish");
        Long missingCount = jdbcTemplate.queryForObject(
                "select count(*) from product where status = 'missing'", Long.class);
        assertThat(missingCount).isZero();
    }

    @Test
    void nonRetryableCommerceError_failsTask() {
        port.failAll = true;
        Optional<Long> taskId = taskQueue.enqueue(tenantId, ProductSyncTaskHandler.TYPE,
                Map.of(), "woo-product-sync");
        assertThat(taskId).isPresent();

        taskDispatcher.pollOnce();

        TaskView task = taskQueue.latest(tenantId, ProductSyncTaskHandler.TYPE).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.lastError()).contains("Application Password");
    }

    // --- helpers -----------------------------------------------------------

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }

    private String statusOf(long externalId) {
        return jdbcTemplate.queryForObject("select status from product where external_id = ?",
                String.class, externalId);
    }

    private void replaceProduct(CommerceProduct updated) {
        port.products.removeIf(p -> p.externalId() == updated.externalId());
        port.products.add(updated);
    }

    private void seedCatalog() {
        port.categories.add(new CommerceCategory(11, null, "Fans", "fans"));
        port.categories.add(new CommerceCategory(12, 11L, "Ceiling", "ceiling"));
        port.products.add(product(201, "simple", "MG-BL200", "Bladeless Fan",
                new BigDecimal("299"), null, new BigDecimal("299"), List.of(11L), "publish"));
        port.products.add(product(202, "simple", "MG-LAMP1", "Night Lamp",
                new BigDecimal("59"), null, new BigDecimal("59"), List.of(12L), "publish"));
        port.products.add(product(101, "variable", "MG-FAN16", "Morgan Fan",
                new BigDecimal("189"), null, new BigDecimal("189"), List.of(11L, 12L), "publish"));
        port.variationsByParent.put(101L, List.of(
                variation(1011, 101, "MG-FAN16-BLK", "Morgan Fan - Black",
                        new BigDecimal("199"), new BigDecimal("179")),
                variation(1012, 101, "MG-FAN16-WHT", "Morgan Fan - White",
                        new BigDecimal("209"), new BigDecimal("189"))));
    }

    private static CommerceProduct product(long externalId, String type, String sku, String name,
            BigDecimal regular, BigDecimal sale, BigDecimal price, List<Long> categoryIds,
            String status) {
        return new CommerceProduct(externalId, null, type, sku, "MorganGo", name,
                name.toLowerCase().replace(' ', '-'), regular, sale, price, 10, "instock", status,
                "https://woo.example.test/p/" + sku.toLowerCase(),
                "https://woo.example.test/img/" + sku.toLowerCase() + ".jpg",
                Instant.parse("2026-10-01T00:00:00Z"), categoryIds, null, null);
    }

    private static CommerceProduct variation(long externalId, long parentExternalId, String sku,
            String name, BigDecimal regular, BigDecimal sale) {
        return new CommerceProduct(externalId, parentExternalId, "variation", sku, "MorganGo",
                name, null, regular, sale, sale, 5, "instock", "publish", null, null,
                Instant.parse("2026-10-01T00:00:00Z"), List.of(), null, null);
    }

    private static CommerceProduct withSaleTo(CommerceProduct p, Instant saleToAt) {
        return new CommerceProduct(p.externalId(), p.parentExternalId(), p.type(), p.sku(),
                p.brand(), p.name(), p.slug(), p.regularPrice(), p.salePrice(), p.price(),
                p.stockQty(), p.stockStatus(), p.status(), p.permalink(), p.imageUrl(),
                p.modifiedAt(), p.categoryExternalIds(), p.saleFromAt(), saleToAt);
    }

    /**
     * Programmable CommercePort: fixed catalog plus failure switches.
     */
    private static final class FakePort implements CommercePort {

        final List<CommerceCategory> categories = new ArrayList<>();
        final List<CommerceProduct> products = new ArrayList<>();
        final Map<Long, List<CommerceProduct>> variationsByParent = new LinkedHashMap<>();
        boolean failVariations;
        boolean failAll;

        @Override
        public List<CommerceCategory> listCategories() {
            if (failAll) {
                throw authFailed();
            }
            return categories;
        }

        @Override
        public List<CommerceProduct> listProducts() {
            if (failAll) {
                throw authFailed();
            }
            return products;
        }

        @Override
        public List<CommerceProduct> listVariations(CommerceProduct parent) {
            if (failAll) {
                throw authFailed();
            }
            if (failVariations) {
                throw new CommerceException("WOO_UNAVAILABLE", "variations endpoint unavailable",
                        true);
            }
            return variationsByParent.getOrDefault(parent.externalId(), List.of());
        }

        @Override
        public void ping() {
            // no-op
        }

        private static CommerceException authFailed() {
            return new CommerceException("WOO_AUTH_FAILED", AUTH_FAILED_MESSAGE, false);
        }
    }
}
