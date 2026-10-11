package com.kiano.content.video;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.ads.AdBaseSelector;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.content.music.MusicPicker;
import com.kiano.content.music.MusicPicker.MusicTrack;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.qc.VideoProbe;
import com.kiano.content.video.h3.H3Backend;
import com.kiano.content.video.h3.H3BackendSelector;
import com.kiano.content.video.h3.H3PromptBuilder;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.ContentTypes;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.VideoInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plans and queues the short videos of one HERO product (C5 Task 9). After the
 * preconditions (409 VIDEO_PRECONDITIONS listing what is missing) it picks real
 * footage windows, renders the caption/end-card overlay PNGs, picks licensed
 * music and plans the EDL - then either creates the H3 hook job (the local
 * ComfyUI workflow when one is active, otherwise the provider API) or goes
 * straight to the VIDEO_COMPOSE job. Both H3 backends share one prompt and one
 * {@link VideoTypeContext}, so an escalation reuses the same plan.
 */
@Service
public class VideoRenderService {

    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final FactSheetService factSheetService;
    private final PolicyService policyService;
    private final AssetMapper assetMapper;
    private final SourceMediaMapper sourceMediaMapper;
    private final ObjectStorage storage;
    private final VideoProbe videoProbe;
    private final VideoOverlayRenderer overlayRenderer;
    private final ClipWindowPicker windowPicker;
    private final MusicPicker musicPicker;
    private final AdBaseSelector baseSelector;
    private final H3BackendSelector h3Selector;
    private final H3PromptBuilder h3PromptBuilder;
    private final VideoProperties videoProperties;
    private final VideoJobFactory jobFactory;
    private final GenerationRunStore runs;
    private final TaskQueue taskQueue;
    private final ObjectMapper objectMapper;

    public VideoRenderService(ProductCatalog productCatalog, ProductProfileService profileService,
            FactSheetService factSheetService, PolicyService policyService, AssetMapper assetMapper,
            SourceMediaMapper sourceMediaMapper, ObjectStorage storage, VideoProbe videoProbe,
            VideoOverlayRenderer overlayRenderer, ClipWindowPicker windowPicker,
            MusicPicker musicPicker, AdBaseSelector baseSelector, H3BackendSelector h3Selector,
            H3PromptBuilder h3PromptBuilder, VideoProperties videoProperties,
            VideoJobFactory jobFactory, GenerationRunStore runs, TaskQueue taskQueue,
            ObjectMapper objectMapper) {
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.factSheetService = factSheetService;
        this.policyService = policyService;
        this.assetMapper = assetMapper;
        this.sourceMediaMapper = sourceMediaMapper;
        this.storage = storage;
        this.videoProbe = videoProbe;
        this.overlayRenderer = overlayRenderer;
        this.windowPicker = windowPicker;
        this.musicPicker = musicPicker;
        this.baseSelector = baseSelector;
        this.h3Selector = h3Selector;
        this.h3PromptBuilder = h3PromptBuilder;
        this.videoProperties = videoProperties;
        this.jobFactory = jobFactory;
        this.runs = runs;
        this.taskQueue = taskQueue;
        this.objectMapper = objectMapper;
    }

    /**
     * Plans and queues the requested types (all when empty) into one VIDEO run and
     * returns its id. This is the heavy path - it downloads and ranks the footage
     * and renders the overlays - so requests only check the preconditions and
     * queue a {@link VideoRenderTaskHandler} task that calls it.
     */
    public long render(CurrentUser user, long productId, @Nullable Set<VideoType> typesOrAll,
            int candidate) {
        return render(user.tenantId(), user.userId(), productId, typesOrAll, candidate);
    }

