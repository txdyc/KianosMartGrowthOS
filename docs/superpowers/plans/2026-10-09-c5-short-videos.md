# C5 短视频 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 为每个 HERO SKU 合成 3 条 1080×1920 竖屏短视频（demo、problem、unboxing，每条 15–30 秒）。
- **素材**：以实拍 V1、V2、V3 为主。开场可以用 MiniMax H3 图生视频。H3 有两个后端，**优先用本地 ComfyUI 工作流**（RTX 4090 16 GB），本地不可用时退到服务商接口；两者都不可用时用实拍代替。
- **文字**：字幕文字由 LLM 按视频生成并经过审核。字幕和结尾卡都用 HTML 模板渲染成透明 PNG，再叠加到画面上；配乐来自 Kiano 内的曲库。
- **AI 占比**：超过 30% 的视频不能进入审核。
- **审核时的调整**：审核时可以手动调整每段实拍的入点和出点，然后重新合成。
- **改价**：Woo 改价后，结尾卡上的价格自动更新。

**Architecture：**
- **api 负责规划**：
  1. 检查前置条件。
  2. 选取实拍片段的时间窗。
  3. 生成剪辑清单 EDL（edit decision list）：一个纯数据结构，列出每一段用哪个素材、从第几秒到第几秒，以及什么时候叠加哪张 PNG。
  4. 渲染字幕和结尾卡的 PNG。
  5. 选配乐。
  6. 创建 `generation_job`。
- **H3 片段**有两个后端，由 `H3BackendSelector` 选择：
  - **本地**：`H3_CLIP_LOCAL` 任务（`executor=COMFYUI`），由 worker 的 `ComfyUIExecutor` 执行已注册并激活的 `H3_I2V` 工作流，复用 C2 的租约、`WAITING_EXECUTOR` 和许可证白名单机制。不花钱，只记录 `gpu_seconds`。
  - **接口**：`H3_CLIP_API` 任务（`executor=H3`），由 api 内的 `H3JobRunner` 按"提交 → 暂存 → 轮询"的方式异步执行（规格 §4.1：H3 接口调用在 api 内），并记录 `cost_usd`。
  - 本地任务等待超时或者失败时，升级为接口任务（接口已启用并且预算足够时），否则用实拍代替。
- **最终合成**是 `VIDEO_COMPOSE` 任务（`executor=FFMPEG`），由 kiano-worker 中的 `FfmpegExecutor` 按 EDL 执行（规格 §4.1：FFmpeg 在 worker 中）。
- **完成之后**，api 创建 VIDEO 资产，并在这一步拦截 AI 占比超标的视频。

**Tech Stack：** 沿用现有技术栈。ffmpeg 和 ffprobe 在 worker 上原生运行（Windows）；api 用它们给实拍片段的时间窗打分（C4 的 `FrameExtractor` 和 `blurVariance`）。字幕和结尾卡由 Playwright 模板渲染，截图时使用透明背景。H3 通过 `H3Client` 端口加 WireMock 契约测试接入。

**Spec：** `docs/superpowers/specs/2026-10-07-content-module-design.md` §4.1、§5.1、§7.4、§9.3（AI 时长占比）、§11（H3 失败时用实拍替代）、§12、§13、§14 C5、§15 第 1 项和第 5 项。

**前置依赖：** C4a 已经合并。本计划复用 C4a 的以下部分：`PriceDisplay`、`GhsFormat`、政策 badge（`requireAdBadges`）、`FrameExtractor` 和 `PhotoQc.blurVariance`、`TemplateRenderer.textBoxes`、`AdPriceListener` 的自动通过判定、`AdReplacementService`、`AdExportService`。C4b 与本计划无关。

## Global Constraints

C1–C4a 计划中的 Global Constraints 继续有效。下面只列 C5 新增的部分。

- **2026-10-09 用户确认的决定**：
  1. **H3 接口后端先接口、用模拟回放**：`H3Client` 定义为端口，契约测试用 WireMock 按"提交 → 轮询 → 下载"回放；单价和预算放在配置中。用户拿到 API 文档和 key 后，执行附录 A 的对接任务，其余任务不受影响。在此之前 `kiano.content.video.h3.api.enabled=false`。
  5. **H3 有两个后端，优先本地，可以退到接口**：`kiano.content.video.h3.backend` 默认为 `LOCAL_FIRST`，可以改为 `LOCAL_ONLY`、`API_ONLY` 或 `OFF`。
     - `LOCAL_FIRST` 的规则：有激活的 `H3_I2V` 工作流时用本地；本地任务从创建起超过 `local-max-wait`（默认 2 小时）还没完成（例如笔记本合盖导致 `WAITING_EXECUTOR`），或者最终失败时，接口已启用并且当天预算足够就转交接口，否则用实拍代替。
     - 没有激活的本地工作流时，接口已启用就直接用接口，否则用实拍。
     - 本地工作流尚未注册、接口也没有启用时（当前状态），开场一律用实拍，AI 占比为 0。
  6. **本地 H3 权重的许可证由用户在安装时核实**：注册 `H3_I2V` 工作流时，照常按 `allowed-licenses` 白名单校验，计划**不预设**任何新的许可证标识。如果是 MiniMax 自定义的商用许可，由用户核实条款后把它的标识加入 `kiano.content.workflow.allowed-licenses` 配置，并记入规格 §12 的已核实清单（附录 B）。在此之前本地后端不可用，按第 5 条自动改用接口或实拍。
  2. **字幕文字由 LLM 按视频生成，再人工审核**：新增 `VIDEO_SCRIPT` 资产，每种视频类型一个 variant。
  3. **按规则自动剪辑，可以手动调整入点和出点**：审核页提供时间轴编辑器，保存后生成新版本。
  4. **Kiano 内建曲库**：上传曲目时必须填写许可证信息；每条视频同时输出一份无配乐版本，供 TikTok 使用平台的商用曲库。
- **视频格式（规格 §7.4）**：1080×1920，H.264 High（`-crf 20 -preset medium -pix_fmt yuv420p`），AAC 128k 48kHz，30fps，`+faststart`。总时长 **15–30 秒**。英文字幕直接烧录进画面。
- **结构（规格 §7.4）**：每条视频依次是 `hook`（3 秒）→ `body`（实拍演示）→ `captions`（卖点字幕，5 秒）→ `endcard`（结尾卡，4 秒）。各段时长写在配置的配方中（见 Task 4），默认值如下：

| 类型 | hook（H3 首帧 / 实拍代替） | body | captions 的画面 | endcard |
|---|---|---|---|---|
| demo | H3：PAGE_SCENE ／ 实拍：V1 | V1，8–15 秒 | V2 | END_CARD |
| problem | H3：PAGE_SCENE ／ 实拍：V1 | V1，8–12 秒 | V2 | END_CARD |
| unboxing | H3：PAGE_MAIN ／ 实拍：V3 | V3，8–14 秒 | V2 | END_CARD |

- **AI 占比（规格 §7.4、§9.3）**：`ai_ratio = H3 片段实际使用的秒数 ÷ 视频总秒数`，其中结尾卡和实拍都算非 AI。**超过 0.30 时拦截**：
  - 规划和手动保存 EDL 时，返回 422 `AI_RATIO_EXCEEDED`。
  - 合成完成、创建资产时再检查一次：超标的资产直接记为 REJECTED（reason 为 `AI_RATIO_EXCEEDED`，actor 为 SYSTEM），**不进入 IN_REVIEW**。
- **H3 的使用边界（规格 §7.4）**：H3 只用于开场的图生视频，首帧只能是已审核通过的 PAGE_SCENE 或 PAGE_MAIN。提示词按 h3-prompt-writing 的 **I2VA** 结构，由版本化模板（`H3_PROMPT_*`）确定性生成，不经过 LLM，**两个后端使用同一份提示词**。内容只能是运镜和氛围，**不出现人手，不演示任何功能，不出现文字**（"不展示没有经过实拍验证的功能"）。H3 自带的音频一律丢弃。
- **H3 失败时依次升级（规格 §11）**：顺序是本地 → 接口 → 实拍。每一次升级都记录在 provenance 的 `h3.attempts` 中，内容为 `[{backend, outcome, reason}]`。最终用实拍时还要记录 `h3Fallback: {reason}`，并重新计算 ai_ratio。
  - 本地升级的原因：`H3_LOCAL_TIMEOUT`（超过 `local-max-wait`）、`H3_LOCAL_FAILED`。
  - 接口升级的原因：`H3_TIMEOUT`（超过 `api.max-wait`，默认 15 分钟）、`H3_CONTENT_REJECTED`、`H3_BUDGET_EXCEEDED`、`H3_FAILED`。
  - 没有任何可用后端时，原因为 `H3_NO_BACKEND`。
  - **视频生成不会因为 H3 而卡住**。
- **本地 H3 与显存（规格 §12）**：
  - 生成分辨率和帧数都写在工作流的 params 中，由 `kiano.content.video.h3.local.width/height/frames/fps` 配置，默认 720×1280、5 秒；在样板阶段按 16 GB 显存标定。生成的片段在合成时由 ffmpeg 放大到 1080×1920。
  - 本地 H3 任务与图片任务共用 GPU，单并发，按任务 ID 顺序领取。
  - 一个片段可能要跑好几分钟，所以 job input 中带 `comfyTimeoutSeconds`（默认 1800），覆盖 worker 全局默认的 15 分钟超时。
- **文字一律由模板渲染（规格 §3 原则 2）**：hook 句、卖点字幕和结尾卡（价格、COD、MoMo、"Order on WhatsApp"）都由 HTML 模板渲染成 1080×1920 的 PNG，再由 ffmpeg `overlay` 叠加上去。**不使用 ffmpeg 的 drawtext 或 libass**，这样字体、转义和安全区都与 C4 保持一致。字幕 PNG 是透明的，结尾卡不透明。
- **9:16 安全区**与 C4 相同：文字只能落在 y ∈ [269, 1248]；放不下时缩小字号，最多缩两档，仍然放不下就记为预检标记 `TEXT_OUTSIDE_SAFE_AREA`。
- **画面规范化**：
  - 竖屏素材（宽高比在 9:16 ± 2% 以内）放大铺满后居中裁切。
  - 其他比例（横屏、方形）用模糊放大的同一画面作为背景，原画面完整居中显示，**产品不会被裁掉**。
  - 统一用 `fps=30` 转换帧率（同时处理可变帧率素材）。旋转信息由 ffmpeg 自动处理。
- **音频**：
  - 实拍段保留原声，音量为 `original-volume`（默认 0.5）。
  - H3 段和结尾卡段为静音（`anullsrc`）；素材没有音轨时，该段也用静音。
  - 配乐音量为 `music-volume`（默认 0.35），曲目不够长时循环，最后 1.5 秒淡出。
  - 两个版本都用 `loudnorm=I=-14:TP=-1.5:LRA=11` 做响度归一化。无配乐版本只有原声。
