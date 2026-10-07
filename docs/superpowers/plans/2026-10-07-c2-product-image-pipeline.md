# C2 商品图流水线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 为一个 SKU 一键生成商品页图组：白底主图、多角度图、配件图和场景图。GPU 任务由笔记本上的 kiano-worker 主动拉取执行。所有生成物经过自动预检后进入审核看板，可以逐张通过、驳回或重新生成。

**Architecture：**
- **api 侧（content 模块）**：负责任务编排。规则是"前置任务成功后，再创建后续任务"。任务存放在 `generation_job` 表中，worker 通过 HTTPS 租用任务、上报心跳、提交结果，输入输出文件都通过预签名 URL 在 MinIO 中直接读写。
- **kiano-worker**：与 api 同一份代码，换一个启动类（`KianoWorkerApplication`，profile=worker），运行时不连数据库，只通过 HTTP 与 api 交互。它包含两个执行器：
  - **ComfyUI 执行器**：只负责抠图和场景图。工作流 JSON 及其输入绑定作为数据注册，带商用许可证白名单校验。
  - **确定性合成执行器**：用 Java2D 生成白底图、多角度图、配件图，以及场景图的输入画布。产品像素全程不经过 AI。
- **自动预检**：在 api 中完成。白底图检查边缘纯白和产品占比；场景图在产品掩膜区域内计算 SSIM，掩膜之外做 OCR。
- **模板渲染器**：用 Playwright 驱动无头 Chromium，加上 INFO/SPEC 两个模板。本 Sprint 只建渲染器和金样测试，任务接入在 C3 事实锁定之后完成。

**Tech Stack：** 沿用 C1 的全部技术栈（Spring Boot 4.1 / Jackson 3 / MyBatis-Plus / Flyway / PostgreSQL 16 / MinIO / Next.js / Vitest）。新增：
- ComfyUI HTTP API（`/prompt`、`/history`、`/view`、`/upload/image`、`/object_info`、`/system_stats`、`/interrupt`）
- `com.microsoft.playwright:playwright`（Java）
- `com.samskivert:jmustache`
- tesseract CLI（可选，用于 OCR）
- Noto Sans 字体（OFL 许可）

**Spec：** `docs/superpowers/specs/2026-10-07-content-module-design.md`。本计划覆盖：
- §3 原则 1、2、6
- §4 架构与 worker 协议
- §5 流水线（图片部分）
- §7.1
- §8 中的 `generation_job`、`asset`、`asset_review`、`comfy_workflow`、`template`、`asset_spec`
- §9 审核与预检
- §11 错误处理
- §12 硬件约束
- §14 C2

上级文档为 `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`。C1 计划为 `docs/superpowers/plans/2026-10-07-c1-skeleton-and-media-import.md`。

## Global Constraints

C1 计划中的 Global Constraints 全部继续有效。下面只列 C2 新增或细化的部分。

- **2026-10-07 用户确认的 C2 范围决定**：
  1. 本 Sprint 只建好模板渲染器、INFO/SPEC 模板和金样测试，**不把它们接入流水线**。C3 事实锁定后再接入。因此 C2 验收中的"完整图组" = 主图 + 多角度图 + 场景图 + 配件图。
  2. 抠图用 ComfyUI（BiRefNet）。白底图、多角度图、配件图和场景输入画布用**确定性合成**，在 worker 中用 Java2D 完成，不经过 AI。
  3. ComfyUI 执行器**不绑定具体模型**。工作流 JSON 和 manifest 作为数据注册；C2 附带 CUTOUT v1 和 SCENE v1 两个基线工作流。样板阶段定稿后，通过管理接口上传新版本即可替换，**不改代码**。
- **AI 只改环境，不改产品（规格 §3.1）**：场景图工作流的最后一步必须把原始产品像素按掩膜贴回。预检在产品掩膜区域内计算 SSIM，低于阈值时标记 `PRODUCT_MISMATCH`。
- **模型许可证必须允许商用（规格 §12）**：manifest 中每个模型都必须有 `license` 字段，值必须在白名单 `kiano.content.workflows.allowed-licenses` 中，默认为 `MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, CreativeML-OpenRAIL++-M`，否则注册直接拒绝。已知必须排除的模型：本机的 `flux1-dev-fp8`（FLUX.1-dev 非商用许可）和 `RMBG-2.0`（BRIA 非商用许可）。
- **GPU 任务单并发**：worker 一次只租用 1 个任务。
- **worker 协议**严格按规格 §4.2（路径、字段、30 秒心跳、10 分钟没有心跳则租约失效）。worker 用独立的 token 认证，权限只限 `/api/v1/worker/**`。
- **ComfyUI 不可用时**：任务设为 `WAITING_EXECUTOR`，**不消耗重试次数**；worker 恢复后自动继续。可重试错误按指数退避，最多 3 次；不可重试错误直接 FAILED（规格 §11）。
- **重新生成总是产生新的 `version`，旧版本保留不覆盖（规格 §9.1）**。新版本创建时，同一 `(product, spec_code, variant)` 下状态为 `DRAFT` 或 `IN_REVIEW` 的旧版本改为 `ARCHIVED`；`APPROVED` 的旧版本不动，到 C3 发布时再处理。
- **图片规格（规格 §7.1）**：所有页面图都是 1600×1600 JPEG（质量 92）。白底图的背景必须是纯白 `#FFFFFF`，产品占比 80–85%（产品包围盒长边 ÷ 1600），不允许有文字、水印或边框。
- **导出文件名（规格 §7.5 / v1.2 附录 B）**格式为 `{SKU}_{角度}_{素材类型}_{宽}x{高}_v{n}.{ext}`。页面图的"角度"段用 `page-main`、`page-p2`、`page-scene1`、`page-inbox`；素材类型规定为：白底类为 `real`，场景图为 `mixed`。
- **新增的全局表**（不含 `tenant_id`，属于系统配置或基础设施）：`asset_spec`、`scene_preset`、`scene_room_rule`、`worker_status`。其余新增表都有 `tenant_id`。
- **数组列**：规格中的 `parent_job_ids[]` 按原样用 `bigint[]`；`reason_codes[]` 和 `model_refs[]` 改用 `jsonb` 数组，以便沿用 MyBatis-Plus 的 String 映射。
- 界面文字继续中英双语，新增的枚举（预检标记、驳回原因、任务状态、步骤）都要在两种语言的字典中有文案。
- **相对规格的有意扩展**：
  - `generation_job` 增加 `step`、`run_id`、`output_json`、`run_after`、`heartbeat_at`、`started_at`。
  - 新增 `generation_run`（一次流水线运行，用于显示进度）和 `worker_status`（worker 在线状态）两张表。
  - `asset` 增加 `thumb_object_key` 和 `run_id`。
  - `comfy_workflow` 增加 `manifest_json`。

## Review Focus

1. **笔记本休眠，租约在任务执行中过期**：休眠期间 api 回收了租约，任务被别的轮次重新租走。worker 醒来后调用 `complete` 或 `heartbeat`，必须收到 409 `LEASE_LOST`，并丢弃这次结果，不能重复生成资产。测试放在 Task 4（`complete_afterLeaseReaped_returns409`）和 Task 6（`leaseLost_discardsResultAndContinues`）。
2. **源照片带 EXIF orientation=6**：worker 上传给 ComfyUI 之前必须先把方向转正。抠图和合成结果都必须是正立的。测试放在 Task 8（`preprocess_appliesExifOrientation`）。
3. **白色产品放在白色卡纸上，抠图失败**：抠图结果可能是空的（alpha 覆盖率低于 1%），也可能是整幅画面（包围盒贴满四边）。这时合成步骤必须以不可重试错误失败，错误码分别为 `CUTOUT_EMPTY` 和 `CUTOUT_FULL_FRAME`，不能产出一张空白的白底主图。测试放在 Task 1 和 Task 7。
4. **重拍之后重新运行流水线**：旧版本中还在 `IN_REVIEW` 的资产要改为 `ARCHIVED`，新结果成为 v2；上一次运行还没结束时再次启动，返回 409 `PIPELINE_RUNNING`。测试放在 Task 10。
5. **worker token 与用户 JWT 必须互相隔离**：用户 JWT 调用 worker 端点返回 403；worker token 调用用户端点返回 401；token 缺失或错误时返回 401，并使用统一的 ApiError 格式。测试放在 Task 2。

---

## 文件结构

```
kiano-api/src/main/java/com/kiano/
  imaging/                        # 新模块：纯函数，不含 Spring bean 和 IO 之外的依赖
    CutoutGeometry, WhiteComposer, SceneCanvasComposer, MaskedSsim, WhiteChecks,
    ImageCodec, CutoutException
  workerprotocol/                 # 新模块：worker 与 api 共享的协议类型（record 和 enum）
    ExecutorType, JobStep, LeaseRequest, LeaseResponse, JobPayload, HeartbeatResponse,
    CompleteRequest, OutputFile, FailRequest
  worker/                         # 新模块：只在 profile=worker 下运行
    KianoWorkerApplication, WorkerProperties, WorkerClient, WorkerLoop,
    GenerationExecutor, ExecutionContext, JobResult, ExecutorException,
    ExecutorUnavailableException
    composite/  CompositeExecutor
    comfy/      ComfyClient, ComfyUIExecutor, WorkflowBinder, InputPreprocessor
  content/
    generation/  GenerationJobStore, GenerationJob, JobStatus, GenerationRunStore,
                 WorkerController, WorkerStatusStore, ExecutorAvailabilityMonitor,
                 LeaseReaper, JobCompletionListener, WorkerAuthProperties
    workflow/    ComfyWorkflowEntity, ComfyWorkflowMapper, WorkflowManifest,
                 WorkflowRegistry, WorkflowController, WorkflowBootstrap
    asset/       AssetEntity, AssetMapper, AssetService, AssetFileName, AssetStatus,
                 Precheck, PrecheckResult, PrecheckFlag, TextDetector,
                 TesseractTextDetector, AssetReviewEntity, AssetReviewMapper,
                 ReviewService, ReviewController, RejectReason
    pipeline/    PipelineService, PipelineProperties, PipelineCompletionHandler,
                 ScenePresetService, PipelineController
    template/    TemplateEntity, TemplateMapper, TemplateRegistry, TemplateRenderer,
                 TemplateFacts, TemplateBootstrap
    text/        KeywordMatcher               # 从 ShotRequirementService 提取出来，供两处共用
  platform/
    storage/     ObjectStorage（+presignPut、download）, S3ObjectStorage
    auth/        SecurityConfig（+ worker 过滤链）, WorkerTokenFilter
kiano-api/src/main/resources/
  application-worker.yml
  db/migration/ V5__generation.sql  V6__workflow.sql  V7__asset.sql
                V8__scene_preset.sql  V9__template.sql
  comfy/CUTOUT/v1/{workflow.json,manifest.json}
  comfy/SCENE/v1/{workflow.json,manifest.json}
  templates/content/PAGE_INFO/v1.html  templates/content/PAGE_SPEC/v1.html
  templates/fonts/NotoSans-Regular.woff2  NotoSans-Bold.woff2  OFL.txt
kiano-web/src/
  app/(app)/review/page.tsx
  app/(app)/products/[id]/page.tsx（修改）
  components/PipelinePanel.tsx  components/RejectDialog.tsx
  lib/types.ts  lib/review.ts（+ review.test.ts）
  i18n/en.ts  i18n/zh.ts  i18n/i18n.test.ts（修改）
```

