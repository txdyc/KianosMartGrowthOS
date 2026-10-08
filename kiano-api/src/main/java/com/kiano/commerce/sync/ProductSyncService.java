package com.kiano.commerce.sync;

import com.kiano.commerce.CommerceCategory;
import com.kiano.commerce.CommercePort;
import com.kiano.commerce.CommerceProduct;
import com.kiano.commerce.ProductPriceChanged;
import com.kiano.commerce.woo.CommercePortFactory;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.integration.IntegrationStore;
import com.kiano.platform.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only product sync: pulls the Woo snapshot into memory, then applies it
 * in one transaction — category upsert (second pass backfills
 * parent_external_id), product upsert with price-change audit + event,
 * variation upsert for variable products, product_category rebuild, and
 * missing marking for products absent this round. markSynced runs after the
 * transaction commits.
 */
@Service
public class ProductSyncService {

    private final CommercePortFactory portFactory;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactionTemplate;
    private final IntegrationStore integrationStore;

    public ProductSyncService(CommercePortFactory portFactory, JdbcTemplate jdbcTemplate,
            AuditLog auditLog, ApplicationEventPublisher events,
            TransactionTemplate transactionTemplate, IntegrationStore integrationStore) {
        this.portFactory = portFactory;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLog = auditLog;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
        this.integrationStore = integrationStore;
    }

    public SyncResult syncAll(long tenantId) {
        CommercePort port = portFactory.forTenant(tenantId);
        long storeId = requireStore(tenantId);

        List<CommerceCategory> categories = port.listCategories();
        List<CommerceProduct> products = port.listProducts();

        SyncResult result = transactionTemplate.execute(status ->
                applySnapshot(tenantId, storeId, port, categories, products));

        integrationStore.markSynced(tenantId, CommercePortFactory.PROVIDER, Instant.now());
        return result;
    }

    private SyncResult applySnapshot(long tenantId, long storeId, CommercePort port,
            List<CommerceCategory> categories, List<CommerceProduct> products) {
        int priceChanges = 0;

        for (CommerceCategory category : categories) {
            jdbcTemplate.update(
                    "insert into category (tenant_id, external_id, parent_external_id, name, slug, synced_at) "
                            + "values (?, ?, ?, ?, ?, now()) "
                            + "on conflict (tenant_id, external_id) do update set "
                            + "name = excluded.name, slug = excluded.slug, synced_at = excluded.synced_at",
                    tenantId, category.externalId(), category.parentExternalId(),
                    category.name(), category.slug());
        }
        for (CommerceCategory category : categories) {
            jdbcTemplate.update(
                    "update category set parent_external_id = ? "
                            + "where tenant_id = ? and external_id = ?",
                    category.parentExternalId(), tenantId, category.externalId());
        }

        Map<Long, ExistingProduct> existingByExternalId = loadExisting(tenantId);

        Map<Long, Long> productIdByExternalId = new HashMap<>();
        for (CommerceProduct product : products) {
            long id = upsertProduct(tenantId, storeId, product, null);
            productIdByExternalId.put(product.externalId(), id);
            ExistingProduct existing = existingByExternalId.get(product.externalId());
            if (existing != null && priceChanged(existing, product)) {
                priceChanges++;
                recordPriceChange(tenantId, id, existing, product);
            }
        }

        int variations = 0;
        for (CommerceProduct product : products) {
            if (!"variable".equals(product.type())) {
                continue;
            }
            for (CommerceProduct variation : port.listVariations(product)) {
                Long parentId = variation.parentExternalId() == null ? null
                        : productIdByExternalId.get(variation.parentExternalId());
                long id = upsertProduct(tenantId, storeId, variation, parentId);
                productIdByExternalId.put(variation.externalId(), id);
                ExistingProduct existing = existingByExternalId.get(variation.externalId());
                if (existing != null && priceChanged(existing, variation)) {
                    priceChanges++;
                    recordPriceChange(tenantId, id, existing, variation);
                }
                variations++;
            }
        }

        for (CommerceProduct product : products) {
            Long productId = productIdByExternalId.get(product.externalId());
            jdbcTemplate.update("delete from product_category where product_id = ?", productId);
            for (Long categoryExternalId : orEmpty(product.categoryExternalIds())) {
                jdbcTemplate.update(
                        "insert into product_category (product_id, category_id) "
                                + "select ?, c.id from category c "
                                + "where c.tenant_id = ? and c.external_id = ?",
                        productId, tenantId, categoryExternalId);
            }
        }

        int markedMissing = markMissing(tenantId, productIdByExternalId.keySet());

        return new SyncResult(categories.size(), products.size(), variations, priceChanges,
                markedMissing);
    }

