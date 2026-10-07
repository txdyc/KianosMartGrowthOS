package com.kiano.content.media;

import com.kiano.content.qc.QcReason;
import java.util.List;

/**
 * Outcome of one imported file. outcome is IMPORTED or DUPLICATE; status is
 * ACCEPTED or RESHOOT. For DUPLICATE, sku/shotCode/status/mediaId come from
 * the existing record.
 */
public record ImportResult(String fileName, String outcome, long mediaId, long productId, String sku,
        String shotCode, String status, List<QcReason> reasons) {

    public static final String OUTCOME_IMPORTED = "IMPORTED";
    public static final String OUTCOME_DUPLICATE = "DUPLICATE";
}