---

### Task 1: imaging 纯函数库

**Files:**
- Create: `imaging/{CutoutGeometry,WhiteComposer,SceneCanvasComposer,MaskedSsim,WhiteChecks,ImageCodec,CutoutException}.java`
- Test: `imaging/{WhiteComposerTest,SceneCanvasComposerTest,MaskedSsimTest,WhiteChecksTest}.java`

**Interfaces:**
- Produces:
```java
public record Bounds(int x, int y, int width, int height) {}           // 放在 CutoutGeometry 中
public final class CutoutGeometry {
  static Bounds alphaBounds(BufferedImage rgba, int alphaThreshold);      // alpha > 阈值的像素包围盒
  static double alphaCoverage(BufferedImage rgba, int alphaThreshold);    // 像素占比 0..1
  static void requireUsable(BufferedImage rgba);  // 覆盖率 < 0.01 抛 CutoutException("CUTOUT_EMPTY")；
                                                  // 包围盒四边都距画面边缘 ≤ 2px 抛 CutoutException("CUTOUT_FULL_FRAME")
}
public record Composed(BufferedImage image, double occupancy) {}
public final class WhiteComposer { static Composed compose(BufferedImage cutout, int canvas, double occupancy); }
public record SceneCanvas(BufferedImage image, BufferedImage productMask) {}  // mask 中产品为白色
public final class SceneCanvasComposer {
  static SceneCanvas compose(BufferedImage cutout, int canvas, double occupancy, double bottomMargin);
}
public final class MaskedSsim { static double compute(BufferedImage a, BufferedImage b, BufferedImage mask, int erodePx); }
public final class WhiteChecks {
  static boolean edgesPureWhite(BufferedImage img, int borderPx);
  static double occupancy(BufferedImage img, int nonWhiteThreshold);    // 非白像素包围盒长边 ÷ 画布边长
}
public final class ImageCodec { static byte[] jpeg(BufferedImage, float quality); static byte[] png(BufferedImage);
  static BufferedImage read(byte[]); static byte[] thumbnailJpeg(BufferedImage, int longSide); }
public class CutoutException extends RuntimeException { String code(); }
```
- 算法要求：
  - `WhiteComposer`：先 `requireUsable`；按 alpha>8 的包围盒裁切；等比缩放，使长边 = `round(canvas × occupancy)`（双三次插值）；在纯白画布上居中，按 alpha 混合。不加阴影。
  - `SceneCanvasComposer`：缩放规则同上；水平居中，底边距画布底部 `canvas × bottomMargin`；背景填充 RGB(128,128,128)；掩膜 = 缩放后的 alpha 二值化（>127 为 255）。
  - `MaskedSsim`：先转为灰度；掩膜腐蚀 `erodePx` 像素；只统计完全落在腐蚀后掩膜内的 8×8 窗口（步长 4），用标准 SSIM 常数（C1=(0.01·255)²，C2=(0.03·255)²），取所有窗口的平均值。有效窗口少于 16 个时返回 `Double.NaN`。

- [ ] **Step 1: 写失败的测试**（测试图在测试中合成，例如透明背景上的一个红色圆角矩形）

```java
@Test void compose_centersAndHitsOccupancy()       // 输出 1600×1600；occupancy 在 0.825±0.005；四角像素为 0xFFFFFF
@Test void compose_edgesStayPureWhite()            // WhiteChecks.edgesPureWhite(out, 2) == true
@Test void emptyCutout_throwsCutoutEmpty()         // 全透明 → code "CUTOUT_EMPTY"
@Test void fullFrameCutout_throwsFullFrame()       // 全不透明 → code "CUTOUT_FULL_FRAME"
@Test void sceneCanvas_maskMatchesProductAndBottomAnchored() // 掩膜白色区域的包围盒底边 = 1600 − 192（±2）
@Test void ssim_identicalInMask_isOne()            // 1.0 ±1e-6
@Test void ssim_productAltered_dropsBelow0_6()     // 在掩膜区域内画一块噪声后 < 0.6
@Test void ssim_changesOutsideMask_ignored()       // 只改掩膜外的像素 → 仍为 1.0
@Test void ssim_tinyMask_isNaN()
@Test void occupancy_measuresNonWhiteBounds()
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `cd kiano-api && ./mvnw -q test -Dtest='WhiteComposerTest,SceneCanvasComposerTest,MaskedSsimTest,WhiteChecksTest'`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**；`ModularityTests` 也要通过（imaging 不能依赖其他模块）
- [ ] **Step 5: Commit** — `git commit -m "feat(imaging): deterministic white/scene composition and masked SSIM"`

---

### Task 2: 预签名上传、对象下载与 worker token 认证

**Files:**
- Modify: `platform/storage/ObjectStorage.java`、`S3ObjectStorage.java`
- Create: `platform/auth/WorkerTokenFilter.java`；Modify: `platform/auth/SecurityConfig.java`、`application.yml`、`.env.example`
- Test: `platform/storage/S3ObjectStorageTest.java`（追加用例）、`platform/auth/WorkerAuthTest.java`（测试源码中加一个 `TestWorkerController`，映射 `GET /api/v1/worker/_ping`）

**Interfaces:**
- Produces: `ObjectStorage.presignPut(String key, Duration ttl, String contentType) -> URI`（用 public-endpoint 签名）；`ObjectStorage.download(String key) -> byte[]`。
- Produces: worker 认证。
  - 新增一条 `SecurityFilterChain`，用 `@Order(1)` 并 `securityMatcher("/api/v1/worker/**")`。
  - `WorkerTokenFilter` 读取 `Authorization: Bearer <token>`，计算 SHA-256 后，与配置 `kiano.worker.token-sha256`（小写 hex）做常量时间比较（`MessageDigest.isEqual`）。匹配时设置认证主体 `worker`，权限为 `ROLE_WORKER`；不匹配或缺失时返回 401 `UNAUTHENTICATED`（ApiError 格式）。
  - 这条链上的所有请求都要求 `ROLE_WORKER`，所以用户 JWT 在这里得到 403 `FORBIDDEN`。
  - 原有的用户链不变：worker token 不是合法的 JWT，在用户链上得到 401。
  - `token-sha256` 为空时，worker 端点全部返回 401。
- `.env.example` 新增 `KIANO_WORKER_TOKEN_SHA256=`（api 使用）和 `KIANO_WORKER_TOKEN=`（worker 使用），注释说明可以用 `openssl rand -hex 32` 生成 token，用 `printf %s "$TOKEN" | sha256sum` 计算哈希。

- [ ] **Step 1: 写失败的测试**

```java
// S3ObjectStorageTest
@Test void presignPut_thenHttpPut_objectExists()      // 用 java.net.http.HttpClient 向预签名 URL 发 PUT → exists 为 true；URL 的 host:port 等于 public-endpoint
@Test void download_returnsBytes()
// WorkerAuthTest（application-test.yml 中写死一个测试 token 的 sha256）
@Test void workerToken_onWorkerEndpoint_200()
@Test void missingToken_onWorkerEndpoint_401()         // code UNAUTHENTICATED，带 traceId
@Test void wrongToken_onWorkerEndpoint_401()
@Test void userJwt_onWorkerEndpoint_403()              // 用 OWNER 的 cookie 访问 → FORBIDDEN
@Test void workerToken_onUserEndpoint_401()            // GET /api/v1/auth/me
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='S3ObjectStorageTest,WorkerAuthTest,AuthControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): presigned PUT, object download and worker token auth chain"`

---

### Task 3: generation_job 表与任务存储

**Files:**
- Create: `db/migration/V5__generation.sql`
- Create: `workerprotocol/{ExecutorType,JobStep}.java`
- Create: `content/generation/{GenerationJobStore,GenerationJob,JobStatus,GenerationRunStore,WorkerStatusStore}.java`
- Test: `content/generation/GenerationJobStoreTest.java`

**Interfaces:**
- Produces: `enum ExecutorType { COMFYUI, COMPOSITE }`。
- Produces: `enum JobStep { CUTOUT(COMFYUI, null), SCENE_INPUT(COMPOSITE, null), WHITE_MAIN(COMPOSITE, "PAGE_MAIN"), WHITE_ANGLE(COMPOSITE, "PAGE_ANGLE"), INBOX(COMPOSITE, "PAGE_INBOX"), SCENE(COMFYUI, "PAGE_SCENE") }`。每个步骤带 `executor()` 和 `assetSpecCode()`，后者为 null 表示这是中间步骤，不产生资产。
- Produces: `enum JobStatus { QUEUED, LEASED, WAITING_EXECUTOR, SUCCEEDED, FAILED, CANCELLED }`。
- Produces: `record GenerationJob(long id, long tenantId, long productId, long runId, JobStep step, String variant, ExecutorType executor, JsonNode input, List<Long> parentJobIds, JobStatus status, int attempts, int maxAttempts, String leaseOwner, Instant leaseExpiresAt, JsonNode output, Double gpuSeconds, String error, Instant createdAt, Instant finishedAt)`。
- Produces: `GenerationJobStore` 的方法：
  - `create(long tenantId, long productId, long runId, JobStep step, String variant, Object input, List<Long> parentJobIds) -> long`
  - `lease(String workerId, Set<ExecutorType> capabilities) -> Optional<GenerationJob>`
  - `heartbeat(long jobId, String workerId) -> boolean`：任务仍由该 worker 持有时返回 true
  - `complete(long jobId, String workerId, Object output, double gpuSeconds) -> Optional<GenerationJob>`：租约已丢失时返回 empty
  - `fail(long jobId, String workerId, String error, boolean retryable, boolean executorUnavailable) -> Optional<GenerationJob>`
  - `reapExpiredLeases() -> int`
  - `markWaiting(ExecutorType)`、`releaseWaiting(ExecutorType) -> int`
  - `retry(long tenantId, long jobId)`：只允许 FAILED 状态，否则抛 409 `JOB_NOT_FAILED`
  - `findByRun(long tenantId, long runId) -> List<GenerationJob>`、`find(long tenantId, long jobId)`