    /**
     * What requests call (render button, review REGENERATE): checks the
     * preconditions synchronously (409 VIDEO_PRECONDITIONS) and queues a
     * VIDEO_RENDER task for the heavy planning. 409 VIDEO_RENDER_IN_PROGRESS
     * when the same render is already queued or running.
     */
    public long queueRender(CurrentUser user, long productId, @Nullable Set<VideoType> typesOrAll,
            int candidate) {
        Set<VideoType> types = typesOrAll == null || typesOrAll.isEmpty()
                ? EnumSet.allOf(VideoType.class)
                : EnumSet.copyOf(typesOrAll);
        checkPreconditions(user.tenantId(), productId, types);
        List<String> wire = types.stream().map(VideoType::wire).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("types", wire);
        payload.put("candidate", candidate);
        payload.put("userId", user.userId());
        return taskQueue.enqueue(user.tenantId(), VideoRenderTaskHandler.TYPE, payload,
                        "video-render:" + productId + ":" + String.join(",", wire) + ":" + candidate)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "VIDEO_RENDER_IN_PROGRESS",
                        "This video render is already queued or running"));
    }

    /** The task handler's entry point: {@code userId} is who queued the render. */
    public long render(long tenantId, @Nullable Long userId, long productId,
            @Nullable Set<VideoType> typesOrAll, int candidate) {
        Set<VideoType> types = typesOrAll == null || typesOrAll.isEmpty()
                ? EnumSet.allOf(VideoType.class)
                : EnumSet.copyOf(typesOrAll);
        checkPreconditions(tenantId, productId, types);
        ProductView product = productCatalog.findById(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "Product " + productId + " does not exist"));
        long runId = runs.create(tenantId, productId, userId, "VIDEO");
        for (VideoType type : types) {
            renderType(tenantId, product, runId, type, candidate);
        }
        return runId;
    }

    /**
     * Re-composes a trimmed cut of exactly the edited version (review editor,
     * Task 10): its compose inputs - footage, the generated H3 clip, captions,
     * end card and music - and its provenance (script, music track, templates,
     * price, H3 attempts) are reused as recorded, so nothing is downloaded,
     * re-ranked or re-picked; only the EDL changes.
     */
    public long recut(CurrentUser user, AssetEntity edited, Edl trimmed) {
        long tenantId = user.tenantId();
        long productId = edited.getProductId();
        VideoType type = VideoType.fromWire(trimmed.videoType());
        JsonNode recorded = objectMapper.readTree(edited.getProvenanceJson());
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : recorded.path("inputs").properties()) {
            inputs.put(entry.getKey(), entry.getValue().asString());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> provenance = new LinkedHashMap<>(
                objectMapper.treeToValue(recorded, Map.class));
        provenance.remove("edl"); // the asset stores the trimmed EDL it was cut from
        provenance.put("recutFrom", edited.getId());
        long runId = runs.create(tenantId, productId, user.userId(), "VIDEO");
        Map<String, String> outputs = Map.of(
                "video", outputKey(tenantId, runId, type, "video.mp4"),
                "video_nomusic", outputKey(tenantId, runId, type, "video_nomusic.mp4"),
                "poster", outputKey(tenantId, runId, type, "poster.jpg"));
        jobFactory.createCompose(tenantId, productId, runId, type.wire(),
                "video:" + runId + ":" + type.wire(), trimmed, inputs, outputs, provenance, null);
        return runId;
    }

    /** Every C5 precondition in one 409 VIDEO_PRECONDITIONS listing what is missing. */
    public void checkPreconditions(long tenantId, long productId, Set<VideoType> types) {
        List<String> missing = new ArrayList<>();
        if (profileService.tierOf(tenantId, productId) != ContentTier.HERO) {
            missing.add("NOT_HERO");
        }
        if (factSheetService.locked(tenantId, productId).isEmpty()) {
            missing.add("FACTS_NOT_LOCKED");
        }
        for (VideoType type : types) {
            if (approvedScript(tenantId, productId, type).isEmpty()) {
                missing.add("VIDEO_SCRIPT:" + type.wire());
            }
        }
        Set<String> requiredShots = new LinkedHashSet<>();
        requiredShots.add("V2");
        for (VideoType type : types) {
            requiredShots.add(type == VideoType.UNBOXING ? "V3" : "V1");
        }
        for (String shot : requiredShots) {
            if (acceptedVideo(tenantId, productId, shot) == null) {
                missing.add(shot);
            }
        }
        if (baseSelector.eligibleBases(tenantId, productId, "PAGE_MAIN").isEmpty()) {
            missing.add("PAGE_MAIN");
        }
        try {
            policyService.requireAdBadges(tenantId);
        } catch (ApiException ex) {
            if ("POLICY_BADGES_MISSING".equals(ex.getCode())) {
                missing.add("POLICY_BADGES_MISSING");
            } else {
                throw ex;
            }
        }
        if (musicPicker.pick(tenantId, types.iterator().next(), 0).isEmpty()) {
            missing.add("MUSIC_LIBRARY_EMPTY");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "VIDEO_PRECONDITIONS",
                    "Video render preconditions are not met", Map.of("missing", missing));
        }
    }

    private void renderType(long tenantId, ProductView product, long runId, VideoType type,
            int candidate) {
        long productId = product.id();
        AssetEntity scriptAsset = approvedScript(tenantId, productId, type)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "VIDEO_PRECONDITIONS",
                        "No approved VIDEO_SCRIPT for " + type.wire(),
                        Map.of("missing", List.of("VIDEO_SCRIPT:" + type.wire()))));
        VideoScriptText script = VideoScriptText.fromJson(scriptAsset.getTextBody());
        int captionCount = script.captions() == null ? 1 : Math.max(1, script.captions().size());

        Map<String, SourceMediaEntity> media = requiredMedia(tenantId, productId, type);
        Map<String, Path> files = download(media);
        try {
            Map<String, VideoPlanner.SourceClip> sources = new LinkedHashMap<>();
            for (Map.Entry<String, SourceMediaEntity> entry : media.entrySet()) {
                sources.put(entry.getKey(),
                        clip(videoProbe.probe(files.get(entry.getKey())), entry.getValue()));
            }
            VideoPlanner.Windows windows = pickWindows(type, files, sources, candidate);

            // object keys of the compose inputs
            Map<String, String> inputs = new LinkedHashMap<>();
            for (Map.Entry<String, SourceMediaEntity> entry : media.entrySet()) {
                inputs.put(entry.getKey(), entry.getValue().getObjectKey());
            }
            Map<String, Object> seeds = new LinkedHashMap<>();
            VideoOverlayRenderer.RenderedOverlays rendered = overlayRenderer.render(tenantId, product,
                    script, captionCount);
            String prefix = "t" + tenantId + "/video-inputs/" + runId + "/" + type.wire() + "/";
            for (Map.Entry<String, byte[]> entry : rendered.pngs().entrySet()) {
                String key = prefix + entry.getKey() + ".png";
                storage.put(key, entry.getValue(), "image/png");
                inputs.put(entry.getKey(), key);
            }
            seeds.put("templates", rendered.templateVersions());
            seeds.put("price", rendered.price());
            MusicTrack music = musicPicker
                    .pick(tenantId, type, rotation(tenantId, productId, type))
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MUSIC_LIBRARY_EMPTY",
                            "The music library has no active track",
                            Map.of("missing", List.of("MUSIC_LIBRARY_EMPTY"))));
            inputs.put("music", music.objectKey());
            seeds.put("musicTrackId", music.id());

            H3Hook hook = h3Hook(tenantId, product, runId, type);
            VideoPlanner.PlanInput planInput = new VideoPlanner.PlanInput(type, sources,
                    hook.backend() != null, windows, captionCount, "music");
            Map<String, String> outputs = Map.of(
                    "video", outputKey(tenantId, runId, type, "video.mp4"),
                    "video_nomusic", outputKey(tenantId, runId, type, "video_nomusic.mp4"),
                    "poster", outputKey(tenantId, runId, type, "poster.jpg"));
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("scriptAssetId", scriptAsset.getId());
            provenance.put("sourceMediaIds", media.values().stream()
                    .map(SourceMediaEntity::getId).toList());
            provenance.put("windows", windowsJson(windows));
            provenance.put("candidate", candidate);
            provenance.put("factVersion", scriptAsset.getFactVersion());
            provenance.put("inputs", inputs);
            provenance.putAll(seeds);
            VideoTypeContext context = new VideoTypeContext("video:" + runId + ":" + type.wire(),
                    type.wire(), product.sku(), candidate, hook.backend(), hook.prompt(),
                    hook.firstFrameKey(), hook.sceneLabel(), planInput, inputs, outputs,
                    outputKey(tenantId, runId, type, "h3_clip.mp4"), provenance, List.of());

            Edl edl = VideoPlanner.plan(planInput, videoProperties);
            if (hook.backend() == null) {
                jobFactory.createCompose(tenantId, productId, runId, context, edl, inputs,
                        composedProvenance(context, Map.of("reason", "H3_NO_BACKEND")));
                return;
            }
            if (hook.backend() == H3Backend.LOCAL
                    && jobFactory.createH3Local(tenantId, productId, runId, context).isPresent()) {
                return;
            }
            jobFactory.createH3Api(tenantId, productId, runId, context);
        } finally {
            files.values().forEach(VideoRenderService::deleteQuietly);
        }
    }

    /** The H3 hook preparation: backend, first frame, scene label and shared prompt. */
    private record H3Hook(@Nullable H3Backend backend, String prompt, String firstFrameKey,
            String sceneLabel) {

        static H3Hook none() {
            return new H3Hook(null, "", "", "");
        }
    }

    private H3Hook h3Hook(long tenantId, ProductView product, long runId, VideoType type) {
        String imageCode = type == VideoType.UNBOXING ? "PAGE_MAIN" : "PAGE_SCENE";
        if (baseSelector.eligibleBases(tenantId, product.id(), imageCode).isEmpty()) {
            return H3Hook.none();
        }
        Optional<H3Backend> backend = h3Selector.initial(tenantId);
        if (backend.isEmpty()) {
            return H3Hook.none();
        }
        String sceneLabel = "PAGE_MAIN".equals(imageCode)
                ? "the clean white-background setup shown in the reference image"
                : "the styled scene shown in the reference image";
        String firstFrameKey = storeFirstFrame(tenantId, product.id(), runId, type, imageCode);
        String prompt = h3PromptBuilder.build(tenantId, type, product, sceneLabel);
        return new H3Hook(backend.get(), prompt, firstFrameKey, sceneLabel);
    }

    /** Copies the reviewed first-frame image into the run's input area. */
    private String storeFirstFrame(long tenantId, long productId, long runId, VideoType type,
            String imageCode) {
        AssetEntity image = baseSelector.eligibleBases(tenantId, productId, imageCode).get(0);
        String key = "t" + tenantId + "/video-inputs/" + runId + "/" + type.wire() + "/h3_first"
                + extension(image.getObjectKey());
        storage.put(key, storage.download(image.getObjectKey()),
                ContentTypes.forKey(image.getObjectKey()));
        return key;
    }

    private static String extension(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        return dot < 0 ? ".jpg" : objectKey.substring(dot);
    }

    private static String outputKey(long tenantId, long runId, VideoType type, String name) {
        return "t" + tenantId + "/gen/video/" + runId + "/" + type.wire() + "/" + name;
    }

    private VideoPlanner.Windows pickWindows(VideoType type, Map<String, Path> files,
            Map<String, VideoPlanner.SourceClip> sources, int candidate) {
        VideoProperties.Recipe recipe = videoProperties.recipe(type);
        String hookKey = type == VideoType.UNBOXING ? "v3" : "v1";
        List<ClipWindowPicker.Window> hookRanked = rank(hookKey, files, sources,
                videoProperties.getHookSeconds());
        ClipWindowPicker.Window hook = windowPicker.pick(hookRanked, candidate, null);
        List<ClipWindowPicker.Window> bodyRanked = rank(hookKey, files, sources,
                recipe.getBodyMaxSeconds());
        // hook and body share one clip: never reuse the same frames twice
        ClipWindowPicker.Window body = windowPicker.pick(bodyRanked, candidate, hook);
        List<ClipWindowPicker.Window> captionsRanked = rank("v2", files, sources,
                videoProperties.getCaptionsSeconds());
        ClipWindowPicker.Window captions = windowPicker.pick(captionsRanked, candidate, null);
        return new VideoPlanner.Windows(new VideoPlanner.Window(hook.in(), hook.out()),
                new VideoPlanner.Window(body.in(), body.out()),
                new VideoPlanner.Window(captions.in(), captions.out()));
    }

    private List<ClipWindowPicker.Window> rank(String key, Map<String, Path> files,
            Map<String, VideoPlanner.SourceClip> sources, double windowSeconds) {
        return windowPicker.rank(files.get(key), sources.get(key).duration(), windowSeconds,
                videoProperties.getWindowCandidates());
    }

    private static VideoPlanner.SourceClip clip(VideoInfo info, SourceMediaEntity media) {
        return new VideoPlanner.SourceClip(media.getId(), info.durationSeconds(), info.width(),
                info.height(), info.hasAudio());
    }

    /** demo and problem cut V1, unboxing cuts V3; all three use V2 for the captions. */
    private Map<String, SourceMediaEntity> requiredMedia(long tenantId, long productId,
            VideoType type) {
        Map<String, SourceMediaEntity> media = new LinkedHashMap<>();
        if (type == VideoType.UNBOXING) {
            media.put("v3", requireVideo(tenantId, productId, "V3"));
        } else {
            media.put("v1", requireVideo(tenantId, productId, "V1"));
        }
        media.put("v2", requireVideo(tenantId, productId, "V2"));
        return media;
    }

    private SourceMediaEntity requireVideo(long tenantId, long productId, String shot) {
        SourceMediaEntity media = acceptedVideo(tenantId, productId, shot);
        if (media == null) {
            throw new ApiException(HttpStatus.CONFLICT, "VIDEO_PRECONDITIONS",
                    "No ACCEPTED " + shot + " video for product " + productId,
                    Map.of("missing", List.of(shot)));
        }
        return media;
    }

    private @Nullable SourceMediaEntity acceptedVideo(long tenantId, long productId, String shot) {
        return sourceMediaMapper.selectOne(Wrappers.<SourceMediaEntity>lambdaQuery()
                .eq(SourceMediaEntity::getTenantId, tenantId)
                .eq(SourceMediaEntity::getProductId, productId)
                .eq(SourceMediaEntity::getShotCode, shot)
                .eq(SourceMediaEntity::getKind, "VIDEO")
                .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                .orderByDesc(SourceMediaEntity::getId)
                .last("limit 1"));
    }

    private Optional<AssetEntity> approvedScript(long tenantId, long productId, VideoType type) {
        return Optional.ofNullable(assetMapper.selectOne(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "VIDEO_SCRIPT")
                .eq(AssetEntity::getVariant, type.wire())
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1")));
    }

    /** The product's VIDEO_AD version count of this type drives the music rotation. */
    private int rotation(long tenantId, long productId, VideoType type) {
        return Math.toIntExact(assetMapper.selectCount(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "VIDEO_AD")
                .eq(AssetEntity::getVariant, type.wire())));
    }

    private Map<String, Path> download(Map<String, SourceMediaEntity> media) {
        Map<String, Path> files = new LinkedHashMap<>();
        for (Map.Entry<String, SourceMediaEntity> entry : media.entrySet()) {
            files.put(entry.getKey(), writeTemp(entry.getValue()));
        }
        return files;
    }

    private Path writeTemp(SourceMediaEntity media) {
        String name = media.getOriginalFileName();
        String suffix = name != null && name.contains(".")
                ? name.substring(name.lastIndexOf('.')) : ".mp4";
        try {
            Path temp = Files.createTempFile("video-source-", suffix);
            Files.write(temp, storage.download(media.getObjectKey()));
            return temp;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort cleanup of the temp copy
        }
    }

    private static Map<String, Object> windowsJson(VideoPlanner.Windows windows) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("hook", windowJson(windows.hook()));
        json.put("body", windowJson(windows.body()));
        json.put("captions", windowJson(windows.captions()));
        return json;
    }

    private static Map<String, Object> windowJson(VideoPlanner.Window window) {
        return Map.of("in", window.in(), "out", window.out());
    }

    /** Seeds plus the H3 outcome recorded when the clip terminates. */
    public static Map<String, Object> composedProvenance(VideoTypeContext context,
            @Nullable Map<String, Object> fallback) {
        Map<String, Object> provenance = new LinkedHashMap<>(context.provenance());
        Map<String, Object> h3 = new LinkedHashMap<>();
        h3.put("attempts", context.h3Attempts());
        h3.put("backend", context.backend() == null ? null : context.backend().name());
        provenance.put("h3", h3);
        if (fallback != null) {
            provenance.put("h3Fallback", fallback);
        }
        return provenance;
    }
}