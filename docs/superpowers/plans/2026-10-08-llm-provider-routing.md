# LLM 提供商可配置与按任务路由 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** OWNER 在设置页中配置多个 LLM 提供商（Anthropic，或兼容 OpenAI 格式的 API，例如 DeepSeek），并为事实草稿和文案分别选择提供商和模型。改完立即生效；没有配置的任务继续使用 `.env` 中的 Claude。

**Architecture：**
- `platform/llm` 中原来的 `AnthropicLlmGateway` 改造为 `AnthropicProviderClient`。
- 新增 `OpenAiCompatibleProviderClient`：通用的 `chat/completions` 客户端，支持 json_object 模式，并在本地做 schema 校验，不合格时重试一次。
- 新增 `RoutingLlmGateway`：成为唯一的 `LlmGateway` bean。它按 `LlmRequest.purpose` 解析路由，来源是 `llm_route` 表，没有配置时用内置默认路由；然后把请求分派给对应的 ProviderClient。
- 提供商和路由由 `LlmSettingsService` 管理，key 用现有的 `CredentialCipher` 加密。
- 前端新增 `/settings/llm` 设置页。

**Tech Stack：** 沿用现有的技术栈（Spring Boot 4.1、Jackson 3、JdbcTemplate、WireMock、Next.js、Vitest）。新增 `com.github.victools:jsonschema-generator` 和 `jsonschema-module-jackson`（Apache-2.0），用于从 record 生成 JSON Schema。

**Spec：** `docs/superpowers/specs/2026-10-08-llm-provider-routing-design.md`（已经过用户评审）。

## Global Constraints

C1、C2、C3 计划中的 Global Constraints 继续有效。另外：

- `LlmGateway`、`LlmRequest`、`LlmResult`、`LlmException` 及其子类的**签名都不变**。`FactDraftTaskHandler` 和 `CopyGenerationTaskHandler` **不能改动**，现有的 `FakeLlmGateway` 测试也不能改动。
- 改造完成后，**`RoutingLlmGateway` 是唯一实现 `LlmGateway` 的 bean**。ProviderClient 不实现 `LlmGateway`，这样测试中用 `@Primary` 注入的 `FakeLlmGateway` 仍然能覆盖它。
- **key 保护**：
  - 只以 `CredentialCipher.encrypt(key, aad)` 的密文形式入库，AAD 为 `tenantId + ":llm:" + providerId`。
  - API 响应中只返回 `hasKey`；`audit_log`、日志、`llm_call.error` 中都**不能出现 key**。上游错误信息写入前要截断到 500 字符，并且去掉 `Authorization`、`x-api-key`、`sk-` 这类片段。
- **内置默认路由**（purpose 没有配置 `llm_route` 时使用）：kind 为 `ANTHROPIC`；key 取 `kiano.llm.api-key`（即 `${ANTHROPIC_API_KEY}`）；模型 `claude-opus-5-5`；`supportsImages=true`；价格 4.00 / 20.00 / 0.20。这些值取自现有的 `LlmProperties`，不另外写死。
- `llm_call.provider` 的取值规则：Anthropic 写 `ANTHROPIC`；OpenAI 兼容写 `OPENAI_COMPATIBLE:{provider name}`。内置默认路由也写 `ANTHROPIC`。
- `OPENAI_COMPATIBLE` 的 `base_url` 必须以 `https://` 开头；只有 active profile 包含 `local` 或 `test` 时，才允许 `http://localhost`、`http://127.0.0.1` 和 `http://host.docker.internal`。
- FACT_DRAFT 路由必须 `supportsImages=true`。保存时不满足返回 422 `ROUTE_REQUIRES_VISION`；调用时如果带了图片而路由不支持，抛 `LLM_MODEL_NO_VISION`（不可重试）。
- 预设（Anthropic、DeepSeek）是后端常量，价格取规格 §4 中的高峰价，只用于在设置页"新增"时预填，不入库。
- 设置相关的所有端点都需要 OWNER 权限，所有写操作都写审计（`LLM_PROVIDER_CREATED/UPDATED/DELETED`、`LLM_ROUTE_SAVED/DELETED`）。
- 界面文字中英双语，新增的错误码都要进入字典覆盖清单。

## Review Focus