- Produces: `GenerationRunStore`：`create(tenantId, productId, createdBy) -> long`、`latest(tenantId, productId) -> Optional<GenerationRun>`、`refreshStatus(long runId)`（所有任务都终结后，全部成功设为 DONE，否则设为 PARTIAL）、`hasActive(tenantId, productId) -> boolean`。`record GenerationRun(long id, long productId, String status, Instant createdAt, Instant finishedAt)`。
- Produces: `WorkerStatusStore`：`touch(String workerId, Set<ExecutorType> capabilities, Set<ExecutorType> unavailable)`、`list() -> List<WorkerStatusView>`、`isAvailable(ExecutorType, Duration window) -> boolean`。`record WorkerStatusView(String workerId, Instant lastSeenAt, Set<ExecutorType> capabilities, Set<ExecutorType> unavailable)`。

`V5__generation.sql`：
```sql
create table generation_run (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  kind text not null default 'IMAGE_SET',
  status text not null check (status in ('RUNNING','DONE','PARTIAL')),
  created_by bigint references app_user(id),
  created_at timestamptz not null default now(), finished_at timestamptz);
create index generation_run_product on generation_run (tenant_id, product_id, id desc);

create table generation_job (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  run_id bigint not null references generation_run(id),
  step text not null, asset_spec_code text, variant text not null, executor text not null,
  input_json jsonb not null default '{}', parent_job_ids bigint[] not null default '{}',
  status text not null check (status in ('QUEUED','LEASED','WAITING_EXECUTOR','SUCCEEDED','FAILED','CANCELLED')),
  attempts int not null default 0, max_attempts int not null default 3,
  run_after timestamptz not null default now(),
  lease_owner text, lease_expires_at timestamptz, heartbeat_at timestamptz,
  output_json jsonb, gpu_seconds numeric(10,2), cost_usd numeric(10,4), error text,
  created_at timestamptz not null default now(), started_at timestamptz, finished_at timestamptz);
create index generation_job_ready on generation_job (executor, run_after) where status = 'QUEUED';
create index generation_job_run on generation_job (run_id);

create table worker_status (
  worker_id text primary key, last_seen_at timestamptz not null,
  capabilities jsonb not null default '[]', unavailable jsonb not null default '[]');
```

租用 SQL（`FOR UPDATE SKIP LOCKED`；租约 10 分钟；认领时 attempts+1）：
```sql
update generation_job j set status='LEASED', attempts=j.attempts+1, lease_owner=:workerId,
       lease_expires_at=now() + interval '10 minutes', heartbeat_at=now(), started_at=coalesce(j.started_at, now())
 where j.id = (select id from generation_job where status='QUEUED' and run_after <= now()
               and executor = any(:executors) order by id for update skip locked limit 1)
returning *;
```
状态迁移规则：
- `heartbeat`：只有 `status='LEASED' and lease_owner=:workerId` 时，才把租约延长到 now()+10 分钟。
- `fail`：
  - `executorUnavailable=true`：设为 WAITING_EXECUTOR，`attempts = attempts − 1`（退还这次尝试），清空租约。
  - 可重试且 attempts < max：设为 QUEUED，`run_after = now() + 30s × 2^(attempts−1)`。
  - 其余情况：设为 FAILED。
- `reapExpiredLeases`：LEASED 且 `lease_expires_at < now()` 的任务，attempts ≥ max 时设为 FAILED（error 为 "lease expired"），否则设为 QUEUED。
- `markWaiting(E)`：executor 为 E 的 QUEUED 任务设为 WAITING_EXECUTOR。`releaseWaiting(E)` 做相反的迁移。
- 以上所有终结状态都写 `finished_at`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void lease_onlyMatchingExecutor_andIncrementsAttempts()
@Test void lease_concurrentWorkers_neverShareJob()        // 2 个线程，6 个任务 → 每个任务只被租用 1 次
@Test void heartbeat_wrongWorker_false_andDoesNotExtend()
@Test void complete_storesOutputAndGpuSeconds()
@Test void complete_afterLeaseReaped_returnsEmpty()       // 先把 lease_expires_at 改成过去时间 → reap → complete 返回 empty
@Test void fail_executorUnavailable_waitingAndRefundsAttempt()  // attempts 回到 0，状态为 WAITING_EXECUTOR
@Test void fail_retryable_backoff_thenFailedAtMax()
@Test void fail_nonRetryable_failedImmediately()
@Test void reap_expiredLease_requeues()
@Test void markWaiting_thenRelease_roundTrip()            // 只影响对应 executor 的任务
@Test void retry_onlyFromFailed()                         // 状态不是 FAILED → 409 JOB_NOT_FAILED；FAILED → QUEUED，attempts 清零
@Test void runStatus_doneOrPartialWhenAllTerminal()
@Test void workerStatus_availability_window()             // touch 后 isAvailable(COMFYUI, 90s) 为 true；last_seen 早于 2 分钟前时为 false；E 在 unavailable 中时也为 false
```

- [ ] **Step 2–4: 先确认失败 → 实现（用 JdbcTemplate，风格与 C1 的 TaskDispatcher 一致）→ 确认通过**　Run: `./mvnw -q test -Dtest=GenerationJobStoreTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): generation_job store with SKIP LOCKED leases and executor waiting"`

---

### Task 4: worker 协议端点、租约回收与执行器可用性监控

**Files:**
- Create: `workerprotocol/{LeaseRequest,LeaseResponse,JobPayload,HeartbeatResponse,CompleteRequest,OutputFile,FailRequest}.java`
- Create: `content/generation/{WorkerController,JobCompletionListener,LeaseReaper,ExecutorAvailabilityMonitor,WorkerAuthProperties}.java`
- Test: `content/generation/WorkerControllerTest.java`、`content/generation/ExecutorAvailabilityMonitorTest.java`

**Interfaces:**
- Produces（协议 record，字段名与规格 §4.2 一致）：
```java
record LeaseRequest(String workerId, Set<ExecutorType> capabilities, Set<ExecutorType> unavailable, int max) {}
record JobPayload(long id, JobStep step, String variant, ExecutorType executor, JsonNode input) {}
record LeaseResponse(JobPayload job, Map<String, URI> inputUrls, Map<String, URI> outputUploadUrls) {}
record HeartbeatResponse(boolean ok) {}
record OutputFile(String name, String objectKey, Integer width, Integer height, Double durationS, String sha256) {}
record CompleteRequest(List<OutputFile> outputs, double gpuSeconds) {}
record FailRequest(String error, boolean retryable, boolean executorUnavailable) {}
```
- 端点：
  - `POST /api/v1/worker/lease`：处理顺序如下。
    1. `WorkerStatusStore.touch`。
    2. 对 `unavailable` 中的每个执行器调用 `markWaiting`，对 `capabilities` 中的每个执行器调用 `releaseWaiting`。
    3. `lease(workerId, capabilities)`；`max` 大于 1 时按 1 处理。
    4. 没有任务时返回 204；有任务时返回 200 `LeaseResponse`。
  - `inputUrls`：由 `input_json.inputs`（名称 → objectKey）生成预签名 GET。
  - `outputUploadUrls`：由 `input_json.outputs`（名称 → objectKey）生成预签名 PUT，content type 为 `image/png`。
  - 预签名 URL 的有效期取 `kiano.worker.url-ttl`，默认 60 分钟。
  - `POST /api/v1/worker/jobs/{id}/heartbeat`：任务仍由该 worker 持有时返回 200 `{ok:true}`，否则返回 409 `LEASE_LOST`。workerId 来自请求头 `X-Worker-Id`，heartbeat、complete、fail 三个端点都用这个请求头。
  - `POST .../complete`：
    1. 检查 `outputs` 的名称集合是否与 `input_json.outputs` 一致、每个 objectKey 是否都存在于存储中；不满足时把任务按可重试失败处理，并返回 422 `OUTPUT_MISSING`。
    2. 调用 `store.complete`；租约已丢失时返回 409 `LEASE_LOST`。
    3. 在提交事务之后调用 `JobCompletionListener.onSucceeded(GenerationJob)`（该接口由 Task 10 实现），再刷新运行状态。成功返回 200。
  - `POST .../fail`：调用 `store.fail`；租约已丢失时返回 409；成功返回 200。
- Produces: `interface JobCompletionListener { void onSucceeded(GenerationJob job); }`。本任务提供一个默认的空实现（`@ConditionalOnMissingBean`），Task 10 替换它。
- `LeaseReaper`：每 30 秒运行一次 `reapExpiredLeases`。`ExecutorAvailabilityMonitor`：每 30 秒运行一次，对 COMFYUI 和 COMPOSITE 分别判断：最近 90 秒内没有任何 worker 报告该执行器可用时，调用 `markWaiting`，否则调用 `releaseWaiting`。这一条覆盖笔记本合盖、worker 完全离线的情况。两个定时任务都受 `kiano.worker.monitor-enabled` 控制，测试中关闭，改为直接调用。

- [ ] **Step 1: 写失败的测试**（MockMvc，使用 worker token，直接在库中插入任务）

```java
@Test void lease_returnsJobWithPresignedUrls()            // inputUrls.image 和 outputUploadUrls.cutout 都指向 public-endpoint
@Test void lease_noJob_204()
@Test void lease_reportsUnavailable_marksComfyJobsWaiting() // unavailable=[COMFYUI] → COMFYUI 任务变为 WAITING_EXECUTOR，COMPOSITE 任务仍可租用
@Test void lease_capabilityBack_releasesWaiting()
@Test void heartbeat_ok_thenLeaseLost409()
@Test void complete_missingOutputObject_422_andRequeued()
@Test void complete_success_callsListenerOnce()           // 用一个记录调用的测试 listener bean
@Test void complete_afterLeaseReaped_returns409()          // Review Focus 1
@Test void fail_executorUnavailable_waiting()
// ExecutorAvailabilityMonitorTest
@Test void noRecentWorker_marksQueuedComfyJobsWaiting()
@Test void workerSeenWithCapability_releases()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='WorkerControllerTest,ExecutorAvailabilityMonitorTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): worker lease/heartbeat/complete/fail endpoints and availability monitor"`

---

### Task 5: ComfyUI 工作流注册表

**Files:**
- Create: `db/migration/V6__workflow.sql`
- Create: `content/workflow/{ComfyWorkflowEntity,ComfyWorkflowMapper,WorkflowManifest,WorkflowRegistry,WorkflowController,WorkflowBootstrap,WorkflowProperties}.java`
- Test: `content/workflow/WorkflowRegistryTest.java`、`WorkflowControllerTest.java`；测试资源 `src/test/resources/comfy-fixtures/{good,missing-node,bad-license}/{workflow.json,manifest.json}`

