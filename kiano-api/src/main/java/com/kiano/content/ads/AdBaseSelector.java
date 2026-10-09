package com.kiano.content.ads;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Picks the base image of an ad static (§7.3 / Global Constraints): pricehook
 * uses the approved PAGE_MAIN, problem and trust the two most recently approved
 * PAGE_SCENE shots (problem gets the newest, trust the second - when only one
 * exists both share it), and demo a V1 frame chosen by {@link FrameExtractor}.
 * Only approved (or already published) assets and ACCEPTED videos are eligible; a missing base
 * is 409 AD_BASE_MISSING. The source type decides the file-name segment: real
 * for PAGE_MAIN and V1 frames, mixed for PAGE_SCENE.
 */
@Component
public class AdBaseSelector {

    /** The chosen base: JPEG bytes, file-name type and the provenance ids. */
    public record BaseImage(byte[] jpeg, String sourceType, @Nullable Long assetId,
            @Nullable Long sourceMediaId, @Nullable Double frameTime) {
    }

    private final AssetMapper assetMapper;
    private final SourceMediaMapper sourceMediaMapper;
    private final ObjectStorage storage;
    private final FrameExtractor frameExtractor;
    private final ObjectMapper objectMapper;

    public AdBaseSelector(AssetMapper assetMapper, SourceMediaMapper sourceMediaMapper,
            ObjectStorage storage, FrameExtractor frameExtractor, ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.sourceMediaMapper = sourceMediaMapper;
        this.storage = storage;
        this.frameExtractor = frameExtractor;
        this.objectMapper = objectMapper;
    }

    public BaseImage select(long tenantId, long productId, AdHook hook, int frameCandidate) {
        return switch (hook) {
            case PRICEHOOK -> fromMain(tenantId, productId);
            case PROBLEM -> fromScene(tenantId, productId, 0);
            case TRUST -> fromScene(tenantId, productId, 1);
            case DEMO -> fromDemo(tenantId, productId, frameCandidate);
        };
    }

    private BaseImage fromMain(long tenantId, long productId) {
        AssetEntity main = latestApproved(tenantId, productId, "PAGE_MAIN")
                .orElseThrow(() -> missingBase("PAGE_MAIN"));
        return new BaseImage(storage.download(main.getObjectKey()), "real", main.getId(),
                sourceMediaId(main), null);
    }

    /** Index 0 is the newest approved PAGE_SCENE, 1 the second newest (shared). */
    private BaseImage fromScene(long tenantId, long productId, int index) {
        List<AssetEntity> scenes = eligibleBases(tenantId, productId, "PAGE_SCENE");
        if (scenes.isEmpty()) {
            throw missingBase("PAGE_SCENE");
        }
        AssetEntity scene = scenes.get(Math.min(index, scenes.size() - 1));
        return new BaseImage(storage.download(scene.getObjectKey()), "mixed", scene.getId(),
                sourceMediaId(scene), null);
    }

    private BaseImage fromDemo(long tenantId, long productId, int frameCandidate) {
        SourceMediaEntity video = sourceMediaMapper.selectOne(
                Wrappers.<SourceMediaEntity>lambdaQuery()
                        .eq(SourceMediaEntity::getTenantId, tenantId)
                        .eq(SourceMediaEntity::getProductId, productId)
                        .eq(SourceMediaEntity::getShotCode, "V1")
                        .eq(SourceMediaEntity::getKind, "VIDEO")
                        .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                        .orderByDesc(SourceMediaEntity::getId)
                        .last("limit 1"));
        if (video == null) {
            throw missingBase("V1");
        }
        byte[] bytes = storage.download(video.getObjectKey());
        Path temp = writeTemp(bytes, video.getOriginalFileName());
        try {
            double duration = video.getDurationS() == null ? 0
                    : video.getDurationS().doubleValue();
            List<FrameExtractor.Frame> frames = frameExtractor.candidates(temp, duration);
            FrameExtractor.Frame frame = frames.get(Math.floorMod(frameCandidate, frames.size()));
            return new BaseImage(frame.jpeg(), "real", null, video.getId(),
                    frame.timeSeconds());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    private Path writeTemp(byte[] bytes, @Nullable String originalName) {
        try {
            String suffix = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf('.')) : ".mp4";
            return Files.write(Files.createTempFile("ad-base", suffix), bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The source media the generated base asset was created from, if recorded. */
    private @Nullable Long sourceMediaId(AssetEntity asset) {
        try {
            JsonNode provenance = objectMapper.readTree(asset.getProvenanceJson());
            JsonNode ids = provenance.path("sourceMediaIds");
            if (ids.isArray() && !ids.isEmpty() && ids.get(0).isNumber()) {
                return ids.get(0).asLong();
            }
        } catch (RuntimeException ignored) {
            // provenance without source media ids - the field stays null
        }
        return null;
    }

    private Optional<AssetEntity> latestApproved(long tenantId, long productId, String specCode) {
        return eligibleBases(tenantId, productId, specCode).stream().findFirst();
    }

    /**
     * Page images eligible as ad bases, newest version first: APPROVED and also
     * PUBLISHED, since C3 production publishing flips approved images to
     * PUBLISHED and live products are exactly the ones that get ads.
     */
    public List<AssetEntity> eligibleBases(long tenantId, long productId, String specCode) {
        return new ArrayList<>(assetMapper.selectList(
                Wrappers.<AssetEntity>lambdaQuery()
                        .eq(AssetEntity::getTenantId, tenantId)
                        .eq(AssetEntity::getProductId, productId)
                        .eq(AssetEntity::getSpecCode, specCode)
                        .in(AssetEntity::getStatus, AssetStatus.APPROVED.name(),
                                AssetStatus.PUBLISHED.name())
                        .orderByDesc(AssetEntity::getVersion)));
    }

    private ApiException missingBase(String missing) {
        return new ApiException(HttpStatus.CONFLICT, "AD_BASE_MISSING",
                "No approved base image for the ad: " + missing,
                Map.of("missing", missing));
    }
}
