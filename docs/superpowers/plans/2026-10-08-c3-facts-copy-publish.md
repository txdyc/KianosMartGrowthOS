# C3 事实、文案与发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 为一个 SKU 完成从事实到上线的全过程：
1. 用 Claude 看 P5 铭牌照和宣传图，生成事实表草稿。
2. 人工核对后锁定事实表（G2 关卡）。
3. 基于锁定的事实生成 6 种英文文案，同时渲染信息图和参数图。
4. 文案经预检和人工审核。
5. 按 SKU 整体发布到 WooCommerce：先在 staging 发布，成功后才能发布到生产。失败时自动恢复原状，发布后可以回滚。

**Architecture：**
- **LLM 调用**：所有调用都经过 `platform` 模块的 `LlmGateway`。它的实现用官方 `anthropic-java` SDK，默认模型 `claude-opus-5-5`，每次调用把 token 用量和成本写入 `llm_call` 表。
- **事实草稿、文案生成、模板渲染、发布**：四者都是 api 内的慢任务，用 C1 的 PostgreSQL 任务队列（`platform_task`）异步执行，不经过 worker。
- **文字的来源**：LLM 只产出纯文本片段（卖点、为什么值得买、FAQ、SEO 文字）。参数表、店铺政策（POLICY_BLOCK）和 HTML 结构全部由 Mustache 模板确定性渲染，LLM 的输出一律经过 HTML 转义。
- **发布写入**：通过 `commerce` 模块新增的 `CommercePublisher`，调用 `wp/v2/media` 和 `wc/v3/products`。staging 和 production 各自有一套加密凭证。写入前先保存商品快照，任何一步失败都按快照恢复。

**Tech Stack：** 沿用 C1、C2 的全部技术栈。新增：
- `com.anthropic:anthropic-java`（2.34.0 或更新版本），使用 class-based structured output（`.outputConfig(Record.class)`）和图片输入
- WireMock 回放 Anthropic API 和 Woo 写入接口
- Rank Math 的商品 meta：`rank_math_title`、`rank_math_description`

**Spec：** `docs/superpowers/specs/2026-10-07-content-module-design.md`。本计划覆盖：
- §3 原则 2、3、6
- §5.1 中事实锁定之后的分支
- §7.1 PAGE_INFO 和 PAGE_SPEC
- §7.2 文案
- §8 中的 `product_fact_sheet`、`publication`，以及 `template` 的各个 kind
- §9.2 的 G2 关卡
- §9.3 文案预检
- §10.1 发布到 WooCommerce
- §14 C3
- 以及 C2 计划中延后到本 Sprint 的事项：INFO/SPEC 接入流水线、gallery 最多 10 张。

上级文档为 `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`。前序计划为 `docs/superpowers/plans/2026-10-07-c1-…`、`2026-10-07-c2-…`。

## Global Constraints

C1 和 C2 计划中的 Global Constraints 全部继续有效。下面只列 C3 新增或细化的部分。

- **2026-10-08 用户确认的决定**：
  1. LLM 用 **Claude API**。
  2. 生产站的 SEO 插件是 **Rank Math**。
  3. staging 环境用 **KianosMart 本地 Docker**（`http://host.docker.internal:8080`）。
  4. 店铺政策**做成 Kiano 中的设置页**。政策没有填完整时，文案照常生成和审核，但**不允许发布**。
- **模型与参数（按 claude-api 规范）**：
  - 模型 ID 取配置 `kiano.llm.model`，默认 `claude-opus-5-5`，只写这个确切的字符串，不加日期后缀。
  - Opus 5.5 的 thinking 不能关闭，不要发送 `ThinkingConfigDisabled` 或 `budgetTokens`（都会返回 400）。思考深度只用 `OutputConfig.effort` 控制：事实草稿用 `HIGH`（准确性优先），文案用 `MEDIUM`。
  - 不使用 assistant prefill，也不使用强制的 `tool_choice`。结构化结果一律用 structured output（`.outputConfig(SomeRecord.class)`）。
  - 每次调用都要先检查 `stopReason()`：`refusal` 时抛 `LlmRefusedException`，不可重试，同时记录 `stopDetails` 的类别；`max_tokens` 时抛 `LlmTruncatedException`，可以重试一次，重试时把 `maxTokens` 翻倍。
  - **默认开启服务端拒答回退**：请求带 beta `server-side-fallback-2026-07-01` 和 `fallbacks: "default"`。Java SDK 中的具体 builder 方法在实现时用 `javap` 确认；如果与 structured output 无法同时使用，改用手写 JSON schema（`OutputConfig.format(JsonOutputFormat…)`），并在 commit 中说明。
  - API key 只来自环境变量 `ANTHROPIC_API_KEY`，写在 `.env` 中，不入库。
- **成本记录（v1.2 §22）**：每次调用写一行 `llm_call`，记录 tenant、purpose、模型、输入输出 token、缓存 token、`cost_usd`、耗时、状态。单价取配置 `kiano.llm.pricing.{model}`，`claude-opus-5-5` 为输入 $4、输出 $20 每百万 token，不写死在代码里。
- **事实先锁定，后生成（规格 §3.3）**：
  - 文案、PAGE_INFO、PAGE_SPEC 的任务只在事实表状态为 `LOCKED` 后入队。资产上的 `fact_version` 必须填写。
  - 锁定新版本时，旧事实版本的这类资产按状态处理：DRAFT、IN_REVIEW、APPROVED 改为 ARCHIVED，PUBLISHED 改为 STALE，然后自动重新生成。
- **LLM 不碰价格、政策、参数表**：
  - 价格不进入页面文案。COPY_WA 只用 `{{price}}` 占位。
  - 政策文字只来自政策设置。
  - 参数表只来自锁定的事实。
- **所有 LLM 文本插入 HTML 时都必须转义**：JMustache 的 `{{ }}` 默认转义，模板中禁止使用 `{{{ }}}`。Woo 的 `name` 和 Rank Math 的 meta 写入纯文本。
- **发布规则（规格 §10.1，以及 KianosMart 仓库"先 staging 再生产"的规则）**：
  - 生产发布必须满足：同一商品、同一组资产 ID 已经在 staging 发布成功（APPLIED），否则返回 409 `STAGING_REQUIRED`。
  - 发布到 staging 需要 OPERATOR 权限；发布到生产和回滚生产需要 **OWNER** 权限。
  - 只有生产发布会把资产改为 PUBLISHED；staging 发布只留下一条 publication 记录。
- **gallery 最多 10 张（规格 §7.1）**：顺序为 MAIN → ANGLE → SCENE → INBOX → INFO → SPEC，各组上限分别为 1、4、2、1、1、1，每组取 APPROVED 的最新版本。
- **不修改 Woo 商品的 slug、价格、库存和 SKU**。只写 `name`、`description`、`short_description`、`images` 和 Rank Math 的 meta。
- **相对规格的有意扩展**：
  - `publication` 增加 `environment`（STAGING|PRODUCTION）、`archived_asset_ids`、`uploaded_media_ids`、`error`，状态增加 `PENDING`。
  - `asset` 增加 `content_json`（文本资产的结构化来源，用于重新渲染）。
  - `template` 的 code 和 kind 增加 `FACT_PROMPT`、`COPY_PROMPT`、`COPY_LONG`、`COPY_SHORT`、`POLICY_BLOCK`；新增 kind `COPY_LAYOUT`。
  - 新增 `llm_call` 和 `store_policy` 两张表。
  - integration 的 provider 增加 `WOOCOMMERCE_STAGING`。已有的 `WOOCOMMERCE` 同时承担商品同步来源和生产发布目标。
  - 文本资产支持人工编辑：编辑后保存为一个新版本，状态为 IN_REVIEW，provenance 中记为 `manual`。

## Review Focus

1. **发布中途失败**（例如第 3 张图上传返回 500，或者商品 PUT 返回 400）：商品的 name、description、short_description、图片及其顺序、Rank Math meta 都要按快照完整恢复；本次已上传的媒体要删除；publication 记为 FAILED；没有任何资产被改为 PUBLISHED。如果恢复这一步本身也失败，publication 要标记 `needsAttention=true`，界面用红色提示。测试放在 Task 10。
2. **发布之后，有人在 Woo 后台改过这个商品，然后有人点了回滚**：当前商品的 `date_modified_gmt` 与发布后快照中的不一致时，返回 409 `WOO_CHANGED_SINCE_PUBLISH`，不覆盖人工修改；只有传 `force=true` 才执行回滚。测试放在 Task 11。
3. **数字和单位的写法不同，导致事实一致性检查误报或漏报**：`1.5L`、`1.5 L`、`1500 ml`、`1,500ml`，`350W`、`350 watts`，`220-240V`、`220–240 V` 都要能识别为同一个值，不能误报；而事实表中没有的数字（例如编造的 `500W`）必须被标记。测试放在 Task 6。
4. **LLM 输出中带 HTML 或脚本**：例如 FAQ 中出现 `<script>alert(1)</script>`、`<b>`、`&`。在 COPY_LONG 和 COPY_SHORT 的 HTML 中必须原样转义显示，绝不能作为标签写进 Woo 商品描述，否则会在店铺前台造成 XSS。测试放在 Task 5。
5. **生产发布绕过 staging**：staging 从未成功发布过；或者 staging 成功之后资产又变了（例如某张图重新生成并通过了审核），此时资产 ID 集合不一致。两种情况都要返回 409 `STAGING_REQUIRED`，并在 `details.diff` 中列出差异。测试放在 Task 10。