**Interfaces:**
- manifest 格式（`WorkflowManifest` record 与它一一对应）：
```json
{
  "code": "SCENE",
  "inputs":  { "image": {"node": "10", "field": "image", "maxLongSide": 2400},
               "mask":  {"node": "11", "field": "image"} },
  "params":  { "positive": {"node": "6", "field": "text"},
               "negative": {"node": "7", "field": "text"},
               "seed":     {"node": "3", "field": "seed"} },
  "outputs": { "image": {"node": "30"} },
  "requiredNodeClasses": ["LoadImage", "KSampler", "ImageCompositeMasked", "SaveImage"],
  "models": [ {"name": "SDXL base 1.0", "file": "sd_xl_base_1.0.safetensors",
               "license": "CreativeML-OpenRAIL++-M",
               "source": "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0"} ]
}
```
- 校验规则（任一条不满足就拒绝，返回 422 `WORKFLOW_INVALID`，`details.errors` 列出全部问题）：
  - `workflow.json` 是 ComfyUI **API 格式**：顶层是"节点 ID → 节点"的映射，每个节点都有 `class_type` 和 `inputs`。
  - `inputs`、`params`、`outputs` 引用的节点都必须存在；`field` 必须存在于该节点的 `inputs` 中。
  - `models` 不能为空，每个模型的 `license` 都在白名单内。
  - `code` 只能是 `CUTOUT` 或 `SCENE`。
- 表 `comfy_workflow`：`id, tenant_id, code, version, workflow_json jsonb, manifest_json jsonb, model_refs jsonb, status (DRAFT|APPROVED|RETIRED), created_by, created_at`，约束 `UNIQUE(tenant_id, code, version)`。
- Produces: `WorkflowRegistry` 的方法：
  - `register(long tenantId, String workflowJson, String manifestJson, Long userId) -> ComfyWorkflowView`：新建版本 n+1，状态为 DRAFT。
  - `activate(long tenantId, long id)`：该版本设为 APPROVED，同一 code 之前的 APPROVED 版本设为 RETIRED，并写审计 `WORKFLOW_ACTIVATED`。
  - `active(long tenantId, String code) -> Optional<ComfyWorkflowView>`
  - `get(long tenantId, long id)`
  - `record ComfyWorkflowView(long id, String code, int version, String status, JsonNode workflow, WorkflowManifest manifest, Instant createdAt)`。
- `WorkflowBootstrap`（`ApplicationRunner`）：对 `CUTOUT` 和 `SCENE`，如果该 tenant 下还没有任何版本，就从 classpath 的 `comfy/{code}/v1/` 读取文件，注册后立即激活。classpath 中缺少文件时只记录 WARN，不报错，因为 Task 12 才会创建这些文件。
- 端点（都需要 OWNER）：`GET /api/v1/content/workflows`；`POST /api/v1/content/workflows`（multipart，字段 `workflow` 和 `manifest`）→ 201；`POST /api/v1/content/workflows/{id}/activate` → 200。

- [ ] **Step 1: 写失败的测试**

```java
@Test void register_good_isDraftVersion1_thenActivateApproves()
@Test void register_secondVersion_increments_andActivateRetiresPrevious()
@Test void register_bindingToMissingNode_rejectedWithAllErrors()
@Test void register_nonCommercialLicense_rejected()       // license "FLUX-1-dev-Non-Commercial" 或 "BRIA-RMBG-2.0" → 拒绝
@Test void register_uiFormatJson_rejected()               // 顶层带 "nodes" 数组的 UI 格式 → 拒绝
@Test void bootstrap_registersClasspathV1Once()           // 用测试 classpath 中的 comfy/CUTOUT/v1 运行两次 → 只有 1 行
@Test void operator_cannotRegister_403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='WorkflowRegistryTest,WorkflowControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): ComfyUI workflow registry with binding and commercial-license validation"`

---

### Task 6: kiano-worker 进程骨架与工作循环

**Files:**
- Create: `worker/{KianoWorkerApplication,WorkerProperties,WorkerClient,WorkerLoop,GenerationExecutor,ExecutionContext,JobResult,ExecutorException,ExecutorUnavailableException}.java`
- Create: `src/main/resources/application-worker.yml`
- Modify: `kiano-api/pom.xml`（`spring-boot-maven-plugin` 加 `<layout>ZIP</layout>`，以便用 `-Dloader.main` 选择启动类）
- Test: `worker/WorkerLoopIT.java`（api 用 `RANDOM_PORT` 启动，worker 组件在测试中手动构造）、`worker/WorkerLoopTest.java`（用假的 WorkerClient 做单元测试）

**Interfaces:**
- Produces: 执行器相关接口（对应规格 §4.3）。
```java
public interface GenerationExecutor {
  ExecutorType type();
  boolean available();                               // 健康探测，执行器自己缓存结果 10 秒
  JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException;
}
public record ExecutionContext(Path workDir, Map<String, Path> inputs, Map<String, Path> outputs) {}
public record JobResult(double gpuSeconds) {}       // 输出文件写到 ctx.outputs 指定的路径
public class ExecutorException extends Exception { boolean retryable(); }
public class ExecutorUnavailableException extends ExecutorException {}   // retryable() 恒为 true
```
- Produces: `WorkerClient` 的方法：`lease(LeaseRequest) -> Optional<LeaseResponse>`、`heartbeat(long jobId) -> boolean`（409 时返回 false）、`complete(long jobId, CompleteRequest) -> boolean`、`fail(long jobId, FailRequest) -> boolean`、`download(URI, Path)`、`upload(URI, Path, String contentType)`。请求都带 `Authorization: Bearer {token}` 和 `X-Worker-Id`。
- Produces: `WorkerLoop.runOnce() -> boolean`（处理了一个任务时返回 true）。流程如下：
  1. 对所有执行器调用 `available()`，把它们分成 `capabilities` 和 `unavailable` 两组。
  2. 调用 `lease`。
  3. 启动心跳：每 `heartbeat-interval`（默认 30 秒）一次。收到 409 时把"租约丢失"标志置为 true，并中断执行器（Task 8 中 ComfyUI 执行器响应中断时会调用 `/interrupt`）。
  4. 把 `inputUrls` 下载到 `workDir/in/{name}`。
  5. 调用 `execute`。
  6. 如果租约已丢失，丢弃结果，记一条 WARN，返回 true。
  7. 计算每个输出文件的 sha256 和宽高，上传到 `outputUploadUrls`，然后调用 `complete`。
  8. 失败时调用 `fail`：`ExecutorUnavailableException` 对应 `executorUnavailable=true`；`ExecutorException.retryable` 原样传递；其他异常按可重试处理。
  9. 无论成功失败，最后都删除 `workDir`。
- `KianoWorkerApplication` 的要点：
  - 标注 `@SpringBootApplication(scanBasePackages = "com.kiano.worker")`，**同时**标注 `@Profile("worker")`。这样 api 的组件扫描会跳过它，不会把它当作配置类加载。
  - worker 包下所有 bean 都标注 `@Profile("worker")`。
  - 用 `@Scheduled(fixedDelayString = "${kiano.worker.idle-delay:3s}")` 循环调用 `runOnce`，处理完一个任务后立刻再试下一个。
- `application-worker.yml` 的内容：
  - `spring.main.web-application-type: none`。
  - `spring.autoconfigure.exclude` 列出 DataSource、Flyway、MyBatis-Plus、Security 的自动配置类。Boot 4 中这些类的包名以实际为准。
  - `kiano.worker` 下的配置项：`api-base-url`（默认 `http://localhost:8081`）、`token`（`${KIANO_WORKER_TOKEN}`）、`id`（默认取主机名）、`work-dir`（默认 `${java.io.tmpdir}/kiano-worker`）、`heartbeat-interval: 30s`、`idle-delay: 3s`。
- 本地运行 worker：`./mvnw spring-boot:run -Dspring-boot.run.main-class=com.kiano.worker.KianoWorkerApplication -Dspring-boot.run.profiles=worker`。

- [ ] **Step 1: 写失败的测试**

```java
// WorkerLoopIT：api 运行在随机端口，用 Testcontainers 提供 PG 和 MinIO；测试中注册一个 TEST_ECHO 执行器，
// 它把输入原样复制为输出。任务的 executor 用 COMPOSITE，以便在协议层面复用。
@Test void runOnce_leasesExecutesUploadsAndCompletes()     // 任务变为 SUCCEEDED；输出对象存在；output_json 中有 sha256 和宽高
@Test void runOnce_executorUnavailable_reportsWaiting()
@Test void runOnce_nonRetryableException_failsJob()
@Test void runOnce_noJob_returnsFalse()
// WorkerLoopTest（假 WorkerClient）
@Test void leaseLost_discardsResultAndContinues()          // heartbeat 返回 false → 不调用 complete
@Test void unavailableExecutor_reportedInLeaseRequest()
@Test void workDir_alwaysCleanedUp()
// ModularityTests 仍然通过：worker 只依赖 workerprotocol 和 imaging
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='WorkerLoopIT,WorkerLoopTest,ModularityTests'`
- [ ] **Step 5: 确认 api 仍能正常启动**：`./mvnw -q -DskipTests package && java -jar target/kiano-api-*.jar` 能起来（Ctrl-C 退出）；`java -Dloader.main=com.kiano.worker.KianoWorkerApplication -Dspring.profiles.active=worker -jar target/kiano-api-*.jar` 启动后开始轮询，api 没开时日志里出现连接失败的 WARN，进程不退出。
- [ ] **Step 6: Commit** — `git commit -m "feat(worker): kiano-worker process with lease/heartbeat/upload loop"`

---

### Task 7: 确定性合成执行器

**Files:**
- Create: `worker/composite/CompositeExecutor.java`
- Test: `worker/composite/CompositeExecutorTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WhiteComposer`、`SceneCanvasComposer`、`CutoutGeometry`、`ImageCodec`。
- Produces: `CompositeExecutor implements GenerationExecutor`，`type()` 为 COMPOSITE，`available()` 恒为 true。按 `job.step()` 分派：
  - `WHITE_MAIN`、`WHITE_ANGLE`、`INBOX`：输入 `cutout`（RGBA PNG）；参数 `input.composer = {canvas:1600, occupancy:0.825}`；输出 `image`（PNG）。
  - `SCENE_INPUT`：输入 `cutout`；参数 `input.placement = {canvas:1600, occupancy:0.60, bottomMargin:0.12}`；输出 `image`（PNG）和 `mask`（灰度 PNG，产品为白色）。
  - `CutoutException` 转换为 `ExecutorException(retryable=false)`，消息以错误码开头，例如 `"CUTOUT_EMPTY: ..."`。
  - 其他 step 抛 `ExecutorException(retryable=false, "UNSUPPORTED_STEP")`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void whiteMain_writes1600PngWithOccupancy()
@Test void sceneInput_writesImageAndMask()
@Test void emptyCutout_failsNonRetryableWithCode()      // Review Focus 3：消息以 "CUTOUT_EMPTY" 开头
@Test void unknownStep_failsNonRetryable()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=CompositeExecutorTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(worker): deterministic composite executor (white, angle, inbox, scene canvas)"`

---