- **曲库许可证（规格 §12、§15 第 5 项）**：`license_type` 只能是 `CC0`、`ROYALTY_FREE_COMMERCIAL`（例如 Pixabay Content License，或付费订阅曲库）、`OWNED` 三种之一。`license_source` 和 `license_proof`（证明链接或说明）必填。CC-BY 这类需要署名的许可不允许上传，因为广告中无法署名。曲目只能停用（RETIRED），不能删除，因为已有视频的 provenance 引用它们。
- **`depends_on_price`**：VIDEO_AD 资产全部为 true，因为结尾卡上有价格。改价后的处理沿用 C4a 的 STALE、重新合成和自动通过规则（见 Task 11）。
- **文件名（规格 §7.5）**：`{SKU}_{type}_{real|mixed}_1080x1920_v{n}.mp4`。含有 H3 段时素材类型为 `mixed`，否则为 `real`。无配乐版本是在扩展名前加 `_nomusic`。
- **出入口**：worker **不能**领取 `executor=H3` 的任务（接口后端），服务端在 lease 时过滤掉 H3；本地后端的任务是 `executor=COMFYUI`，照常由 worker 领取。H3 的 key 只放在 `.env` 的 `MINIMAX_API_KEY` 中，不能出现在日志、错误信息、`generation_job` 和审计中。
- **相对规格的有意扩展**：
  - 新增资产规格 `VIDEO_SCRIPT`（TEXT）。
  - 新增 `LlmPurpose.VIDEO_SCRIPT`，复用 COPY 的路由，成本单独统计。
  - 新增 `music_track` 表。
  - `asset` 表增加 `renditions_json` 列，存放无配乐版本和封面图。
  - `generation_job` 表增加 `external_ref` 列，存放 H3 接口的任务 ID。
  - `comfy_workflow.code` 允许 `H3_I2V`，工作流注册时校验它的输入输出契约。
  - 新增 `JobFailureListener`：任务**最终**进入 FAILED 时通知监听者，供 H3 升级使用。worker 上报的失败目前没有这样的回调。
  - 广告导出可以选择包含视频（Task 11）。

## Review Focus

1. **手机拍摄的素材千差万别**：横屏、带旋转信息、60fps 或可变帧率、没有音轨、4K HEVC。这些都必须合成为 1080×1920、30fps 的视频，时长等于 EDL 的总时长（误差 ±0.1 秒）；没有音轨的段落是静音，而不是报错。测试放在 Task 7。
2. **实拍素材比配方要求的短**：例如 V1 只有 9 秒，而 body 要求 8–15 秒，加上其余各段后总时长仍不足 15 秒。此时 body 先缩短到最小值；仍然不足时返回 409 `VIDEO_SOURCE_TOO_SHORT`，`details` 中写明是哪个镜头、需要多少秒。手动调整时，出点超过素材长度返回 422。测试放在 Task 4 和 Task 10。
3. **H3 失败、超时、超出预算或内容被拒，或者笔记本合盖导致本地任务一直在等待**：按"本地 → 接口 → 实拍"依次升级，视频照常生成；本地任务被升级之后，worker 才把结果交回来时，结果被丢弃，不会生成两个版本。api 在 H3 执行中途重启，也能从 `external_ref` 继续轮询，不会重复提交、重复计费。测试放在 Task 8a、8b 和 Task 9。
4. **AI 占比超标**：手动把实拍段剪得很短，或者配方配置错误，都可能让 H3 段的占比超过 0.30。保存 EDL 时返回 422；万一绕过了这一步，合成完成后资产会被记为 REJECTED，不进入审核。测试放在 Task 4、Task 9 和 Task 10。
5. **合成之后又改价**：只有价格变了时，新版本自动通过；EDL 被手动改过，或者字幕有了新的通过版本时，新版本要进入审核；worker 离线期间视频保持 STALE，worker 恢复后自动完成。测试放在 Task 11。

---

## 文件结构

```
kiano-api/src/main/resources/db/migration/
  V19__video_specs_templates.sql   （Task 1）
  V20__music_track.sql             （Task 2）
  V21__video_jobs.sql              （Task 3）
kiano-api/src/main/java/com/kiano/
  workerprotocol/ ExecutorType(+H3, FFMPEG), JobStep(+H3_CLIP_API, H3_CLIP_LOCAL, VIDEO_COMPOSE), ContentTypes, Edl（共享协议）
  worker/ffmpeg/ FfmpegProperties, FfmpegCommandBuilder, FfmpegExecutor, OutputProbe
  worker/ WorkerLoop（按扩展名确定 content type，视频用 ffprobe 测时长，sha256 流式计算）
  platform/llm/ LlmPurpose(+VIDEO_SCRIPT), LlmRouteStore
  content/qc/ VideoInfo(+hasAudio), FfprobeJsonParser
  content/generation/ GenerationJobStore（park、外部任务 ID、完成时记录成本）, WorkerController（过滤 H3）
  content/video/
    VideoType, VideoProperties（配方、音量、H3 开关）
    VideoScriptDraft, VideoScriptText, VideoScriptTaskHandler
    EdlMath, EdlValidator, VideoPlanner, ClipWindowPicker
    VideoOverlayRenderer（字幕和结尾卡的 PNG）
    VideoRenderService, VideoCompositionCoordinator, VideoEdlController, VideoController
    VideoPriceListener
  content/video/h3/ H3Client, H3Request, H3TaskStatus, MiniMaxH3Client, H3Properties, H3PromptBuilder, H3JobRunner,
                    H3Backend, H3BackendSelector, H3LocalWatchdog
  content/workflow/ WorkflowRegistry（H3_I2V 契约）
  worker/comfy/ ComfyUIExecutor（视频输出、按任务覆盖超时）
  content/music/ MusicTrackService, MusicController, MusicPicker
  content/asset/ AssetService(+createVideoFromJob), ReviewService（视频相关的排序、重新生成、播放地址）
  content/ads/ AdExportService, AdReplacementService（加入 VIDEO_AD）
kiano-api/src/main/resources/templates/content/
  VIDEO_SCRIPT_PROMPT/v1.txt  VIDEO_CAPTION/v1.html  END_CARD/v1.html
  H3_PROMPT_DEMO/v1.txt  H3_PROMPT_PROBLEM/v1.txt  H3_PROMPT_UNBOXING/v1.txt
kiano-web/src/
  app/(app)/settings/music/page.tsx  components/VideosPanel.tsx  components/VideoAssetCard.tsx
  components/EdlEditor.tsx  lib/edl.ts (+test)  lib/music.ts (+test)  lib/types.ts  i18n/*
```

---

### Task 1: 按视频生成字幕文字（VIDEO_SCRIPT）

**Files:**
- Create: `db/migration/V19__video_specs_templates.sql`、`content/video/{VideoType,VideoScriptDraft,VideoScriptText,VideoScriptTaskHandler}.java`、`templates/content/VIDEO_SCRIPT_PROMPT/v1.txt`
- Modify: `platform/llm/{LlmPurpose,LlmRouteStore}.java`、`content/copy/TextPrecheck.java`、`content/derive/FactDependentAssets.java`、`content/template/TemplateBootstrap.java`、`content/asset/ReviewService.java`（VIDEO_SCRIPT 的重新生成和编辑）
- Test: `content/video/VideoScriptTaskHandlerTest.java`、`LlmRouteStoreTest`、`TextPrecheckTest`、`TextReviewTest`（追加用例）

**Interfaces:**
- V19 的内容：
```sql
insert into asset_spec (code, kind, tier, width, height, format, pipeline_ref) values
 ('VIDEO_SCRIPT','TEXT','HERO',null,null,'json','VIDEO_SCRIPT'),
 ('VIDEO_AD','VIDEO','HERO',1080,1920,'mp4','VIDEO');
alter table template drop constraint template_code_check;
alter table template add constraint template_code_check check (code in
  ('PAGE_INFO','PAGE_SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LONG','COPY_SHORT','POLICY_BLOCK',
   'AD_COPY_PROMPT','AD_PRICEHOOK','AD_PROBLEM','AD_DEMO','AD_TRUST',
   'VIDEO_SCRIPT_PROMPT','VIDEO_CAPTION','END_CARD','H3_PROMPT_DEMO','H3_PROMPT_PROBLEM','H3_PROMPT_UNBOXING'));
alter table template drop constraint template_kind_check;
alter table template add constraint template_kind_check check (kind in
  ('INFOGRAPHIC','SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LAYOUT','POLICY_BLOCK','AD_OVERLAY',
   'VIDEO_SCRIPT_PROMPT','VIDEO_OVERLAY','END_CARD','H3_PROMPT'));
```
  执行前先核对 C4a 的 V18 中 code 和 kind 的实际列表，以它为基础追加，不能漏掉已有的值。
- `enum VideoType { DEMO, PROBLEM, UNBOXING }`，`wire()` 返回小写形式，用作 variant 和文件名中的类型段。
- `LlmPurpose` 增加 `VIDEO_SCRIPT`。`LlmRouteStore` 按 COPY 解析它的路由，与 AD_COPY 的做法相同，`purpose` 保持为 VIDEO_SCRIPT。
- LLM 的结构化输出：
```java
record VideoScriptDraft(List<VideoScript> videos) {}
record VideoScript(VideoType type,
    @JsonPropertyDescription("Opening hook line shown in the first 3 seconds, max 40 characters, no price") String hook,
    @JsonPropertyDescription("3 or 4 selling-point captions, each max 42 characters, no price, only locked facts") List<String> captions) {}
```
  VIDEO_SCRIPT_PROMPT 要写明三种视频的意图：demo 描述画面中可以看到的演示；problem 先讲痛点再给出解决；unboxing 讲包装内有什么，只能取自 `in_box`。英文，面向 Ghana 用户，只能使用锁定的事实，不写价格，不写 COD、MoMo 和配送（这些由结尾卡渲染）。
