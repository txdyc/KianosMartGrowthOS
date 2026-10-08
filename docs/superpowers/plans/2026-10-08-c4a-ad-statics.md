# C4a 广告静态图、导出与改价重渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 为每个 HERO SKU 生成 12 张广告静态图（4 个 hook × 3 种尺寸）。图上文字由 LLM 按 hook 生成并人工审核，价格、COD、MoMo、配送和保修由模板渲染。审核通过后打包成 ZIP 加 `manifest.csv` 导出给投放使用。Woo 中改价后，依赖价格的广告图自动重新渲染，满足条件时自动通过审核，并列入"需要在广告平台替换的素材"清单。

**Architecture：** 全部在 api 中完成，不经过 worker。
- **广告文案**：`AdCopyTaskHandler` 通过 `LlmGateway` 生成，`LlmPurpose.AD_COPY` 复用文案路由。得到 4 个 TEXT 资产 `AD_COPY`，variant 为 hook 名。
- **广告图**：`AdRenderService` 用 C2 的 Playwright `TemplateRenderer` 渲染 4 个 `AD_*` 模板，得到 12 个 IMAGE 资产 `AD_STATIC`，variant 为 `{hook}-{w}x{h}`。
- **改价**：监听 C1 已经发布的 `ProductPriceChanged` 事件，处理 STALE、重新渲染和自动通过。
- **导出**：`AD_EXPORT` 任务生成 ZIP 并写入 MinIO，同时记一条 `publication(target=AD_EXPORT)`。

**Tech Stack：** 沿用现有技术栈（Spring Boot 4.1、JdbcTemplate/MyBatis-Plus、Playwright 模板渲染、PG 任务队列、MinIO、Next.js）。demo 取帧使用 api 镜像中已有的 `ffmpeg`。

**Spec：** `docs/superpowers/specs/2026-10-07-content-module-design.md` §7.3、§7.5、§10.2、§10.3、§14 C4。C4 中的 kiano-connector 插件拆成 C4b，另写计划。

## Global Constraints

C1–C3 和 LLM 路由计划中的 Global Constraints 继续有效。下面只列 C4a 新增的部分。

- **2026-10-08 用户确认的决定**：
  1. C4 拆成 C4a（本计划）和 C4b（kiano-connector 插件）。
  2. 广告文案由 **LLM 按 hook 生成，再人工审核**，可以编辑。
  3. demo 的底图**自动从 V1 视频中选最清晰的一帧**；审核时点"重新生成"，就换成下一个候选帧。
- **文字来源的分工**：
  - **只有文案（图上短句、headline、primary text）来自 LLM**，它们是 `AD_COPY` 资产，必须审核通过后才能用于渲染。
  - 价格、促销截止日期、COD、MoMo、配送、保修**一律来自数据**，由模板渲染：价格和促销取自 Woo 镜像；COD、MoMo、配送取自政策设置中的 badge；保修取自锁定事实中的 `warranty`。
  - LLM 的输出中不能出现价格；`TextPrecheck` 发现时标记 `PRICE_IN_COPY`。
- **价格显示（规格 §7.3）**：只显示当前价格 `price`。**只有**同时满足以下三个条件时，才显示划线原价和"Ends {d MMM}"：`sale_price < regular_price`；`sale_to_at` 不为空；`sale_to_at` 晚于当前时间。金额格式为 `GH₵ 299`，有小数时为 `GH₵ 299.50`，千分位用逗号。
- **9:16 安全区（规格 §7.3）**：1080×1920 中，顶部 14%（269px）和底部 35%（672px）内不放任何文字；文字区域只能落在 y ∈ [269, 1248]。1:1 和 4:5 两种尺寸四周留 5% 边距。
- **底图选取**：底图只能来自已审核通过的资产；demo 的底图来自 ACCEPTED 的 V1 源视频。
  - 默认对应关系是 pricehook 用 `PAGE_MAIN`，problem 和 trust 用 `PAGE_SCENE` 中最新通过的第 1、2 张（只有 1 张时两者共用），demo 用 V1 的帧。
  - 对应关系写在配置 `kiano.content.ads.base` 中。
  - 方形底图放进 4:5 和 9:16 画布时，**底图本身完整居中显示（contain），并用同一张图放大、模糊后作为背景填满画布**，产品绝不被裁掉。
- **`depends_on_price`**：只有 `pricehook` 的 3 个尺寸为 true，其余 hook 不显示价格。
- **文件名（规格 §7.5 / v1.2 附录 B）**：`{SKU}_{hook}_{素材类型}_{w}x{h}_v{n}.jpg`。素材类型规定为：底图是 PAGE_MAIN 或 V1 帧时为 `real`，是 PAGE_SCENE 时为 `mixed`。
- **改价自动通过（规格 §10.3）**：新版本可以**自动通过**，必须同时满足：旧版本是 APPROVED（或已经导出）；新旧两版的模板 code 和 version、底图资产 ID、AD_COPY 资产 ID 都相同（也就是只有价格变量变了）；模板是 APPROVED 状态。满足时写审计 `AD_AUTO_APPROVED_PRICE_CHANGE`，actor 为 SYSTEM。否则新版本进入 IN_REVIEW。
- **导出**：ZIP 中只包含 12 张图全部为 APPROVED 的 SKU；不满足的 SKU 列入 `skipped`，并写明原因。`manifest.csv` 的列严格按规格 §10.2：`file_name, sku, hook, size, headline, primary_text, price_snapshot, asset_id`；使用 UTF-8 BOM 和 RFC4180 格式。
- **相对规格的有意扩展**：
  - `product` 表增加 `sale_from_at` 和 `sale_to_at`。C1 没有同步促销日期，而规格 §7.3 的划线规则需要它们。
  - 政策设置的每个分区增加可选的 `badge` 字段（短标签，不超过 40 字符）。trust hook 需要 COD、MOMO、DELIVERY 三个分区的 badge。
  - 新增资产规格 `AD_COPY`（TEXT）和 `AD_STATIC`（IMAGE），都只用于 HERO。
  - 新增 `LlmPurpose.AD_COPY`：解析路由时复用 COPY 的路由，`llm_call.purpose` 仍记为 AD_COPY，便于分开统计成本。