---

## 文件结构

```
kiano-api/src/main/java/com/kiano/
  platform/llm/      LlmGateway, LlmRequest, LlmImage, LlmResult, LlmPurpose, LlmException,
                     LlmRefusedException, LlmTruncatedException, AnthropicLlmGateway,
                     LlmProperties, LlmCallRecorder
  commerce/          CommercePublisher, PublishEnvironment, WooProductSnapshot, WooMedia,
                     ProductContentUpdate   （根包 = 对外 API）
  commerce/woo/      WooPublisherAdapter, CommercePublisherFactory（+ CommercePortFactory 增加环境参数）
  commerce/web/      WooIntegrationController（增加 environment 参数）
  content/facts/     FactSheetEntity, FactSheetMapper, FactSheetService, FactsJson, FieldSource,
                     FactDraftTaskHandler, FactDraftResult, FactSheetController, FactLockedEvent
  content/policy/    StorePolicyEntity, StorePolicyMapper, PolicyService, PolicySection,
                     PolicyController, PolicyChangedEvent
  content/copy/      CopyDraft, CopyGenerationTaskHandler, CopyAssembler, TextPrecheck,
                     QuantityNormalizer, CopyProperties
  content/derive/    FactDependentAssets（锁定事实后统一处理旧资产并入队）, TemplateRenderTaskHandler
  content/publish/   PublicationEntity, PublicationMapper, PublicationService, GallerySelector,
                     PublishTaskHandler, PublicationController, AltTextBuilder
  content/asset/     AssetService（+createText、createImageFromBytes）, ReviewService（+文本资产、编辑）
  content/template/  TemplateBootstrap（增加新的 code）
kiano-api/src/main/resources/
  db/migration/ V10__llm_call.sql  V11__fact_sheet.sql  V12__template_codes.sql
                V13__store_policy.sql  V14__copy.sql  V15__publication.sql   （版本号按任务执行顺序递增，每个任务一个新文件）
  templates/content/FACT_PROMPT/v1.txt  COPY_PROMPT/v1.txt  COPY_LONG/v1.html
                    COPY_SHORT/v1.html  POLICY_BLOCK/v1.html
kiano-web/src/
  app/(app)/products/[id]/facts/page.tsx
  app/(app)/settings/policy/page.tsx
  components/FactsPanel.tsx  components/PublishPanel.tsx  components/TextAssetCard.tsx
  app/(app)/review/page.tsx（修改）  app/(app)/settings/integrations/page.tsx（修改）
  lib/types.ts  lib/facts.ts（+ facts.test.ts）  i18n/*
```

---

### Task 1: LLM 网关（platform/llm）

**Files:**
- Create: `db/migration/V10__llm_call.sql`
- Create: `platform/llm/{LlmGateway,LlmRequest,LlmImage,LlmResult,LlmPurpose,LlmException,LlmRefusedException,LlmTruncatedException,AnthropicLlmGateway,LlmProperties,LlmCallRecorder}.java`
- Modify: `kiano-api/pom.xml`（加入 `com.anthropic:anthropic-java`）、`application.yml`、`.env.example`（加入 `ANTHROPIC_API_KEY=`）
- Test: `platform/llm/AnthropicLlmGatewayTest.java`（用 WireMock 模拟 Anthropic API，SDK 的 `baseUrl` 指向 WireMock）、`LlmCallRecorderTest.java`；测试资源 `src/test/resources/anthropic/{structured-ok.json,refusal.json,max-tokens.json,overloaded-529.json}`；另在测试源码中提供 `FakeLlmGateway`，供后续任务注入

**Interfaces:**
- Produces:
```java
public enum LlmPurpose { FACT_DRAFT, COPY }
public record LlmImage(byte[] jpeg, String label) {}                 // label 例如 "P5 rating plate"
public record LlmRequest<T>(long tenantId, LlmPurpose purpose, String system, String userText,
    List<LlmImage> images, Class<T> outputType, Effort effort, long maxTokens) {}   // Effort: LOW|MEDIUM|HIGH
public record LlmResult<T>(T output, String model, long inputTokens, long outputTokens, BigDecimal costUsd, long llmCallId) {}
public interface LlmGateway { <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException; }
public class LlmException extends Exception { boolean retryable(); String code(); }
// 子类：LlmRefusedException（code LLM_REFUSED，不可重试，带 category）、LlmTruncatedException（code LLM_TRUNCATED）
```
- `AnthropicLlmGateway` 的实现要点：
  - 客户端：`AnthropicOkHttpClient.builder().apiKey(…).maxRetries(2)`，以及测试用的 `baseUrl`。SDK 自身会重试 429、5xx 和连接错误。
  - 请求：model 取配置；`maxTokens(request.maxTokens)`；`outputConfig(request.outputType)` 拿到结构化结果；effort 通过 `OutputConfig.Effort` 设置；按 Global Constraints 开启 fallbacks。
  - 图片：以 `ContentBlockParam` 形式（image 类型，base64 JPEG）排在文本前面。类名以 SDK 实际为准，用 `javap` 或编译错误确认。
  - 出错时：`AnthropicServiceException` 中的 401/403/400 映射为不可重试的 `LlmException("LLM_CONFIG")` 或 `("LLM_BAD_REQUEST")`；超过 SDK 重试次数的 429/5xx 映射为可重试的 `LLM_UNAVAILABLE`。
  - **无论成功失败**都通过 `LlmCallRecorder` 写一行 `llm_call`。
- 表：
```sql
create table llm_call (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), purpose text not null, model text not null,
  input_tokens bigint, output_tokens bigint, cache_read_tokens bigint, cache_write_tokens bigint,
  cost_usd numeric(10,6), latency_ms int,
  status text not null check (status in ('OK','REFUSED','TRUNCATED','ERROR')),
  stop_reason text, error text, created_at timestamptz not null default now());
create index llm_call_tenant_time on llm_call (tenant_id, created_at desc);
```
- 配置：`kiano.llm.model: claude-opus-5-5`；`kiano.llm.pricing.claude-opus-5-5: {input-per-mtok: 4.00, output-per-mtok: 20.00, cache-read-per-mtok: 0.20}`。`ANTHROPIC_API_KEY` 为空时，gateway 抛 `LlmException("LLM_NOT_CONFIGURED")`，应用仍能正常启动。

- [ ] **Step 1: 写失败的测试**

```java
record Probe(String model, Integer powerW) {}
@Test void structuredOutput_parsesRecord_andRecordsUsageAndCost()   // cost = in×4/1e6 + out×20/1e6；llm_call 为 OK
@Test void request_sendsModelEffortImagesBeforeText_andNoThinkingDisabled() // 检查 WireMock 收到的请求体：model 为 "claude-opus-5-5"，image 块排在 text 块前面，没有 "type":"disabled"，也没有 budget_tokens
@Test void request_enablesServerSideFallback()                     // 请求头 anthropic-beta 包含 server-side-fallback-2026-07-01，请求体中 fallbacks 为 "default"
@Test void refusal_throwsNonRetryableWithCategory_andRecordsRefused()
@Test void maxTokens_throwsTruncated()
@Test void overloaded_afterSdkRetries_throwsRetryableUnavailable()
@Test void unauthorized_throwsConfigError()
@Test void missingApiKey_throwsNotConfigured_appStillStarts()
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `cd kiano-api && ./mvnw -q test -Dtest='AnthropicLlmGatewayTest,LlmCallRecorderTest'`
- [ ] **Step 3: 实现**（SDK 中类名和方法名的确认顺序：先查 skill 的 `java/claude-api/README.md` 和 `tool-use.md`，再用 `javap`，最后看编译错误）
- [ ] **Step 4: 运行测试，确认通过**；`ModularityTests` 也要通过
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): Claude LLM gateway with structured output, refusal handling and cost ledger"`

---

### Task 2: 事实表与锁定（G2）

**Files:**
- Create: `db/migration/V11__fact_sheet.sql`
- Create: `content/facts/{FactSheetEntity,FactSheetMapper,FactSheetService,FactsJson,FieldSource,FactSheetController,FactLockedEvent}.java`
- Test: `content/facts/{FactSheetServiceTest,FactSheetControllerTest}.java`