- `VideoScriptText`：textBody 是 `{"hook":…,"captions":[…]}`，每个字段都用 `PlainText.strip` 处理；提供 `toJson` 和 `fromJson`。
- `VideoScriptTaskHandler`（任务类型 `VIDEO_SCRIPT_GENERATE`，payload 为 `{productId, factVersion, onlyType?}`）：只处理 HERO 商品，事实版本过期时 skip；effort MEDIUM，maxTokens 8000；返回的 videos 必须恰好覆盖 3 种类型，否则抛不可重试的 `LLM_INVALID_OUTPUT`；每种类型运行一次 `TextPrecheck.checkVideoScript`，然后 `createText(…, "VIDEO_SCRIPT", type.wire(), …)`。
- `TextPrecheck.checkVideoScript(VideoScriptText, FactsJson)` 检查 FACT_MISMATCH、FORBIDDEN_CLAIM、PRICE_IN_COPY；captions 的数量必须是 3 或 4 条，hook ≤ 40 字符，每条 caption ≤ 42 字符，不满足时标记 TOO_LONG 或 `CAPTION_COUNT`，`metrics.field` 中写明是哪个字段。
- `FactDependentAssets.onFactLocked`：HERO 商品额外入队 `VIDEO_SCRIPT_GENERATE`；旧事实版本的 VIDEO_SCRIPT 和 VIDEO_AD 按现有规则处理（ARCHIVED 或 STALE）。
- ReviewService 中 pipeline_ref 为 `VIDEO_SCRIPT` 时，REGENERATE 入队 `{onlyType}`；`editText` 要求 JSON 能被解析且不含标签，编辑后重新运行 `checkVideoScript`。
- 端点：`POST /api/v1/content/products/{id}/videos/script`（OPERATOR）→ 202。不是 HERO 商品时返回 422 `NOT_HERO`；没有锁定事实时返回 409 `FACTS_NOT_LOCKED`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void generatesThreeTypeAssets_withPlainTextJsonBodies()
@Test void missingTypeInOutput_invalidOutputNonRetryable()
@Test void nonHero_skipped()  @Test void staleFactVersion_skipped()  @Test void onlyType_savesSingleNewVersion()
@Test void factLock_onHero_enqueuesVideoScript()
@Test void videoScript_resolvesCopyRoute_keepsPurpose()                  // LlmRouteStoreTest
@Test void videoScript_priceTooLongOrFiveCaptions_flagged()             // TextPrecheckTest
@Test void editVideoScript_invalidJsonOrMarkup_422()                    // TextReviewTest
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='VideoScriptTaskHandlerTest,LlmRouteStoreTest,TextPrecheckTest,TextReviewTest,FactDependentAssetsTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): per-video script (hook + captions) via LLM with precheck and review"`

---

### Task 2: 曲库

**Files:**
- Create: `db/migration/V20__music_track.sql`、`content/music/{MusicTrackService,MusicController,MusicPicker}.java`
- Test: `content/music/{MusicTrackServiceTest,MusicControllerTest,MusicPickerTest}.java`

**Interfaces:**
- V20 的内容：
```sql
create table music_track (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  title text not null, artist text, object_key text not null, duration_s numeric(8,2) not null,
  moods text[] not null default '{}',
  license_type text not null check (license_type in ('CC0','ROYALTY_FREE_COMMERCIAL','OWNED')),
  license_source text not null, license_proof text not null,
  status text not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
  sha256 text not null, created_by bigint references app_user(id), created_at timestamptz not null default now(),
  unique (tenant_id, sha256));
```
- `MusicTrackService`：
  - `upload(CurrentUser user, MultipartFile file, MusicMeta meta) -> MusicTrackView`，仅限 OWNER。
  - 接受 mp3、m4a、wav，最大 20 MB，时长 10–300 秒（用 ffprobe 测量）。
  - `title`、`licenseSource`、`licenseProof` 必填，`licenseType` 必须在白名单中；`moods` 只能取 `upbeat`、`uplifting`、`calm`、`energetic`。不满足时返回 422 `VALIDATION_FAILED`。
  - sha256 重复时返回 409 `DUPLICATE_TRACK`。
  - 存储路径为 `t{tid}/music/{id}.{ext}`，写审计 `MUSIC_TRACK_ADDED`，after 中记录许可证信息。
- `retire(user, id)`：写审计 `MUSIC_TRACK_RETIRED`。**没有删除操作**。
- `list(tenantId)`：返回列表，含已经使用的次数（从 VIDEO_AD 的 provenance 中按 `musicTrackId` 计数）。
- `MusicPicker.pick(long tenantId, VideoType type, int rotation) -> Optional<MusicTrack>`：
  1. 候选是 ACTIVE 的曲目中，`moods` 包含配置 `kiano.content.video.mood.{type}` 的那些（默认 demo 为 upbeat、problem 为 uplifting、unboxing 为 upbeat）；一首都没有时，退回到全部 ACTIVE 曲目。
  2. 按使用次数从少到多、再按 id 排序，取第 `rotation % n` 首。
  3. 曲库为空时返回 empty，调用方据此报 409 `MUSIC_LIBRARY_EMPTY`。
- 端点：
  - `GET /api/v1/content/music`（VIEWER）
  - `POST /api/v1/content/music`（OWNER，multipart）
  - `POST /api/v1/content/music/{id}/retire`（OWNER）
  - `GET /api/v1/content/music/{id}/preview` 返回 302，跳转到预签名 URL

- [ ] **Step 1: 写失败的测试**

```java
@Test void upload_requiresLicenseFields_andWhitelistedType()   // CC_BY 或缺少 licenseProof → 422
@Test void upload_rejectsDuplicateSha_andTooShortAudio()       // 音频用 ffmpeg 现场生成 sine 测试音
@Test void retire_keepsObject_andIsNotPicked()
@Test void picker_prefersMoodThenLeastUsed_rotates()
@Test void picker_emptyLibrary_empty()
@Test void operatorCannotUpload_403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='MusicTrackServiceTest,MusicControllerTest,MusicPickerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): licensed music library with mood-based rotation"`

---

### Task 3: 任务协议支持视频和外部任务

**Files:**
- Create: `db/migration/V21__video_jobs.sql`、`workerprotocol/ContentTypes.java`、`content/generation/JobFailureListener.java`
- Modify: `workerprotocol/{ExecutorType,JobStep}.java`、`content/generation/{GenerationJobStore,WorkerController,GenerationRunStore}.java`、`worker/WorkerLoop.java`、`content/qc/{VideoInfo,FfprobeJsonParser,FfprobeVideoProbe}.java`
- Test: `content/generation/GenerationJobStoreTest.java`、`WorkerControllerTest.java`、`worker/WorkerLoopTest.java`、`content/qc/FfprobeJsonParserTest.java`（追加用例）

**Interfaces:**
- V21 的内容：
```sql
alter table generation_job add column external_ref text;
alter table asset add column renditions_json jsonb;
alter table comfy_workflow drop constraint comfy_workflow_code_check;
alter table comfy_workflow add constraint comfy_workflow_code_check check (code in ('CUTOUT','SCENE','H3_I2V'));
```
  约束名是 V6 的隐式名称，执行前用 `\d comfy_workflow` 核对。`generation_run.kind` 允许 `'VIDEO'`（目前没有约束，只需在 `GenerationRunStore.create` 增加一个带 kind 参数的重载）。
- `ExecutorType` 增加 `H3` 和 `FFMPEG`；`JobStep` 增加三个步骤：`H3_CLIP_API(ExecutorType.H3, null)`、`H3_CLIP_LOCAL(ExecutorType.COMFYUI, null)`、`VIDEO_COMPOSE(ExecutorType.FFMPEG, "VIDEO_AD")`。
- 新增 `content/generation/JobFailureListener`：`void onFailed(GenerationJob job)`。只有任务**最终**进入 FAILED 状态时才回调（可重试的退避和 WAITING_EXECUTOR 都不回调）；`WorkerController.fail` 和 `H3JobRunner` 两处在失败提交之后调用它。
- `GenerationJobStore.cancel(long tenantId, long jobId, String reason) -> boolean`：只对 QUEUED 和 WAITING_EXECUTOR 状态的任务生效，把它改为 CANCELLED。已经被 worker 领取（LEASED）的任务，取消时同样改为 CANCELLED 并清空租约；worker 之后调用 complete 或 heartbeat 会因为不再持有租约而失败，结果被丢弃（Review Focus 3）。
- `ContentTypes.forKey(String objectKey) -> String`：`.png` 对应 `image/png`，`.jpg` 对应 `image/jpeg`，`.mp4` 对应 `video/mp4`，其他扩展名一律抛 IllegalArgumentException。`WorkerController.toLeaseResponse` 和 `WorkerLoop.complete` 都改用它，替换现在写死的 PNG。
- `WorkerController.lease`：从 worker 上报的 capabilities 中**去掉 H3** 再领取任务（见 Global Constraints 中的"出入口"）。
- `GenerationJobStore` 新增以下方法：
  - `park(long jobId, String workerId, String externalRef, Duration delay) -> boolean`：任务回到 QUEUED，`run_after = now() + delay`，`attempts - 1`（退还这次领取消耗的次数），写入 `external_ref`，清空租约。
  - `complete(long jobId, String workerId, Object output, double gpuSeconds, @Nullable BigDecimal costUsd)`：在现有方法上增加成本参数，原方法委托给它，成本传 null。
  - `costSince(long tenantId, ExecutorType executor, Instant since) -> BigDecimal`，用于预算检查。
- `VideoInfo` 增加 `boolean hasAudio`；`FfprobeJsonParser` 根据是否存在 `codec_type=audio` 的流来判断。所有现有的构造调用都要补上这个参数。
- `WorkerLoop.complete`：
  - sha256 改为流式计算，不再整个文件读进内存。
  - `.mp4` 输出用 ffprobe 测量 `durationS`、宽和高（worker 配置 `kiano.worker.ffprobe-path`，默认 `ffprobe`）。
  - 图片输出的处理方式不变。

- [ ] **Step 1: 写失败的测试**

```java
@Test void park_requeuesWithDelay_refundsAttempt_keepsExternalRef()
@Test void cancel_leasedJob_laterCompleteFailsAsLeaseLost()
@Test void finalFailure_notifiesFailureListener_retryableAndWaitingDoNot()   // WorkerControllerTest
@Test void complete_recordsCost_andCostSinceSums()
@Test void lease_workerAdvertisingH3_neverGetsH3Job()          // WorkerControllerTest
@Test void leaseResponse_mp4Output_presignedAsVideoMp4()
@Test void contentTypes_unknownExtension_throws()
@Test void workerComplete_mp4_reportsDurationAndSize()         // WorkerLoopTest：OutputProbe 用桩替代
@Test void parser_detectsAudioStreamPresence()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='GenerationJobStoreTest,WorkerControllerTest,WorkerLoopTest,FfprobeJsonParserTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): job protocol for H3 and FFMPEG executors, video outputs, parked external tasks and failure listeners"`

---

### Task 4: EDL、规划器与校验（纯函数）

**Files:**
- Create: `workerprotocol/Edl.java`、`content/video/{VideoProperties,EdlMath,EdlValidator,VideoPlanner}.java`
- Modify: `src/main/resources/application.yml`（`kiano.content.video.*`）
- Test: `content/video/{EdlMathTest,EdlValidatorTest,VideoPlannerTest}.java`

**Interfaces:**
- EDL 是 api 和 worker 共用的协议，放在 `workerprotocol` 中：
```java
public record Edl(String videoType, double width, double height, List<Segment> segments, List<Overlay> overlays,
                  @Nullable String musicInput, Audio audio) {
  public enum Kind { REAL, H3, STILL }
  public record Segment(Kind kind, String role /* hook|body|captions|endcard */, String input,
                        double in, double out, boolean hasAudio, boolean blurFill,
                        @Nullable Long sourceMediaId) {}
  public record Overlay(String input, double start, double end) {}      // 时间是成片的时间轴
  public record Audio(double originalVolume, double musicVolume, double musicFadeOut) {}
}
```
  `STILL` 表示静态图（结尾卡），此时 `in = 0`，`out` 为时长。`input` 是 job 中 `inputs` 的键名，例如 `v1`、`v2`、`h3_hook`、`cap_0`、`endcard`、`music`。