## Review Focus

1. **促销到期之后，广告仍然显示划线价**：Woo 在促销截止时自动把 `price` 改回原价，下一次同步会触发 `ProductPriceChanged`，所以 pricehook 必须重新渲染。另外，渲染时的判断要用渲染那一刻的时间：`sale_to_at` 已经过去时不能画划线价，即使同步还没发生。测试放在 Task 2 和 Task 8。
2. **9:16 的文字落进安全区**：标题较长时会换行，长的 headline 也会把文字区往下推。渲染后用 Playwright 读取所有文字节点的包围盒，断言全部落在 [269, 1248] 之内；放不下时缩小字号，最多缩两档，仍然放不下就记为预检标记 `TEXT_OUTSIDE_SAFE_AREA`。测试放在 Task 6。
3. **改价后重新渲染，误用了未审核的新文案或底图**：自动通过的前提是只有价格变了。如果在两次渲染之间，AD_COPY 被编辑过、或者有新的场景图通过了审核，就不能自动通过，必须进入 IN_REVIEW。测试放在 Task 8。
4. **价格变化时这个 SKU 还没有广告图，或者广告图还在审核中**：没有广告图时什么都不做；还在 IN_REVIEW 的旧版本改为 ARCHIVED，并用新价格重新渲染，新版本进入 IN_REVIEW。替换清单中只列出**导出过**的旧版本。测试放在 Task 8 和 Task 9。
5. **导出时某个 SKU 只有部分图通过，或者 manifest 中的文字含有逗号、引号或换行**：这个 SKU 不能混进 ZIP 中，要列入 skipped；CSV 要正确加引号。测试放在 Task 9。

---

## 文件结构

```
kiano-api/src/main/resources/db/migration/V17__product_sale_dates.sql   （Task 1）
kiano-api/src/main/resources/db/migration/V18__ad_specs_templates.sql   （Task 4）
kiano-api/src/main/java/com/kiano/
  commerce/  CommerceProduct, ProductView, ProductPriceChanged       （加入促销日期）
  commerce/woo/WooCommerceAdapter, commerce/sync/ProductSyncService   （映射与变更检测）
  platform/llm/ LlmPurpose（+AD_COPY）, LlmRouteStore（AD_COPY 使用 COPY 路由）
  content/policy/ PolicyService（section 增加 badge, requireAdBadges）
  content/ads/
    GhsFormat, PriceDisplay                     （纯函数：金额格式与划线规则）
    AdHook, AdSize, AdCopyDraft, AdCopyTaskHandler, AdCopyText
    FrameExtractor                              （ffmpeg 取 3 帧，选最清晰的一帧）
    AdRenderService, AdRenderTaskHandler, AdBaseSelector, AdModelBuilder
    AdPriceListener                             （ProductPriceChanged → STALE / 重新渲染 / 自动通过）
    AdExportService, AdExportTaskHandler, ManifestCsv, AdReplacementService
    AdController
  content/asset/ AssetService（createImageFromBytes 增加 AdRenderMeta 参数的重载）, ReviewService（AD_* 的重新生成）
  content/derive/FactDependentAssets（HERO 锁定事实后入队 AD_COPY）
kiano-api/src/main/resources/templates/content/
  AD_COPY_PROMPT/v1.txt  AD_PRICEHOOK/v1.html  AD_PROBLEM/v1.html  AD_DEMO/v1.html  AD_TRUST/v1.html
kiano-web/src/
  components/AdsPanel.tsx  app/(app)/ads/page.tsx  components/TextAssetCard.tsx（AD_COPY）
  app/(app)/settings/policy/page.tsx（badge）  lib/ads.ts (+test)  lib/types.ts  i18n/*
```

---

### Task 1: 同步促销日期

**Files:**
- Create: `db/migration/V17__product_sale_dates.sql`（每个改表结构的任务各自新建一个迁移，不往已有的迁移文件中追加）
- Modify: `commerce/CommerceProduct.java`、`commerce/ProductView.java`、`commerce/ProductPriceChanged.java`、`commerce/woo/WooCommerceAdapter.java`、`commerce/sync/ProductSyncService.java`、`commerce/persistence/{ProductEntity,ProductCatalogImpl}.java`
- Test: `commerce/woo/WooCommerceAdapterTest.java`、`commerce/sync/ProductSyncServiceTest.java`（追加用例），测试资源 `woo/products-page1.json` 中加入促销日期字段

**Interfaces:**
- SQL：`alter table product add column sale_from_at timestamptz, add column sale_to_at timestamptz;`
- `CommerceProduct` 和 `ProductView` 末尾加 `@Nullable Instant saleFromAt, @Nullable Instant saleToAt`。适配器从 `date_on_sale_from_gmt` 和 `date_on_sale_to_gmt` 映射，值为空或 null 时为 null，按 UTC 解析。
- `ProductPriceChanged` 末尾加 `@Nullable Instant oldSaleToAt, @Nullable Instant newSaleToAt`。同步时 `sale_to_at` 变了也视为价格变化，同样发事件、写审计 `PRODUCT_PRICE_CHANGED`。
- 所有构造 `ProductView`、`CommerceProduct`、`ProductPriceChanged` 的调用处都要补上新参数，测试中的也一样。

- [ ] **Step 1: 写失败的测试**

