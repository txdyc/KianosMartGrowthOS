package com.kiano.content.video;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.facts.FieldSource;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.music.MusicPicker;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.qc.VideoInfo;
import com.kiano.content.qc.VideoProbe;
import com.kiano.content.template.TemplateBootstrap;
import com.kiano.content.video.h3.H3Properties;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared C5 Task 9 fixture: a HERO product with locked facts, store badges, an
 * approved script per type, ACCEPTED V1/V2/V3 footage, approved PAGE_MAIN and
 * PAGE_SCENE images and one licensed music track. The window picker, the music
 * picker and the video probe are replaced by stubs so the tests stay on the
 * orchestration instead of ffmpeg.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
abstract class VideoTestSupport {

    static final String SKU = "MG-KTL17";
    static final String PRODUCT_NAME = "Morgan 1.7L Kettle";

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected VideoRenderService renderService;

    @Autowired
    protected VideoCompositionCoordinator coordinator;

    @Autowired
    protected GenerationJobStore jobs;

    @Autowired
    protected GenerationRunStore runs;

    @Autowired
    protected AssetMapper assetMapper;

    @Autowired
    protected ObjectStorage storage;

    @Autowired
    protected FactSheetService factSheetService;

    @Autowired
    protected PolicyService policyService;

    @Autowired
    protected ProductProfileService profileService;

    @Autowired
    protected TemplateBootstrap bootstrap;

    @Autowired
    protected WorkflowRegistry workflows;

    @Autowired
    protected H3Properties h3Properties;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @MockitoBean
    protected ClipWindowPicker windowPicker;

    @MockitoBean
    protected MusicPicker musicPicker;

    @MockitoBean
    protected VideoProbe videoProbe;

    protected long tenantId;
    protected long productId;
    protected long userId;
    protected CurrentUser user;
    protected byte[] jpeg;
    protected long musicTrackId;

