package com.kiano.commerce.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ProductCatalog read queries: trimmed case-insensitive SKU lookup, variation
 * parentId resolution, and top-level listing that hides variations and
 * missing products while exposing category slugs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductCatalogTest {

    @Autowired
    private ProductCatalog catalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        seed();
    }

    @Test
    void findBySku_isTrimmedCaseInsensitive() {
        Optional<ProductView> found = catalog.findBySku(tenantId, " mg-bl200 ");

        assertThat(found).isPresent();
        assertThat(found.get().sku()).isEqualTo("MG-BL200");
        assertThat(found.get().name()).isEqualTo("Bladeless Fan");
    }

    @Test
    void findBySku_variation_returnsParentId() {
        Optional<ProductView> found = catalog.findBySku(tenantId, "mg-fan16-blk");

        assertThat(found).isPresent();
        assertThat(found.get().parentId()).isEqualTo(idOf(101));
        assertThat(found.get().type()).isEqualTo("variation");
    }

    @Test
    void listTopLevel_excludesVariationsAndMissing_includesCategorySlugs() {
        List<ProductView> topLevel = catalog.listTopLevel(tenantId);

        assertThat(topLevel).extracting(ProductView::sku)
                .containsExactly("MG-BL200", "MG-FAN16");
        ProductView fan = topLevel.stream()
                .filter(p -> p.sku().equals("MG-BL200"))
                .findFirst().orElseThrow();
        assertThat(fan.parentId()).isNull();
        assertThat(fan.type()).isEqualTo("simple");
        assertThat(fan.categorySlugs()).containsExactly("ceiling", "fans");
    }

    // --- helpers -----------------------------------------------------------

    private long idOf(long externalId) {
        Long id = jdbcTemplate.queryForObject("select id from product where external_id = ?",
                Long.class, externalId);
        if (id == null) {
            throw new IllegalStateException("product not seeded: " + externalId);
        }
        return id;
    }

    private void seed() {
        jdbcTemplate.update(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test')",
                tenantId);
        insertCategory(11, null, "Fans", "fans");
        insertCategory(12, 11L, "Ceiling", "ceiling");

        insertProduct(201, null, "simple", "MG-BL200", "Bladeless Fan", "299.00", "publish");
        insertProduct(202, null, "simple", "MG-LAMP1", "Night Lamp", "59.00", "missing");
        insertProduct(101, null, "variable", "MG-FAN16", "Morgan Fan", "189.00", "publish");
        insertProduct(1011, idOf(101), "variation", "MG-FAN16-BLK", "Morgan Fan - Black",
                "199.00", "publish");

        link(201, 11L);
        link(201, 12L);
        link(101, 11L);
    }

    private void insertCategory(long externalId, Long parentExternalId, String name, String slug) {
        jdbcTemplate.update(
                "insert into category (tenant_id, external_id, parent_external_id, name, slug, synced_at) "
                        + "values (?, ?, ?, ?, ?, now())",
                tenantId, externalId, parentExternalId, name, slug);
    }

    private void insertProduct(long externalId, Long parentId, String type, String sku,
            String name, String regularPrice, String status) {
        jdbcTemplate.update(
                "insert into product (tenant_id, store_id, external_id, parent_id, type, sku, name, "
                        + "regular_price, price, status, synced_at) "
                        + "values (?, (select id from store where tenant_id = ? and platform = 'WOOCOMMERCE'), "
                        + "?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, now())",
                tenantId, tenantId, externalId, parentId, type, sku, name, regularPrice,
                regularPrice, status);
    }

    private void link(long productExternalId, long categoryExternalId) {
        jdbcTemplate.update(
                "insert into product_category (product_id, category_id) "
                        + "select p.id, c.id from product p, category c "
                        + "where p.tenant_id = ? and p.external_id = ? and c.tenant_id = ? "
                        + "and c.external_id = ?",
                tenantId, productExternalId, tenantId, categoryExternalId);
    }
}