**Interfaces:**
- 表（规格 §8）：
```sql
create table product_fact_sheet (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  version int not null, facts_json jsonb not null, field_sources jsonb not null default '{}',
  source_refs jsonb not null default '[]',
  status text not null check (status in ('DRAFT','LOCKED','SUPERSEDED')),
  llm_call_id bigint references llm_call(id),
  locked_by bigint references app_user(id), locked_at timestamptz,
  created_by bigint references app_user(id), created_at timestamptz not null default now(),
  unique (product_id, version));
create unique index fact_sheet_one_draft on product_fact_sheet (product_id) where status = 'DRAFT';
create unique index fact_sheet_one_locked on product_fact_sheet (product_id) where status = 'LOCKED';
```
- Produces:
```java
public record FactsJson(String model, String category, String capacity, Integer powerW, String voltage,
    String material, String colour, String warranty, List<String> inBox, List<String> features,
    List<String> benefits, List<String> forbiddenClaims) {
  public TemplateFacts toTemplateFacts(String productName);   // 映射到 C2 的 TemplateFacts
}
public enum FieldSource { P5, PROMO, WOO_TEXT, MANUAL, NONE }          // field_sources：字段名 → 来源
public record FactSheetView(long id, long productId, int version, String status, FactsJson facts,
    Map<String, FieldSource> fieldSources, List<Long> sourceMediaIds, Instant lockedAt, String lockedBy) {}
```
- `FactSheetService` 的方法：
  - `current(tenantId, productId) -> Optional<FactSheetView>`：有 DRAFT 时返回 DRAFT，否则返回 LOCKED。
  - `locked(tenantId, productId) -> Optional<FactSheetView>`
  - `saveDraft(CurrentUser, productId, FactsJson, Map<String,FieldSource>) -> FactSheetView`：没有 DRAFT 时新建一个，版本号为 max+1；已有 DRAFT 时就地更新。
  - `createDraftFromLlm(tenantId, productId, FactDraftResult, sourceMediaIds, llmCallId)`
  - `lock(CurrentUser, productId, int draftVersion, Set<String> confirmedFields) -> FactSheetView`
- **lock 的规则**（规格 §9.2）：
  1. `confirmedFields` 必须包含 `model, capacity, powerW, voltage, warranty, inBox` 这 6 项，缺少时返回 422 `FACTS_NOT_CONFIRMED`，`details.missing` 列出缺少的项。
  2. `model` 和 `warranty` 不能为空，否则返回 422 `FACTS_INCOMPLETE`。其余 4 项允许为空（例如没有功率的商品），但要在确认清单中勾选，表示"已核对"。
  3. 草稿已经被修改过（`draftVersion` 不是当前版本）时，返回 409 `FACT_DRAFT_CHANGED`。
  4. 原来的 LOCKED 版本改为 SUPERSEDED，当前草稿改为 LOCKED。
  5. 写审计 `FACTS_LOCKED`，在事务提交后发布 `FactLockedEvent(tenantId, productId, version)`。
- 端点：
  - `GET /api/v1/content/products/{id}/facts`（VIEWER）→ `{current, locked}`，同时附带 P5、PROMO 的预签名 URL，供审核页并排查看。
  - `PUT /api/v1/content/products/{id}/facts/draft`（OPERATOR）
  - `POST /api/v1/content/products/{id}/facts/lock {draftVersion, confirmedFields}`（OPERATOR）

- [ ] **Step 1: 写失败的测试**

```java
@Test void saveDraft_createsVersion1_thenUpdatesInPlace()
@Test void lock_requiresAllSixConfirmations_422WithMissing()
@Test void lock_emptyModel_422Incomplete()
@Test void lock_staleDraftVersion_409()
@Test void lock_supersedesPrevious_andPublishesEventAfterCommit()   // @RecordApplicationEvents
@Test void newDraftAfterLock_isVersion2_lockedStillServed()
@Test void factsJson_toTemplateFacts_mapsAllFields()
@Test void viewer_cannotLock_403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='FactSheetServiceTest,FactSheetControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): versioned fact sheets with G2 lock and confirmation checklist"`

---

### Task 3: 用 Claude 生成事实草稿

**Files:**
- Create: `content/facts/{FactDraftTaskHandler,FactDraftResult}.java`、`src/main/resources/templates/content/FACT_PROMPT/v1.txt`
- Create: `db/migration/V12__template_codes.sql`
- Modify: `content/template/TemplateBootstrap.java`（注册 FACT_PROMPT）
- Test: `content/facts/FactDraftTaskHandlerTest.java`（使用 `FakeLlmGateway`）

**Interfaces:**
- `V12__template_codes.sql`：
```sql
alter table template drop constraint template_code_check;
alter table template add constraint template_code_check check (code in
  ('PAGE_INFO','PAGE_SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LONG','COPY_SHORT','POLICY_BLOCK'));
alter table template drop constraint template_kind_check;
alter table template add constraint template_kind_check check (kind in
  ('INFOGRAPHIC','SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LAYOUT','POLICY_BLOCK'));
```
（约束名以 V9 实际生成的为准，可以从 `\d template` 中查到。）
- Produces:
```java
public record FactDraftResult(FactsJson facts, Map<String, FieldSource> sources, List<String> unreadable) {}
```
这个 record 就是 LLM 的 structured output 类型。字段上用 `@JsonPropertyDescription` 写清楚：看不清的值必须为 null，不允许猜测；`sources` 中只能出现 P5、PROMO、WOO_TEXT、NONE；`unreadable` 列出看不清的字段。
- 入口：`POST /api/v1/content/products/{id}/facts/draft-from-ai`（OPERATOR）入队任务 `FACT_DRAFT`，dedupeKey 为 `fact-draft:{productId}`，返回 202 `{taskId}`。已经存在 DRAFT 时返回 409 `FACT_DRAFT_EXISTS`（要先放弃或锁定）。如果 P5 和 PROMO 都没有 ACCEPTED 的媒体，返回 422 `NO_FACT_SOURCES`。
- `FactDraftTaskHandler` 的流程：
  1. 读取 P5 和 PROMO 的当前媒体。按 EXIF 转正，等比缩小到长边 1568px，编码为 JPEG（质量 90）。
  2. system prompt 取 FACT_PROMPT 模板的最新 APPROVED 版本，user 文本包含商品名、Woo 分类、Woo 现有描述的纯文本（截断到 4000 字符）。
  3. 调用 `LlmGateway`，purpose 为 FACT_DRAFT，effort 为 HIGH，`maxTokens` 为 16000。
  4. 调用 `createDraftFromLlm`。
  5. 任务结果为 `{factSheetId, version, llmCallId}`。
  6. 异常处理：`LlmRefusedException` 和 `LlmException(!retryable)` 转为 `NonRetryableTaskException`，消息形如 `"LLM_REFUSED: …"`，界面据此提示"请手工填写"。可重试的异常交给队列退避重试。
- FACT_PROMPT v1（英文）要点：
  - 角色是电器参数录入员。
  - 只抄录图片中清晰可见的内容，数值保留原单位。
  - `benefits` 只能从参数推导，不能使用绝对化表述。
  - `forbiddenClaims` 留空，由人工填写。
  - 输出必须符合给定的 schema。

- [ ] **Step 1: 写失败的测试**

```java
@Test void draftFromAi_enqueues_202_thenHandlerCreatesDraftWithSources()
@Test void handler_sendsP5AndPromoImagesDownscaled_andFactPromptVersion()  // FakeLlmGateway 记录收到的请求：2 张图，长边 ≤ 1568，effort 为 HIGH
@Test void noP5NorPromo_422()
@Test void existingDraft_409()
@Test void refusal_failsTaskNonRetryable_noDraftCreated()                  // Review Focus 中的拒答处理
@Test void truncatedOrInvalid_noPartialDraftPersisted()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=FactDraftTaskHandlerTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): Claude vision fact draft from rating plate and promo image"`

---

### Task 4: 店铺政策设置与 POLICY_BLOCK

**Files:**
- Create: `db/migration/V13__store_policy.sql`
- Create: `content/policy/{StorePolicyEntity,StorePolicyMapper,PolicyService,PolicySection,PolicyController,PolicyChangedEvent}.java`、`templates/content/POLICY_BLOCK/v1.html`
- Modify: `TemplateBootstrap`（注册 POLICY_BLOCK）
- Test: `content/policy/{PolicyServiceTest,PolicyControllerTest}.java`

**Interfaces:**
- 表：`store_policy(id, tenant_id, version int, sections_json jsonb, updated_by, created_at, unique(tenant_id, version))`。每次保存都新建一个版本，最新版本即为当前生效的版本。
- Produces: `enum PolicySection { DELIVERY, COD, MOMO, WARRANTY, RETURNS }`；`record PolicyView(int version, Map<PolicySection, SectionText> sections, boolean complete, Instant updatedAt)`；`record SectionText(String title, String body)`。title 和 body 都是英文纯文本，因为它们要写进 Woo 页面；body 中的换行转为段落。
- `PolicyService` 的方法：
  - `current(tenantId) -> Optional<PolicyView>`
  - `save(CurrentUser, Map<PolicySection, SectionText>) -> PolicyView`：新建版本，写审计 `POLICY_UPDATED`，提交后发布 `PolicyChangedEvent(tenantId, version)`。
  - `requireComplete(tenantId) -> PolicyView`：5 个分区的 body 都不为空才算完整，否则抛 409 `POLICY_INCOMPLETE`，`details.missing` 列出缺少的分区。
  - `renderBlock(tenantId) -> String html`：用 POLICY_BLOCK 模板渲染，所有文本都转义。
- 端点：`GET /api/v1/content/policy`（VIEWER）；`PUT /api/v1/content/policy`（OWNER）。