    @BeforeEach
    void seedVideoFixture() throws Exception {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from template");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from music_track");
        jdbcTemplate.update("delete from app_user");
        bootstrap.bootstrapAll();
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        long storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, "
                        + "regular_price, sale_price, price, status, synced_at) "
                        + "values (?, ?, -7001, 'simple', ?, ?, 299, 249, 249, 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId, SKU, PRODUCT_NAME);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", productId, tenantId);
        jpeg = ImageCodec.jpeg(productImage(), 0.9f);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'video-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "video-op@example.test");
        FactsJson facts = new FactsJson(SKU, "Electric Kettles", "1.7 L", 350, "220-240V",
                "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts, Map.of("model", FieldSource.P5));
        factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox"));
        policyService.save(user, sectionsWithBadges());
        for (VideoType type : VideoType.values()) {
            script(type, 1);
        }
        media("V1", "ACCEPTED");
        media("V2", "ACCEPTED");
        media("V3", "ACCEPTED");
        imageAsset("PAGE_MAIN", "main");
        imageAsset("PAGE_SCENE", "scene1");
        musicTrackId = musicTrack();
        // the config bean is shared with the cached context: every test starts from the defaults
        h3Properties.setBackend(H3Properties.Mode.LOCAL_FIRST);
        h3Properties.getApi().setEnabled(false);
        h3Properties.setRequestSeconds(5);

        when(videoProbe.probe(any(Path.class)))
                .thenReturn(new VideoInfo(1080, 1920, 30.0, 30.0, true));
        when(windowPicker.rank(any(Path.class), anyDouble(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    double duration = invocation.getArgument(1);
                    double window = invocation.getArgument(2);
                    return List.of(new ClipWindowPicker.Window(0, Math.min(window, duration), 10));
                });
        when(windowPicker.pick(anyList(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    List<ClipWindowPicker.Window> ranked = invocation.getArgument(0);
                    return ranked.get(Math.floorMod((int) invocation.getArgument(1),
                            ranked.size()));
                });
        when(musicPicker.pick(anyLong(), any(), anyInt())).thenReturn(
                Optional.of(new MusicPicker.MusicTrack(musicTrackId, "Upbeat Test",
                        "t" + tenantId + "/music/upbeat.mp3", 30.0)));
    }

    protected void script(VideoType type, int version) {
        String body = "{\"hook\":\"Meet the kettle\",\"captions\":[\"1.7 litre\","
                + "\"Auto shut-off\",\"Boils fast\"]}";
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "text_body, status, precheck_json, provenance_json, fact_version, created_at) "
                        + "values (?, ?, 'VIDEO_SCRIPT', ?, ?, 'TEXT', ?, 'APPROVED', '{}'::jsonb, "
                        + "'{}'::jsonb, 1, now())",
                tenantId, productId, type.wire(), version, body);
    }

    protected long media(String shot, String status) {
        String key = "t" + tenantId + "/source-media/" + productId + "/" + shot + ".mp4";
        storage.put(key, videoBytes(), "video/mp4");
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, content_type, size_bytes, duration_s, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'VIDEO', ?, ?, 'video/mp4', 2048, 30.0, ?, '{}', ?) "
                        + "returning id",
                Long.class, tenantId, productId, shot, shot + ".mp4", key,
                (shot.hashCode() + "0".repeat(58)), status);
    }

    protected void imageAsset(String specCode, String variant) {
        String key = "t" + tenantId + "/assets/" + productId + "/" + specCode + "/" + variant
                + "/v1.jpg";
        storage.put(key, jpeg, "image/jpeg");
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, thumb_object_key, width, height, status, precheck_json, "
                        + "provenance_json, created_at) values (?, ?, ?, ?, 1, 'IMAGE', ?, ?, 1080, "
                        + "1080, 'APPROVED', '{}'::jsonb, '{}'::jsonb, now())",
                tenantId, productId, specCode, variant, key, key);
    }

    protected long musicTrack() {
        String key = "t" + tenantId + "/music/upbeat.mp3";
        storage.put(key, new byte[]{1, 2, 3}, "audio/mpeg");
        return jdbcTemplate.queryForObject(
                "insert into music_track (tenant_id, title, artist, object_key, duration_s, "
                        + "license_type, license_source, license_proof, moods, status, sha256) "
                        + "values (?, 'Upbeat Test', 'Kiano', ?, 30.0, 'CC0', 'Kiano library', "
                        + "'owned recording', array['upbeat','uplifting'], 'ACTIVE', ?) returning id",
                Long.class, tenantId, key, "a".repeat(64));
    }

    protected void activateH3Workflow() {
        WorkflowRegistry.ComfyWorkflowView view = workflows.register(tenantId,
                fixture("/workflows/h3_i2v_fixture/workflow.json"),
                fixture("/workflows/h3_i2v_fixture/manifest.json"), userId);
        workflows.activate(tenantId, view.id(), userId);
    }

    /** All jobs of one run, oldest first. */
    protected List<GenerationJob> runJobs(long runId) {
        return jobs.findByRun(tenantId, runId);
    }

    protected GenerationJob jobOf(long runId, JobStep step, VideoType type) {
        return runJobs(runId).stream()
                .filter(job -> job.step() == step && type.wire().equals(job.variant()))
                .findFirst().orElseThrow(() -> new AssertionError("no " + step + " job for " + type));
    }

    /** Simulates the worker's report of a finished compose and the fan-out. */
    protected GenerationJob finishCompose(GenerationJob compose, double durationS) {
        String videoKey = compose.input().path("outputs").path("video").asString();
        String noMusicKey = compose.input().path("outputs").path("video_nomusic").asString();
        String posterKey = compose.input().path("outputs").path("poster").asString();
        storage.put(videoKey, videoBytes(), "video/mp4");
        storage.put(noMusicKey, videoBytes(), "video/mp4");
        storage.put(posterKey, jpeg, "image/jpeg");
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("video", Map.of("objectKey", videoKey, "durationS", durationS));
        outputs.put("video_nomusic", Map.of("objectKey", noMusicKey, "durationS", durationS));
        outputs.put("poster", Map.of("objectKey", posterKey));
        return complete(compose, Map.of("outputs", outputs));
    }

    /** Leases the job as the test worker, completes it and fans out. */
    protected GenerationJob complete(GenerationJob job, Object output) {
        assertThatLeased(job);
        GenerationJob completed = jobs.complete(job.id(), "test-worker", output, 0).orElseThrow();
        coordinator.onSucceeded(completed);
        return completed;
    }

    protected void assertThatLeased(GenerationJob job) {
        Set<ExecutorType> capabilities = switch (job.executor()) {
            case FFMPEG -> Set.of(ExecutorType.FFMPEG);
            case H3 -> Set.of(ExecutorType.H3);
            default -> Set.of(ExecutorType.COMFYUI);
        };
        boolean leased = jobs.lease("test-worker", capabilities)
                .filter(candidate -> candidate.id() == job.id()).isPresent();
        if (!leased) {
            throw new AssertionError("could not lease " + job.step() + " job " + job.id());
        }
    }

    protected List<AssetEntity> videoAssets(VideoType type) {
        List<AssetEntity> assets = new ArrayList<>(assetMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AssetEntity>lambdaQuery()
                        .eq(AssetEntity::getTenantId, tenantId)
                        .eq(AssetEntity::getProductId, productId)
                        .eq(AssetEntity::getSpecCode, "VIDEO_AD")
                        .eq(AssetEntity::getVariant, type.wire())
                        .orderByDesc(AssetEntity::getVersion)));
        return assets;
    }

    protected static byte[] videoBytes() {
        byte[] bytes = new byte[16];
        byte[] header = {'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        for (int i = 0; i < header.length; i++) {
            bytes[4 + i] = header[i];
        }
        return bytes;
    }

    static String fixture(String path) {
        try (InputStream in = VideoTestSupport.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static BufferedImage productImage() {
        BufferedImage img = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1080, 1080);
        g.setColor(new Color(180, 60, 40));
        g.fillRoundRect(200, 180, 680, 720, 40, 40);
        g.dispose();
        return img;
    }

    private static Map<PolicySection, SectionText> sectionsWithBadges() {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        sections.put(PolicySection.DELIVERY, new SectionText("Delivery", "1-3 days", "Fast delivery"));
        sections.put(PolicySection.COD, new SectionText("COD", "Pay on receipt", "COD"));
        sections.put(PolicySection.MOMO, new SectionText("MoMo", "MTN MoMo", "MTN MoMo"));
        sections.put(PolicySection.WARRANTY, new SectionText("Warranty", "1 year", null));
        sections.put(PolicySection.RETURNS, new SectionText("Returns", "7 days", null));
        return sections;
    }
}