```java
// WooCommerceAdapterTest
@Test void mapsSaleDates_utc_andEmptyToNull()      // "2026-10-20T23:59:59" 对应 Instant 2026-10-20T23:59:59Z；"" 对应 null
// ProductSyncServiceTest
@Test void saleEndDateChange_onlyDateChanged_firesPriceChanged()   // 价格不变、sale_to 变了 → 1 个事件，事件中 old/new 的 saleToAt 正确
@Test void unchangedSaleDates_noEvent()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `cd kiano-api && ./mvnw -q test -Dtest='WooCommerceAdapterTest,ProductSyncServiceTest,ProductCatalogTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(commerce): sync Woo sale dates and treat sale end changes as price changes"`

---

### Task 2: 金额格式与价格显示规则（纯函数）

**Files:**
- Create: `content/ads/{GhsFormat,PriceDisplay}.java`
- Test: `content/ads/{GhsFormatTest,PriceDisplayTest}.java`

**Interfaces:**
- Produces: `GhsFormat.format(BigDecimal amount) -> String`。整数显示为 `GH₵ 299`，有小数时显示为 `GH₵ 299.50`，`1299` 显示为 `GH₵ 1,299`。null 时抛 IllegalArgumentException。
- Produces: `PriceDisplay.of(ProductView product, Instant now) -> PriceDisplay`；`record PriceDisplay(String current, @Nullable String strike, @Nullable String endsLabel, BigDecimal snapshot)`。
  - `current`：`price` 格式化后的结果；`price` 为 null 时用 `regular_price`；两者都为 null 时抛 `ApiException(409, "PRICE_MISSING")`。
  - `strike` 和 `endsLabel`：只在 Global Constraints 中的三个条件同时满足时才有值，否则为 null。`endsLabel` 的格式为 `"Ends 20 Oct"`，使用 Locale.ENGLISH 和 Africa/Accra 时区。
  - `snapshot`：当前使用的价格值，写入 `asset.price_snapshot`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void format_integerDecimalAndThousands()
@Test void noSale_currentOnly()
@Test void realSaleWithEndDate_strikeAndEnds()      // regular 299、sale 249、sale_to 为 now+3 天 → strike "GH₵ 299"，endsLabel "Ends 23 Oct"
@Test void saleWithoutEndDate_noStrike()            // 规格要求：没有截止日期的"促销"不显示划线价
@Test void expiredSale_noStrike_evenBeforeSync()    // Review Focus 1：sale_to 早于 now
@Test void salePriceNotLower_noStrike()
@Test void missingPrice_409()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='GhsFormatTest,PriceDisplayTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): GH₵ price formatting and strikethrough rule for dated promotions"`

---

### Task 3: 政策分区的 badge

**Files:**
- Modify: `content/policy/{PolicyService,PolicyController}.java`
- Test: `content/policy/PolicyServiceTest.java`、`PolicyControllerTest.java`（追加用例）

**Interfaces:**
- `PolicyService.SectionText` 改为 `record SectionText(String title, String body, @Nullable String badge)`。sections_json 中旧数据没有 badge 字段，读取时按 null 处理，**不需要迁移**。现有调用 `new SectionText(t, b)` 的地方一律补上第三个参数 null，测试也一样。
- 校验规则：badge 去掉首尾空白后不超过 40 字符，否则返回 422 `VALIDATION_FAILED`；不能包含 HTML 标签，用 C3 的 `PlainText.hasMarkup` 检查。
- Produces: `PolicyService.requireAdBadges(long tenantId) -> Map<PolicySection, String>`：COD、MOMO、DELIVERY 三个分区都要有 badge，缺少时抛 409 `POLICY_BADGES_MISSING`，`details.missing` 列出缺少的分区。
- **只修改 badge 也会产生新的政策版本**，因为 badge 属于政策内容。`PolicyChangedEvent` 照常触发，C3 的长文案重新渲染不受影响（badge 不出现在 POLICY_BLOCK 中）。

- [ ] **Step 1: 写失败的测试**

```java
@Test void badge_roundTrips_andLegacyNullBadgeReadsAsNull()
@Test void badge_over40_orWithMarkup_422()
@Test void requireAdBadges_missingMomo_409WithList()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='PolicyServiceTest,PolicyControllerTest,PolicyRerenderTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): optional policy badges for COD, MoMo and delivery"`

---

### Task 4: 按 hook 生成广告文案（AD_COPY）

**Files:**
- Create: `db/migration/V18__ad_specs_templates.sql`（asset_spec 种子数据和 template 约束，见下）
- Modify: `platform/llm/{LlmPurpose,LlmRouteStore}.java`、`content/copy/TextPrecheck.java`、`content/derive/FactDependentAssets.java`、`content/template/TemplateBootstrap.java`
- Create: `content/ads/{AdHook,AdCopyDraft,AdCopyText,AdCopyTaskHandler}.java`、`templates/content/AD_COPY_PROMPT/v1.txt`
- Test: `content/ads/AdCopyTaskHandlerTest.java`（使用 FakeLlmGateway）、`platform/llm/LlmRouteStoreTest.java`（追加用例）、`content/copy/TextPrecheckTest.java`（追加用例）

