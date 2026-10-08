package com.kiano.commerce.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * MyBatis-Plus backed ProductCatalog. findBySku compares lower(sku) with the
 * trimmed input so " MG-BL200 " matches "MG-BL200".
 */
@Service
public class ProductCatalogImpl implements ProductCatalog {

    private final ProductMapper productMapper;

    public ProductCatalogImpl(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    public Optional<ProductView> findById(long tenantId, long productId) {
        return productMapper.selectList(Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getTenantId, tenantId)
                        .eq(ProductEntity::getId, productId))
                .stream().findFirst().map(this::toView);
    }

    @Override
    public Optional<ProductView> findBySku(long tenantId, String sku) {
        return productMapper.selectList(Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getTenantId, tenantId)
                        .ne(ProductEntity::getStatus, "missing")
                        .apply("lower(sku) = lower({0})", sku.trim()))
                .stream().findFirst().map(this::toView);
    }

    @Override
    public List<ProductView> listTopLevel(long tenantId) {
        return productMapper.selectList(Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getTenantId, tenantId)
                        .isNull(ProductEntity::getParentId)
                        .ne(ProductEntity::getStatus, "missing")
                        .orderByAsc(ProductEntity::getSku))
                .stream().map(this::toView).toList();
    }

    private ProductView toView(ProductEntity entity) {
        return new ProductView(entity.getId(), entity.getParentId(), entity.getType(),
                entity.getSku(), entity.getName(), entity.getRegularPrice(), entity.getSalePrice(),
                entity.getPrice(), entity.getStockQty(), entity.getStockStatus(),
                entity.getStatus(), entity.getImageUrl(),
                productMapper.selectCategorySlugs(entity.getId()),
                entity.getSaleFromAt() == null ? null : entity.getSaleFromAt().toInstant(),
                entity.getSaleToAt() == null ? null : entity.getSaleToAt().toInstant());
    }
}