1. **key 泄露**：上游 401 的响应体或异常消息中可能带有请求头或 key 的片段，写入 `llm_call.error` 和 API 错误前必须脱敏；GET 设置接口和审计中都不能出现 key。测试放在 Task 4（`upstreamErrorWithKeyEcho_isRedactedInLedger`）和 Task 6（`getSettings_andAudit_neverContainKey`）。
2. **更换 key 后客户端缓存没有失效**：如果还用旧客户端，就会继续用旧 key 计费。缓存键必须包含 `updated_at`。测试放在 Task 5（`keyRotation_rebuildsClient`）。
3. **DeepSeek 在 json_object 模式下仍然返回 Markdown 代码块，或者在 JSON 前后带有说明文字**：要容错地提取第一个完整的 JSON 对象，提取不到才算作不合格并重试。测试放在 Task 4（`fencedOrPrefixedJson_isExtracted`）。
4. **路由被删除，或者路由指向的 provider 被删除**：路由被删除后要回到内置默认路由；provider 被路由引用时禁止删除。测试放在 Task 1 和 Task 6。
5. **事实草稿被路由到不能看图的模型**：保存时拦截，调用时还要再检查一次。测试放在 Task 5 和 Task 6。

---

## 文件结构

```
kiano-api/src/main/java/com/kiano/platform/llm/
  LlmGateway, LlmRequest, LlmResult, LlmException…         （不变）
  LlmPurpose                    （+CONNECTION_TEST）
  LlmCallRecorder               （LlmCallRow + provider）
  ProviderKind, Pricing, ResolvedRoute, ProviderClient       （新增）
  LlmRouteStore                 （新增：读写 llm_provider / llm_route、解密、内置默认路由）
  RoutingLlmGateway             （新增：唯一的 LlmGateway bean）
  AnthropicProviderClient       （由 AnthropicLlmGateway 改名改造而来）
  OpenAiCompatibleProviderClient, OutputSchemas, OutputValidator, JsonExtraction, ErrorRedaction  （新增）
  settings/ LlmSettingsService, LlmSettingsController, LlmPresets, ConnectionTester  （新增）
kiano-api/src/main/resources/db/migration/V16__llm_provider.sql
kiano-api/src/main/java/com/kiano/content/facts/FactsJson.java   （标量字段加 @Nullable）
kiano-web/src/app/(app)/settings/llm/page.tsx
kiano-web/src/lib/llmSettings.ts (+ .test.ts)  lib/types.ts  i18n/*  app/(app)/layout.tsx
```

---

### Task 1: 表结构、路由存储与内置默认路由

**Files:**
- Create: `db/migration/V16__llm_provider.sql`（DDL 原样取自规格 §4）
- Create: `platform/llm/{ProviderKind,Pricing,ResolvedRoute,LlmRouteStore}.java`
- Modify: `platform/llm/LlmPurpose.java`（加 `CONNECTION_TEST`）、`platform/llm/LlmCallRecorder.java`（`LlmCallRow` 末尾加 `@Nullable String provider`，insert 语句写入 provider 列）
- Test: `platform/llm/LlmRouteStoreTest.java`、`platform/llm/LlmCallRecorderTest.java`（追加用例）

**Interfaces:**
- Produces:
```java
public enum ProviderKind { ANTHROPIC, OPENAI_COMPATIBLE }
public record Pricing(BigDecimal inputPerMtok, BigDecimal outputPerMtok, BigDecimal cacheReadPerMtok) {
  public BigDecimal cost(long input, long output, long cacheRead);   // (input − cacheRead)×in + cacheRead×cache + output×out，再 ÷1e6，保留 6 位小数，HALF_UP；cacheRead > input 时按 input 截断
}
public record ResolvedRoute(LlmPurpose purpose, @Nullable Long providerId, ProviderKind kind,
    String providerName, @Nullable String baseUrl, String apiKey, String model, boolean supportsImages,
    Pricing pricing, Instant updatedAt, boolean usingDefault) {
  public String providerLabel();   // 规则见 Global Constraints
}
public record ProviderView(long id, String name, ProviderKind kind, @Nullable String baseUrl, boolean hasKey, String status, Instant updatedAt) {}
public record RouteView(LlmPurpose purpose, @Nullable Long providerId, @Nullable String model, boolean supportsImages,
    @Nullable Pricing pricing, boolean usingDefault) {}
```
- `LlmRouteStore`（基于 JdbcTemplate，按 tenant 隔离）的方法：
  - `resolve(long tenantId, LlmPurpose purpose) -> ResolvedRoute`：有路由时 join provider 并解密 key，`updatedAt` 取 route 和 provider 两者中较晚的那个；没有路由时（包括 CONNECTION_TEST）返回内置默认路由，`providerId=null`，`usingDefault=true`，`updatedAt=Instant.EPOCH`。
  - `listProviders(tenantId) -> List<ProviderView>`
  - `findProvider(tenantId, id) -> Optional<ProviderView>`
  - `createProvider(tenantId, name, kind, baseUrl, apiKey) -> long`
  - `updateProvider(tenantId, id, name, baseUrl, @Nullable apiKey)`：apiKey 为 null 或空字符串时保留原 key；`updated_at` 更新为 now()。
  - `deleteProvider(tenantId, id)`
  - `isProviderInUse(tenantId, id) -> boolean`
  - `listRoutes(tenantId) -> List<RouteView>`：始终返回 FACT_DRAFT 和 COPY 两行，没有配置的那行 `usingDefault=true`。
  - `saveRoute(tenantId, purpose, providerId, model, supportsImages, Pricing)`：upsert。
  - `deleteRoute(tenantId, purpose)`
  - 业务校验在 Task 6 的 Service 中完成，Store 只负责存取。