**Interfaces:**
- V18 的内容：
```sql
insert into asset_spec (code, kind, tier, width, height, format, pipeline_ref) values
 ('AD_COPY','TEXT','HERO',null,null,'json','AD_COPY'),
 ('AD_STATIC','IMAGE','HERO',null,null,'jpeg','AD_RENDER');
alter table template drop constraint template_code_check;
alter table template add constraint template_code_check check (code in
  ('PAGE_INFO','PAGE_SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LONG','COPY_SHORT','POLICY_BLOCK',
   'AD_COPY_PROMPT','AD_PRICEHOOK','AD_PROBLEM','AD_DEMO','AD_TRUST'));
alter table template drop constraint template_kind_check;
alter table template add constraint template_kind_check check (kind in
  ('INFOGRAPHIC','SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LAYOUT','POLICY_BLOCK','AD_OVERLAY'));
```
- `enum AdHook { PRICEHOOK, PROBLEM, DEMO, TRUST }`，`wire()` 返回小写形式，例如 `pricehook`，用作 variant 和文件名中的 hook 段。
- `LlmPurpose` 增加 `AD_COPY`。`LlmRouteStore.resolve(tenant, AD_COPY)` 按 COPY 查找路由（没有配置时用内置默认路由），返回的 `ResolvedRoute.purpose` 保持为 AD_COPY。
- LLM 的结构化输出：
```java
record AdCopyDraft(List<HookCopy> hooks) {}
record HookCopy(AdHook hook,
    @JsonPropertyDescription("Short line printed on the ad image, max 40 characters, no price") String overlay,
    @JsonPropertyDescription("Ad headline, max 40 characters, no price") String headline,
    @JsonPropertyDescription("Ad primary text, max 125 characters, no price") String primaryText) {}
```
  AD_COPY_PROMPT 中要写明 4 个 hook 各自的意图：pricehook 强调性价比，但**不写数字价格**；problem 先讲痛点再给出解决；demo 描述可见的功能演示；trust 讲为什么可以放心购买，但**不要提 COD、MoMo、配送，这些由模板渲染**。只能使用锁定的事实，英文，面向 Ghana 用户。
- Produces: `AdCopyText.toJson / fromJson`。每个 AD_COPY 资产的 textBody 是 `{"overlay":…,"headline":…,"primaryText":…}`，三个字段都用 `PlainText.strip` 处理；contentJson 中保存 hook 和 factVersion。
- `AdCopyTaskHandler`（任务类型 `AD_COPY_GENERATE`，payload 为 `{productId, factVersion, onlyHook?}`）：
  - 只处理 HERO 商品，非 HERO 时 skip。
  - 事实版本过期时 skip，规则与 C3 相同。
  - 调用 LLM，effort MEDIUM，maxTokens 8000。返回的 hooks 必须恰好覆盖 4 个 hook，否则抛不可重试的 `LLM_INVALID_OUTPUT`。
  - 每个 hook 运行一次 `TextPrecheck.checkAdCopy`，然后 `createText(…, "AD_COPY", hook.wire(), …)`。
- `TextPrecheck.checkAdCopy(AdCopyText, FactsJson)`：复用 FACT_MISMATCH、FORBIDDEN_CLAIM、PRICE_IN_COPY 三项检查；长度限制为 overlay ≤ 40、headline ≤ 40、primaryText ≤ 125，超出时标记 TOO_LONG，`metrics.field` 中写明是哪个字段。
- `FactDependentAssets.onFactLocked`：商品 tier 为 HERO 时，额外入队 `AD_COPY_GENERATE`。旧事实版本的 AD_COPY 和 AD_STATIC 资产，与 COPY_* 资产一样做 ARCHIVED 或 STALE 处理。
- 端点：`POST /api/v1/content/products/{id}/ads/copy`（OPERATOR）→ 202。商品不是 HERO 时返回 422 `NOT_HERO`；没有锁定事实时返回 409 `FACTS_NOT_LOCKED`。

- [ ] **Step 1: 写失败的测试**

```java
// AdCopyTaskHandlerTest
@Test void generatesFourHookAssets_withPlainTextJsonBodies()
@Test void missingHookInOutput_invalidOutputNonRetryable()
@Test void nonHero_skipped()  @Test void staleFactVersion_skipped()
@Test void onlyHook_savesSingleNewVersion()
@Test void factLock_onHero_enqueuesAdCopy_nonHero_doesNot()
// LlmRouteStoreTest
@Test void adCopy_resolvesCopyRoute_keepsPurpose()
// TextPrecheckTest
@Test void adCopy_priceOrTooLongHeadline_flagged()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='AdCopyTaskHandlerTest,LlmRouteStoreTest,TextPrecheckTest,FactDependentAssetsTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): per-hook ad copy via LLM with precheck and review"`

---

### Task 5: 从 V1 视频中取最清晰的一帧

**Files:**
- Create: `content/ads/FrameExtractor.java`
- Modify: `content/qc/PhotoQc.java`（把清晰度计算提取为公开方法 `blurVariance(BufferedImage)`，`evaluate` 内部改为调用它）
- Test: `content/ads/FrameExtractorTest.java`

**Interfaces:**
- Produces: `FrameExtractor.candidates(Path video, double durationSeconds) -> List<Frame>`。依次在 25%、50%、75% 处执行 `ffmpeg -v error -ss {t} -i {video} -frames:v 1 -q:v 2 {out}.jpg`（路径取自 `kiano.media.ffmpeg-path`，默认 `ffmpeg`），得到 3 帧。结果按 `blurVariance` 从高到低排序，记为 `record Frame(int index, double timeSeconds, double blurVariance, byte[] jpeg)`。
- ffmpeg 中途失败时跳过那一帧；一帧都取不到时抛 `ApiException(422, "DEMO_FRAME_UNAVAILABLE")`。
- 如果帧画面是横向的，按 C1 的 `FfprobeJsonParser` 读到的旋转信息转正。ffmpeg 默认会自动旋转，这里加一个测试确认。
- 下载源视频由调用方负责（Task 7 从 MinIO 下载到临时文件）。

- [ ] **Step 1: 写失败的测试**（`@EnabledIf("ffmpegAvailable")`：只在 ffmpeg 可用时运行；测试视频用 ffmpeg 在测试中现场生成）

```java
@Test void returnsThreeFrames_sortedSharpestFirst()    // 生成的视频中间一段清晰、两端模糊 → 第一帧来自 50%
@Test void rotatedPortraitVideo_framesUpright()       // 1920×1080 加 rotation 90 → 帧为 1080×1920
@Test void unreadableVideo_422()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='FrameExtractorTest,PhotoQcTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): pick the sharpest V1 frame for the demo hook"`

---

### Task 6: 4 个 hook 的叠加模板与金样测试