### Task 8: ComfyUI 执行器

**Files:**
- Create: `worker/comfy/{ComfyClient,ComfyUIExecutor,WorkflowBinder,InputPreprocessor,ComfyProperties}.java`
- Test: `worker/comfy/ComfyUIExecutorTest.java`（用 WireMock 模拟 ComfyUI）、`WorkflowBinderTest.java`、`InputPreprocessorTest.java`；测试资源 `src/test/resources/comfy-replay/{prompt-ok.json,prompt-node-errors.json,history-running.json,history-success.json,history-error.json,object-info.json}`

**Interfaces:**
- Consumes: `JobPayload.input` 中有 `workflow = {code, version, json, manifest}`。api 在创建 COMFYUI 任务时就把工作流内容整份写进 input_json，worker 不需要再查注册表。还有 `params`（名称 → 值）。
- Produces: `ComfyClient` 的方法：
  - `systemStats()`：超时 3 秒，用于健康探测。
  - `objectInfoClasses() -> Set<String>`：结果缓存 5 分钟。
  - `uploadImage(Path) -> String`：上传到 `/upload/image`，`subfolder=kiano`、`overwrite=true`，返回 `kiano/{name}`。
  - `queuePrompt(JsonNode workflow, String clientId) -> String promptId`：`node_errors` 不为空时抛不可重试的 `ExecutorException`，消息里带上 node_errors。
  - `history(String promptId) -> Optional<JsonNode>`
  - `view(String filename, String subfolder, String type) -> byte[]`
  - `interrupt()`
- Produces: `WorkflowBinder.bind(JsonNode workflow, WorkflowManifest manifest, Map<String,String> uploadedInputs, Map<String,Object> params) -> JsonNode`：返回一份修改过的拷贝，按绑定关系把值写进对应节点的 `inputs.{field}`。
- Produces: `InputPreprocessor.prepare(Path in, Integer maxLongSide) -> Path`：读入图片，按 EXIF orientation 转正，按需等比缩小到 `maxLongSide`，再写出 PNG。未指定 `maxLongSide` 且 orientation 为 1 时直接返回原文件。
- `ComfyUIExecutor.execute` 的流程：
  1. 检查 `manifest.requiredNodeClasses` 是否都在 `objectInfoClasses` 中，缺少时抛不可重试异常，消息为 `"ComfyUI is missing node classes: [...]"`。
  2. 对每个输入先 prepare 再 upload。
  3. `bind`。
  4. `queuePrompt`。
  5. 每秒轮询一次 `history`，直到 `status.completed == true` 或超时（`kiano.worker.comfy.timeout`，默认 15 分钟）。超时时调用 `interrupt`，再抛可重试异常。
  6. 如果 `status_str == "error"`，抛可重试异常，消息里带上 ComfyUI 的错误信息。
  7. 对每个 `manifest.outputs.{name}.node`，取 `outputs[node].images[0]`，调用 `view` 下载后写入 `ctx.outputs.get(name)`。
  8. gpuSeconds 用 history 消息里 `execution_start` 和 `execution_success` 两个时间戳之差计算；缺少时用墙钟时间。
- 错误分类：任何调用遇到连接被拒或连接超时，抛 `ExecutorUnavailableException`；HTTP 5xx 抛可重试异常；`available()` 等价于 `systemStats()` 成功。
- 配置：`kiano.worker.comfy.base-url`，默认 `http://127.0.0.1:8188`。

- [ ] **Step 1: 写失败的测试**

```java
// WorkflowBinderTest
@Test void bind_writesInputsAndParams_withoutMutatingOriginal()
// InputPreprocessorTest
@Test void preprocess_appliesExifOrientation()      // Review Focus 2：4000×3000 + Orientation=6（用 commons-imaging 写入）→ 输出 3000×4000
@Test void preprocess_downscalesToMaxLongSide()     // 长边 2400
// ComfyUIExecutorTest（WireMock）
@Test void success_uploadsQueuesPollsAndDownloadsOutput()  // 输出文件字节与 /view 返回的一致；gpuSeconds 与 history 中的时间戳一致
@Test void nodeErrors_nonRetryable()
@Test void missingNodeClass_nonRetryable()
@Test void connectionRefused_executorUnavailable()  // base-url 指向一个没有服务的端口
@Test void http500_retryable()
@Test void executionError_retryableWithMessage()
@Test void timeout_interruptsAndRetryable()         // timeout 设为 2 秒，history 一直返回空 → 验证调用过 POST /interrupt
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='ComfyUIExecutorTest,WorkflowBinderTest,InputPreprocessorTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(worker): ComfyUI executor with workflow binding, EXIF-safe inputs and failure classes"`

---

### Task 9: 资产表、文件命名与自动预检

**Files:**
- Create: `db/migration/V7__asset.sql`
- Create: `content/asset/{AssetEntity,AssetMapper,AssetStatus,AssetService,AssetFileName,Precheck,PrecheckResult,PrecheckFlag,PrecheckProperties,TextDetector,TesseractTextDetector,TsvParser}.java`
- Test: `content/asset/{AssetFileNameTest,PrecheckTest,AssetServiceTest,TsvParserTest}.java`

**Interfaces:**
- 表：
```sql
create table asset_spec (code text primary key, kind text not null, tier text not null,
  width int, height int, format text, pipeline_ref text);
insert into asset_spec values
 ('PAGE_MAIN','IMAGE','STANDARD',1600,1600,'jpeg','WHITE_MAIN'),
 ('PAGE_ANGLE','IMAGE','STANDARD',1600,1600,'jpeg','WHITE_ANGLE'),
 ('PAGE_SCENE','IMAGE','STANDARD',1600,1600,'jpeg','SCENE'),
 ('PAGE_INBOX','IMAGE','STANDARD',1600,1600,'jpeg','INBOX'),
 ('PAGE_INFO','IMAGE','STANDARD',1600,1600,'jpeg','TEMPLATE'),
 ('PAGE_SPEC','IMAGE','STANDARD',1600,1600,'jpeg','TEMPLATE');

create table asset (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  spec_code text not null references asset_spec(code), variant text not null, version int not null,
  kind text not null check (kind in ('IMAGE','VIDEO','TEXT')),
  object_key text, thumb_object_key text, text_body text, width int, height int, duration_s numeric(8,2),
  status text not null check (status in ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','STALE','ARCHIVED')),
  precheck_json jsonb not null default '{}', provenance_json jsonb not null default '{}',
  ai_ratio numeric(4,3), depends_on_price boolean not null default false, price_snapshot numeric(12,2),
  fact_version int, file_name text, run_id bigint references generation_run(id),
  created_at timestamptz not null default now(),
  unique (tenant_id, product_id, spec_code, variant, version));
create index asset_product_status on asset (tenant_id, product_id, status);

create table asset_review (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), asset_id bigint not null references asset(id),
  reviewer bigint not null references app_user(id),
  decision text not null check (decision in ('APPROVE','REJECT','REGENERATE')),
  reason_codes jsonb not null default '[]', comment text, created_at timestamptz not null default now());
```
- Produces: `AssetFileName.format(String sku, String angle, String type, int w, int h, int version, String ext) -> String` 和 `parse(String fileName) -> Parsed(sku, angle, type, w, h, version, ext)`。解析时从右往左取 4 个固定段，剩下的部分是 SKU，所以 SKU 中可以带下划线。
- Produces: `enum PrecheckFlag { PRODUCT_MISMATCH, AI_TEXT, EDGE_NOT_WHITE, OCCUPANCY_OUT_OF_RANGE }`；`record PrecheckResult(List<PrecheckFlag> flags, Map<String, Object> metrics)`，metrics 包含 `ssim`、`occupancy`、`ocrWords`、`ocr`（值为 `"OK"` 或 `"SKIPPED"`）。
- Produces: `Precheck` 的两个方法：
  - `white(BufferedImage image)`：检查 `edgesPureWhite(img, 2)` 和 occupancy 是否在 [`min-occupancy`, `max-occupancy`] 内，默认 0.80–0.85。用于 PAGE_MAIN 和 PAGE_ANGLE。PAGE_INBOX 只检查边缘。
  - `scene(BufferedImage output, BufferedImage sceneInput, BufferedImage productMask)`：SSIM 小于 `ssim-min`（默认 0.90）或为 NaN 时标记 PRODUCT_MISMATCH；把产品区域用灰色涂掉后交给 `TextDetector`，检测到至少一个"字母数字字符 ≥ 3 且置信度 ≥ 60"的词时标记 AI_TEXT。
  - 配置前缀 `kiano.content.precheck`。白底类合成图由代码生成、没有 AI 参与，所以不做 OCR，以免产品本身印的字被误报。
- Produces: `interface TextDetector { boolean enabled(); List<OcrWord> detect(Path png); }`；`record OcrWord(String text, double confidence)`。`TesseractTextDetector` 执行 `{tesseract-path} <png> stdout --psm 11 tsv`，用 `TsvParser` 解析输出。`kiano.content.precheck.tesseract-path` 为空时 `enabled()` 返回 false。
- Produces: `AssetService.createFromJob(GenerationJob job, String sku, Map<String, Object> provenance) -> AssetView`。处理顺序：
  1. 从存储中下载 `output.image`，编码为 JPEG（质量 92），生成长边 400px 的缩略图。
  2. 版本号 = max(version) + 1。
  3. 写入对象存储：`t{tid}/assets/{productId}/{spec}/{variant}/v{n}.jpg` 和对应的 `_thumb.jpg`。
  4. 跑预检。SCENE 步骤需要的 sceneInput 和 mask 来自父任务 `SCENE_INPUT` 的输出。
  5. 插入一行，状态为 IN_REVIEW（DRAFT 只在事务内短暂存在，不对外可见）。
  6. 把同一 `(product, spec_code, variant)` 下状态为 DRAFT 或 IN_REVIEW 的旧版本改为 ARCHIVED。
  7. `file_name` 用 `AssetFileName` 生成。
  8. 写审计 `ASSET_CREATED`，actor 为 SYSTEM。
- Produces: `record AssetView(long id, long productId, String specCode, String variant, int version, String status, List<PrecheckFlag> flags, Map<String,Object> metrics, String fileName, Long sourceMediaId, Instant createdAt)`。

- [ ] **Step 1: 写失败的测试**

