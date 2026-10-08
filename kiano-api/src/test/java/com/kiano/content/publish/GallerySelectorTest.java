package com.kiano.content.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.asset.ReviewService.ReviewItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GallerySelector: group caps (MAIN 1, ANGLE 4, SCENE 2, INBOX/INFO/SPEC 1),
 * newest version per variant, ANGLE order P2→P3→P4→P6 and SCENE numeric.
 */
class GallerySelectorTest {

    private static ReviewItem item(long id, String spec, String variant, int version) {
        return new ReviewItem(id, 1L, "SKU", "Name", spec, variant, version, "APPROVED", "IMAGE",
                List.of(), Map.of(), null, null, null, null, null, null, null, null);
    }

    @Test
    void capsAndOrder_heroWith4Scenes_keeps2_total10() {
        List<ReviewItem> approved = List.of(
                item(1, "PAGE_MAIN", "main", 1),
                item(2, "PAGE_ANGLE", "P2", 1),
                item(3, "PAGE_ANGLE", "P3", 1),
                item(4, "PAGE_ANGLE", "P4", 1),
                item(5, "PAGE_ANGLE", "P6", 1),
                item(6, "PAGE_ANGLE", "P6", 2),   // newest P6 wins
                item(7, "PAGE_SCENE", "scene1", 1),
                item(8, "PAGE_SCENE", "scene2", 1),
                item(9, "PAGE_SCENE", "scene3", 1),
                item(10, "PAGE_SCENE", "scene4", 1),
                item(11, "PAGE_INBOX", "inbox", 1),
                item(12, "PAGE_INFO", "default", 1),
                item(13, "PAGE_SPEC", "default", 1));

        List<ReviewItem> selected = GallerySelector.select(approved);

        assertThat(selected).hasSize(10);
        assertThat(selected).extracting(ReviewItem::specCode)
                .containsExactly("PAGE_MAIN", "PAGE_ANGLE", "PAGE_ANGLE", "PAGE_ANGLE",
                        "PAGE_ANGLE", "PAGE_SCENE", "PAGE_SCENE", "PAGE_INBOX", "PAGE_INFO",
                        "PAGE_SPEC");
        assertThat(selected).extracting(ReviewItem::assetId)
                .containsExactly(1L, 2L, 3L, 4L, 6L, 7L, 8L, 11L, 12L, 13L);
    }

    @Test
    void latestVersionPerVariant_only() {
        List<ReviewItem> approved = List.of(
                item(1, "PAGE_ANGLE", "P2", 1),
                item(2, "PAGE_ANGLE", "P2", 2),
                item(3, "PAGE_ANGLE", "P2", 3),
                item(4, "PAGE_MAIN", "main", 1),
                item(5, "PAGE_MAIN", "main", 2));

        List<ReviewItem> selected = GallerySelector.select(approved);

        assertThat(selected).extracting(ReviewItem::assetId).containsExactly(5L, 3L);
    }
}