- `VideoProperties`（`kiano.content.video`）：
  - 各类型的配方（见 Global Constraints 中的表格）：`hookSeconds=3`，`bodyMin` 和 `bodyMax`，`captionsSeconds=5`，`endCardSeconds=4`。
  - `minTotal=15`，`maxTotal=30`，`maxAiRatio=0.30`，`windowCandidates=5`。
  - 音量参数：`originalVolume`、`musicVolume`、`musicFadeOut`。
  - 每种类型对应的 mood。
  - 要求宽高比在 9:16 ± 2% 以内才算竖屏，即 `portraitTolerance=0.02`。
- `EdlMath`：`totalSeconds(Edl)`，`aiSeconds(Edl)` 只累加 kind 为 H3 的段，`aiRatio(Edl)` 保留 3 位小数，`mixed(Edl)` 判断是否含有 H3 段。
- `EdlValidator.validate(Edl edl, VideoProperties p, Map<String, Double> inputDurations) -> List<Violation>`，其中 `record Violation(String code, Map<String,Object> details)`，code 的取值如下：
  - `AI_RATIO_EXCEEDED`
  - `TOTAL_OUT_OF_RANGE`
  - `SEGMENT_OUT_OF_SOURCE`：`out` 超过素材时长，或者 `in < 0`，或者 `in >= out`。
  - `BODY_OUT_OF_RANGE`
  - `OVERLAY_OUT_OF_TIMELINE`
  - `SEGMENT_ORDER`：顺序必须是 hook、body、captions、endcard，并且每个角色恰好出现一次。
- `VideoPlanner.plan(PlanInput in) -> Edl`，其中 `record PlanInput(VideoType type, Map<String, SourceClip> sources /* v1|v2|v3 */, boolean useH3, Windows windows, int captionCount, String musicInput)`，`record SourceClip(long sourceMediaId, double duration, int width, int height, boolean hasAudio)`，`record Windows(Window hook, Window body, Window captions)`。时间窗由 Task 5 选好之后传进来，规划器自身不访问 ffmpeg。
  - `blurFill` 按宽高比是否在 9:16 ± 2% 以内来决定。
  - body 取 `min(bodyMax, window.length)`，并且不小于 bodyMin；素材不够长时，抛 409 `VIDEO_SOURCE_TOO_SHORT`，`details` 为 `{shot, neededSeconds, actualSeconds}`。
  - 字幕叠加的时间：`cap_hook` 覆盖 hook 段；`cap_0` 覆盖 body 段；`cap_1` 到 `cap_n` 平均分配 captions 段。结尾卡是 STILL 段，没有叠加层。
  - 规划结束时调用 `EdlValidator`，有违规时抛 422，code 取第一个违规的 code，`details.violations` 列出全部违规。

- [ ] **Step 1: 写失败的测试**

```java
@Test void aiRatio_onlyCountsH3_endCardIsNonAi()              // H3 3 秒，总长 24 秒 → 0.125
@Test void plan_demoWithH3_structureAndTimings()               // 段落顺序和叠加时间正确，总长在 [15, 30] 内
@Test void plan_withoutH3_hookIsRealWindow_ratioZero()
@Test void plan_landscapeSource_blurFillTrue_portraitFalse()
@Test void plan_shortV1_shrinksBody_thenTooShort409()          // Review Focus 2
@Test void validate_manualTrimMakesAiOver30_flagged()          // Review Focus 4：H3 3 秒，body 剪到 2 秒
@Test void validate_outBeyondSource_inGeOut_orderAndCounts()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='EdlMathTest,EdlValidatorTest,VideoPlannerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): video EDL model, recipe planner and validator with AI-ratio gate"`

---

### Task 5: 实拍时间窗打分

**Files:**
- Create: `content/video/ClipWindowPicker.java`
- Modify: `content/ads/FrameExtractor.java`（提取公开方法 `frameAt(Path video, double t) -> Optional<byte[]>`；`candidates` 内部改为调用它）
- Test: `content/video/ClipWindowPickerTest.java`（`@EnabledIf("ffmpegAvailable")`）

**Interfaces:**
- `ClipWindowPicker.rank(Path video, double duration, double windowSeconds, int candidates) -> List<Window>`，其中 `record Window(double in, double out, double score)`：
  - 在 `[0, duration - windowSeconds]` 范围内均匀取 `candidates` 个起点，每个窗口在中点取一帧，用 `PhotoQc.blurVariance` 打分，结果按分数从高到低排序。
  - 素材长度不足 `windowSeconds` 时，返回唯一的一个窗口 `[0, duration]`。
- `pick(List<Window> ranked, int candidate, @Nullable Window avoid) -> Window`：取第 `candidate % n` 个窗口；hook 窗口与 body 窗口来自同一个素材时，跳过与 `avoid` 重叠的窗口（实拍开场时 hook 不能和 body 用同一段画面）。

- [ ] **Step 1: 写失败的测试**（测试视频用 ffmpeg 现场生成：前 4 秒加 `gblur=sigma=20` 模糊，之后清晰）

```java
@Test void rank_prefersSharpWindows()
@Test void pick_rotatesWithCandidate_andAvoidsOverlap()
@Test void shortSource_singleFullWindow()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='ClipWindowPickerTest,FrameExtractorTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): rank real-footage windows by sharpness for video cuts"`

---

### Task 6: 字幕与结尾卡模板

**Files:**
- Create: `templates/content/VIDEO_CAPTION/v1.html`、`templates/content/END_CARD/v1.html`、`content/video/VideoOverlayRenderer.java`
- Modify: `content/template/TemplateRenderer.java`（新增 `renderTransparent`）、`content/template/TemplateBootstrap.java`（注册这两个模板，状态 APPROVED）
- Test: `content/video/VideoOverlayRendererTest.java`，`src/test/resources/golden/video/{caption-hook,caption-body,endcard,endcard-sale}.png`

**Interfaces:**
- `TemplateRenderer.renderTransparent(tenantId, code, model, w, h) -> byte[]`：截图时使用 `setOmitBackground(true)`，模板的 body 背景必须是 transparent。
- **VIDEO_CAPTION 模型**为 `{text, position: "hook"|"body", fontScale}`：
  - hook 文字大，放在安全区的上部。
  - body 文字放在安全区的下部（下沿 y ≤ 1248）。
  - 文字下面衬半透明圆角底，容器 class 为 `ad-text`。
- **END_CARD 模型**为 `{productName, productImage (PAGE_MAIN data URI), current, strike?, endsLabel?, badges:[cod, momo, delivery], cta:"Order on WhatsApp", whatsapp?}`：
  - 价格由 C4a 的 `PriceDisplay.of(product, now)` 计算。
  - badges 由 `requireAdBadges` 提供。
  - `whatsapp` 取自配置 `kiano.content.video.whatsapp-display`，默认空，为空时不显示。
- Produces: `VideoOverlayRenderer.render(long tenantId, ProductView product, VideoScriptText script, int captionCount) -> RenderedOverlays`，其中 `record RenderedOverlays(Map<String, byte[]> pngs /* cap_hook, cap_0..cap_n, endcard */, PriceDisplay price, List<PrecheckFlag> flags, Map<String,Object> templateVersions)`。
  - 安全区的处理沿用 C4a：依次用 `fontScale` 1.0、0.9、0.8 渲染，仍然越界时加上 `TEXT_OUTSIDE_SAFE_AREA`。
  - `templateVersions` 记录每个模板的 code 和 version，写入 provenance，Task 11 的自动通过判定要用到。

- [ ] **Step 1: 写失败的测试**

```java
@Test void captionsMatchGolden_andAreTransparentOutsideText()   // 四角像素的 alpha 为 0
@Test void endCardMatchesGolden_withAndWithoutSale()
@Test void longCaption_staysInsideSafeArea_orFlagged()
@Test void escapesScriptText()                                  // "<b>x</b>" 原样显示为文本
@Test void endCard_noWhatsappConfigured_hidesNumber()
```

- [ ] **Step 2–4: 先确认失败 → 实现（首次生成 golden 后要人工看一遍）→ 确认通过**　Run: `./mvnw -q test -Dtest='VideoOverlayRendererTest,AdTemplateGoldenTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): transparent caption and END_CARD video overlays with safe-area fitting"`

---

### Task 7: worker 中的 FFmpeg 合成执行器

**Files:**
- Create: `worker/ffmpeg/{FfmpegProperties,FfmpegCommandBuilder,FfmpegExecutor}.java`
- Modify: `worker/KianoWorkerApplication.java`（启动时探测 ffmpeg，可用时才上报 FFMPEG 能力）、`scripts/run-worker.ps1`（如有需要，补充 ffmpeg 路径的提示）
- Test: `worker/ffmpeg/FfmpegCommandBuilderTest.java`（纯函数）、`worker/ffmpeg/FfmpegExecutorTest.java`（`@EnabledIf("ffmpegAvailable")`，用真实的 ffmpeg）

**Interfaces:**
- 输入：job 的 `input_json` 中有 `edl`（即 `Edl`）以及 `inputs` 和 `outputs`；`outputs` 固定为三个键：`video`（.mp4）、`video_nomusic`（.mp4）、`poster`（.jpg）。
- `FfmpegCommandBuilder.build(Edl edl, Map<String, Path> inputs, Path video, Path noMusic, Path poster, FfmpegProperties p) -> List<List<String>>`，返回三条命令，依次执行：
  1. **合成画面和原声**，输出到中间文件 `master.mkv`：
     - 每一段都执行 trim/atrim（或者对 STILL 用 `-loop 1 -t`），然后按 `blurFill` 选择以下其中一种处理：
       - `scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920`
       - `split` 成两路：一路 `scale…increase,crop,boxblur=40:5`，另一路 `scale…decrease`，再 `overlay=(W-w)/2:(H-h)/2`
     - 之后统一 `fps=30,setsar=1,format=yuv420p`。
     - 音频：段落有原声时用原声乘以 `originalVolume`；没有原声、H3 段和 STILL 段用 `anullsrc=r=48000:cl=stereo` 截取到段落长度。
     - 用 `concat=n=N:v=1:a=1` 拼接，再按时间轴依次 `overlay=enable='between(t,start,end)'` 叠加 PNG。
  2. **加配乐版本**：master 的音频与 `-stream_loop -1` 读入的配乐（`atrim` 到总长，`afade=t=out` 在最后 `musicFadeOut` 秒淡出，音量乘以 `musicVolume`）用 `amix=inputs=2:normalize=0` 混合，再 `loudnorm`。编码参数见 Global Constraints，输出 `video`。
  3. **无配乐版本**：只把 master 的音频做 `loudnorm`，输出 `video_nomusic`；另外在 `min(4.0, 总长/2)` 秒处截一帧，输出 `poster`。