```java
// AssetFileNameTest
@Test void format_pageMain()                // "MG-BL200_page-main_real_1600x1600_v1.jpg"
@Test void roundTrip_skuWithUnderscore()    // "MG_FAN_16_page-scene2_mixed_1600x1600_v3.jpg"
@Test void parse_adName_fromAppendixB()     // "MG-BL200_pricehook_real_1080x1350_v1.jpg"
// PrecheckTest
@Test void white_compliant_noFlags()
@Test void white_greyEdge_flagsEdgeNotWhite()
@Test void white_occupancy90_flags()
@Test void scene_productPasteBack_noFlags()       // 产品像素一致，背景不同 → 不标记
@Test void scene_productAltered_flagsMismatch()   // 对应验收："预检能标记出被改动的产品"
@Test void scene_textOutsideProduct_flagsAiText() // 使用假的 TextDetector
@Test void scene_ocrDisabled_metricsSkipped()
// TsvParserTest
@Test void parsesWordsAndConfidence_skipsEmpty()
// AssetServiceTest（SpringBootTest；直接插入 SUCCEEDED 的任务，并把输出对象上传到 MinIO）
@Test void createFromJob_storesJpegThumbVersion1_inReview()
@Test void secondVersion_archivesPreviousInReview_keepsApproved()
@Test void sceneAsset_usesParentSceneInputForSsim()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='AssetFileNameTest,PrecheckTest,TsvParserTest,AssetServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): asset store, export file names and automatic precheck"`

---

### Task 10: 流水线编排与场景预设

**Files:**
- Create: `db/migration/V8__scene_preset.sql`
- Create: `content/text/KeywordMatcher.java`；Modify: `content/shots/ShotRequirementService.java`（改为使用 KeywordMatcher，行为不变）
- Create: `content/pipeline/{PipelineService,PipelineProperties,PipelineCompletionHandler,ScenePresetService,PipelineController}.java`
- Test: `content/text/KeywordMatcherTest.java`、`content/pipeline/{PipelineServiceTest,PipelineDagTest,ScenePresetServiceTest,PipelineControllerTest}.java`；`ShotRequirementServiceTest` 必须原样通过

**Interfaces:**
- Produces: `KeywordMatcher.matches(String keyword, List<String> categorySlugs, String productName) -> Match`，`enum Match { CATEGORY, NAME, NONE }`。规则与 C1 现有实现完全相同：先归一化，再按整词匹配，允许 `s`/`es` 复数，品类优先于商品名。
- 场景预设表：
```sql
create table scene_room_rule (keyword text primary key, room text not null);
insert into scene_room_rule values ('blender','KITCHEN'),('kettle','KITCHEN'),('rice-cooker','KITCHEN'),
 ('pressure-cooker','KITCHEN'),('air-fryer','KITCHEN'),('juicer','KITCHEN'),('toaster','KITCHEN'),
 ('stove','KITCHEN'),('fan','LIVING'),('television','LIVING'),('tv','LIVING'),
 ('iron','LAUNDRY'),('steamer','LAUNDRY'),('hair-dryer','BEDROOM');
create table scene_preset (code text primary key, room text not null, sort_order int not null,
  prompt_en text not null);
-- 每个房间（KITCHEN、LIVING、LAUNDRY、BEDROOM、GENERIC）各 4 条，共 20 条。prompt 用英文描述
-- Ghana 中产家庭的场景，例如 "on a clean tiled kitchen counter in a bright Accra apartment, morning light"。
-- 每条只描述环境，不描述产品本身。
```
- Produces: `ScenePresetService.presetsFor(List<String> categorySlugs, String productName, int count) -> List<ScenePrompt>`。用 `scene_room_rule` 加上 KeywordMatcher 判断房间（品类优先，没有匹配时为 GENERIC），按 sort_order 取前 `count` 条，不够时循环使用。`record ScenePrompt(String presetCode, String positive, String negative)`：positive 为 prompt 加上 `kiano.content.pipeline.scene-prompt-suffix`；negative 取 `scene-negative`。两项默认值如下，写在 `application.yml` 中：
  - suffix：`", Ghanaian home interior, photorealistic product photography, natural light, high detail"`
  - negative：`"text, letters, watermark, logo, brand name, US plug, European plug, people, hands, deformed, blurry"`
- Produces: `PipelineService.start(CurrentUser user, long productId) -> long runId`，规则如下：
  - 只接受顶层商品。商品已有 RUNNING 的运行时，返回 409 `PIPELINE_RUNNING`。
  - P1 不是 ACCEPTED 时，返回 422 `SHOTS_NOT_READY`，`details.missing` 为 `["P1"]`。
  - CUTOUT 或 SCENE 没有已激活的工作流时，返回 409 `WORKFLOW_NOT_ACTIVE`，`details.code` 为缺少的那个。
  - 需要抠图的镜头 = P1，加上 `angle-shots`（默认 `[P2,P3,P4,P6]`），加上 P8，加上场景来源镜头。场景来源取 `scene-source-shots`（默认 `[P2,P3,P1]`）中第一个状态为 ACCEPTED 的。不是 ACCEPTED 的镜头直接跳过。
  - 对每个需要抠图的镜头创建一个 CUTOUT 任务，`input_json` 为：
    ```json
    {
      "sourceMediaId": …, "shotCode": "P2",
      "downstream": [{"step": "WHITE_ANGLE", "variant": "P2"}, {"step": "SCENE_INPUT", "variant": "scene1"}, …],
      "workflow": {"code": "CUTOUT", "version": n, "json": …, "manifest": …},
      "params": {},
      "inputs":  {"image": "<source object_key>"},
      "outputs": {"cutout": "t{tid}/gen/{jobId}/cutout.png"}
    }
    ```
    outputs 中的 key 含有 jobId，所以要先插入任务，再回填 input_json。
  - 写审计 `IMAGE_PIPELINE_STARTED`。
- CUTOUT 任务的后续任务**在创建时就确定**，写在 `input_json.downstream` 中，完成时只按这个列表创建。这样重新生成某一张角度图时，不会顺带重新生成场景图。`start` 计算 downstream 的规则如下：
  - P1 → `{WHITE_MAIN, main}`。
  - 角度镜头 Pk → `{WHITE_ANGLE, Pk}`。
  - P8 → `{INBOX, P8}`。
  - 场景来源 → N 个 `{SCENE_INPUT, scene1..N}`；N 在 STANDARD 时为 2，HERO 时为 4，取 `scene-count`。
  - 同一镜头同时属于多个集合时（例如 P2 既是角度镜头又是场景来源），合并到同一个 CUTOUT 任务的 downstream 中，不重复抠图。
- Produces: `PipelineCompletionHandler implements JobCompletionListener`，按完成的任务类型创建后续任务（每个后续任务的 `parent_job_ids` 都是 `[父任务 id]`，`outputs` 的路径规则与 CUTOUT 相同）：
  - CUTOUT → 按 `input.downstream` 逐项创建 WHITE_MAIN、WHITE_ANGLE、INBOX 或 SCENE_INPUT 任务。
  - SCENE_INPUT → 创建 SCENE。input 中写入 SCENE 工作流、`params = {positive, negative, seed}`（seed 用 `SecureRandom` 生成的非负 long）、`inputs = {image, mask}`（取自父任务的输出）。
  - WHITE_MAIN、WHITE_ANGLE、INBOX、SCENE 完成后调用 `AssetService.createFromJob`。provenance 包括 `sourceMediaIds`、`workflow{code,version}`（取整条链上的 CUTOUT 和 SCENE）、`models`、`seed`、`jobIds`（整条链）、`composer`（参数和 occupancy）。
- 端点：
  - `POST /api/v1/content/products/{id}/image-pipeline`（OPERATOR）→ 202 `{runId}`。
  - `GET /api/v1/content/products/{id}/image-pipeline`（VIEWER）→ `{runId, status, createdAt, finishedAt, jobs:[{id, step, variant, executor, status, attempts, error, gpuSeconds}]}`；从未运行过时返回 204。
  - `POST /api/v1/content/jobs/{id}/retry`（OPERATOR）。
  - `GET /api/v1/content/workers`（VIEWER）→ `List<WorkerStatusView>`，每项附加 `online`（最近 90 秒内出现过）。

- [ ] **Step 1: 写失败的测试**

```java
// KeywordMatcherTest：把 ShotRequirementServiceTest 中名称匹配的用例逐条搬过来，再加 Match 类型的断言
// ScenePresetServiceTest
@Test void blenderByName_kitchenPresets()  @Test void unknown_generic()  @Test void count4_cyclesWhenFewer()
// PipelineServiceTest
@Test void start_createsCutoutsForAcceptedShotsOnly()  // P1–P4、P6、P8 都 ACCEPTED，P3 为 RESHOOT → 不为 P3 创建 CUTOUT
@Test void start_p2AngleAndSceneSource_singleCutoutWithMergedDownstream() // HERO 时 P2 只有一个 CUTOUT，downstream = WHITE_ANGLE P2 加上 4 个 SCENE_INPUT
@Test void start_missingP1_422()
@Test void start_whileRunning_409()                    // Review Focus 4
@Test void start_noActiveWorkflow_409()
// PipelineDagTest：用 store.lease/complete 模拟 worker；每次 complete 前把假的输出对象上传到 MinIO
@Test void hero_fullDag_producesTenAssetsInReview()    // MAIN 1 + ANGLE 4 + SCENE 4 + INBOX 1；运行状态为 DONE
@Test void standard_twoScenes()
@Test void missingP8_noInbox()
@Test void sceneJob_inputCarriesPromptSeedAndParentOutputs()
@Test void failedCutout_runPartial_noDownstream()
@Test void rerunAfterReshoot_v2_archivesInReviewV1()   // Review Focus 4
// PipelineControllerTest：202 / 204 / retry / workers 列表 / VIEWER 调用 POST 返回 403
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='KeywordMatcherTest,ShotRequirementServiceTest,ScenePresetServiceTest,PipelineServiceTest,PipelineDagTest,PipelineControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): image pipeline orchestration, scene presets and shared keyword matcher"`

---

### Task 11: 审核 API

**Files:**
- Create: `content/asset/{AssetReviewEntity,AssetReviewMapper,ReviewService,ReviewController,RejectReason}.java`
- Test: `content/asset/ReviewServiceTest.java`、`ReviewControllerTest.java`

**Interfaces:**
- Produces: `enum RejectReason { PRODUCT_MISMATCH, AI_ARTIFACT, WRONG_FACT, TEXT_ERROR, STYLE, LOW_QUALITY, POLICY }`（规格 §8）。
- Produces: `GET /api/v1/content/assets?productId=&status=`（VIEWER），返回 `List<ReviewItem>`。排序规则：有预检标记的在前，然后按 sku、spec 顺序（MAIN → ANGLE → SCENE → INBOX）、variant 排列。
  ```java
  record ReviewItem(long assetId, long productId, String sku, String productName, String specCode, String variant,
      int version, String status, List<PrecheckFlag> flags, Map<String,Object> metrics, String imageUrl, String thumbUrl,
      String sourceThumbUrl, String sourceUrl, String fileName)
  ```
  `sourceThumbUrl` 是 provenance 中第一个 source_media 的缩略图。所有 URL 都是有效期 15 分钟的预签名 GET。