- [ ] **Step 1: 写失败的测试**

```java
@Test void save_createsNewVersionEachTime_andPublishesEvent()
@Test void requireComplete_missingSections_409WithList()
@Test void renderBlock_escapesHtml_andParagraphsLines()    // body 为 "Accra & Tema\n<b>x</b>" → 输出中包含 "Accra &amp; Tema"、"&lt;b&gt;"，并有两个 <p>
@Test void operator_cannotSave_403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='PolicyServiceTest,PolicyControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): versioned store policy settings and POLICY_BLOCK rendering"`

---

### Task 5: 文案生成与组装

**Files:**
- Create: `db/migration/V14__copy.sql`
- Create: `content/copy/{CopyDraft,CopyGenerationTaskHandler,CopyAssembler,CopyProperties}.java`、`templates/content/COPY_PROMPT/v1.txt`、`COPY_LONG/v1.html`、`COPY_SHORT/v1.html`
- Modify: `content/asset/AssetService.java`（新增 `createText`）、`TemplateBootstrap`
- Test: `content/copy/{CopyAssemblerTest,CopyGenerationTaskHandlerTest}.java`

**Interfaces:**
- `V14__copy.sql`：
```sql
alter table asset add column content_json jsonb;
insert into asset_spec (code, kind, tier, width, height, format, pipeline_ref) values
 ('COPY_TITLE','TEXT','STANDARD',null,null,'text','COPY'),
 ('COPY_SHORT','TEXT','STANDARD',null,null,'html','COPY'),
 ('COPY_LONG','TEXT','STANDARD',null,null,'html','COPY'),
 ('COPY_SEO','TEXT','STANDARD',null,null,'json','COPY'),
 ('COPY_GSHOP','TEXT','STANDARD',null,null,'text','COPY'),
 ('COPY_WA','TEXT','STANDARD',null,null,'text','COPY');
```
- Produces: LLM 的 structured output 类型。
  ```java
  record CopyDraft(String title, List<String> shortBullets, List<String> whyBuy, List<Faq> faq,
      String seoTitle, String seoDescription, String gshopTitle, String waMessage)
  record Faq(String q, String a)
  ```
  字段描述中写明以下约束：
  - `title` 的格式为 `Morgan {model} {category} {key spec} – {core benefit}`。
  - `shortBullets` 3–5 条；`faq` 3–5 条。
  - `seoTitle` ≤ 60 字符，`seoDescription` ≤ 155 字符。
  - `gshopTitle` 把品牌、品类、关键属性放在前面。
  - `waMessage` 中必须包含 `{{price}}`，**任何地方都不能写出价格数字**。
  - 只能使用事实表中的数字。
- Produces: `CopyAssembler.assemble(CopyDraft d, FactsJson facts, String productName, String policyHtmlOrNull) -> Map<String /*specCode*/, TextAsset>`；`record TextAsset(String textBody, Map<String,Object> contentJson)`。各个 spec 的组装方式：
  - COPY_TITLE：纯文本。
  - COPY_SHORT：用 COPY_SHORT 模板渲染为 `<ul><li>…</li></ul>`。
  - COPY_LONG：用 COPY_LONG 模板渲染，依次包含"Why buy"段落、参数表（取自事实，与 SPEC 图使用相同的行规则，空值不出现）、FAQ、POLICY_BLOCK。政策不完整时（`policyHtmlOrNull` 为 null），这里放一个 `<!-- POLICY_PENDING -->` 占位，`contentJson.policyVersion` 为 null；此时可以审核，但不能发布。
  - COPY_SEO：JSON 字符串 `{"title":…,"description":…}`。
  - COPY_GSHOP：纯文本。
  - COPY_WA：纯文本。
  - `contentJson` 中保存 `CopyDraft` 的对应片段、`policyVersion` 和 `factVersion`，以便政策变化时不调用 LLM 也能重新渲染。
- Produces: `AssetService.createText(long tenantId, long productId, String specCode, String variant, TextAsset text, int factVersion, Map<String,Object> provenance, PrecheckResult precheck) -> AssetView`。版本号和"旧的 DRAFT、IN_REVIEW 改为 ARCHIVED"的规则与图片资产相同；variant 固定为 `default`；`file_name` 为 null。
- `CopyGenerationTaskHandler`（任务类型 `COPY_GENERATE`，payload 为 `{productId, factVersion, onlySpec?}`）的流程：
  1. 读取 LOCKED 的事实。如果版本号与 payload 中的 factVersion 不一致，说明事实已经过期，任务直接成功但什么也不做，结果为 `{skipped:"STALE_FACTS"}`。
  2. 调用 LLM（effort MEDIUM，maxTokens 16000）。
  3. 组装。
  4. 调用 Task 6 的 `TextPrecheck`。
  5. 逐个 spec 调用 `createText`。带 `onlySpec` 时只保存这一个 spec，用于"重新生成"。
  6. provenance 中记录 `factVersion`、`template{COPY_PROMPT,v}`、`template{COPY_LONG,v}`、`policyVersion`、`model`、`llmCallId`。

- [ ] **Step 1: 写失败的测试**

```java
// CopyAssemblerTest
@Test void long_containsWhyBuySpecTableFaqAndPolicy_inOrder()
@Test void long_specTableFromFacts_omitsNullRows()
@Test void llmHtml_isEscapedEverywhere()          // Review Focus 4：whyBuy 中含 "<script>alert(1)</script>"、faq.a 中含 "<b>&</b>" → 输出中只有转义后的文本，不出现 "<script"
@Test void short_rendersUlWithEscapedBullets()
@Test void policyMissing_placeholderAndNullPolicyVersion()
@Test void seo_isJsonWithTitleAndDescription()
// CopyGenerationTaskHandlerTest（FakeLlmGateway）
@Test void generates6TextAssetsInReview_withFactVersionAndProvenance()
@Test void staleFactVersion_skipped()
@Test void onlySpec_savesSingleNewVersion()
@Test void refusal_nonRetryable_noAssets()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='CopyAssemblerTest,CopyGenerationTaskHandlerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): Claude copy generation with template-assembled long/short copy"`

---

### Task 6: 文案预检

**Files:**
- Create: `content/copy/{TextPrecheck,QuantityNormalizer}.java`
- Modify: `content/asset/PrecheckFlag.java`（增加文本类标记）、`application.yml`（`kiano.content.copy.*`）
- Test: `content/copy/{QuantityNormalizerTest,TextPrecheckTest}.java`

**Interfaces:**
- `PrecheckFlag` 新增 `FACT_MISMATCH, FORBIDDEN_CLAIM, TOO_LONG, PRICE_IN_COPY, MISSING_PLACEHOLDER, POLICY_PENDING`。
- Produces: `QuantityNormalizer.extract(String text) -> Set<Quantity>`；`record Quantity(BigDecimal value, String unit)`。规则如下：
  - **单位归一**：L、l、litre(s)、liter(s) 统一为 `L`；`ml` 换算为 L（÷1000）；W、watt(s) 统一为 `W`；kW 换算为 W（×1000）；V、volt(s) 统一为 `V`；Hz 统一为 `Hz`；`inch`、`in`、`"` 统一为 `in`；`cm` 统一为 `cm`；`kg` 统一为 `kg`；`months`、`month`、`mo` 统一为 `month`；`year(s)` 换算为 month（×12）。
  - 数字中的千分位逗号要去掉。
  - 区间 `220-240V`、`220–240 V`、`220 to 240V` 展开为两个值。
  - 数字和单位之间可以有空格，也可以没有。
  - 不带单位的纯数字不提取，例如 "2 in 1"、"3 speeds"。
- Produces: `TextPrecheck.check(String specCode, String text, FactsJson facts, CopyProperties props) -> PrecheckResult`，规则如下：
  - **FACT_MISMATCH**：文案中提取出的每个 Quantity，都必须能在事实表中所有字符串字段提取出的 Quantity 集合里找到，`powerW` 视为 `{powerW W}`。`metrics.unmatched` 列出找不到的值。
  - **FORBIDDEN_CLAIM**：不区分大小写，按整词匹配。词表 = 全局词表 `kiano.content.copy.forbidden-claims`（默认 `best in ghana, 100% guaranteed, no.1, number one, cheapest, lowest price, guaranteed results`）加上事实表中的 `forbiddenClaims`。
  - **TOO_LONG**：SEO 标题 > 60、SEO 描述 > 155、COPY_TITLE > `max-title`（默认 120）、COPY_GSHOP > 150 时标记。上限都可以配置。
  - **PRICE_IN_COPY**：除 COPY_WA 外，文案中出现 `GH₵`、`GHS`、`cedi`，或者"货币符号加数字"时标记。
  - **MISSING_PLACEHOLDER**：COPY_WA 中没有 `{{price}}` 时标记。
  - **POLICY_PENDING**：COPY_LONG 中含有政策占位符时标记。
  - 检查时先把 HTML 标签去掉，只看文本。

- [ ] **Step 1: 写失败的测试**

