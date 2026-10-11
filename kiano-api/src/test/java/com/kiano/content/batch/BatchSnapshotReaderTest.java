package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.batch.BatchStageResolver.ItemSnapshot;
import com.kiano.content.batch.BatchStageResolver.Resolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BatchSnapshotReader: a required spec whose newest version was rejected (and
 * that has no live version) reaches the resolver, so the item is BLOCKED
 * instead of waiting in GENERATING forever; a rejected version that was
 * replaced by an approved one does not count.
 */
class BatchSnapshotReaderTest extends BatchTestSupport {

    @Autowired
    private BatchSnapshotReader reader;

    @Test
    void rejectedRequiredSpec_withoutLiveVersion_blocksTheItem() {
        long rejected = publishableProduct("MG-STD1");
        long replaced = publishableProduct("MG-STD2");
        jdbcTemplate.update("update asset set status = 'REJECTED' where product_id in (?, ?) "
                + "and spec_code = 'PAGE_MAIN'", rejected, replaced);
        // the second SKU's main image was regenerated and approved
        jdbcTemplate.update("insert into asset (tenant_id, product_id, spec_code, variant, "
                + "version, kind, status, precheck_json, provenance_json) "
                + "values (?, ?, 'PAGE_MAIN', 'main', 2, 'TEXT', 'APPROVED', '{}'::jsonb, "
                + "'{}'::jsonb)", tenantId, replaced);
        long batchId = batches.create(operator, "Spring", List.of(rejected, replaced), false)
                .id();

        Map<Long, ItemSnapshot> snapshots = reader.read(tenantId, batches.items(tenantId,
                batchId));

        assertThat(snapshots.get(rejected).rejectedSpecs()).containsExactly("PAGE_MAIN");
        Resolution blocked = BatchStageResolver.resolve(snapshots.get(rejected));
        assertThat(blocked.stage()).isEqualTo(BatchStage.BLOCKED);
        assertThat(blocked.blockedReason()).isEqualTo("ASSET_REJECTED");
        assertThat(snapshots.get(replaced).rejectedSpecs()).isEmpty();
        assertThat(BatchStageResolver.resolve(snapshots.get(replaced)).stage())
                .isNotEqualTo(BatchStage.BLOCKED);
    }
}