- `FfmpegExecutor implements GenerationExecutor`，`type()` 为 FFMPEG：依次执行三条命令。stderr 只保留最后 2 KB，写入错误信息。ffmpeg 返回非零时抛不可重试的 `ExecutorException`（输入无效）；进程无法启动时抛 `ExecutorUnavailableException`。`gpuSeconds` 记为 0。

- [ ] **Step 1: 写失败的测试**

```java
// FfmpegCommandBuilderTest
@Test void portraitSegment_coverCrop_landscape_blurFill()
@Test void silentSegments_useAnullsrcWithSegmentDuration()
@Test void overlaysUseEnableBetweenTimeline()
@Test void musicLoopedTrimmedAndFaded_noMusicHasOriginalOnly()
// FfmpegExecutorTest（Review Focus 1）：素材用 ffmpeg lavfi 现场生成——竖屏 30fps 带音频；横屏 1920×1080 加 rotate=90 元数据；60fps 无音轨
@Test void composesAllVariants_to1080x1920_30fps_h264_aac_durationMatchesEdl()   // 用 ffprobe 核对，误差 ±0.1 秒
@Test void noAudioSource_producesSilenceNotError()
@Test void writesNoMusicRenditionAndPoster()
@Test void corruptInput_nonRetryableWithStderrTail()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='FfmpegCommandBuilderTest,FfmpegExecutorTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(worker): FFMPEG executor composing EDL videos with overlays, music and no-music rendition"`

---

### Task 8a: H3 接口后端（端口、模拟回放适配器、轮询、预算）

**Files:**
- Create: `content/video/h3/{H3Client,H3Request,H3TaskStatus,MiniMaxH3Client,H3Properties,H3PromptBuilder,H3JobRunner}.java`、`templates/content/H3_PROMPT_{DEMO,PROBLEM,UNBOXING}/v1.txt`、`src/test/resources/wiremock/h3/*.json`（**暂定**响应，见附录 A）
- Modify: `.env.example`（`MINIMAX_API_KEY=`、`KIANO_H3_API_ENABLED=false`、`KIANO_H3_BACKEND=LOCAL_FIRST`）、`application.yml`
- Test: `content/video/h3/{MiniMaxH3ClientTest,H3PromptBuilderTest,H3JobRunnerTest}.java`

**Interfaces:**
- 端口：
```java
public interface H3Client {
  String submit(H3Request request);            // 返回提供商的任务 ID
  H3TaskStatus poll(String taskId);
  void download(H3TaskStatus.Succeeded done, Path target);
}
public record H3Request(String prompt, byte[] firstFrameJpeg, int durationSeconds, String resolution) {}
public sealed interface H3TaskStatus {
  record Pending() implements H3TaskStatus {}
  record Succeeded(String fileRef) implements H3TaskStatus {}
  record Failed(String reason, boolean retryable, boolean contentRejected) implements H3TaskStatus {}
}
```
- 回调接口 `H3TerminalHandler { void onH3Succeeded(GenerationJob job); void onH3Escalate(GenerationJob job, String reason); }` 放在 `content/video/h3`，本任务和 Task 8b 都通过它回调，Task 9 由 `VideoCompositionCoordinator` 实现。在 Task 9 之前，测试中用桩替代它。
- `H3Properties`（`kiano.content.video.h3`）：
  - `backend=LOCAL_FIRST`，取值为 `LOCAL_FIRST`、`LOCAL_ONLY`、`API_ONLY`、`OFF`，Task 8b 使用。
  - `requestSeconds=5`：两个后端共用。
  - `api.*`：
    - `enabled=false`
    - `baseUrl`；`apiKey` 取 `${MINIMAX_API_KEY:}`；`model`；`submitPath`、`queryPath`、`filePath` 三个路径
    - `resolution=1080P`
    - `pricePerSecondUsd=0`（拿到报价后标定）
    - `dailyBudgetUsd=10`，`pollInterval=20s`，`maxWait=15m`，`timeout=30s`
  - `local.*`：`width=720`，`height=1280`，`fps=24`，`localMaxWait=2h`，`comfyTimeoutSeconds=1800`，Task 8b 使用。
- `MiniMaxH3Client`：用 RestClient 实现，请求头为 `Authorization: Bearer {apiKey}`。**请求和响应的结构是暂定的**：参照 MiniMax 现有视频生成 API 的"提交任务 → 查询状态 → 取文件"三步模式，所有路径和字段名都集中在这一个类和 WireMock 录制文件中，便于真实文档到达后替换。
  - 状态映射：429 和 5xx 视为可重试；4xx 视为不可重试；内容安全拒绝时 `contentRejected=true`。
  - 异常信息和日志中**不能出现 key**，用 `ErrorRedaction.clean(raw, apiKey)` 处理。
- `H3PromptBuilder.build(VideoType type, ProductView product, String sceneLabel) -> String`：用 `H3_PROMPT_*` 模板渲染，每次使用模板的当前 APPROVED 版本。结构严格按照 h3-prompt-writing 的 I2VA 格式：
  1. 第一行是 `For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.`，后面空一行。
  2. 然后依次是 `integrated_multimodal_description`、`overall_soundscape`、`non_diegetic_music` 三个字段。
  3. 描述内容只能是静态商品的运镜：demo 用 `slow push in with small amplitude`，problem 用 `slow arc shot`，unboxing 用 `slow pedestal down`；加上光线和氛围的变化。
  4. 明确写出 `No hands, no people, the product does not operate, no on-screen text`。
  5. 时长与 `requestSeconds` 一致；`non_diegetic_music: None`。
- `H3JobRunner`：`@Scheduled(fixedDelay = api.pollInterval)`，**只在非 worker 的 profile 中运行**，只处理 `H3_CLIP_API` 任务。每轮最多处理 2 个任务，处理方式如下：
  1. 用 `jobs.lease("api-h3", Set.of(H3))` 领取任务。
  2. 任务还没有 `external_ref` 时：
     1. 检查当天预算：`costSince(今天 00:00 UTC) + requestSeconds × price` 超过 `dailyBudgetUsd` 时，任务以不可重试失败结束，error 为 `H3_BUDGET_EXCEEDED`。
     2. 从 MinIO 读取首帧图，构造提示词并提交。
     3. 调用 `park(id, "api-h3", taskId, pollInterval)`。
  3. 已经有 `external_ref` 时，查询状态：
     - **Pending**：`now - started_at > maxWait` 时以不可重试失败结束，error 为 `H3_TIMEOUT`；否则继续 `park`。
     - **Succeeded**：下载到临时文件，上传到 `outputs.clip` 指向的位置，然后 `complete(…, cost = requestSeconds × price)`，并调用 `H3TerminalHandler.onH3Succeeded(job)`。
     - **Failed**：用 `jobs.fail(...)` 记录失败，可重试时指数退避。只有任务**最终**进入 FAILED 状态时，才通知 `JobFailureListener`（由 coordinator 处理升级或改用实拍）。
  4. 每次提交和完成都写审计 `H3_CLIP_SUBMITTED` 或 `H3_CLIP_FINISHED`，after 中只记录 jobId、taskId、秒数和成本，**不记录提示词之外的输入，也不记录 key**。

- [ ] **Step 1: 写失败的测试**

```java
// MiniMaxH3ClientTest（WireMock）
@Test void submitPollDownload_happyPath()
@Test void http429Retryable_4xxNot_contentRejectedFlagged()
@Test void errorMessagesNeverContainApiKey()
// H3PromptBuilderTest
@Test void i2vaStructure_firstLineBlankLineThreeFields_inOrder()
@Test void forbidsHandsOperationAndText()
// H3JobRunnerTest（H3Client 用桩替代，GenerationJobStore 是真实的）
@Test void submitsThenParks_withoutConsumingAttempt()
@Test void apiRestartMidPoll_resumesFromExternalRef_noResubmit()     // Review Focus 3：再次领取到已有 external_ref 的任务，不再调用 submit
@Test void timeoutAfterMaxWait_failsAndNotifiesCoordinator()
@Test void budgetExceeded_failsWithoutSubmitting()
@Test void succeeded_uploadsClip_recordsCost_notifiesCoordinator()
@Test void retryableFailure_backsOff_doesNotNotifyUntilFinal()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='MiniMaxH3ClientTest,H3PromptBuilderTest,H3JobRunnerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): async H3 API backend with parked polling, budget, timeout and cost ledger"`

---

### Task 8b: H3 本地 ComfyUI 后端与后端选择

**Files:**
- Create: `content/video/h3/{H3Backend,H3BackendSelector,H3LocalWatchdog}.java`、`src/test/resources/workflows/h3_i2v_fixture/{workflow.json,manifest.json}`（测试用的最小工作流，模型许可证为 MIT，**只用于测试注册和绑定**）
- Modify: `content/workflow/WorkflowRegistry.java`（允许 `H3_I2V`，并校验契约）、`workerprotocol/WorkflowManifest.java`（文档注释：输出绑定的 `field` 表示历史记录中的键名）、`worker/comfy/ComfyUIExecutor.java`
- Test: `content/workflow/WorkflowRegistryTest`（追加用例）、`worker/comfy/ComfyUIExecutorTest`（追加用例，用 WireMock 模拟 ComfyUI）、`content/video/h3/{H3BackendSelectorTest,H3LocalWatchdogTest}.java`

**Interfaces:**
- **`H3_I2V` 契约**，在 `WorkflowRegistry.validateCode` 和对应的契约校验中实现：
  - inputs 必须有 `image`（首帧，可选 `maxLongSide`）。
  - params 必须有 `positive`、`seed`、`width`、`height`、`frames`；`negative` 和 `fps` 可选。
  - outputs 必须有 `clip`，它的 `field` 是 ComfyUI 历史记录中输出所在的键名：核心的 SaveVideo 节点用 `images`，VHS_VideoCombine 节点用 `gifs`；不写时默认为 `images`。
  - 模型许可证照常按白名单校验（Global Constraints 第 6 条）。
- **`ComfyUIExecutor` 的修改**：
  - 读取输出时用 `history.outputs[node][binding.field ?: "images"][0]`。
  - 下载下来的文件必须是 mp4（检查扩展名，并检查文件头中有 `ftyp`），否则抛不可重试的 `OUTPUT_FORMAT`。
  - job input 中有 `comfyTimeoutSeconds` 时，用它替代全局超时。
  - 现有的 CUTOUT 和 SCENE 工作流行为不变，outputs 不写 field 时取 `images`。
- **`H3BackendSelector.initial(long tenantId) -> Optional<H3Backend>`**，`enum H3Backend { LOCAL, API }`，按 `h3.backend` 选择：
  - `LOCAL_FIRST`：有激活的 `H3_I2V` 工作流时返回 LOCAL；否则 `api.enabled` 时返回 API；否则为 empty。
  - `LOCAL_ONLY`：只考虑 LOCAL。
  - `API_ONLY`：只考虑 API。
  - `OFF`：一律为 empty。