```java
// QuantityNormalizerTest（Review Focus 3）
@ParameterizedTest @CsvSource({"1.5L,1.5,L","1.5 L,1.5,L","1500 ml,1.5,L","'1,500ml',1.5,L","350W,350,W","350 watts,350,W","1.2kW,1200,W","12 months,12,month","1 year,12,month"})
void normalises(...)
@Test void range_expandsBothEnds()        // "220–240 V" 和 "220-240V" → 都得到 {220 V, 240 V}
@Test void bareNumbers_ignored()          // "2 in 1 blender with 3 speeds" → 空集
// TextPrecheckTest
@Test void numbersPresentInFacts_noFlag()          // 事实为 capacity "1.5L"、powerW 350；文案为 "1500 ml jar, 350 watts" → 不标记
@Test void inventedNumber_flagsFactMismatch()      // 文案中出现 "500W" → FACT_MISMATCH，unmatched 为 ["500 W"]
@Test void forbiddenGlobalAndPerSku()
@Test void seoTooLong_flags()
@Test void priceInLongCopy_flags_butWaPlaceholderOk()
@Test void waWithoutPlaceholder_flags()
@Test void policyPlaceholder_flagsPolicyPending()
```

- [ ] **Step 2–4: 先确认失败 → 实现（`CopyGenerationTaskHandler` 改为调用 `TextPrecheck`）→ 确认通过**　Run: `./mvnw -q test -Dtest='QuantityNormalizerTest,TextPrecheckTest,CopyGenerationTaskHandlerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): copy precheck with unit-normalised fact consistency and claim filters"`

---

### Task 7: 事实锁定后的派生——旧资产处理、文案入队与 INFO/SPEC 渲染

**Files:**
- Create: `content/derive/{FactDependentAssets,TemplateRenderTaskHandler}.java`
- Modify: `content/asset/AssetService.java`（新增 `createImageFromBytes`）、`content/asset/ReviewService.java`（PAGE_MAIN 通过审核后发布 `MainImageApprovedEvent`）
- Test: `content/derive/{FactDependentAssetsTest,TemplateRenderTaskHandlerTest}.java`

**Interfaces:**
- Consumes: `FactLockedEvent`、`TemplateRenderer.render/specModel/infoModel`（C2）、`TaskQueue`。
- `FactDependentAssets`：用 `@TransactionalEventListener` 监听 `FactLockedEvent(tenantId, productId, version)`，处理步骤如下：
  1. 该商品的 COPY_*、PAGE_INFO、PAGE_SPEC 资产中，`fact_version < version` 的按状态处理：DRAFT、IN_REVIEW、APPROVED 改为 ARCHIVED，PUBLISHED 改为 STALE，并写审计 `ASSETS_STALE_BY_FACTS`。
  2. 入队 `COPY_GENERATE {productId, factVersion}`，dedupeKey 为 `copy:{productId}:{version}`。
  3. 入队 `TEMPLATE_RENDER {productId, factVersion, spec:"PAGE_SPEC"}`。
  4. 如果该商品已经有 APPROVED 的 PAGE_MAIN，再入队 `TEMPLATE_RENDER {…, spec:"PAGE_INFO"}`；否则不入队，等 `MainImageApprovedEvent` 触发时再入队，前提是那时已经有 LOCKED 的事实。
- Produces: `AssetService.createImageFromBytes(long tenantId, long productId, String specCode, String variant, byte[] png, int factVersion, Map<String,Object> provenance) -> AssetView`：编码为 JPEG（质量 92），生成缩略图，按 `AssetFileName` 命名（角度段为 `page-info` 或 `page-spec`，素材类型为 `real`），状态设为 IN_REVIEW。这类图由模板渲染，不经过 AI，所以 precheck 为空。
- `TemplateRenderTaskHandler`（任务类型 `TEMPLATE_RENDER`）的流程：
  1. 检查事实版本是否过期，处理方式与 COPY_GENERATE 相同。
  2. PAGE_SPEC 用 `specModel(facts.toTemplateFacts(name))` 渲染。
  3. PAGE_INFO 先取已通过审核的 PAGE_MAIN 图片，转成 PNG，再用 `infoModel(…, png)` 渲染。
  4. 尺寸都是 1600×1600。
  5. 调用 `createImageFromBytes`，provenance 中记录 `template{code,version}` 和 `factVersion`；PAGE_INFO 另外记录 `mainAssetId`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void lock_v2_archivesOldCopyAndInfo_stalesPublished_enqueuesCopyAndSpec()
@Test void lock_withApprovedMain_alsoEnqueuesInfo()
@Test void mainApprovedLater_enqueuesInfoOnlyIfFactsLocked()
@Test void templateRender_spec_createsInReviewAssetWithFactVersion()   // 使用真实的 TemplateRenderer（Playwright），并检查输出为 1600×1600 JPEG
@Test void templateRender_staleFacts_skipped()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='FactDependentAssetsTest,TemplateRenderTaskHandlerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): fact-lock derivation — stale old assets, enqueue copy and INFO/SPEC renders"`

---

### Task 8: 文本资产的审核与编辑

**Files:**
- Modify: `content/asset/{ReviewService,ReviewController}.java`
- Test: `content/asset/TextReviewTest.java`

**Interfaces:**
- `ReviewItem` 新增字段 `kind`、`textBody`、`charCount`（去掉 HTML 后的字符数）、`factVersion`。文本资产的各个 URL 字段为 null。排序规则不变：有预检标记的在前；文本 spec 排在图片之后，顺序为 TITLE、SHORT、LONG、SEO、GSHOP、WA。
- `GET /api/v1/content/assets` 新增参数 `kind=IMAGE|TEXT`，不传时返回全部。
- **编辑**：`PUT /api/v1/content/assets/{id}/text {textBody}`（OPERATOR），只适用于 IN_REVIEW 或 REJECTED 的文本资产。处理方式：创建一个新版本，状态为 IN_REVIEW，内容就是提交的文本；provenance 为 `{manual:true, editedFrom:id, by:userId}`；重新跑 `TextPrecheck`；写审计 `ASSET_TEXT_EDITED`。COPY_LONG 和 COPY_SHORT 的 HTML 只允许 `p, ul, li, table, tr, th, td, h2, h3, strong, em` 这几种标签，以及 POLICY_BLOCK 原样的内容，其余标签直接拒绝，返回 422 `TEXT_HTML_NOT_ALLOWED`。白名单检查用 jsoup `Safelist`（新增依赖 `org.jsoup:jsoup`）。
- **REGENERATE**：对文本资产而言，入队 `COPY_GENERATE {productId, factVersion, onlySpec: specCode}`，旧版本改为 ARCHIVED（与 C2 的规则一致）。

- [ ] **Step 1: 写失败的测试**

```java
@Test void list_textAssets_haveBodyAndCharCount_afterImages()
@Test void edit_createsNewVersionInReview_rerunsPrecheck()
@Test void edit_disallowedHtml_422()                // 提交 "<script>" 或 "<img onerror>" → 拒绝
@Test void edit_onApproved_409()
@Test void regenerate_text_enqueuesOnlySpec()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='TextReviewTest,ReviewServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): text asset review with safe manual edits and per-spec regeneration"`

---

### Task 9: Woo 写入端口与多环境凭证

**Files:**
- Create: `commerce/{CommercePublisher,PublishEnvironment,WooProductSnapshot,WooMedia,ProductContentUpdate}.java`、`commerce/woo/{WooPublisherAdapter,CommercePublisherFactory}.java`
- Modify: `commerce/web/WooIntegrationController.java`（增加 `environment` 参数）、`kiano-web` 的集成设置页在 Task 15 修改
- Test: `commerce/woo/WooPublisherAdapterTest.java`（WireMock）、`commerce/web/WooIntegrationControllerTest.java`（追加用例）

**Interfaces:**
- Produces:
```java
public enum PublishEnvironment { STAGING, PRODUCTION }   // STAGING 对应 provider WOOCOMMERCE_STAGING，PRODUCTION 对应 WOOCOMMERCE
public record WooImageRef(long id, String src, int position, String alt) {}
public record WooProductSnapshot(long id, String sku, String name, String description, String shortDescription,
    List<WooImageRef> images, Map<String, String> rankMath, Instant modifiedAt) {}   // rankMath 中只有 rank_math_title 和 rank_math_description 两个键
public record WooMedia(long id, String src) {}
public record ProductContentUpdate(String name, String description, String shortDescription,
    List<Long> imageIdsInOrder, String seoTitle, String seoDescription) {}
public interface CommercePublisher {
  Optional<WooProductSnapshot> findBySku(String sku);
  WooProductSnapshot get(long productId);
  WooMedia uploadMedia(String fileName, byte[] bytes, String contentType, String altText);
  WooProductSnapshot updateContent(long productId, ProductContentUpdate update);
  void deleteMedia(long mediaId);
}
```
- `CommercePublisherFactory.forEnvironment(long tenantId, PublishEnvironment env) -> CommercePublisher`。对应环境没有配置时返回 409 `WOO_ENV_NOT_CONFIGURED`，`details.environment` 为环境名。
- `WooPublisherAdapter` 复用 C1 适配器的认证、超时和错误分类（`WOO_AUTH_FAILED`、`WOO_BLOCKED`、`WOO_UNAVAILABLE`），各接口如下：
  - `findBySku`：`GET /wc/v3/products?sku={sku}`，从 `meta_data` 中取出 Rank Math 的两个键。
  - `uploadMedia`：`POST /wp/v2/media`，请求体为原始字节，带 `Content-Disposition: attachment; filename="{fileName}"` 和 `Content-Type`；成功后再 `POST /wp/v2/media/{id} {alt_text}`。
  - `updateContent`：`PUT /wc/v3/products/{id}`，请求体为 `{name, description, short_description, images:[{id},…], meta_data:[{key:"rank_math_title",value},{key:"rank_math_description",value}]}`；seo 字段为 null 时不发送 meta_data。
  - `deleteMedia`：`DELETE /wp/v2/media/{id}?force=true`。
  - 写操作遇到 5xx 时**不在适配器内重试**，以免产生重复的媒体；由上层的发布流程负责恢复。