- Produces: `POST /api/v1/content/assets/{id}/review {decision, reasonCodes, comment}`（OPERATOR），返回更新后的 `ReviewItem`。规则：
  - 资产状态必须是 IN_REVIEW，否则返回 409 `ASSET_NOT_IN_REVIEW`。
  - REJECT 至少要带一个 reasonCode，否则返回 400 `VALIDATION_FAILED`。
  - APPROVE → APPROVED；REJECT → REJECTED。
  - REGENERATE：旧资产改为 ARCHIVED；然后按资产的步骤重新创建产生它的那个任务。SCENE 用新的 seed，并复用原来父任务 SCENE_INPUT 的输出；白底类重新创建 CUTOUT（使用当前激活的工作流版本），其 `downstream` 只包含被重新生成的这一项，例如 `[{WHITE_ANGLE, P2}]`。新任务挂在一个新的 `generation_run` 下，返回 202 和 `{runId}`。
  - 每个决定都写一行 `asset_review` 和审计 `ASSET_REVIEWED`。
- Produces: `POST /api/v1/content/products/{id}/review/approve-remaining`（OPERATOR）→ 200 `{approved:n}`：该商品所有 IN_REVIEW 的资产都改为 APPROVED，并逐条写 `asset_review`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void list_flaggedFirst_thenSpecOrder()
@Test void approve_setsApproved_andRecordsReview()
@Test void reject_requiresReason_400()
@Test void reject_withReasons_rejected()
@Test void decision_onNonReview_409()
@Test void regenerate_scene_newJobNewSeedSameSceneInput()
@Test void regenerate_angle_reCutsOnlyThatAngle()     // 新 CUTOUT 的 downstream 只有 [{WHITE_ANGLE,P2}]；完成后不会产生新的场景图
@Test void approveRemaining_onlyInReviewOfProduct()
@Test void viewer_cannotDecide_403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='ReviewServiceTest,ReviewControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): image review API with approve, reject reasons, regenerate and approve-remaining"`

---

### Task 12: 基线 ComfyUI 工作流（在真实 ComfyUI 上制作）

**Files:**
- Create: `src/main/resources/comfy/CUTOUT/v1/{workflow.json,manifest.json}`、`src/main/resources/comfy/SCENE/v1/{workflow.json,manifest.json}`
- Create: `src/test/java/com/kiano/worker/comfy/ComfyLiveIT.java`（标注 `@Tag("comfy-live")`，并加 `@EnabledIfEnvironmentVariable(named = "KIANO_COMFY_LIVE", matches = "1")`）
- Modify: `kiano-api/pom.xml`：surefire 默认排除 `comfy-live` 标签
- Modify: `src/test/resources/comfy-replay/*.json`（换成从真实 ComfyUI 录下来的响应）

**Interfaces:**
- 工作流必须满足 Task 5 的 manifest 契约。这些工作流在本机 `D:\ComfyUI`（0.35.0）的 UI 中搭建，然后用 "Export (API)" 导出。
- **CUTOUT v1**：
  - 节点链：`LoadImage` → BiRefNet 抠图节点 → `JoinImageWithAlpha` → `SaveImage`（输出 RGBA PNG）。
  - 抠图节点用 `comfyui-rmbg` 的 RMBG 节点，**model 必须选 BiRefNet**，不能用默认的 RMBG-2.0。如果 `/object_info` 中有 ComfyUI 内置的 background removal 节点，也可以用，但同样只能用 `models/background_removal/birefnet.safetensors`。
  - manifest：`inputs.image` 设 `maxLongSide: 2400`；`outputs.cutout`；`models` 中写 BiRefNet，license 为 `MIT`，source 为 `https://github.com/ZhengPeng7/BiRefNet`。
- **SCENE v1**：
  - 节点链：`LoadImage`（image）和 `LoadImageMask`（mask，取 red 通道）→ 缩放到 1024 → 反转掩膜，得到背景区域 → `CheckpointLoaderSimple`（`sd_xl_base_1.0.safetensors`）→ 正负提示词 → `InpaintModelConditioning` → `KSampler`（steps 30，cfg 6，denoise 1.0）→ `VAEDecode` → 放大回 1600（lanczos）→ **`ImageCompositeMasked`（destination 为生成图，source 为原始 image，mask 为产品掩膜，即把产品像素贴回）** → `SaveImage`。
  - manifest：`params` 为 `positive`、`negative`、`seed`；`models` 中写 SDXL base 1.0，license 为 `CreativeML-OpenRAIL++-M`；`requiredNodeClasses` 列出用到的所有节点类。
  - 节点 ID 以实际导出的为准，manifest 跟着改。
- `ComfyLiveIT`：读取 `src/test/resources/comfy-live/P1.jpg`（这一项需要用户提供一张真实产品照片，也可以先用 Woo 中的商品图），直接用 `ComfyUIExecutor` 依次运行 CUTOUT 和 SCENE（SCENE 的输入画布用 Task 1 的 `SceneCanvasComposer` 生成），断言：抠图结果的 alpha 覆盖率在 5%–95% 之间；场景图为 1600×1600；产品掩膜区域内的 `MaskedSsim` ≥ 0.98（因为产品像素是贴回的）。

- [ ] **Step 1: 启动 ComfyUI**：在 `D:\ComfyUI` 启动，端口 8188。确认 `curl -s 127.0.0.1:8188/object_info` 的结果中包含要用到的节点类。
- [ ] **Step 2: 在 UI 中搭建 CUTOUT 和 SCENE**，用一张真实照片手工跑通，然后用 Export (API) 导出到上面列出的路径，并编写 manifest。
- [ ] **Step 3: 用这两个文件跑一遍 Task 5 的注册校验**　Run: `./mvnw -q test -Dtest=WorkflowRegistryTest`，在测试中加一条用例 `classpathBaselines_areValid`，把 classpath 中的这两个 v1 交给 `register` 校验。
- [ ] **Step 4: 录制回放数据**：用 `curl` 调用真实 ComfyUI，把 `/prompt`、`/history/{id}`、`/object_info` 的真实响应保存进 `comfy-replay/`，替换 Task 8 中手写的版本。然后确认 `ComfyUIExecutorTest` 仍然通过。
- [ ] **Step 5: 跑真实链路测试**　Run: `KIANO_COMFY_LIVE=1 ./mvnw -q test -Dgroups=comfy-live`　Expected: PASS。把耗时记录在 commit 信息中：CUTOUT 约 X 秒，SCENE 约 Y 秒，后面用来估算批次耗时。
- [ ] **Step 6: Commit** — `git commit -m "feat(content): baseline CUTOUT (BiRefNet) and SCENE (SDXL inpaint + paste-back) workflows"`

---

### Task 13: 模板渲染器与 INFO/SPEC 模板（本 Sprint 不接入流水线）

**Files:**
- Create: `db/migration/V9__template.sql`
- Create: `content/template/{TemplateEntity,TemplateMapper,TemplateRegistry,TemplateRenderer,TemplateFacts,TemplateBootstrap}.java`
- Create: `src/main/resources/templates/content/PAGE_INFO/v1.html`、`PAGE_SPEC/v1.html`、`templates/fonts/{NotoSans-Regular.woff2,NotoSans-Bold.woff2,OFL.txt}`
- Modify: `kiano-api/pom.xml`（引入 `com.microsoft.playwright:playwright`、`com.samskivert:jmustache`）、`kiano-api/Dockerfile`
- Test: `content/template/TemplateRendererGoldenTest.java`、`TemplateRegistryTest.java`；`src/test/resources/golden/{page_spec_v1.png,page_info_v1.png}`

**Interfaces:**
- 表 `template`（规格 §8）：`id, tenant_id, code, kind, version, body, status (DRAFT|APPROVED|RETIRED), created_at`，约束 `UNIQUE(tenant_id, code, version)`。`TemplateBootstrap` 把 classpath 中的 v1 注册为 APPROVED：PAGE_INFO 的 kind 为 `INFOGRAPHIC`，PAGE_SPEC 的 kind 为 `SPEC`。如果库中已有同一版本但 body 不一致，启动失败，因为已发布的模板版本不允许改动。
- Produces: `record TemplateFacts(String productName, String model, String category, String capacity, Integer powerW, String voltage, String material, String colour, String warranty, List<String> inBox, List<String> features, List<String> benefits)`，字段与规格 §8 的 `facts_json` 对应。C3 负责把锁定后的事实表映射到这个 record。
- Produces: `TemplateRenderer.render(long tenantId, String templateCode, Map<String, Object> model, int width, int height) -> byte[] png`。处理过程如下：
  1. 读取该 code 下最新的 APPROVED 模板。
  2. 用 JMustache 渲染，默认做 HTML 转义。
  3. 在模板的 `<style>` 中注入 `@font-face`，字体以 base64 data URI 内联，不发任何网络请求。
  4. 用 Playwright Chromium 渲染：浏览器全局共享一个；每次渲染新开一个 page，设置 viewport → `setContent` → 等待 `document.fonts.ready` → 截图。
- Produces: `TemplateRenderer.specModel(TemplateFacts) -> Map<String,Object>` 和 `infoModel(TemplateFacts, byte[] productPng) -> Map<String,Object>`。规则：值为 null 或空的参数行不渲染；INFO 最多取 4 条 benefits，产品图以 data URI 嵌入。
- 模板要求：
  - 尺寸 1600×1600，白底。
  - SPEC 是两列的参数表；INFO 是产品图加 3–4 条利益点。
  - **不出现价格**。价格只出现在 C4 的广告叠加层中。
  - 长文本要自动换行，不能溢出画布。
- Dockerfile：运行阶段设置 `PLAYWRIGHT_BROWSERS_PATH=/ms-playwright`，执行 `java -Dloader.main=com.microsoft.playwright.CLI -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps chromium`（借助 Task 6 改成的 ZIP layout）；同时 `apt-get install -y tesseract-ocr`，供 Task 9 的 OCR 使用。

- [ ] **Step 1: 写失败的测试**

```java
// TemplateRendererGoldenTest（首次运行会下载 Chromium）
@Test void pageSpec_matchesGolden()       // 用固定的事实数据渲染 → 与 golden 比较：差值超过 32 个灰阶的像素占比 ≤ 0.5%
@Test void pageInfo_matchesGolden()
@Test void nullFacts_rowsOmitted_notPrintedAsNull()   // 页面文本中不出现 "null"，并且没有 "Power" 这一行
@Test void longFeature_wrapsWithinCanvas()            // 用 page.evaluate 检查：document.body.scrollWidth ≤ 1600
@Test void htmlInFacts_isEscaped()                    // 事实中含 "<b>x</b>" → 原样显示为文本
// 运行时加 -Dkiano.golden.update=true 会重新生成 golden，用于有意修改模板的情况
// TemplateRegistryTest
@Test void bootstrap_registersV1Approved_once()
@Test void changedBodyForExistingVersion_failsStartup()
```