- [ ] **Step 1: 写失败的测试**

```java
@Test void resolve_withoutRoute_returnsEnvDefaultAnthropic()     // kind ANTHROPIC，model claude-opus-5-5，key 来自 LlmProperties，usingDefault true，supportsImages true，价格 4/20/0.20
@Test void createProvider_storesCiphertextNotKey()               // credentials_encrypted 以 "v1:" 开头，不包含明文 key
@Test void saveRoute_thenResolve_decryptsKeyAndCarriesPricing()
@Test void resolve_updatedAt_isLaterOfRouteAndProvider()
@Test void updateProvider_blankKey_keepsOldKey_bumpsUpdatedAt()
@Test void deleteRoute_fallsBackToDefault()                     // Review Focus 4
@Test void listRoutes_alwaysHasBothPurposes()
@Test void pricing_cost_subtractsCacheHitsFromInput()           // 输入 1000（其中缓存命中 400）、输出 500，价格 0.30/1.20/0.006 → 0.000782
// LlmCallRecorderTest
@Test void record_writesProviderColumn()
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `cd kiano-api && ./mvnw -q test -Dtest='LlmRouteStoreTest,LlmCallRecorderTest'`
- [ ] **Step 3: 实现**（把 `LlmCallRow` 现有的调用处统一补上 provider 参数，此时传 `"ANTHROPIC"`）
- [ ] **Step 4: 运行测试，确认通过**
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): llm_provider/llm_route storage with encrypted keys and env default route"`

---

### Task 2: AnthropicLlmGateway 改造为 AnthropicProviderClient

**Files:**
- Create: `platform/llm/ProviderClient.java`
- Rename/Modify: `platform/llm/AnthropicLlmGateway.java` 改为 `AnthropicProviderClient.java`
- Rename/Modify: `test/.../AnthropicLlmGatewayTest.java` 改为 `AnthropicProviderClientTest.java`，断言内容不变
- Modify: `platform/llm/LlmProperties.java`（保留 model、apiKey、pricing，供内置默认路由使用）

**Interfaces:**
- Produces:
```java
public interface ProviderClient {
  ProviderKind kind();
  <T> LlmResult<T> complete(LlmRequest<T> request, ResolvedRoute route) throws LlmException;
}
```
- `AnthropicProviderClient implements ProviderClient`（`@Component`，**不再实现 LlmGateway**）的改动点：
  - 模型、key、价格一律取自 `route`，不再读 `properties.getModel()` 或 `getPricing()`。
  - SDK 客户端按 `apiKey` 缓存，用 `ConcurrentHashMap<String, AnthropicClient>`，键为 key 的 SHA-256。
  - `llm_call.provider` 取 `route.providerLabel()`。
  - 其余行为（structured output、fallback beta、effort、refusal、truncation、错误映射、成本）**保持不变**。
  - 包级的测试构造器保留 `baseUrl` 参数。
- 同时新建**最小版本**的 `platform/llm/RoutingLlmGateway.java`（`@Component implements LlmGateway`），保证 Spring 上下文在本任务结束时仍能启动：
  - 构造时注入 `List<ProviderClient>`，按 `kind()` 建成 `EnumMap`。
  - `complete(request)` 的处理是：`route = store.resolve(...)`，然后交给 `clients.get(route.kind())`。
  - 对应 kind 没有 client 时（此时还没有 OpenAI 兼容适配器），抛 `LlmException("LLM_CONFIG", "No client for provider kind …", false)`。
  - 看图检查、`completeWith`、未配置 key 的处理放在 Task 5。