**Files:**
- Create: `templates/content/{AD_PRICEHOOK,AD_PROBLEM,AD_DEMO,AD_TRUST}/v1.html`
- Modify: `content/template/TemplateBootstrap.java`（注册这 4 个模板，kind 为 AD_OVERLAY，状态 APPROVED）、`content/template/TemplateRenderer.java`（新增 `textBoxes`，见下）
- Test: `content/ads/AdTemplateGoldenTest.java`，`src/test/resources/golden/ads/{hook}-{w}x{h}.png`（12 张）

**Interfaces:**
- **模板模型**，由 Task 7 的 `AdModelBuilder` 构造：
  - 所有模板共有：`{width, height, sizeClass:"s1x1"|"s4x5"|"s9x16", baseImage:"data:image/jpeg;base64,…", overlay, productName}`
  - pricehook 额外有：`{current, strike?, endsLabel?}`
  - trust 额外有：`{badges:[cod, momo, delivery, "{warranty} warranty"]}`
  - 所有文本都用 `{{ }}` 转义。
- **版式**：
  - 1:1 时，底图铺满画布（cover），文字放在底部的半透明条上。
  - 4:5 和 9:16 时，背景是同一张图放大并加 `filter: blur(40px)`；前景底图完整居中显示（contain）。9:16 时前景放在画布的中段。
  - 所有文字容器都使用 class `ad-text`，以便检查安全区。
  - 字体使用 C2 内联的 Noto Sans。
- Produces: `TemplateRenderer.textBoxes(long tenantId, String code, Map<String,Object> model, int w, int h) -> List<Rect>`：渲染后执行 `document.querySelectorAll('.ad-text')` 并取每个元素的 `getBoundingClientRect()`；`record Rect(double top, double bottom, double left, double right)`。
- **安全区的处理**在 Task 7 中完成：先用默认字号渲染；越界时把 `fontScale` 依次设为 0.9、0.8 重新渲染；仍然越界时加上预检标记 `TEXT_OUTSIDE_SAFE_AREA`，图照样保存，交给人工判断。

- [ ] **Step 1: 写失败的测试**

```java
@ParameterizedTest @CsvSource({"AD_PRICEHOOK,1080,1080", …共 12 组})
void matchesGolden(String code, int w, int h)              // 容差与 C2 相同：差值超过 32 个灰阶的像素占比 ≤ 0.5%；需要更新时运行 -Dkiano.golden.update=true
@Test void nineBySixteen_textInsideSafeArea_withLongHeadline()   // Review Focus 2：overlay 为 40 个字符 → 所有矩形都在 [269, 1248] 内
@Test void squareBase_notCroppedIn9x16()                   // 前景 img 的 rect 宽度 ≤ 1080，并且完整可见
@Test void pricehook_strikeOnlyWhenProvided()              // 模型中没有 strike 时，页面中不存在 .strike 元素
@Test void escapesOverlayHtml()                            // overlay 为 "<b>x</b>" 时原样显示为文本
```

- [ ] **Step 2–4: 先确认失败 → 实现（首次生成 golden 后要人工看一遍，确认版式没问题）→ 确认通过**　Run: `./mvnw -q test -Dtest='AdTemplateGoldenTest,TemplateRendererGoldenTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): AD_* overlay templates with safe-area text boxes and golden tests"`

---

### Task 7: 广告图渲染（12 张）

**Files:**
- Create: `content/ads/{AdSize,AdBaseSelector,AdModelBuilder,AdRenderService,AdRenderTaskHandler}.java`
- Modify: `content/asset/AssetService.java`（`createImageFromBytes` 增加接收 `AdRenderMeta` 的重载）、`content/asset/AssetFileName.java`（如果需要支持 hook 角度段）
- Test: `content/ads/{AdBaseSelectorTest,AdRenderServiceTest}.java`

**Interfaces:**
- `enum AdSize { S1X1(1080,1080), S4X5(1080,1350), S9X16(1080,1920) }`，`label()` 返回 `1080x1350` 这样的字符串。variant 为 `hook.wire() + "-" + size.label()`。
- Produces: `AdBaseSelector.select(long tenantId, long productId, AdHook hook, int frameCandidate) -> BaseImage`；`record BaseImage(byte[] jpeg, String sourceType /* real|mixed */, @Nullable Long assetId, @Nullable Long sourceMediaId, @Nullable Double frameTime)`。规则按 Global Constraints；demo 用 `FrameExtractor` 返回的第 `frameCandidate % 3` 个候选帧。缺少所需底图时抛 409 `AD_BASE_MISSING`，`details` 中写明 hook 和需要的资产。
- Produces: `record AdRenderMeta(boolean dependsOnPrice, @Nullable BigDecimal priceSnapshot, String fileType)`；`AssetService.createImageFromBytes(tenantId, productId, specCode, variant, png, factVersion, provenance, sku, AdRenderMeta meta, PrecheckResult precheck)`。文件名用 `AssetFileName.format(sku, hook, fileType, w, h, version, "jpg")`，并写入 `depends_on_price` 和 `price_snapshot`。
- Produces: `AdRenderService.renderAll(long tenantId, long productId, Set<String> variantsOrAll, int frameCandidate, boolean priceOnly) -> List<AssetView>`：
  - **前置条件**（全量渲染时检查）：商品是 HERO；有锁定的事实；4 个 AD_COPY 都已通过；`requireAdBadges`；有通过的 PAGE_MAIN 和至少 1 张通过的 PAGE_SCENE；有 ACCEPTED 的 V1。不满足时返回 409 `AD_PRECONDITIONS`，`details.missing` 列出缺少的项。
  - **每个 variant 的处理**：选底图 → 由 `AdModelBuilder` 构造模型，价格由 `PriceDisplay.of(product, now)` 计算 → 渲染，并按 Task 6 的方式检查安全区 → `createImageFromBytes`。
  - provenance 中记录 `template{code,version}`、`adCopyAssetId`、`baseAssetId`、`sourceMediaId`、`frameTime`、`price{current, strike, endsLabel, snapshot}`、`factVersion`。