- **`H3BackendSelector.next(long tenantId, H3Backend failed) -> Optional<H3Backend>`**：只有 `LOCAL_FIRST` 模式下才会从 LOCAL 升级到 API，前提是 `api.enabled` 并且当天预算足够；其余情况返回 empty，即改用实拍。
- **本地任务的创建**由 Task 9 负责：`H3_CLIP_LOCAL` 的 input 为 `{workflow:{code,version,json,manifest}, params:{positive, negative, seed (随机), width, height, frames = requestSeconds × fps, fps}, inputs:{image: firstFrameKey}, outputs:{clip: ….mp4}, comfyTimeoutSeconds, fallbackWindow, typeContext}`。provenance 中记录 workflow 的 code 和 version、models、seed。
- **`H3LocalWatchdog`**：`@Scheduled(fixedDelay = 1m)`，只在非 worker 的 profile 中运行。它查找创建时间早于 `now() - localMaxWait` 并且仍处于 QUEUED、WAITING_EXECUTOR 或 LEASED 状态的 `H3_CLIP_LOCAL` 任务，先 `jobs.cancel(...)`，取消成功后调用 `H3TerminalHandler.onH3Escalate(job, "H3_LOCAL_TIMEOUT")`。本地任务最终失败时，由 `JobFailureListener` 触发 `onH3Escalate(job, "H3_LOCAL_FAILED")`。

- [ ] **Step 1: 写失败的测试**

```java
// WorkflowRegistryTest
@Test void h3I2v_registersWithContract_missingFramesParam422()
@Test void h3I2v_modelLicenseNotWhitelisted_rejected()        // Global Constraints 第 6 条
// ComfyUIExecutorTest
@Test void videoOutput_readsConfiguredHistoryKey_gifs()
@Test void videoOutput_notMp4_nonRetryableOutputFormat()
@Test void perJobTimeoutOverridesGlobal()
@Test void existingSceneWorkflow_unchanged_defaultImagesKey()
// H3BackendSelectorTest
@Test void localFirst_prefersActiveLocalWorkflow_elseApiIfEnabled_elseEmpty()
@Test void next_localFailed_escalatesToApiOnlyInLocalFirstWithBudget()
@Test void localOnly_apiOnly_off_modes()
// H3LocalWatchdogTest（Review Focus 3）
@Test void waitingLocalJobPastMaxWait_cancelledAndEscalated()
@Test void cancelledLeasedJob_lateWorkerCompletion_discarded_noSecondAsset()
@Test void jobWithinMaxWait_untouched()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='WorkflowRegistryTest,ComfyUIExecutorTest,H3BackendSelectorTest,H3LocalWatchdogTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): local ComfyUI H3 backend (H3_I2V workflow) with local-first selection and escalation"`

---

### Task 9: 视频生成编排与资产创建

**Files:**
- Create: `content/video/{VideoRenderService,VideoCompositionCoordinator,VideoController}.java`
- Modify: `content/asset/{AssetService,AssetFileName}.java`（新增 `createVideoFromJob` 和 `rendition`）
- Test: `content/video/{VideoRenderServiceTest,VideoCompositionCoordinatorTest}.java`、`content/asset/AssetFileNameTest.java`（追加用例）

**Interfaces:**
- `VideoRenderService.render(CurrentUser user, long productId, Set<VideoType> typesOrAll, int candidate, @Nullable Edl overrideEdl) -> long runId`（OPERATOR）：
  - **前置条件**：
    - 商品是 HERO，有锁定的事实。
    - 对应类型的 VIDEO_SCRIPT 已经通过审核。
    - V1、V2、V3 都是 ACCEPTED 的源视频；unboxing 需要 V3，demo 和 problem 需要 V1，三种都需要 V2。
    - 有通过审核的 PAGE_MAIN，满足 `requireAdBadges`，曲库不为空。
    - `H3BackendSelector.initial` 选出了后端时，还需要有 H3 首帧所需的通过审核的图；没有时不报错，直接按 `H3_NO_BACKEND` 用实拍开场。
    - 不满足时返回 409 `VIDEO_PRECONDITIONS`，`details.missing` 列出缺少的项。
  - **每种类型的处理**：
    1. 用 `ClipWindowPicker` 选出时间窗（源视频从 MinIO 下载到临时目录）。
    2. 用 `VideoPlanner.plan` 生成 EDL；`useH3 = H3BackendSelector.initial(tenant).isPresent()`。
    3. 用 `VideoOverlayRenderer` 渲染字幕和结尾卡，结果存到 `t{tid}/video-inputs/{runId}/{type}/…png`。
    4. 用 `MusicPicker` 选配乐，`rotation` 取该商品该类型已有的 VIDEO_AD 版本数。
    5. 在 `generation_run(kind=VIDEO)` 下创建任务：
       - 使用 H3 时，先按选出的后端创建 `H3_CLIP_LOCAL`（input 见 Task 8b）或 `H3_CLIP_API` 任务（input 为 `{prompt, firstFrameKey, outputs:{clip}, fallbackWindow, typeContext}`）。两者的 `typeContext` 都包含 compose 任务所需的全部信息，升级时据此创建下一个后端的任务。
       - 不使用 H3 时，直接创建 `VIDEO_COMPOSE` 任务。
    6. compose 任务的 input 包含 `edl`、`inputs`（各素材的对象键）、`outputs`（video、video_nomusic、poster）以及 `provenance` 的种子信息：`scriptAssetId`、`sourceMediaIds`、`musicTrackId`、`templates`、`price`、`windows`、`candidate`、`factVersion`、`h3`。
  - 传入 `overrideEdl` 时（Task 10 的手动调整），跳过选窗和规划，使用传入的 EDL，并重新运行校验。
- `VideoCompositionCoordinator implements JobCompletionListener, JobFailureListener, H3TerminalHandler`：
  - `onH3Succeeded(job)`：由 `onSucceeded` 在步骤为 `H3_CLIP_LOCAL` 时调用，或者由 `H3JobRunner` 调用。把 EDL 中的 hook 段设为 `kind=H3`、`input="h3_hook"`、`in=0`、`out=hookSeconds`，在 `h3.attempts` 中追加一条成功记录，然后重新校验，再创建 `VIDEO_COMPOSE` 任务。
  - `onH3Escalate(job, reason)`：由 `JobFailureListener.onFailed`（两种 H3 步骤都会触发，reason 取 job.error 的 code）和 `H3LocalWatchdog` 调用。
    1. 在 `h3.attempts` 中追加一条失败记录。
    2. `H3BackendSelector.next(tenant, 失败的后端)` 有结果时，用同一个 `typeContext` 创建下一个后端的任务。
    3. 没有结果时，改用 `fallbackWindow` 作为实拍 hook 段，记录 `h3Fallback.reason`，重新校验，再创建 `VIDEO_COMPOSE` 任务。
  - **幂等**：同一个 `typeContext` 只能产生一个 VIDEO_COMPOSE 任务，用 `typeContext.composeKey` 作为去重键。被取消的本地任务即使后来又成功回报，也不会再次触发合成（Review Focus 3）。
  - `onSucceeded(job)`：步骤为 VIDEO_COMPOSE 时，调用 `assets.createVideoFromJob(job, sku, provenance)`，然后 `runs.refreshStatus`。
- `AssetService.createVideoFromJob(GenerationJob job, String sku, Map<String,Object> provenance) -> AssetView`：
  - 把 `video` 复制到 `t{tid}/assets/{pid}/VIDEO_AD/{type}/v{n}.mp4`；`video_nomusic` 和 `poster` 放到同一目录，并写入 `renditions_json = {nomusic, poster}`。缩略图用 poster 生成。
  - `duration_s` 取 worker 上报的测量值。
  - `ai_ratio = EdlMath.aiRatio(edl)`，`depends_on_price = true`，`price_snapshot` 取 provenance 中的价格。
  - 文件名由 `AssetFileName.format(sku, type.wire(), mixed ? "mixed" : "real", 1080, 1920, n, "mp4")` 生成。
  - **ai_ratio 超过 0.30 时**，状态设为 REJECTED，加上预检标记 `AI_RATIO_EXCEEDED`，并写入 asset_review（reviewer 为 null，reason_codes 为 `["AI_RATIO_EXCEEDED"]`），审计 `VIDEO_BLOCKED_AI_RATIO`；否则状态为 IN_REVIEW。同一 variant 已有的 DRAFT 和 IN_REVIEW 版本改为 ARCHIVED，规则与现有的 `createFromJob` 相同。
- `AssetFileName.rendition(String fileName, String suffix) -> String`：例如 `…_v1.mp4` 加上 `nomusic` 变为 `…_v1_nomusic.mp4`。
- 端点：`POST /api/v1/content/products/{id}/videos/render {types?}`（OPERATOR）→ 202 `{runId}`；前置条件不满足时直接返回 409。

- [ ] **Step 1: 写失败的测试**（真实的 PG、MinIO 和 TemplateRenderer；ClipWindowPicker 和 MusicPicker 用 `@MockitoBean`；ffmpeg 合成部分在测试中直接调用 `complete`，并预先放好 mp4 夹具）

```java
@Test void render_h3Disabled_createsThreeComposeJobs_withEdlInputsOutputs()
@Test void preconditionsMissing_409WithList()                  // 缺少 V3、unboxing 的脚本未审核、曲库为空
@Test void localWorkflowActive_createsLocalH3Jobs_composeAfterSuccess()
@Test void localFailed_escalatesToApi_thenApiFailed_fallsBackToReal()    // Review Focus 3：h3.attempts 中有两条记录
@Test void h3Failed_composeUsesFallbackWindow_recordsReason_ratioZero()
@Test void duplicateTerminalSignals_createSingleComposeJob()
@Test void composeSucceeded_createsVideoAsset_withRenditionsFileNameAndPrice()
@Test void aiRatioOver30AtCompletion_assetRejectedNotInReview()           // Review Focus 4：在 job input 中手工构造超标的 EDL
@Test void newVersion_archivesPreviousInReview()
@Test void rendition_appendsSuffixBeforeExtension()            // AssetFileNameTest
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='VideoRenderServiceTest,VideoCompositionCoordinatorTest,AssetFileNameTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): orchestrate HERO video renders - plan, overlays, music, H3 fallback and AI-gated assets"`

---

### Task 10: 审核、手动调整入出点与重新生成

**Files:**
- Create: `content/video/VideoEdlController.java`
- Modify: `content/asset/ReviewService.java`
- Test: `content/video/VideoEdlControllerTest.java`、`content/asset/VideoReviewTest.java`