- 集成端点：`PUT/GET/POST test /api/v1/integrations/woocommerce?environment=STAGING|PRODUCTION`，默认为 PRODUCTION，与 C1 的行为兼容。STAGING 环境存储为 provider `WOOCOMMERCE_STAGING`，并写入 `store` 表，platform 为 `WOOCOMMERCE_STAGING`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void findBySku_mapsImagesInOrderAndRankMathMeta()
@Test void uploadMedia_sendsBytesWithDisposition_thenSetsAlt()
@Test void updateContent_sendsImageIdsInOrderAndRankMathMeta()
@Test void updateContent_withoutSeo_omitsMetaData()
@Test void write5xx_notRetried()                       // WireMock 断言 POST 只收到 1 次
@Test void deleteMedia_forceTrue()
@Test void factory_unconfiguredStaging_409()
// WooIntegrationControllerTest
@Test void putStaging_storedAsSeparateProvider_productionUnchanged()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='WooPublisherAdapterTest,WooIntegrationControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(commerce): Woo publisher port (media, product content, Rank Math) with staging/production credentials"`

---

### Task 10: 按 SKU 发布（staging → production）

**Files:**
- Create: `db/migration/V15__publication.sql`
- Create: `content/publish/{PublicationEntity,PublicationMapper,PublicationService,GallerySelector,PublishTaskHandler,PublicationController,AltTextBuilder}.java`
- Test: `content/publish/{GallerySelectorTest,PublicationServiceTest,PublishTaskHandlerTest,PublicationControllerTest}.java`；Woo 写入用一个可编程的假 `CommercePublisher` 模拟

**Interfaces:**
- 表：
```sql
create table publication (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  environment text not null check (environment in ('STAGING','PRODUCTION')),
  target text not null default 'WOO_PRODUCT' check (target in ('WOO_PRODUCT','AD_EXPORT')),
  asset_ids jsonb not null, external_ref text,
  before_json jsonb, after_json jsonb, uploaded_media_ids jsonb not null default '[]',
  archived_asset_ids jsonb not null default '[]',
  status text not null check (status in ('PENDING','APPLIED','ROLLED_BACK','FAILED')),
  needs_attention boolean not null default false, error text,
  published_by bigint references app_user(id), published_at timestamptz,
  created_at timestamptz not null default now());
create index publication_product_env on publication (tenant_id, product_id, environment, id desc);
```
- Produces: `GallerySelector.select(List<AssetView> approved) -> List<AssetView>`：先按 spec 分组，每组同一 variant 只保留最新版本，然后按 MAIN → ANGLE → SCENE → INBOX → INFO → SPEC 排序，各组上限为 1、4、2、1、1、1。组内顺序如下：ANGLE 按 variant 排（P2、P3、P4、P6）；SCENE 按 variant 编号排。
- Produces: `PublicationService.request(CurrentUser user, long productId, PublishEnvironment env) -> long publicationId`。前置检查按以下顺序执行，任何一项失败都不会创建 publication：
  1. 权限：PRODUCTION 需要 OWNER，否则返回 403。
  2. 必需的资产：PAGE_MAIN、至少 3 张 PAGE_ANGLE、COPY_TITLE、COPY_SHORT、COPY_LONG 都已 APPROVED，否则返回 422 `PUBLISH_PRECONDITIONS`，`details.missing` 列出缺少的项。
  3. `PolicyService.requireComplete`，并且 COPY_LONG 的 `contentJson.policyVersion` 等于当前政策版本，否则返回 409 `POLICY_INCOMPLETE` 或 `COPY_POLICY_OUTDATED`。
  4. 资产集合 = 选出的 gallery 加上上面 3 个文案资产，再加上 COPY_SEO（如果已 APPROVED）。
  5. PRODUCTION 额外要求：该商品在 STAGING 环境的**最新一条** publication 状态为 APPLIED，且 `asset_ids` 与第 4 步算出的集合完全相同，否则返回 409 `STAGING_REQUIRED`，`details.diff` 为 `{added:[], removed:[]}`（Review Focus 5）。
  6. 插入一条 publication，状态为 PENDING；入队 `PUBLISH_PRODUCT {publicationId}`，dedupeKey 为 `publish:{productId}:{env}`，已有排队中的发布时返回 409 `PUBLISH_IN_PROGRESS`。
  7. 写审计 `PUBLISH_REQUESTED`。
- `PublishTaskHandler` 按规格 §10.1 执行：
  1. 用 `findBySku` 找到商品，找不到时记为 FAILED，错误码 `WOO_PRODUCT_NOT_FOUND`。把快照保存到 `before_json`。
  2. 按 gallery 顺序逐张上传。从存储读取 JPEG；文件名用 `asset.file_name`；alt 文本由 `AltTextBuilder` 生成，格式为 `"{product name} – {view}"`，view 的取值是：MAIN 为 `front view`，P2 为 `front-left view`，P3 为 `front-right view`，P4 为 `side view`，P6 为 `top view`，SCENE 为 `in a Ghanaian home`，INBOX 为 `what's in the box`，INFO 为 `key features`，SPEC 为 `specifications`。每上传成功一张，就把媒体 ID 追加到 `uploaded_media_ids`，并且**立即落库**，保证中途失败时也知道要删哪些。
  3. 调用 `updateContent`：name 取 COPY_TITLE，description 取 COPY_LONG，short_description 取 COPY_SHORT，图片按上传顺序排列，SEO 取 COPY_SEO（没有时为 null）。然后检查返回结果中的图片数量和顺序是否与预期一致。
  4. 成功后：把快照保存到 `after_json`；状态设为 APPLIED；`external_ref` 设为 Woo 的商品 ID。如果是 PRODUCTION，在同一个事务中：本次的资产改为 PUBLISHED；同一 `(spec, variant)` 下原来 PUBLISHED 的旧版本改为 ARCHIVED，并记入 `archived_asset_ids`；写审计 `PUBLISHED`。
  5. 任何一步抛异常时**进入恢复流程**：如果已经执行过第 3 步，或者无法确定第 3 步是否成功，就用 `updateContent` 把 `before_json` 写回去，包括原来的图片 ID 顺序和原来的 Rank Math 值；然后逐个 `deleteMedia(uploaded_media_ids)`；最后状态设为 FAILED，`error` 写入原始错误。恢复过程中再次出错时，设置 `needs_attention=true`，并在 `error` 中追加恢复失败的原因。这个任务**不重试**，失败时抛 `NonRetryableTaskException`。
- 端点：
  - `POST /api/v1/content/products/{id}/publish {environment}` → 202 `{publicationId}`。
  - `GET /api/v1/content/products/{id}/publications` → 列表，每条包含 environment、status、needsAttention、error、assetIds、publishedAt、publishedBy，以及 `canRollback`（见 Task 11）。

- [ ] **Step 1: 写失败的测试**

```java
// GallerySelectorTest
@Test void capsAndOrder_heroWith4Scenes_keeps2_total10()
@Test void latestVersionPerVariant_only()
// PublicationServiceTest
@Test void missingRequiredAssets_422WithList()
@Test void policyIncompleteOrOutdatedCopy_409()
@Test void production_withoutStaging_409StagingRequired()             // Review Focus 5
@Test void production_assetsChangedSinceStaging_409WithDiff()         // Review Focus 5
@Test void production_requiresOwner_403()
@Test void concurrentPublish_409InProgress()
// PublishTaskHandlerTest（假 CommercePublisher）
@Test void staging_success_appliedButAssetsNotPublished()
@Test void production_success_assetsPublished_previousArchived()
@Test void thirdUploadFails_restoresNothingChanged_deletesTwoUploaded_failed()   // Review Focus 1：updateContent 一次都没有被调用；两次 deleteMedia
@Test void updateRejected_restoresBeforeSnapshotExactly_deletesAllUploaded()     // Review Focus 1：第二次 updateContent 的参数与 before_json 完全一致（名称、描述、图片顺序、Rank Math）
@Test void restoreAlsoFails_needsAttention()
@Test void productNotFoundBySku_failedWithCode()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='GallerySelectorTest,PublicationServiceTest,PublishTaskHandlerTest,PublicationControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): per-SKU Woo publishing with staging gate, gallery selection and failure restore"`

---