- `AdRenderTaskHandler`（任务类型 `AD_RENDER`，payload 为 `{productId, variants?, frameCandidate?, priceOnly?, autoApproveFrom?}`，其中 `autoApproveFrom` 由 Task 8 使用）调用 `renderAll`。
- 端点：`POST /api/v1/content/products/{id}/ads/render`（OPERATOR）→ 202；前置条件不满足时直接返回 409，不入队。

- [ ] **Step 1: 写失败的测试**（用真实的 TemplateRenderer；资产直接通过 SQL 插入；FrameExtractor 用 `@MockitoBean` 替换）

```java
@Test void renderAll_creates12_withVariantsFileNamesAndPriceFlags()   // 只有 pricehook 的 3 张 depends_on_price=true，并且带有 price_snapshot；文件名形如 MG-BL200_pricehook_real_1080x1350_v1.jpg；problem 的素材类型为 mixed
@Test void preconditionsMissing_409WithList()                         // 缺少 V1 和 MOMO 的 badge → details.missing 同时包含两者
@Test void demo_usesSharpestFrame_regenerateUsesNext()
@Test void oneScene_problemAndTrustShareIt()
@Test void provenanceRecordsTemplateCopyBaseAndPrice()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='AdBaseSelectorTest,AdRenderServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): render 12 HERO ad statics per SKU from approved bases, copy and live price"`

---

### Task 8: 改价 → STALE → 重新渲染 → 自动通过

**Files:**
- Create: `content/ads/AdPriceListener.java`
- Modify: `content/ads/AdRenderService.java`（新增自动通过的判定）
- Test: `content/ads/AdPriceListenerTest.java`

**Interfaces:**
- `AdPriceListener` 用 `@TransactionalEventListener(AFTER_COMMIT)` 监听 `ProductPriceChanged`，处理方式如下：
  1. 找到该商品 `depends_on_price=true` 的 AD_STATIC 资产，按 variant 取最新版本。一个都没有时直接结束（Review Focus 4）。
  2. 处理旧版本：状态为 APPROVED 或 PUBLISHED 的改为 STALE，记下它们的 ID，作为后续 `autoApproveFrom` 的依据；状态为 IN_REVIEW 或 DRAFT 的改为 ARCHIVED。
  3. 入队 `AD_RENDER {productId, variants: 上述 variant, priceOnly: true, autoApproveFrom: {variant → staleAssetId}}`。
  4. 写审计 `ADS_STALE_BY_PRICE`，actor 为 SYSTEM。
- 新版本渲染出来后，`AdRenderService` 判断能否自动通过：拿新版本与 `autoApproveFrom[variant]` 指向的旧版本比较 provenance 中的 `template.code`、`template.version`、`adCopyAssetId`、`baseAssetId`（demo 则比较 `sourceMediaId` 和 `frameTime`），并确认模板状态是 APPROVED。全部相同时，新版本直接设为 APPROVED，并写审计 `AD_AUTO_APPROVED_PRICE_CHANGE`（before 和 after 都包含价格快照）；否则保持 IN_REVIEW（Review Focus 3）。
- `priceOnly` 时**不检查**全量渲染的前置条件；但是 AD_COPY 和底图必须仍然可用，不可用时这个 variant 失败，记 error，并且该 variant 保持 STALE 状态。

- [ ] **Step 1: 写失败的测试**

```java
@Test void priceChange_approvedPricehook_staleThenNewApprovedAutomatically()   // 新版本为 APPROVED，并带有新价格快照，审计有 1 行
@Test void priceChange_onlyPriceDependentVariantsRerendered()                   // problem、demo、trust 不变
@Test void copyEditedSinceLastRender_newVersionInReview()                       // Review Focus 3
@Test void newSceneApprovedSinceLastRender_newVersionInReview()
@Test void inReviewOldVersion_archived_newInReview()                            // Review Focus 4
@Test void noAdsYet_noOp()
@Test void saleExpiry_sync_rerendersWithoutStrike()                             // Review Focus 1：sale_to 已经过去时同步触发事件 → 新图不含 strike
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=AdPriceListenerTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): price changes stale ad statics, re-render and auto-approve price-only updates"`

---

### Task 9: ZIP + manifest 导出与替换清单

**Files:**
- Create: `content/ads/{AdExportService,AdExportTaskHandler,ManifestCsv,AdReplacementService,AdController}.java`
- Test: `content/ads/{ManifestCsvTest,AdExportServiceTest,AdReplacementServiceTest}.java`

**Interfaces:**
- Produces: `AdExportService.request(CurrentUser user, List<Long> productIdsOrEmpty) -> long publicationId`（OPERATOR）。列表为空时表示全部 HERO SKU。处理过程如下：
  1. 插入一条 `publication`，target 为 `AD_EXPORT`，environment 取 `PRODUCTION`（字段是必填的，这里只作占位），status 为 PENDING，`asset_ids` 暂时为 `[]`。
  2. 入队 `AD_EXPORT {publicationId, productIds}`。
  3. `AdExportTaskHandler` 对每个 SKU 取 12 个 variant 的最新 APPROVED 版本。不足 12 张时把该 SKU 写入 `skipped`，形式为 `{sku, reason:"NOT_ALL_APPROVED", missing:[variants]}`。
  4. 把图打包进 ZIP，文件名用 `asset.file_name`，再加上 `manifest.csv`。上传到 `t{tid}/exports/ads/{publicationId}.zip`。
  5. publication 的 `asset_ids` 写入实际导出的 ID，`after_json` 写入 `{zipKey, skipped, fileCount}`，状态设为 APPLIED，写审计 `ADS_EXPORTED`。
  6. 没有任何 SKU 符合条件时，状态设为 FAILED，error 为 `NOTHING_TO_EXPORT`，并列出 skipped。
