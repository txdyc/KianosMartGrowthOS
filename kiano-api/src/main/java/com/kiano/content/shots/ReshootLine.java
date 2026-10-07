package com.kiano.content.shots;

import com.kiano.content.ContentTier;
import com.kiano.content.qc.QcReason;
import java.util.List;

/**
 * One reshoot-list entry: a checklist line whose state is not OK, with the
 * guidance of the requirement. Ordered HERO products first, then sku, then
 * shot sort order.
 */
public record ReshootLine(String sku, String productName, ContentTier tier, String shotCode, ShotState state,
        List<QcReason> reasons, String guidanceEn, String guidanceZh) {
}