- [ ] **Step 2–4: 先确认失败 → 实现（首次运行时用 update 开关生成 golden，人工看一眼图片确认版式没问题，再提交）→ 确认通过**　Run: `./mvnw -q test -Dtest='TemplateRendererGoldenTest,TemplateRegistryTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): Playwright template renderer with PAGE_INFO/PAGE_SPEC v1 and golden tests"`

---

### Task 14: 前端——商品页生成入口、流水线进度与 worker 状态

**Files:**
- Create: `kiano-web/src/components/PipelinePanel.tsx`
- Modify: `kiano-web/src/app/(app)/products/[id]/page.tsx`、`src/lib/types.ts`、`src/i18n/{en,zh}.ts`、`src/i18n/i18n.test.ts`

**Interfaces:**
- Produces（TS 类型，字段与 Java 端一致）：`JobStep`、`JobStatus`（包含 `WAITING_EXECUTOR`）、`PipelineRun`、`PipelineJob`、`WorkerStatus`、`PrecheckFlag`、`RejectReason`、`ReviewItem`。
- `PipelinePanel` 放在商品详情页的镜头网格上方，包含以下内容：
  - **worker 状态行**：在线时显示"Worker 在线 · ComfyUI 可用/不可用"，离线时显示"Worker 离线（最后在线时间 …）"。数据来自 `/content/workers`。
  - **"生成商品图"按钮**：OPERATOR 及以上可见；G1 没有完成（`complete == false`）时仍可点击，但先弹出确认框。按钮调用 POST，遇到 409 或 422 时用 `errorText` 显示错误。
  - **最近一次运行**：显示运行状态，以及按 step 分组的任务列表（step、variant、状态徽标、attempts、error）。FAILED 的任务旁有"重试"按钮。运行中时每 3 秒轮询一次。
  - **审核入口**：运行状态为 DONE 或 PARTIAL 时显示"去审核（n 张待审）"，链接到 `/review?productId=`。
- i18n 新增的键：
  - `step.*`（6 个步骤）、`job.*`（6 个状态）、`precheck.*`（4 个标记）、`reject.*`（7 个驳回原因）
  - 错误码 `PIPELINE_RUNNING`、`SHOTS_NOT_READY`、`WORKFLOW_NOT_ACTIVE`、`JOB_NOT_FAILED`、`ASSET_NOT_IN_REVIEW`、`LEASE_LOST`、`OUTPUT_MISSING`、`WORKFLOW_INVALID`
  - 面板上用到的文字
  - `i18n.test.ts` 中检查字典覆盖范围的清单同步补上这些枚举。

- [ ] **Step 1: 写失败的测试**：先补全 `i18n.test.ts` 的枚举清单，确认测试失败（新键还没有）。
- [ ] **Step 2: 实现字典、类型和组件**
- [ ] **Step 3: 运行 `pnpm vitest run && pnpm lint && pnpm build`**　Expected: 全部通过
- [ ] **Step 4: Commit** — `git commit -m "feat(web): image pipeline panel with progress, retry and worker status"`

---

### Task 15: 前端——图片审核看板

**Files:**
- Create: `kiano-web/src/app/(app)/review/page.tsx`、`src/components/RejectDialog.tsx`、`src/lib/review.ts`、`src/lib/review.test.ts`
- Modify: `src/app/(app)/layout.tsx`（导航中加入"审核 Review"）、`src/i18n/{en,zh}.ts`

**Interfaces:**
- Produces: `src/lib/review.ts` 中的三个纯函数：
  - `groupBySku(items: ReviewItem[]): { sku: string; productId: number; productName: string; items: ReviewItem[] }[]`：保持后端给出的顺序。
  - `nextFocus(items: ReviewItem[], currentId: number | null, decidedId: number): number | null`：做出决定后，焦点移到同组的下一张待审图；本组没有了就移到下一组。
  - `keyToDecision(key: string): 'APPROVE' | 'REJECT' | 'REGENERATE' | null`：`a` 和 `A` 对应 APPROVE，`r` 对应 REJECT，`g` 对应 REGENERATE；焦点在输入框中时，由调用方忽略按键。
- `/review` 页面：
  - 支持 `?productId=` 参数，不带时显示全部 IN_REVIEW 的资产。
  - 按 SKU 分组的网格，每张卡片左边是生成图，右边是对应的实拍原图缩略图，下面显示 spec、variant、版本号和预检标记徽标。有标记的卡片加红框，预检数值放在悬浮提示中。
  - 键盘：方向键移动焦点；A 通过；R 弹出 `RejectDialog`（驳回原因多选，至少选一个，可填备注）；G 重新生成。
  - 每组有一个"本 SKU 剩余全部通过"按钮，点击后先确认。
  - 点击图片在新标签页中打开原图。
- 所有文字都通过 `t()` 输出；预检标记和驳回原因都用字典。

- [ ] **Step 1: 写失败的测试**

```ts
test('groupBySku keeps backend order and groups')
test('nextFocus moves within group then to next group, null at end')
test('keyToDecision maps a/A, r, g and ignores others')
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `pnpm vitest run src/lib/review.test.ts`
- [ ] **Step 3: 实现 lib、页面和对话框**
- [ ] **Step 4: 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): image review board with keyboard shortcuts and reject reasons"`

---

### Task 16: 部署、文档与 C2 验收

**Files:**
- Modify: `kiano-api/Dockerfile`（如果 Task 13 中还没完成）、`docker-compose.yml`（api 增加环境变量 `KIANO_WORKER_TOKEN_SHA256`）、`README.md`、`CLAUDE.md`（补充 worker 的运行命令和 C2 的硬规则）
- Create: `scripts/run-worker.ps1`：读取 `.env` 中的 `KIANO_WORKER_TOKEN`，在 Windows 上原生运行 worker

**Interfaces:**
- README 新增以下内容：
  - worker 的运行方式（本机原生运行，不进 Docker，`scripts/run-worker.ps1`）。
  - ComfyUI 的启动方法（`D:\ComfyUI`，端口 8188）。
  - 工作流新版本的注册和激活，附 `curl` 示例。
  - 许可证白名单的说明。
  - tesseract 是可选的，本机用 `winget install UB-Mannheim.TesseractOCR` 安装。
  - 预检和场景图的各项阈值都要在样板阶段标定。

- [ ] **Step 1: 编写文档和脚本**；执行 `docker compose --profile app up -d --build`，确认健康检查返回 UP；确认 api 镜像中有 tesseract，并能用 Playwright 渲染出 SPEC 模板（在容器中执行一次 `TemplateRendererGoldenTest` 的冒烟版本，或者调用一个只在 local profile 下启用的调试端点；二选一，在 commit 信息中写明选了哪种）。
- [ ] **Step 2: 准备验收素材**：需要一个 HERO SKU 的**真实** P1–P8 照片，全部为 ACCEPTED。这一项需要用户提供，用手机临时拍一件家电也可以。按 C1 的方式导入，然后把这个 SKU 设为 HERO。
- [ ] **Step 3: 按规格 §14 的 C2 标准验收，逐条记录结果**
  1. **一个 HERO SKU 自动生成完整图组**：启动 ComfyUI 和 worker，在商品页点击"生成商品图"。运行最终为 DONE，审核看板中出现 10 张图：MAIN 1、ANGLE 4、SCENE 4、INBOX 1。逐张检查：白底图的边缘为纯白，占比在 80–85% 之间；场景图中产品与实拍一致，没有出现其他品牌的标识；provenance 中有 workflow 的版本号和 seed。CUTOUT 和 SCENE 的 `gpu_seconds` 记录下来。INFO 和 SPEC 由 Task 13 的金样测试覆盖，C3 接入流水线。
  2. **关闭 ComfyUI 后任务进入等待，恢复后继续**：在运行过程中关闭 ComfyUI。COMFYUI 任务变为 WAITING_EXECUTOR，attempts 不增加；COMPOSITE 任务照常完成；商品页显示"ComfyUI 不可用"。重新启动 ComfyUI 后，任务自动继续，最终为 DONE。再把 worker 进程整个停掉，90 秒后待处理的 COMFYUI 任务同样进入等待。
  3. **预检能标记出被改动的产品**：通过管理接口注册 `SCENE v2-test`：内容是 v1 去掉最后的贴回节点，并把 denoise 设为 1.0，同时覆盖产品区域（即不反转掩膜）。激活 v2-test 后，对一张场景图点击"重新生成"，新图被标记为 PRODUCT_MISMATCH 并排在审核看板最前面。最后重新激活 v1。
- [ ] **Step 4: 运行全部测试**　Run: `cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm lint && pnpm build`
- [ ] **Step 5: Commit** — `git commit -m "chore: worker run script, docs and C2 acceptance notes"`

---

## Self-Review 记录

- **规格覆盖**（对照 §14 C2 一行的内容）：
  - kiano-worker 与租约协议：Task 2、3、4、6。
  - ComfyUI 执行器与工作流（抠图、场景）：Task 5、8、12。
  - 白底图和多角度图：Task 1、7，采用确定性合成，这是用户确认的偏差。
  - 模板渲染（信息图、参数图）：Task 13，本 Sprint 只建渲染器，这是用户确认的范围。
  - 自动预检：Task 9。
  - 图片审核看板：Task 11、15。
  - 三条验收标准都在 Task 16。
  - 规格 §11 的错误处理表：WAITING_EXECUTOR 在 Task 3、4、8；租约超时在 Task 3、4；可重试和不可重试的区分在 Task 3、8；H3 和 Woo 不在本 Sprint。
  - 规格 §12：量化模型、单并发、gpu_seconds、许可证校验分别在 Task 5、6、8、12。
  - 规格 §9.5 要求的"按 SKU 分组、并排显示实拍原图、A/R/G 快捷键、剩余全部通过、有标记的排前面"全部在 Task 15。
- **类型一致性**：
  - `JobStep` 和 `ExecutorType` 在 Task 3 定义，Task 4、6–11、14 都使用它们。
  - `JobCompletionListener` 在 Task 4 定义，在 Task 10 实现。
  - `WorkflowManifest` 在 Task 5 定义，Task 8 和 Task 12 使用。
  - `PrecheckFlag` 在 Task 9 定义，Task 11、14、15 使用。
  - 输出名称：CUTOUT 为 `cutout`；合成类为 `image`；SCENE_INPUT 为 `image` 和 `mask`；SCENE 为 `image`。这些名称在 Task 7、8、10、12 中保持一致。
- **刻意不做的事**：
  - INFO 和 SPEC 接入流水线、gallery 最多 10 张的选择：放在 C3。
  - 广告叠加层和价格：放在 C4。
  - 视频和 H3：放在 C5。
  - 批量运行和耗时预估看板：放在 C6。C2 只记录 `gpu_seconds`。
  - 工作流上传的界面：用 curl 调用管理接口就够了。
  - 多个 worker token：一个 worker 就够了。