### Task 11: 回滚

**Files:**
- Modify: `content/publish/{PublicationService,PublicationController}.java`
- Test: `content/publish/RollbackTest.java`

**Interfaces:**
- Produces: `PublicationService.rollback(CurrentUser user, long publicationId, boolean force)`。规则如下：
  1. 权限：PRODUCTION 需要 OWNER，STAGING 需要 OPERATOR。
  2. 只能回滚该商品在该环境中**最新的一条** APPLIED 记录，否则返回 409 `NOT_LATEST_PUBLICATION`。
  3. 读取 Woo 当前的商品。如果 `modifiedAt` 与 `after_json.modifiedAt` 不一致且 `force` 为 false，返回 409 `WOO_CHANGED_SINCE_PUBLISH`，`details` 中给出两个时间（Review Focus 2）。
  4. 用 `updateContent` 把 `before_json` 写回去。
  5. `deleteMedia(uploaded_media_ids)`：逐个删除，单个失败只记一条 WARN，不中断回滚。
  6. 状态设为 ROLLED_BACK。如果是 PRODUCTION：本次的资产从 PUBLISHED 改回 APPROVED；`archived_asset_ids` 中的资产从 ARCHIVED 改回 PUBLISHED。
  7. 写审计 `PUBLICATION_ROLLED_BACK`，并记录 force 的值。
  8. 整个回滚**同步执行**：只有一次商品 PUT 加若干次媒体 DELETE，耗时在几秒以内。
- 端点：`POST /api/v1/content/publications/{id}/rollback?force=false` → 200，返回更新后的 publication。`canRollback` 在满足"最新、APPLIED、当前用户有权限"时为 true。

- [ ] **Step 1: 写失败的测试**

```java
@Test void rollback_restoresBefore_deletesMedia_statusRolledBack()
@Test void production_rollback_revertsAssetStatuses()     // 本次的资产改回 APPROVED，被本次归档的资产改回 PUBLISHED
@Test void wooChangedSincePublish_409_unlessForce()       // Review Focus 2
@Test void notLatest_409()
@Test void production_requiresOwner_403()
@Test void mediaDeleteFailure_doesNotAbortRollback()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=RollbackTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): publication rollback with Woo change detection and asset status reversal"`

---

### Task 12: 政策变更后自动重新渲染长描述

**Files:**
- Create: `content/copy/PolicyRerender.java`
- Modify: `content/publish/PublicationController.java`（新增"需要重新发布"列表）
- Test: `content/copy/PolicyRerenderTest.java`

**Interfaces:**
- 用 `@TransactionalEventListener` 监听 `PolicyChangedEvent(tenantId, version)`，入队任务 `POLICY_RERENDER {version}`，dedupeKey 为 `policy-rerender`。处理逻辑如下：
  - 对每个商品当前的 COPY_LONG（状态为 IN_REVIEW、APPROVED 或 PUBLISHED，`contentJson.policyVersion` 不等于 version）：用 `CopyAssembler` 重新渲染，只替换 POLICY_BLOCK 部分（使用 `contentJson` 中保存的片段，**不调用 LLM**），然后 `createText` 生成新版本。
  - 新版本的状态取决于旧版本：
    - 旧版本为 IN_REVIEW → 新版本也是 IN_REVIEW。
    - 旧版本为 APPROVED 或 PUBLISHED，并且 COPY_LONG 和 POLICY_BLOCK 模板都是 APPROVED → 新版本**自动通过**为 APPROVED，审计 actor 为 SYSTEM，action 为 `COPY_AUTO_APPROVED_POLICY_CHANGE`。这一条参照规格 §10.3 中"只有价格变量变化时自动通过"的规则：这里变化的只是经过 OWNER 确认的政策文本，所以同样处理。
    - 旧版本为 PUBLISHED 时，旧版本还要改为 STALE。
- Produces: `GET /api/v1/content/publications/needs-republish`（VIEWER），返回 PUBLISHED 资产中有 STALE 版本的商品列表 `[{productId, sku, name, reasons:["POLICY_CHANGED"|"FACTS_CHANGED"]}]`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void policyChange_rerendersLongCopyWithoutLlm()          // FakeLlmGateway 的调用次数为 0
@Test void approvedOrPublished_autoApprovedNewVersion_audited()
@Test void published_becomesStale_listedInNeedsRepublish()
@Test void inReview_staysInReview()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=PolicyRerenderTest`；然后运行全部后端测试 `./mvnw -q verify`，结果应为 PASS（运行前先停掉 worker）
- [ ] **Step 5: Commit** — `git commit -m "feat(content): re-render long copy on policy change and list products needing republish"`

---

### Task 13: 前端——事实表核对页与商品页的事实/文案面板

**Files:**
- Create: `kiano-web/src/app/(app)/products/[id]/facts/page.tsx`、`src/components/FactsPanel.tsx`、`src/lib/facts.ts`、`src/lib/facts.test.ts`
- Modify: `src/app/(app)/products/[id]/page.tsx`、`src/lib/types.ts`、`src/i18n/{en,zh}.ts`、`i18n.test.ts`

**Interfaces:**
- Produces: `src/lib/facts.ts` 中的三个函数：
  - `REQUIRED_CONFIRMATIONS = ['model','capacity','powerW','voltage','warranty','inBox'] as const`
  - `missingConfirmations(confirmed: Set<string>): string[]`
  - `listFieldToText(list: string[]) / textToListField(text: string): string[]`：多行文本与数组互转，去掉空行和首尾空白。
- `/products/[id]/facts`：
  - 左侧是事实表单，字段与 FactsJson 一一对应。数组字段用多行文本框编辑。每个字段旁边显示来源徽标（P5、PROMO、WOO_TEXT、MANUAL、NONE），用户手工改过的字段自动变为 MANUAL。
  - 右侧并排显示 P5 和 PROMO 原图，支持点击放大。
  - 顶部提供三个按钮："AI 生成草稿"（等待任务完成期间每 3 秒轮询一次）、"保存草稿"、"锁定"。
  - 点击"锁定"后弹出确认清单，必需的 6 项全部勾选后按钮才能点击。
  - 草稿已被他人修改时，提示"草稿已被修改，请刷新"。
- `FactsPanel` 放在商品详情页，显示事实状态（无、草稿 vN、已锁定 vN）和锁定时间，并显示文案、INFO、SPEC 的生成进度：各 spec 的最新状态，有预检标记的计数。
- i18n 新增：事实表各字段的名称、来源、确认清单和按钮文字；错误码 `FACTS_NOT_CONFIRMED`、`FACTS_INCOMPLETE`、`FACT_DRAFT_CHANGED`、`FACT_DRAFT_EXISTS`、`NO_FACT_SOURCES`、`LLM_REFUSED`、`LLM_NOT_CONFIGURED`、`LLM_UNAVAILABLE`。

- [ ] **Step 1: 写失败的测试**

```ts
test('missingConfirmations lists unchecked required fields in order')
test('list field round-trip trims and drops empty lines')
// i18n.test.ts：在字典覆盖清单中加入新的错误码和字段键
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `cd kiano-web && pnpm vitest run`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): fact sheet review page with G2 confirmation and product facts panel"`

---

### Task 14: 前端——审核看板支持文本资产

**Files:**
- Create: `kiano-web/src/components/TextAssetCard.tsx`
- Modify: `src/app/(app)/review/page.tsx`、`src/lib/review.ts`（+ 测试）、`src/i18n/*`

**Interfaces:**
- 审核看板顶部新增筛选："全部 / 图片 / 文案"，对应接口参数 `kind`。
- `TextAssetCard` 的显示内容：
  - spec 名称、版本号、字符数。超出上限时字符数显示为红色，上限与后端一致：SEO 标题 60、SEO 描述 155、标题 120、GSHOP 150。
  - 预检标记徽标；FACT_MISMATCH 时列出 `unmatched` 中的值。
  - COPY_LONG 和 COPY_SHORT 用"渲染预览"显示：后端已经转义过，前端只用 jsoup 白名单允许的标签显示，用 DOMPurify 或自写的白名单过滤（二选一，写进 commit 信息）。COPY_SEO 显示为标题和描述两行。
  - 右侧显示锁定事实的摘要（型号、容量、功率、电压、保修），方便对照。
- 快捷键：A、R、G 与图片卡片相同；新增 **E** 进入编辑。编辑框使用等宽字体，保存时调用 PUT；编辑框中按 Esc 取消，此时 A、R、G 快捷键不生效。
- Produces: `charLimit(specCode: string, part?: 'title'|'description'): number | null`，放在 `src/lib/review.ts` 中并配有测试。

- [ ] **Step 1: 写失败的测试**：`charLimit` 的取值，以及 `keyToDecision('e')` 返回 `'EDIT'`。
- [ ] **Step 2–4: 先确认失败 → 实现 → 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): text asset review cards with limits, fact summary and inline edit"`

---

### Task 15: 前端——政策设置、staging 集成与发布面板

**Files:**
- Create: `kiano-web/src/app/(app)/settings/policy/page.tsx`、`src/components/PublishPanel.tsx`
- Modify: `src/app/(app)/settings/integrations/page.tsx`、`src/app/(app)/layout.tsx`（设置菜单中加入"店铺政策"）、`src/app/(app)/products/[id]/page.tsx`、`src/i18n/*`