- Produces: `ManifestCsv.write(List<ManifestRow> rows) -> byte[]`：UTF-8 BOM，表头 `file_name,sku,hook,size,headline,primary_text,price_snapshot,asset_id`，RFC4180 格式；`price_snapshot` 为数字，保留 2 位小数，非价格图为空。`record ManifestRow(String fileName, String sku, String hook, String size, String headline, String primaryText, @Nullable BigDecimal priceSnapshot, long assetId)`，其中 headline 和 primaryText 取自该图渲染时所用 AD_COPY 版本的 JSON。
- Produces: `AdReplacementService.list(long tenantId) -> List<Replacement>`：`record Replacement(String sku, String variant, String oldFileName, long oldAssetId, String newFileName, long newAssetId, String newStatus)`。旧版本的条件是：曾经出现在某次 APPLIED 的 AD_EXPORT 的 asset_ids 中，并且现在状态为 STALE；新版本是同一 variant 最新的 APPROVED 版本。还没有通过的新版本不列出（等待审核）。
- 端点：
  - `POST /api/v1/content/ads/exports {productIds}` → 202 `{publicationId}`
  - `GET /api/v1/content/ads/exports` 返回导出历史，含状态、skipped、文件数和时间
  - `GET /api/v1/content/ads/exports/{id}/download` 返回 302，跳转到有效期 15 分钟的预签名 URL；只有 APPLIED 的导出才能下载
  - `GET /api/v1/content/ads/replacements`

- [ ] **Step 1: 写失败的测试**

```java
// ManifestCsvTest
@Test void bomHeaderAndQuoting_commasQuotesNewlines()     // Review Focus 5
// AdExportServiceTest
@Test void exportsOnlyFullyApprovedSkus_listsSkipped()    // Review Focus 5
@Test void zipContainsFilesNamedByAssetFileName_andManifest()   // 解压后核对文件名，以及 manifest 的行数等于图片数
@Test void nothingExportable_failedWithSkipped()
// AdReplacementServiceTest
@Test void exportedThenStale_listsOldToNewApproved()
@Test void staleButNeverExported_notListed()              // Review Focus 4
@Test void newVersionStillInReview_notListedYet()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='ManifestCsvTest,AdExportServiceTest,AdReplacementServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): HERO ad export (ZIP + manifest.csv) and platform replacement list"`

---

### Task 10: 审核看板接入 AD_COPY 和 AD_STATIC

**Files:**
- Modify: `content/asset/ReviewService.java`
- Test: `content/asset/AdReviewTest.java`

**Interfaces:**
- 排序：AD_STATIC 排在页面图之后、文本之前，按 hook 顺序（PRICEHOOK、PROBLEM、DEMO、TRUST）再按尺寸排列。AD_COPY 排在 COPY_* 之后。
- **REGENERATE**：
  - AD_COPY：入队 `AD_COPY_GENERATE {onlyHook}`。
  - AD_STATIC：入队 `AD_RENDER {variants:[variant]}`；如果是 demo，`frameCandidate` 取旧版本 provenance 中的值加 1，以换用下一个候选帧。
- **编辑 AD_COPY**：沿用 C3 的 `editText`。textBody 必须能用 `AdCopyText.fromJson` 解析，三个字段都不能含有标签，否则返回 422 `TEXT_HTML_NOT_ALLOWED` 或 `VALIDATION_FAILED`；编辑后重新运行 `checkAdCopy`。
- **AD_COPY 有了新的通过版本时**，对应 hook 的广告图并不会自动重新渲染，由操作员手动点击"生成广告图"。**广告图只用渲染那一刻已经通过的文案**，这一点写进 UI 提示。

- [ ] **Step 1: 写失败的测试**

```java
@Test void ordering_adStaticsAfterPageImages_adCopyAfterCopy()
@Test void regenerate_adCopy_enqueuesOnlyHook()
@Test void regenerate_demoStatic_usesNextFrameCandidate()
@Test void editAdCopy_invalidJsonOrMarkup_422()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='AdReviewTest,TextReviewTest,ReviewServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): review, edit and regenerate ad copy and ad statics"`

---

### Task 11: 前端——商品页的广告面板与政策 badge

**Files:**
- Create: `kiano-web/src/components/AdsPanel.tsx`、`src/lib/ads.ts`、`src/lib/ads.test.ts`
- Modify: `src/app/(app)/products/[id]/page.tsx`（只有 HERO 商品显示 AdsPanel）、`src/app/(app)/settings/policy/page.tsx`（每个分区增加 badge 输入框，带字数计数，上限 40）、`src/components/TextAssetCard.tsx`（AD_COPY 显示为三个字段并带字数，分别是 40、40、125）、`src/lib/types.ts`、`src/i18n/*`

**Interfaces:**
- Produces（`src/lib/ads.ts`）：
  - `AD_VARIANTS`：12 个 variant，顺序固定。
  - `adGrid(items: ReviewItem[]): Record<variant, ReviewItem | null>`：每个 variant 取最新版本。
  - `adCopyFields(textBody: string): {overlay, headline, primaryText} | null`
  - `adCopyLimits = {overlay: 40, headline: 40, primaryText: 125}`
- AdsPanel 的内容：
  - 一个 4×3 的网格：行为 hook，列为尺寸，每格显示缩略图和状态徽标。
  - "生成广告文案"按钮。
  - "生成广告图"按钮：遇到 409 `AD_PRECONDITIONS` 时，把 `details.missing` 逐项翻译显示。
  - 一行提示："广告图使用渲染时已通过的文案与当前价格"。