**Interfaces:**
- `ReviewItem` 增加以下字段，非视频资产时为 null：
  - `videoUrl`、`noMusicUrl`、`posterUrl`：预签名，有效期 15 分钟。
  - `durationS`、`aiRatio`。
  - `edl`：取自 provenance。
  - `sourceUrls`：`{v1, v2, v3}` 的预签名地址，供编辑器预览。
- 排序：VIDEO_AD 排在 AD_STATIC 之后，按 demo、problem、unboxing 的顺序排列；VIDEO_SCRIPT 排在 AD_COPY 之后。
- **REGENERATE**：VIDEO_AD 入队重新渲染这一种类型，`candidate` 取旧版本 provenance 中的值加 1，以换用下一组时间窗；同时轮换配乐（因为版本数增加了）。VIDEO_SCRIPT 的处理见 Task 1。
- `PUT /api/v1/content/assets/{id}/edl`（OPERATOR），请求体为 `{segments:[{role, in, out}]}`：
  - **只允许修改 kind 为 REAL 的段的入点和出点**。H3 段、STILL 段和叠加层不能改；叠加层的时间按新的段落时长重新计算（规则与 Task 4 规划器相同）。
  - 修改后用 `EdlValidator` 校验，有违规时返回 422，`details.violations` 列出全部违规。例如 AI 占比超标返回 `AI_RATIO_EXCEEDED`，出点超过素材时长返回 `SEGMENT_OUT_OF_SOURCE`（Review Focus 2 和 4）。
  - 校验通过后调用 `VideoRenderService.render(…, overrideEdl)`，**复用旧版本的字幕和结尾卡 PNG 以及配乐**（价格变化走 Task 11 的流程，不走这里），新版本进入 IN_REVIEW。写审计 `VIDEO_EDL_EDITED`，before 和 after 中记录各段的入点和出点。
  - 资产状态为 PUBLISHED 或 ARCHIVED 时返回 409 `ASSET_NOT_EDITABLE`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void reviewItem_videoHasPresignedUrlsEdlAndRatio()
@Test void ordering_videosAfterAdStatics_scriptsAfterAdCopy()
@Test void regenerate_video_usesNextCandidateAndRotatesMusic()
@Test void editEdl_validTrim_enqueuesComposeReusingOverlaysAndMusic()
@Test void editEdl_aiOver30_or_outBeyondSource_422WithViolations()
@Test void editEdl_cannotChangeH3OrStill_422()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='VideoEdlControllerTest,VideoReviewTest,ReviewServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): video review with presigned playback, manual in/out trims and regenerate"`

---

### Task 11: 改价后重新合成、替换清单与导出

**Files:**
- Create: `content/video/VideoPriceListener.java`
- Modify: `content/ads/{AdExportService,AdExportTaskHandler,ManifestCsv,AdReplacementService}.java`
- Test: `content/video/VideoPriceListenerTest.java`、`content/ads/AdExportServiceTest`、`AdReplacementServiceTest`（追加用例）

**Interfaces:**
- `VideoPriceListener` 用 `@TransactionalEventListener(AFTER_COMMIT)` 监听 `ProductPriceChanged`，处理每个 variant 的最新 VIDEO_AD 版本：
  1. 状态为 APPROVED 或 PUBLISHED 的改为 STALE，记下它的 ID；状态为 IN_REVIEW 或 DRAFT 的改为 ARCHIVED。
  2. 用新价格**只重新渲染结尾卡**，字幕 PNG、EDL、H3 片段和配乐都复用旧版本的对象键。
  3. 创建 VIDEO_COMPOSE 任务，input 中带上 `autoApproveFrom: staleAssetId`。
  4. 写审计 `VIDEOS_STALE_BY_PRICE`。
- **自动通过**在 `createVideoFromJob` 中判定，规则与 C4a 一致：新旧两版的 `scriptAssetId`、EDL（价格以外的部分）、`musicTrackId`、`templates`（版本）、`h3` 都相同，并且 END_CARD 模板是 APPROVED 状态，满足时新版本直接设为 APPROVED，并写审计 `VIDEO_AUTO_APPROVED_PRICE_CHANGE`；否则进入 IN_REVIEW。worker 离线时，任务进入 WAITING_EXECUTOR，旧版本保持 STALE，worker 恢复后自动完成。
- `AdReplacementService` 的范围扩大到 `spec_code in ('AD_STATIC','VIDEO_AD')`，`Replacement` 增加 `specCode` 字段。
- 导出：`AdExportService.request(user, productIds, Set<ExportPart> parts)`，其中 `enum ExportPart { STATIC, VIDEO }`，默认值为 `{STATIC}`，保持 C4a 的行为不变。
  - 包含 VIDEO 时，这个 SKU 的 3 条 VIDEO_AD 必须都已通过审核，否则列入 skipped，原因为 `VIDEOS_NOT_ALL_APPROVED`。
  - ZIP 中放入 mp4 和 `_nomusic.mp4` 两个文件。manifest 增加对应的行：`hook` 填视频类型，`size` 为 `1080x1920`，`headline` 为脚本的 hook 句，`primary_text` 为全部字幕用 ` · ` 连接，`price_snapshot` 照常填写。
  - 端点的请求体增加 `parts`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void priceChange_approvedVideo_staleThenRecomposedAndAutoApproved()     // Review Focus 5
@Test void priceChange_onlyEndCardRerendered_otherInputsReused()
@Test void edlEditedOrNewScriptSinceLastRender_newVersionInReview()
@Test void workerOffline_staysStaleUntilComposeCompletes()
@Test void replacements_includeExportedThenStaleVideos()
@Test void export_withVideos_requiresAllThree_zipHasNoMusicRendition_manifestRows()
@Test void export_defaultStaticOnly_unchanged()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='VideoPriceListenerTest,AdExportServiceTest,AdReplacementServiceTest,AdPriceListenerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): price-driven video recompose with auto-approval, replacements and video export"`

---

### Task 12: 前端——曲库设置页

**Files:**
- Create: `kiano-web/src/app/(app)/settings/music/page.tsx`、`src/lib/music.ts`、`src/lib/music.test.ts`
- Modify: `src/app/(app)/layout.tsx`（设置菜单中加入"曲库 Music"）、`src/lib/types.ts`、`src/i18n/{en,zh}.ts`、`src/i18n/i18n.test.ts`

**Interfaces:**
- `lib/music.ts`：
  - `LICENSE_TYPES = ['CC0','ROYALTY_FREE_COMMERCIAL','OWNED']`
  - `MOODS = ['upbeat','uplifting','calm','energetic']`
  - `validateMusicForm(form) -> Record<field, errorKey>`：必填项、文件扩展名、20 MB 上限。
- 页面：
  - 曲目列表：标题、时长、情绪、许可证类型和来源、使用次数、状态、试听（使用 preview 端点）、停用按钮。
  - 上传表单：只有 OWNER 能看到；许可证三个字段都是必填，表单上方说明不接受 CC-BY 这类需要署名的许可。
  - 错误码 `DUPLICATE_TRACK`、`VALIDATION_FAILED` 要做本地化。

- [ ] **Step 1: 写失败的测试**（`music.test.ts`：缺少许可证字段、扩展名或文件大小不对时都返回错误；i18n 覆盖清单中加入新的键）
- [ ] **Step 2–4: 先确认失败 → 实现 → 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): music library settings page with license capture"`

---

### Task 13: 前端——商品页视频面板、审核视频卡与入出点编辑器

**Files:**
- Create: `kiano-web/src/components/{VideosPanel,VideoAssetCard,EdlEditor}.tsx`、`src/lib/edl.ts`、`src/lib/edl.test.ts`
- Modify: `src/app/(app)/products/[id]/page.tsx`（只有 HERO 商品显示 VideosPanel）、`src/app/(app)/review/page.tsx`（kind 为 VIDEO 时显示 VideoAssetCard）、`src/components/TextAssetCard.tsx`（VIDEO_SCRIPT 显示 hook 和逐条字幕，带字数，上限分别为 40 和 42）、`src/lib/types.ts`、`src/i18n/*`

**Interfaces:**
- `lib/edl.ts` 中是与后端规则对应的纯函数：
  - `totalSeconds(edl)`、`aiRatio(edl)`
  - `applyTrims(edl, trims) -> Edl`：只修改 REAL 段，并重新计算叠加层的时间。
  - `violations(edl, durations, limits) -> string[]`：返回违规的 code 列表，与后端 `EdlValidator` 一致。
- **VideosPanel**：三张卡，分别是 demo、problem、unboxing。每张卡显示脚本状态、视频状态、时长和 AI 占比。面板上有"生成脚本"和"生成视频"两个按钮；遇到 409 `VIDEO_PRECONDITIONS` 时，把 `details.missing` 逐项翻译显示。
- **VideoAssetCard**：
  - 用 `<video controls preload="metadata" poster>` 播放，可以在有配乐和无配乐之间切换。
  - 显示 AI 占比徽标（超过 30% 时为红色）、预检标记，以及 H3 是否改用了实拍（`h3Fallback`）。
  - 提供通过、驳回、重新生成三个按钮。
- **EdlEditor**：
  - 每个 REAL 段显示角色名、所用素材，以及入点和出点两个数字输入框（步长 0.1 秒）。
  - 旁边有一个小的 `<video>` 预览，`currentTime` 跟随当前编辑的输入框。
  - 实时显示总时长、AI 占比和违规列表，有违规时禁用"保存并重新合成"按钮。
  - 保存时调用 `PUT /assets/{id}/edl`；返回 422 时显示后端给出的违规列表，以后端结果为准。
- i18n 新增：视频类型、段落角色（hook、body、captions、endcard），错误码 `VIDEO_PRECONDITIONS`、`VIDEO_SOURCE_TOO_SHORT`、`AI_RATIO_EXCEEDED`、`SEGMENT_OUT_OF_SOURCE`、`TOTAL_OUT_OF_RANGE`、`BODY_OUT_OF_RANGE`、`ASSET_NOT_EDITABLE`、`MUSIC_LIBRARY_EMPTY`，以及 H3 的后端名称（本地、接口）和各种升级或改用实拍的原因（`H3_NO_BACKEND`、`H3_LOCAL_TIMEOUT`、`H3_LOCAL_FAILED`、`H3_TIMEOUT`、`H3_BUDGET_EXCEEDED`、`H3_CONTENT_REJECTED`、`H3_FAILED`）。VideoAssetCard 中按 `h3.attempts` 显示 H3 的尝试记录，例如"本地超时 → 接口成功"。

- [ ] **Step 1: 写失败的测试**（`edl.test.ts`：对同一组夹具，`aiRatio`、`applyTrims` 和 `violations` 的结果与后端 `EdlValidatorTest` 一致；i18n 覆盖清单）
- [ ] **Step 2–4: 先确认失败 → 实现 → 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: 浏览器验证**：在审核页播放视频，在有配乐和无配乐之间切换；把 body 剪到很短时出现 AI 占比违规，"保存并重新合成"按钮被禁用。
- [ ] **Step 6: Commit** — `git commit -m "feat(web): HERO videos panel, video review card and EDL trim editor"`