**Interfaces:**
- `/settings/policy`（只有 OWNER 可见）：5 个分区，各有标题和正文（英文，页面上提示"此处内容将显示在英文商品页"），显示版本号和完整度。保存时如果有空的分区，提示"未填完整，暂不能发布"，但仍然允许保存。
- `/settings/integrations`：分成"生产（同步来源 + 生产发布）"和"Staging（本地 Docker）"两个区块，各自有 Base URL、用户名、Application Password 和测试连接按钮。staging 区块注明本地应填 `http://host.docker.internal:8080`。
- `PublishPanel`（商品详情页）：
  - 前置条件清单：必需的资产、政策是否完整、事实是否已锁定，逐项显示 ✓ 或 ✗。
  - "发布到 Staging"按钮（OPERATOR 可用）；"发布到生产"按钮（只有 OWNER 可用，前置条件是 staging 已经成功，并且资产集合一致；不满足时按钮置灰，悬停提示原因，例如 STAGING_REQUIRED 的差异）。
  - 发布历史：环境、状态、时间、操作人。`needsAttention` 为 true 时显示红色警告"Woo 可能处于不一致状态，请人工检查"。可以回滚的记录显示"回滚"按钮；遇到 `WOO_CHANGED_SINCE_PUBLISH` 时，弹出二次确认"Woo 中的商品已被修改，强制回滚将覆盖这些修改"。
  - 运行中时每 3 秒轮询一次。
- 商品列表页或顶部导航显示"需要重新发布（n）"，点击后进入列表，数据来自 `/publications/needs-republish`。
- i18n 新增：政策的分区名，发布相关的状态、错误码（`PUBLISH_PRECONDITIONS`、`POLICY_INCOMPLETE`、`COPY_POLICY_OUTDATED`、`STAGING_REQUIRED`、`PUBLISH_IN_PROGRESS`、`WOO_ENV_NOT_CONFIGURED`、`WOO_PRODUCT_NOT_FOUND`、`NOT_LATEST_PUBLICATION`、`WOO_CHANGED_SINCE_PUBLISH`、`TEXT_HTML_NOT_ALLOWED`）和提示文字；同时更新 `i18n.test.ts` 中的覆盖清单。

- [ ] **Step 1: 补全 i18n 覆盖清单，确认测试失败**
- [ ] **Step 2: 实现**
- [ ] **Step 3: 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 4: Commit** — `git commit -m "feat(web): store policy settings, staging integration and publish/rollback panel"`

---

### Task 16: 文档、配置与 C3 验收

**Files:**
- Modify: `.env.example`（`ANTHROPIC_API_KEY`）、`docker-compose.yml`（api 增加环境变量 `ANTHROPIC_API_KEY`）、`README.md`、`CLAUDE.md`

**Interfaces:**
- README 新增以下内容：
  - LLM 的配置方法和成本查询（`select purpose, sum(cost_usd) from llm_call group by 1`）。
  - 事实草稿 → 锁定 → 文案 → 审核 → 发布的操作顺序。
  - staging 的配置方法：本地 Docker 中 Shop Manager 用户的 Application Password；Base URL 填 `host.docker.internal`。
  - **生产发布前的检查清单**：
    1. 生产环境的 Shop Manager Application Password 由用户在 WordPress 后台创建，只在设置页中输入。
    2. Cloudflare 的 WAF 要对 Kiano 服务器的 IP 放行 `/wp-json/wc/` 和 `/wp-json/wp/v2/media`。
    3. Rank Math 已经启用。
  - 回滚的说明。
- CLAUDE.md 补充 C3 的硬规则：LLM 输出必须转义；生产发布必须先在 staging 成功；Opus 5.5 不能关闭 thinking。

- [ ] **Step 1: 编写文档和配置**；执行 `docker compose --profile app up -d --build`，确认健康检查返回 UP。
- [ ] **Step 2: 用 staging 做端到端验收**：用一个 C2 已经生成并通过审核的 HERO SKU。如果 C2 验收还没做，就先做 C2 验收。目标环境是 KianosMart 本地 Docker。
  1. 在设置页中配置 staging 和政策。政策可以先填测试文本，例如"Accra & Tema: 1–3 days"，验收完成后再由用户填写最终文本。
  2. 点击"AI 生成草稿"，核对 P5 中读出的型号、功率、电压，然后锁定。确认 llm_call 表中有记录，并且成本合理。
  3. 文案 6 项、INFO、SPEC 都进入审核看板。逐一检查：FACT_MISMATCH 和 FORBIDDEN_CLAIM 的结果符合预期；手工编辑一次标题；全部通过。
  4. 发布到 Staging，得到 APPLIED。用 `wp wc product get <id>` 核对 name、description（包含转义后的 FAQ 和 POLICY_BLOCK）、short_description、images（≤10 张，顺序正确，alt 正确），以及 `wp post meta get <id> rank_math_title`。在浏览器中打开 `http://localhost:8080` 的商品页，看一下实际效果。
  5. **失败恢复**：临时把 staging 的 Application Password 改成错误的，然后再次发布，状态为 FAILED，商品没有变化。改回正确的密码。
  6. **回滚**：在 staging 上回滚，商品恢复为发布前的样子，本次上传的媒体被删除。再发布一次，在 Woo 后台手工改一下这个商品的标题，然后回滚，得到 409 `WOO_CHANGED_SINCE_PUBLISH`；选择强制回滚后成功。
  7. **生产门槛**：在 staging 发布成功之后，重新生成一张场景图并通过审核，然后点击"发布到生产"，得到 409 `STAGING_REQUIRED`，并显示差异。
- [ ] **Step 3: 生产发布（需要用户授权，并且只能由用户操作）**：规格 §14 的 C3 验收要求"15 个 HERO SKU 在 staging 验证后发布到生产，并且回滚可用"。这一步需要：用户在生产 WordPress 中创建 Shop Manager 的 Application Password；配置好 Cloudflare 放行规则；在设置页中填写政策的最终文本；然后由用户本人逐个点击"发布到生产"。**实施者不得代为执行生产发布**，只负责在 README 中写明步骤，并在验收记录中注明"待用户执行"。
- [ ] **Step 4: 运行全部测试**　Run: `cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm lint && pnpm build`（运行前先停掉 worker）
- [ ] **Step 5: Commit** — `git commit -m "chore: C3 docs, LLM config and staging acceptance notes"`

---

## Self-Review 记录

- **规格覆盖**（对照 §14 C3 一行的内容）：
  - 事实草稿与锁定：Task 2、3、13。
  - LLM 文案：Task 1、5。
  - POLICY_BLOCK：Task 4、5、12、15。
  - 文案预检与审核：Task 6、8、14。
  - 按 SKU 发布到 Woo：Task 9、10、15。
  - 失败恢复：Task 10。
  - 回滚：Task 11。
  - 验收：Task 16，其中生产发布由用户执行。
  - C2 延后的事项：INFO/SPEC 接入流水线在 Task 7；gallery 最多 10 张在 Task 10。
  - 规格 §7.2 的约束：标题格式、3–5 条卖点、FAQ、SEO 长度、GShop、`{{price}}`、禁用词、`GH₵` 不进入页面文案、POLICY_BLOCK 不由 AI 生成，分别在 Task 5 和 Task 6 中实现。
  - 规格 §10.1 的第 1–6 步都在 Task 10 和 Task 11 中实现。
  - 规格 §9.2 的 G2 并排核对在 Task 2 和 Task 13。
- **类型一致性**：
  - `FactsJson.toTemplateFacts` 衔接 C2 的 `TemplateFacts`。
  - `FactLockedEvent` 在 Task 2 定义，Task 7 使用。
  - `PolicyChangedEvent` 在 Task 4 定义，Task 12 使用。
  - `CopyAssembler` 和 `TextAsset` 在 Task 5 定义，Task 8 和 Task 12 使用。
  - `PrecheckFlag` 在 Task 6 扩展，Task 14 使用。
  - `CommercePublisher` 在 Task 9 定义，Task 10 和 Task 11 使用。
  - `PublishEnvironment` 在 Task 9 定义，Task 10、11、15 使用。
  - 迁移版本号按任务执行顺序分配（Task 1 为 V10，Task 2 为 V11，Task 3 为 V12，Task 4 为 V13，Task 5 为 V14，Task 10 为 V15），每个任务只新建自己的文件、不修改其他任务已提交的迁移，符合"已提交的迁移不再修改"的硬规则。
- **刻意不做的事**：
  - 价格变化后标记 STALE、广告导出、kiano-connector 插件：放在 C4。
  - Google Shopping 标题写入 Woo：Google 插件的 meta 字段还没确认，COPY_GSHOP 先只保存在库中。
  - Batch API 降低成本：放在 C6 的批量处理。
  - 政策正文的中文版本：页面内容只有英文，界面标签照常双语。
  - 本地 Rank Math：本地没有安装这个插件，写入的 meta 只作为普通 postmeta 存在，生产验收时再确认效果。