- [ ] **Step 1: 改写测试**：`AnthropicProviderClientTest` 改为调用 `client.complete(request, route(...))`，用 helper 构造 `ResolvedRoute`。原有用例全部保留，另外新增：

```java
@Test void usesRouteModelKeyAndPricing_notProperties()   // route 的模型为 "claude-sonnet-5-5"、价格 2/10/0.2 → 请求体中的 model 和计算出的成本都随 route
@Test void differentKeys_useSeparateClients()            // 两个不同 key 的 route 先后调用 → WireMock 收到的 x-api-key 分别对应
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `./mvnw -q test -Dtest=AnthropicProviderClientTest`（编译失败，因为 `AnthropicProviderClient` 还不存在）
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**　Run: `./mvnw -q test -Dtest=AnthropicProviderClientTest`；再运行全部后端测试 `./mvnw -q verify`（先停掉 worker），包括使用 FakeLlmGateway 的测试，全部通过
- [ ] **Step 5: Commit** — `git commit -m "refactor(platform): AnthropicLlmGateway → AnthropicProviderClient behind a routing LlmGateway"`

---

### Task 3: schema 生成、本地校验与 JSON 提取

**Files:**
- Create: `platform/llm/{OutputSchemas,OutputValidator,JsonExtraction,ErrorRedaction}.java`
- Modify: `content/facts/FactsJson.java`：12 个字段中，`model, category, capacity, powerW, voltage, material, colour, warranty` 这 8 个标量字段加 JSpecify `@Nullable`；4 个 List 字段保持必填（可以为空列表）。
- Modify: `kiano-api/pom.xml`（引入 victools 的 `jsonschema-generator` 和 `jsonschema-module-jackson`，用最新的 4.x 版本）
- Test: `platform/llm/{OutputSchemasTest,OutputValidatorTest,JsonExtractionTest,ErrorRedactionTest}.java`

**Interfaces:**
- Produces: `OutputSchemas.schemaFor(Class<?> type) -> String`：返回 JSON 字符串，按类型缓存。使用 Draft 2020-12；启用 JacksonModule，以便把 `@JsonPropertyDescription` 写成 `description`；非 `@Nullable` 的 record 组件列入 `required`。
- Produces: `OutputValidator.validate(Object value) -> List<String>`：返回所有违规项，形如 `"facts.inBox is required"`。递归检查 record：没有在 `RecordComponent.getAnnotatedType()` 上标注 `org.jspecify.annotations.Nullable` 的组件不能为 null；List 和 Map 本身不能为 null；List 中的 record 元素也要递归检查。
- Produces: `JsonExtraction.firstObject(String content) -> Optional<String>`：先去掉 Markdown 代码块；然后从第一个 `{` 开始做括号配对，要正确跳过字符串和转义字符，取出第一个完整的对象。内容为空或者找不到完整对象时返回 empty。
- Produces: `ErrorRedaction.clean(String raw) -> String`：去掉 `Bearer\s+\S+`、`sk-[A-Za-z0-9_-]{8,}`、`x-api-key[^,\n]*`，并截断到 500 字符。

- [ ] **Step 1: 写失败的测试**

```java
// OutputSchemasTest
@Test void copyDraft_schemaHasDescriptionsAndAllRequired()
@Test void factDraftResult_nullableFactFieldsNotRequired_listsRequired()   // facts.required 中包含 inBox，不包含 capacity
// OutputValidatorTest
@Test void validCopyDraft_noViolations()
@Test void missingTitle_violation()
@Test void nestedNullList_violationWithPath()       // FactDraftResult 中 facts.inBox 为 null → "facts.inBox is required"
@Test void nullableFactField_null_isFine()
// JsonExtractionTest
@Test void plainJson()  @Test void fencedJson()  @Test void prefixedText()
@Test void braceInsideString_handled()             // {"a":"x } y"} 被完整取出
@Test void emptyOrNoObject_empty()
// ErrorRedactionTest
@Test void removesBearerSkKeysAndTruncates()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='OutputSchemasTest,OutputValidatorTest,JsonExtractionTest,ErrorRedactionTest,FactSheetServiceTest,FactDraftTaskHandlerTest'`（后两个用来确认加上 `@Nullable` 后行为没有变化）
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): JSON schema generation, local output validation and error redaction"`

---

### Task 4: OpenAI 兼容适配器

**Files:**
- Create: `platform/llm/OpenAiCompatibleProviderClient.java`
- Test: `platform/llm/OpenAiCompatibleProviderClientTest.java`（WireMock），测试资源 `src/test/resources/openai/{ok.json,empty.json,invalid-then-ok/*,length.json,content-filter.json,error-401.json}`

**Interfaces:**
- Consumes: Task 1 的 `ResolvedRoute` 和 `Pricing`；Task 3 的 `OutputSchemas`、`OutputValidator`、`JsonExtraction`、`ErrorRedaction`；`LlmCallRecorder`。
- Produces: `OpenAiCompatibleProviderClient implements ProviderClient`（`@Component`，`kind()` 返回 OPENAI_COMPATIBLE），行为严格按规格 §5.2：
  - 用 `RestClient` 加 JDK HttpClient，连接超时 10 秒、读取超时 120 秒，地址为 `POST {baseUrl 去掉末尾 /}/chat/completions`。
  - system 消息 = `request.system()` + `"\n\nRespond with a single JSON object that conforms to this JSON Schema:\n"` + `schemaFor(outputType)`。
  - user 消息的 content 数组中，图片在前，形式为 `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}`；文本在后，形式为 `{"type":"text","text":…}`。没有图片时 content 仍然使用数组形式。
  - 请求体字段：`model`、`max_tokens`、`response_format:{"type":"json_object"}`、`stream:false`。
  - **解析流程**：`choices[0].finish_reason` 为 `length` 时抛 `LlmTruncatedException`，为 `content_filter` 时抛 `LlmRefusedException`（category 为 `content_filter`）。否则对 `choices[0].message.content` 执行 `firstObject`，再用 Jackson 反序列化为 `outputType`（允许未知字段），再执行 `validate`。任何一步失败，就在原有消息后追加 `{"role":"user","content":"Your previous reply was not valid: <原因>. Reply again with only the JSON object."}` 重试**一次**；仍然失败时抛 `LlmException("LLM_INVALID_OUTPUT", retryable=false)`。
  - **HTTP 层**：401、403 抛 `LLM_CONFIG`；400、404、422 抛 `LLM_BAD_REQUEST`；429、5xx 和 IO 错误在适配器内退避重试（第 1 次等 1 秒，第 2 次等 3 秒；测试中通过构造参数把延迟设为 0），重试 2 次后仍失败则抛 `LLM_UNAVAILABLE`（可重试）。
  - **成本与记录**：每一次 HTTP 调用（包括格式重试那一次）都写一行 `llm_call`。用量取自 `usage.prompt_tokens`、`usage.completion_tokens` 和 `usage.prompt_cache_hit_tokens`（缺失时为 0），成本用 `route.pricing().cost(...)` 计算。失败时 `error` 写入 `ErrorRedaction.clean(...)` 处理后的内容。`LlmResult.model` 取响应中的 `model`，缺失时取 `route.model()`。
  - `request.effort()` 被忽略，在类注释中说明原因。

- [ ] **Step 1: 写失败的测试**

```java
@Test void requestShape_imagesBeforeText_jsonObject_schemaInSystem_bearer()
@Test void ok_parsesAndCostsWithCacheHits()                 // usage 为 1000/500，其中缓存命中 400 → 成本与 Pricing.cost 一致；llm_call.provider 为 "OPENAI_COMPATIBLE:DeepSeek"
@Test void emptyContent_retriesOnce_thenSucceeds()          // WireMock 场景：先返回空内容，再返回正确结果 → 共 2 次请求，第 2 次请求带有纠错 user 消息，2 行 llm_call
@Test void invalidTwice_llmInvalidOutputNonRetryable()
@Test void missingRequiredField_triggersRetry()
@Test void fencedOrPrefixedJson_isExtracted()               // Review Focus 3
@Test void finishLength_truncatedNonRetryable()
@Test void contentFilter_refused()
@Test void unauthorized_configError()
@Test void rateLimitedThenOk_retriedInsideClient()
@Test void rateLimitedAlways_unavailableRetryable()          // 共 3 次请求
@Test void upstreamErrorWithKeyEcho_isRedactedInLedger()     // Review Focus 1：401 响应体中带 "Authorization: Bearer sk-abc…" → llm_call.error 和异常消息中都不出现 sk-abc
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=OpenAiCompatibleProviderClientTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): OpenAI-compatible provider client with local schema validation"`

---

### Task 5: RoutingLlmGateway

**Files:**
- Modify: `platform/llm/RoutingLlmGateway.java`（在 Task 2 的最小版本上补全）
- Test: `platform/llm/RoutingLlmGatewayTest.java`（SpringBootTest；两个 ProviderClient 用 `@MockitoBean` 替换）

**Interfaces:**
- Produces: `RoutingLlmGateway implements LlmGateway` 补全为以下两个方法：
  - `complete(request)`：`route = store.resolve(request.tenantId(), request.purpose())`，然后调用 `completeWith(request, route)`。
  - `completeWith(LlmRequest<T> request, ResolvedRoute route)`（public，Task 6 的测试连接会用到）：
    1. 如果 `!route.supportsImages()` 而 `request.images()` 不为空，抛 `LlmException("LLM_MODEL_NO_VISION", false)`，并写一行 status 为 ERROR 的 `llm_call`，provider 为 `route.providerLabel()`。
    2. 如果路由是内置默认路由，而 key 为空，抛 `LLM_NOT_CONFIGURED`，与 C3 的行为一致。
    3. 按 `route.kind()` 找到对应的 ProviderClient，调用 `complete(request, route)`。
- **客户端缓存**由各个 ProviderClient 自己负责：Anthropic 按 key 的哈希缓存（Task 2）；OpenAI 兼容适配器无状态，或者按 `providerId + updatedAt` 缓存 RestClient。Router 本身不缓存，每次调用都通过 `resolve` 读取最新配置（一次简单的 PK 查询），所以改完设置立即生效。

- [ ] **Step 1: 写失败的测试**

```java
@Test void dispatchesByPurpose_toConfiguredKinds()          // FACT_DRAFT 路由到 Anthropic，COPY 路由到 DeepSeek → 两个 mock 分别收到各自的 route
@Test void noRoute_usesEnvDefaultAnthropic()
@Test void imagesToNonVisionRoute_failsWithoutCallingProvider()   // Review Focus 5（直接改库，把 FACT_DRAFT 路由的 supports_images 设为 false）
@Test void keyRotation_rebuildsClient()                     // Review Focus 2：真实的 AnthropicProviderClient 加 WireMock；更新 provider 的 key 后再调用，x-api-key 变成新值
@Test void defaultWithoutEnvKey_notConfigured()
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `./mvnw -q test -Dtest=RoutingLlmGatewayTest`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**；再运行全部后端测试 `./mvnw -q verify`（先停掉 worker），全部通过
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): vision guard and env-key check in the routing LLM gateway"`

---

### Task 6: 设置 API 与测试连接

**Files:**
- Create: `platform/llm/settings/{LlmSettingsService,LlmSettingsController,LlmPresets,ConnectionTester}.java`、`src/main/resources/llm/vision-probe.png`（64×64 白底，中间一个黑色大写 "K"；在测试中用 Java2D 生成一次后提交）
- Test: `platform/llm/settings/{LlmSettingsServiceTest,LlmSettingsControllerTest,ConnectionTesterTest}.java`

**Interfaces:**
- Produces: `LlmPresets.all() -> List<Preset>`；`record Preset(String code, String name, ProviderKind kind, @Nullable String baseUrl, List<PresetModel> models)`；`record PresetModel(String model, boolean supportsImages, Pricing pricing)`。各项取值见规格 §4，DeepSeek 的价格为 flash 0.30/1.20/0.006、v4-pro 1.32/3.96/0.044。
- Produces: `LlmSettingsService` 的方法与校验规则：
  - `get(CurrentUser) -> LlmSettingsView(providers, routes, presets)`
  - `createProvider(CurrentUser, ProviderInput)`：name 不能为空；kind 为 OPENAI_COMPATIBLE 时 baseUrl 必须满足 Global Constraints，否则返回 422 `LLM_BASE_URL_INVALID`；新增时 apiKey 必填，否则返回 422 `VALIDATION_FAILED`；名称重复返回 409 `LLM_PROVIDER_NAME_TAKEN`。
  - `updateProvider(CurrentUser, id, ProviderInput)`
  - `deleteProvider(CurrentUser, id)`：被路由引用时返回 409 `PROVIDER_IN_USE`。
  - `saveRoute(CurrentUser, LlmPurpose purpose, RouteInput)`：purpose 只能是 FACT_DRAFT 或 COPY，否则返回 400；provider 必须存在；model 不能为空；价格不能为负数；FACT_DRAFT 且 `supportsImages=false` 时返回 422 `ROUTE_REQUIRES_VISION`。
  - `deleteRoute(CurrentUser, purpose)`
  - `record ProviderInput(String name, ProviderKind kind, @Nullable String baseUrl, @Nullable String apiKey)`；`record RouteInput(long providerId, String model, boolean supportsImages, BigDecimal inputPerMtok, BigDecimal outputPerMtok, BigDecimal cacheReadPerMtok)`。
  - 所有写操作都写审计，before 和 after 中都不包含 key。
- Produces: `ConnectionTester.test(CurrentUser, LlmPurpose purpose) -> TestResult(boolean ok, @Nullable String model, long latencyMs, @Nullable BigDecimal costUsd, @Nullable String code, @Nullable String message)`：
  - 先解析 purpose 对应的路由。没有配置时用内置默认路由，同样可以测试。
  - 构造请求：purpose 为 `CONNECTION_TEST`，输出类型为 `record Ping(String answer)`，`maxTokens` 为 1024，effort 为 LOW。
  - FACT_DRAFT 时附带 vision-probe.png，并要求模型 "Which capital letter is shown in the image? Answer with the letter only."；`ok` 的条件是 `answer` 去掉空白后，不区分大小写地包含 "K"，否则 `ok=false`，code 为 `LLM_VISION_CHECK_FAILED`。COPY 时只要求模型回答 "pong"。
  - 调用 `RoutingLlmGateway.completeWith(request, route)`。`LlmException` 转换为 `ok=false` 加上 code 和经过脱敏的 message，**不抛出到 controller**。
- 端点：前缀 `/api/v1/settings/llm`，全部需要 OWNER 权限，路径与规格 §6 完全一致。

- [ ] **Step 1: 写失败的测试**

```java
// LlmSettingsServiceTest
@Test void createOpenAiCompatible_requiresHttpsBaseUrl()
@Test void createProvider_duplicateName_409()
@Test void saveFactDraftRoute_nonVision_422()               // Review Focus 5
@Test void saveRoute_negativePrice_400()
@Test void deleteProviderInUse_409()                        // Review Focus 4
@Test void updateProvider_blankKey_keepsKey()
// LlmSettingsControllerTest（MockMvc）
@Test void nonOwner_403_onAllEndpoints()
@Test void getSettings_andAudit_neverContainKey()           // Review Focus 1：响应体和 audit_log 中都不包含明文 key
@Test void routesListShowsUsingDefault()
@Test void presetsIncludeDeepSeekFlashWithVision()
// ConnectionTesterTest（RoutingLlmGateway 用 @MockitoBean 替换）
@Test void factDraft_sendsProbeImage_okWhenAnswerIsK()
@Test void factDraft_wrongLetter_visionCheckFailed()
@Test void copy_okWithoutImage()
@Test void providerError_returnsOkFalseWithRedactedMessage()
@Test void recordsPurposeConnectionTest()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='LlmSettingsServiceTest,LlmSettingsControllerTest,ConnectionTesterTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): OWNER LLM settings API with presets, validation and connection test"`

---

### Task 7: 前端设置页 `/settings/llm`

**Files:**
- Create: `kiano-web/src/app/(app)/settings/llm/page.tsx`、`src/lib/llmSettings.ts`、`src/lib/llmSettings.test.ts`
- Modify: `src/lib/types.ts`、`src/app/(app)/layout.tsx`（设置菜单中加入 "AI 模型 / AI models"，只对 OWNER 显示）、`src/i18n/{en,zh}.ts`、`src/i18n/i18n.test.ts`

**Interfaces:**
- Produces（`src/lib/llmSettings.ts`）：
  - `applyPresetModel(preset: Preset, model: string): { supportsImages: boolean; inputPerMtok: number; outputPerMtok: number; cacheReadPerMtok: number } | null`：model 不在预设中时返回 null，此时保留用户已经填写的值。
  - `routeWarning(purpose: LlmPurpose, supportsImages: boolean): MessageKey | null`：FACT_DRAFT 且不支持图片时返回 `'llm.warning.visionRequired'`。
- TS 类型 `ProviderView`、`RouteView`、`Preset`、`PresetModel`、`Pricing`、`TestResult` 的字段与 Java 端一致。
- 页面结构与交互按规格 §6：
  - 提供商区：列表，"新增"时先选预设；编辑时 key 输入框为空表示不修改；删除前二次确认。
  - 路由区：两行，模型下拉框中列出该提供商预设里的模型，也可以直接输入；选中预设模型时用 `applyPresetModel` 带出看图能力和价格；`routeWarning` 返回提示时显示红字，并禁用保存按钮；有"测试连接"按钮，显示结果（成功或失败、模型、延迟、成本，失败时显示 `errorText`）；有"恢复默认"按钮（对应删除路由）。
  - 路由显示为 `usingDefault` 时，标注"使用 .env 默认（Claude）"。
- i18n 新增：`llm.*` 页面文案，以及错误码 `PROVIDER_IN_USE`、`ROUTE_REQUIRES_VISION`、`LLM_BASE_URL_INVALID`、`LLM_PROVIDER_NAME_TAKEN`、`LLM_MODEL_NO_VISION`、`LLM_INVALID_OUTPUT`、`LLM_CONFIG`、`LLM_BAD_REQUEST`、`LLM_VISION_CHECK_FAILED`、`LLM_TRUNCATED`。这些都要写入 `i18n.test.ts` 的覆盖清单。

- [ ] **Step 1: 写失败的测试**

```ts
test('applyPresetModel fills vision and pricing for deepseek-flash, null for unknown model')
test('routeWarning only for FACT_DRAFT without vision')
// i18n 覆盖清单加入新的错误码
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `cd kiano-web && pnpm vitest run`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): AI model settings page with providers, per-task routes and connection test"`

---

### Task 8: 文档同步与验收

**Files:**
- Modify: `docs/superpowers/specs/2026-10-07-content-module-design.md`（§15 第 2 项）、`CLAUDE.md`（硬规则）、`README.md`（AI 模型设置）、`.env.example`（注释说明 `ANTHROPIC_API_KEY` 只用于默认路由）

**Interfaces:**
- 文档改动按规格 §10 逐条完成。
- README 增加以下内容：如何在设置页添加 DeepSeek（预设、key、把文案路由切换到 `deepseek-flash`，再测试连接）；按提供商查询成本的 SQL（`select provider, purpose, sum(cost_usd) from llm_call group by 1,2`）；DeepSeek 价格分峰谷时段而预设取高峰价的说明。

- [ ] **Step 1: 更新文档**
- [ ] **Step 2: 运行全部测试**　Run: `cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm lint && pnpm build`（先停掉 worker）
- [ ] **Step 3: 在真实环境中验收（需要用户提供 key）**：执行 `docker compose --profile app up -d --build`，然后以 OWNER 身份打开 `/settings/llm`：
  1. 两个路由都显示"使用 .env 默认（Claude）"。如果 `.env` 中有 Anthropic key，测试连接结果为 ok。
  2. 用 DeepSeek 预设新增一个提供商，key 由用户在页面中输入，实施者不经手。把文案路由设为 `deepseek-flash` 并测试连接，结果为 ok。事实草稿路由选 `deepseek-v4-pro` 时，看图勾选框不能勾选，保存被拒绝；改选 `deepseek-flash` 后测试连接，视觉检查通过。
  3. 对一个已经锁定事实的商品重新生成文案，6 项文本都进入审核看板；`llm_call` 中 provider 为 `OPENAI_COMPATIBLE:DeepSeek`，成本合理。
  4. 删除文案路由，恢复为默认；被路由引用的提供商不能删除。
  - 没有 key 时，这一步记为"待用户执行"，自动化测试已经覆盖了逻辑。
- [ ] **Step 4: Commit** — `git commit -m "docs: configurable LLM providers in spec, CLAUDE.md and README"`

---

## Self-Review 记录

- **规格覆盖**：
  - §4 数据模型、预设和内置默认路由：Task 1、6。
  - §5 路由与两个适配器：Task 2、4、5。
  - §5.2 的全部细节（schema 附在 system 中、图片在前、重试一次、finish_reason 和 HTTP 映射、成本含缓存命中）：Task 3、4。
  - §6 设置 API、测试连接和界面：Task 6、7。
  - §7 错误处理表的每一行，在 Task 4、5、6 中都有对应的测试。
  - §8 测试策略：分布在各个任务中。
  - §10 文档同步：Task 8。
  - §9 不在范围内的事项：本计划都没有实现。
- **类型一致性**：
  - `ResolvedRoute`、`Pricing`、`ProviderKind` 在 Task 1 定义，Task 2、4、5、6 使用。
  - `ProviderClient` 在 Task 2 定义，Task 4 和 5 使用。
  - `RoutingLlmGateway.completeWith` 在 Task 5 定义，Task 6 使用。
  - `LlmCallRow.provider` 在 Task 1 加入，所有的 recorder 调用处都同步补上。
  - 前端类型在 Task 7 与 Task 6 的返回结构一一对应。
- **每个任务都能独立验证和提交**：Task 2 在去掉 Anthropic 网关上的 `LlmGateway` 的同时，就建好最小版本的 `RoutingLlmGateway`（按 kind 分派），所以 Spring 上下文和全部测试在每个任务结束时都能运行。Task 4 新增的 OpenAI 兼容适配器作为 `ProviderClient` bean 被自动注册；Task 5 只补看图检查和未配置 key 的处理。