- i18n 新增：hook 名称、尺寸、错误码 `NOT_HERO`、`FACTS_NOT_LOCKED`、`AD_PRECONDITIONS`、`AD_BASE_MISSING`、`POLICY_BADGES_MISSING`、`DEMO_FRAME_UNAVAILABLE`、`PRICE_MISSING`、`NOTHING_TO_EXPORT`，预检标记 `TEXT_OUTSIDE_SAFE_AREA`，以及 badge 相关的文案。这些都要写入 `i18n.test.ts` 的覆盖清单。

- [ ] **Step 1: 写失败的测试**（`ads.test.ts`：`adGrid` 取最新版本、缺少的格子为 null；`adCopyFields` 能解析和容错；i18n 覆盖清单）
- [ ] **Step 2–4: 先确认失败 → 实现 → 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): HERO ads panel, ad copy cards and policy badges"`

---

### Task 12: 前端——广告导出页 `/ads`

**Files:**
- Create: `kiano-web/src/app/(app)/ads/page.tsx`
- Modify: `src/app/(app)/layout.tsx`（导航中加入"广告 Ads"）、`src/lib/types.ts`、`src/i18n/*`

**Interfaces:**
- 页面分三个区块：
  1. **HERO 商品列表**：多选；每行显示通过数 `n/12`，12 张都通过时才能勾选。点击"导出所选"或"导出全部"后，每 3 秒轮询一次导出状态。
  2. **导出历史**：显示时间、状态、文件数和 skipped 明细；状态为 APPLIED 时显示"下载"链接，指向 download 端点。
  3. **替换清单**：列出 SKU、variant、旧文件名、新文件名，并提供"复制新文件名"按钮。顶部说明这些素材需要在 Meta 或 TikTok 广告后台手动替换。
- 所有文字都通过 `t()` 输出。

- [ ] **Step 1: 实现**（逻辑放在 `lib/ads.ts` 中，并由 Task 11 的测试覆盖；页面本身用浏览器验证）
- [ ] **Step 2: 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 3: Commit** — `git commit -m "feat(web): ad export page with history, downloads and replacement list"`

---

### Task 13: 文档与 C4a 验收

**Files:**
- Modify: `docs/superpowers/specs/2026-10-07-content-module-design.md`（在 §14 C4 一行之后注明 C4 拆分为 C4a 和 C4b，以及本计划确认的决定）、`README.md`（广告图生成、导出和替换清单的说明）、`CLAUDE.md`（硬规则：广告图上价格、COD、MoMo、配送、保修一律由模板渲染；LLM 只写 overlay、headline 和 primary text）

- [ ] **Step 1: 更新文档**
- [ ] **Step 2: 运行全部测试**　Run: `cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm lint && pnpm build`（先停掉 worker）
- [ ] **Step 3: 按规格 §14 的 C4 广告部分做端到端验收**（前提：一个 C3 已经完成验收的 HERO SKU，有 V1 视频，政策中 COD、MOMO、DELIVERY 的 badge 已经填好）：
  1. 点击"生成广告文案"，4 组文案进入审核；逐一通过，其中编辑一条。
  2. 点击"生成广告图"，12 张图进入审核。逐张检查：9:16 的文字没有落进顶部 14% 和底部 35%；产品没有被裁掉；价格显示为 `GH₵ …`；demo 的帧清晰。全部通过。
  3. 在导出页导出这个 SKU，下载 ZIP，核对 12 个文件名和 `manifest.csv` 的内容。
  4. **改价**：在本地 Woo 中把价格改为 279，并设置一个 3 天后截止的促销（sale price）。在 Kiano 中点"立即同步"。pricehook 的 3 张图变为 STALE，新版本自动通过，图上出现划线价和"Ends …"；其余 9 张不变。替换清单中出现 3 行"旧文件名 → 新文件名"。审计中有 `AD_AUTO_APPROVED_PRICE_CHANGE`。
  5. 把价格和促销都恢复原样，再同步一次。
- [ ] **Step 4: Commit** — `git commit -m "docs: C4a ad statics in spec, README and CLAUDE.md"`

---

## Self-Review 记录

- **规格覆盖**（对照 §14 C4 一行的内容，不含插件）：
  - AD_OVERLAY 模板（4 hook × 3 尺寸）：Task 6。
  - 安全区：Task 6、7。
  - ZIP 加 CSV 导出：Task 9。
  - 价格 STALE 与自动重新渲染：Task 1、8。
  - 验收标准"每个 HERO SKU 导出 12 张广告图，改价后相关图自动更新并列入替换清单"：Task 13。
  - 规格 §7.3 的各项要求：4 个 hook、3 种尺寸、9:16 安全区、只在有真实截止日期的促销时显示划线价、底图来自通过审核的 SCENE 或 MAIN、demo 取 V1 帧，分别在 Task 2、5、6、7 中实现。
  - 规格 §10.2 的 manifest 列：Task 9。
  - 规格 §10.3 的三点：STALE、只有价格变化且模板已通过时自动通过、替换清单：Task 8、9。
  - kiano-connector 插件放在 C4b。
- **类型一致性**：
  - `AdHook.wire()` 和 `AdSize.label()` 一起组成 variant 和文件名，在 Task 4、6、7、9、10、11 中保持一致。
  - `ProductPriceChanged` 增加的字段在 Task 1 定义，Task 8 使用。
  - `SectionText.badge` 在 Task 3 定义，Task 7 和 Task 11 使用。
  - `AdRenderMeta` 和 `createImageFromBytes` 的重载在 Task 7 定义。
  - `autoApproveFrom` 在 Task 8 定义，并在 Task 7 的任务 payload 中预留。
  - `PrecheckFlag` 增加 `TEXT_OUTSIDE_SAFE_AREA`（Task 7），前端在 Task 11 中补上对应文案。
- **迁移**：Task 1 新建 V17（促销日期），Task 4 新建 V18（广告资产规格和模板约束），之后的任务不再改表结构。已经提交的迁移文件不能修改。