    private Map<Long, ExistingProduct> loadExisting(long tenantId) {
        Map<Long, ExistingProduct> existing = new HashMap<>();
        jdbcTemplate.query(
                "select external_id, regular_price, sale_price, sale_to_at "
                        + "from product where tenant_id = ?",
                rs -> {
                    existing.put(rs.getLong("external_id"), new ExistingProduct(
                            rs.getBigDecimal("regular_price"), rs.getBigDecimal("sale_price"),
                            rs.getTimestamp("sale_to_at") == null ? null
                                    : rs.getTimestamp("sale_to_at").toInstant()));
                },
                tenantId);
        return existing;
    }

    private long upsertProduct(long tenantId, long storeId, CommerceProduct product,
            Long parentId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "insert into product (tenant_id, store_id, external_id, parent_external_id, parent_id, "
                        + "type, sku, brand, name, slug, regular_price, sale_price, price, stock_qty, "
                        + "stock_status, status, permalink, image_url, sale_from_at, sale_to_at, "
                        + "woo_modified_at, synced_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                        + "on conflict (tenant_id, external_id) do update set "
                        + "store_id = excluded.store_id, "
                        + "parent_external_id = excluded.parent_external_id, parent_id = excluded.parent_id, "
                        + "type = excluded.type, sku = excluded.sku, brand = excluded.brand, "
                        + "name = excluded.name, slug = excluded.slug, "
                        + "regular_price = excluded.regular_price, sale_price = excluded.sale_price, "
                        + "price = excluded.price, stock_qty = excluded.stock_qty, "
                        + "stock_status = excluded.stock_status, status = excluded.status, "
                        + "permalink = excluded.permalink, image_url = excluded.image_url, "
                        + "sale_from_at = excluded.sale_from_at, sale_to_at = excluded.sale_to_at, "
                        + "woo_modified_at = excluded.woo_modified_at, synced_at = excluded.synced_at "
                        + "returning id",
                Long.class,
                tenantId, storeId, product.externalId(), product.parentExternalId(), parentId,
                product.type(), product.sku(), product.brand(), product.name(), product.slug(),
                product.regularPrice(), product.salePrice(), product.price(), product.stockQty(),
                product.stockStatus(), product.status(), product.permalink(), product.imageUrl(),
                product.saleFromAt() == null ? null : Timestamp.from(product.saleFromAt()),
                product.saleToAt() == null ? null : Timestamp.from(product.saleToAt()),
                product.modifiedAt() == null ? null : Timestamp.from(product.modifiedAt()));
        return ids.get(0);
    }

    private int markMissing(long tenantId, Set<Long> seenExternalIds) {
        if (seenExternalIds.isEmpty()) {
            return jdbcTemplate.update(
                    "update product set status = 'missing' "
                            + "where tenant_id = ? and status <> 'missing'",
                    tenantId);
        }
        String placeholders = String.join(",", Collections.nCopies(seenExternalIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(seenExternalIds);
        return jdbcTemplate.update(
                "update product set status = 'missing' "
                        + "where tenant_id = ? and status <> 'missing' "
                        + "and external_id not in (" + placeholders + ")",
                args.toArray());
    }

    private static boolean priceChanged(ExistingProduct existing, CommerceProduct incoming) {
        return !moneyEquals(existing.regularPrice(), incoming.regularPrice())
                || !moneyEquals(existing.salePrice(), incoming.salePrice())
                || !instantEquals(existing.saleToAt(), incoming.saleToAt());
    }

    private static boolean moneyEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private static boolean instantEquals(Instant a, Instant b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    private void recordPriceChange(long tenantId, long productId, ExistingProduct existing,
            CommerceProduct product) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("regularPrice", existing.regularPrice());
        before.put("salePrice", existing.salePrice());
        before.put("saleToAt", existing.saleToAt());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("regularPrice", product.regularPrice());
        after.put("salePrice", product.salePrice());
        after.put("saleToAt", product.saleToAt());
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                "PRODUCT_PRICE_CHANGED", "product", String.valueOf(productId), before, after,
                null, "WOO_SYNC"));
        events.publishEvent(new ProductPriceChanged(tenantId, productId, product.sku(),
                existing.regularPrice(), product.regularPrice(), existing.salePrice(),
                product.salePrice(), existing.saleToAt(), product.saleToAt()));
    }

    private long requireStore(long tenantId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from store where tenant_id = ? and platform = ?",
                Long.class, tenantId, CommercePortFactory.PROVIDER);
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "WOO_NOT_CONFIGURED",
                    "WooCommerce integration is not configured for this tenant");
        }
        return ids.get(0);
    }

    private static List<Long> orEmpty(List<Long> ids) {
        return ids == null ? List.<Long>of() : ids;
    }

    /**
     * Prices and promotion end of an already-stored product row, for change
     * detection.
     */
    private record ExistingProduct(BigDecimal regularPrice, BigDecimal salePrice,
            Instant saleToAt) {
    }
}
