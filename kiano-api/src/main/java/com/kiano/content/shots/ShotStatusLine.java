package com.kiano.content.shots;

import com.kiano.content.media.MediaKind;
import com.kiano.content.qc.QcReason;
import java.util.List;

/**
 * One line of a product's shot checklist: the requirement plus the state of
 * its current media. mediaUrl/thumbUrl are only filled when presigned URLs
 * were requested (thumbUrl only when a thumbnail exists, i.e. photos).
 */
public record ShotStatusLine(String code, MediaKind kind, boolean required, ShotState state, Long mediaId,
        List<QcReason> reasons, String guidanceEn, String guidanceZh, String thumbUrl, String mediaUrl) {
}