---

### Task 14: 文档与 C5 验收

**Files:**
- Modify:
  - `docs/superpowers/specs/2026-10-07-content-module-design.md`：在 §14 C5 后写明本计划确认的决定；在 §15 第 1 项注明"H3 有本地 ComfyUI 和服务商接口两个后端，优先本地；接口先行、模拟回放，真实对接见 C5 计划附录 A，本地工作流注册见附录 B"；在 §4.1 的组件图中注明本地 H3 由 worker 中的 ComfyUIExecutor 执行；把 §15 第 5 项更新为"已确认：Kiano 内建曲库"。
  - `README.md`：worker 需要安装 ffmpeg；曲库；H3 的开关与预算。
  - `CLAUDE.md`：硬规则加上 AI 占比在保存和完成两处拦截、视频上的文字一律由模板渲染成 PNG 叠加、H3 只做运镜和氛围以及失败时用实拍代替、曲库许可证白名单。

- [ ] **Step 1: 更新文档**
- [ ] **Step 2: 运行全部测试**（先停掉 worker）：`cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm lint && pnpm build`
- [ ] **Step 3: 按规格 §14 C5 做端到端验收**。前提：一个 C4a 已经完成验收的 HERO SKU，带有 V1、V2、V3；曲库中至少有 2 首曲目；worker 已经启动，并且装好了 ffmpeg；没有注册本地 `H3_I2V` 工作流，`h3.api.enabled=false`（即当前状态）。
  1. 生成脚本，3 组脚本进入审核，全部通过。
  2. 生成视频，3 条视频进入审核。逐条检查以下各项：
     - 1080×1920、30fps，时长在 15–30 秒之间。
     - 字幕在安全区内；结尾卡上的价格显示为 `GH₵ …`，并且有 COD 和 MoMo 的 badge。
     - 有配乐版本与无配乐版本都能播放。
     - AI 占比为 0%，文件名中的素材类型为 `real`。
  3. 在编辑器中调整 demo 的 body 入点，保存后生成新版本；把 body 剪到让总长不足 15 秒，界面阻止保存；直接调用 API 时返回 422。
  4. **拦截验证**：
     1. 临时把配置 `max-ai-ratio` 调到 0.05，设置 `h3.backend=API_ONLY` 和 `h3.api.enabled=true`，并把 H3 客户端指向 WireMock 回放（启动 WireMock 加载 `src/test/resources/wiremock/h3`）。
     2. 生成 demo 视频：H3 段占比约 12%，规划阶段就返回 422 `AI_RATIO_EXCEEDED`。
     3. 把配置恢复为 0.30 后再生成，视频进入审核，素材类型为 `mixed`。
     4. 验收完成后**恢复**为 `h3.backend=LOCAL_FIRST`、`h3.api.enabled=false`。
     5. 本地后端的端到端验证要等本地 H3 权重安装并通过许可证核实之后，见附录 B。
  5. **改价**：在 Woo 中改价并同步，3 条视频变为 STALE；新版本自动通过，结尾卡上是新价格；替换清单中出现视频。最后恢复原价。
  6. 导出这个 SKU，勾选包含视频，核对 ZIP 中有 6 个视频文件，manifest 中有对应的行。
- [ ] **Step 4: Commit** — `git commit -m "docs: C5 short videos in spec, README and CLAUDE.md"`

---

## 附录 A：H3 接口后端对接真实 API（用户提供文档后执行）

触发条件：用户提供 MiniMax H3 的 API 文档、配额和单价，并把 key 写进 `.env` 的 `MINIMAX_API_KEY`（**实施者不经手 key**）。

1. 按文档改写 `MiniMaxH3Client` 中的路径、请求字段和状态映射，以及 `H3Properties` 的默认值（model、resolution、requestSeconds、pricePerSecondUsd）。
2. 用**真实响应**重新录制 `wiremock/h3/*.json`，录制前去掉响应中的 key、URL 签名等敏感字段；更新 `MiniMaxH3ClientTest`。
3. 新增一个 `@Tag("h3-live")` 的手动测试，默认不运行（与 `comfy-live` 的做法相同）：只提交 1 个 5 秒的片段，核对下载、成本记录和 I2VA 首帧对齐。
4. 用真实 key 跑一次端到端：设置 `h3.backend=API_ONLY` 和 `h3.api.enabled=true`，生成 demo 视频，检查 H3 开场是否满足"产品不变形、没有人手、没有文字"。如果不满足，调整 `H3_PROMPT_*` 模板，注册新版本。验证完成后，把 `backend` 改回 `LOCAL_FIRST`；接口是否作为本地的后备保持启用，由用户决定。
5. 更新规格 §15 第 1 项为"已确认"，并记录单价和预算的初始值。

---

## 附录 B：H3 本地 ComfyUI 工作流注册（用户安装权重后执行）

触发条件：用户在 `D:\ComfyUI` 中装好 H3 的本地权重和所需的自定义节点（2026-10-09 检查时 `models/` 中还没有 H3 相关文件）。

1. **许可证核实（用户负责）**：
   - 用户阅读权重的许可证条款，确认允许商用（包括广告用途）。
   - 如果标识不在白名单中（例如 MiniMax 的自定义许可），由用户确认后，把标识加入 `kiano.content.workflow.allowed-licenses` 配置，并在规格 §12 中记下"已核实：{模型} — {许可证} — {条款链接} — {日期}"。
   - **实施者不能替用户做这个判断。**
   - 许可证不允许商用时，本地后端就不启用，只使用接口后端。
2. **显存标定**：在 ComfyUI 界面中用 PAGE_SCENE 作为首帧，按 `H3PromptBuilder` 生成的 I2VA 提示词试跑。选出在 16 GB 显存内稳定运行的设置（量化版本、分辨率、帧数、是否需要 tiled VAE），记录每个 5 秒片段的耗时。
3. **导出与注册**：
   - 导出 API 格式的 workflow.json，编写 manifest：`code=H3_I2V`；inputs 为 `image`；params 为 `positive`、`negative`、`seed`、`width`、`height`、`frames`、`fps`；outputs 为 `clip`，`field` 填输出节点在历史记录中的键名；`models` 填写各个模型及其许可证。
   - 通过 `POST /api/v1/content/workflows` 注册，然后激活。注册失败时，按返回的错误修正契约或许可证。
4. **把标定结果写回配置**：`h3.local.width/height/fps/comfyTimeoutSeconds`。
5. **实机测试**：新增一个 `@Tag("comfy-live")` 的手动测试，默认不运行：对 1 个商品跑一个 `H3_CLIP_LOCAL` 任务，核对输出是 mp4、时长正确、记录了 `gpu_seconds`。然后在 Kiano 中生成 demo 视频，检查开场是否满足"产品不变形、没有人手、没有文字"。不满足时调整 `H3_PROMPT_*` 模板或工作流参数，注册新版本。
6. **升级路径验证**：worker 运行时关闭 ComfyUI，并把 `local-max-wait` 临时调成 2 分钟，确认任务先进入 WAITING_EXECUTOR，超时后升级为接口任务（接口启用时）或改用实拍；然后恢复配置。

---

## Self-Review 记录

- **规格覆盖**（对照规格 §14 C5 一行和 §7.4）：
  - H3 执行器（异步、记录成本）：Task 3（park、外部任务 ID 和失败回调）、Task 8a（接口后端）、Task 8b（本地 ComfyUI 后端、后端选择与升级）；真实接口对接见附录 A，本地工作流注册见附录 B。
  - FFmpeg 合成（剪辑、字幕、配乐、结尾卡）：Task 4、5、6、7。
  - AI 占比的计算与拦截：Task 4（规划和保存时）、Task 9（完成时）。
  - 视频审核：Task 10、13。
  - 验收标准"每个 SKU 3 条视频，AI 占比超标被拦截"：Task 14 Step 3。
  - §7.4 的各项要求：
    - 3 种类型、格式和结构：Task 4、7。
    - 英文字幕烧录：Task 6、7。
    - H3 只做开场，首帧来自审核通过的图，提示词遵循 h3-prompt-writing 规范，两个后端共用：Task 8a、8b。
    - 配乐只用有商用授权的曲目，并提供无配乐版本供 TikTok 使用：Task 2、7。
  - §11 H3 失败时用实拍代替：按"本地 → 接口 → 实拍"依次升级，见 Task 8a、8b、9。
  - §12 显存和许可证：本地分辨率、帧数和超时都可以配置，标定步骤见附录 B；许可证照常在工作流注册时校验（Task 8b）。
  - §12 GPU 单并发：ffmpeg 不占 GPU，worker 的 lease 仍然是 max=1。
  - §1 中"导出短视频包"：Task 11。
- **类型一致性**：
  - `VideoType.wire()` 在 Task 1、4、9、10、11、13 中保持一致。
  - `Edl` 及其 `Segment`、`Overlay`、`Audio` 在 Task 4 定义，在 Task 7（worker）、9、10、11、13（前端镜像）中使用。
  - `EdlValidator.Violation.code` 与前端 `violations()` 使用同一套 code。
  - `GenerationJobStore.park` 和 `complete(…costUsd)` 在 Task 3 定义，在 Task 8a 中使用；`cancel` 和 `JobFailureListener` 在 Task 3 定义，在 Task 8b 和 9 中使用。
  - `H3BackendSelector.initial/next` 在 Task 8b 定义，在 Task 9 中使用；`onH3Succeeded` 和 `onH3Escalate` 在 Task 9 定义，被 Task 8a 和 8b 调用。由于 Task 8a、8b 先于 Task 9 实施，这两个任务中的 coordinator 调用先依赖一个接口 `H3TerminalHandler`，Task 9 中由 `VideoCompositionCoordinator` 实现它。
  - `JobStep.H3_CLIP_API` 和 `H3_CLIP_LOCAL` 在 Task 3 定义，在 Task 8a、8b、9 中使用。
  - `VideoInfo.hasAudio` 在 Task 3 定义，在 Task 4（`SourceClip.hasAudio`）中使用。
  - `RenderedOverlays.templateVersions` 在 Task 6 定义，在 Task 11 的自动通过判定中使用。
  - `MusicPicker.pick` 在 Task 2 定义，在 Task 9 中使用。
- **迁移**：V19（Task 1）、V20（Task 2）、V21（Task 3），之后的任务不再改表结构。V19 必须基于 C4a 的 V18 实际约束列表追加（已核对：V18 的 code 列表到 `AD_TRUST` 为止，kind 列表到 `AD_OVERLAY` 为止）。
- **仍然依赖外部信息的地方**：
  - H3 接口：真实的接口、单价和配额（附录 A）。
  - H3 本地：权重的安装、许可证核实和显存标定（附录 B）。

  在此之前 C5 可以完整验收：H3 没有可用的后端，AI 占比为 0；接口路径用 WireMock 回放验证，本地路径用 WireMock 模拟 ComfyUI 加测试工作流验证。
