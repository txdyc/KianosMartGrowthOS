# Kiano Growth OS — LLM 提供商可配置与按任务路由 设计规格

| 项目 | 内容 |
|---|---|
| 日期 | 2026-10-08 |
| 状态 | 设计已确认（三部分逐段确认），待评审规格 |
| 上级文档 | `docs/superpowers/specs/2026-10-07-content-module-design.md`（§15 第 2 项）、`docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`（§22「AI（文本）：LLM Provider Adapter」） |
| 前序实现 | C3：`platform/llm`（`LlmGateway`、`AnthropicLlmGateway`、`llm_call`） |

---

## 1. 背景与目标

C3 把 LLM 固定为 Claude：`AnthropicLlmGateway` 从 `.env` 读取 `ANTHROPIC_API_KEY` 和固定模型 `claude-opus-5-5`。用户希望自己选择提供商，例如 Anthropic 或 DeepSeek，主要出于成本和可用性考虑。

**目标**
- OWNER 可以在设置页配置多个 LLM 提供商（Anthropic，或兼容 OpenAI 格式的 API，例如 DeepSeek），并为每个任务（事实草稿、文案）分别选择提供商和模型。改完立即生效，不需要重启。
- 调用方（`FactDraftTaskHandler`、`CopyGenerationTaskHandler`）的代码和 `LlmGateway` 接口不变。
- 没有配置路由的任务，继续使用当前的默认行为（Claude 加上 `.env` 中的 key），升级后无需任何配置也能照常工作。

**成功标准**
- 把文案路由切换到 DeepSeek 后，文案生成能产出 6 种文本资产，与使用 Claude 时一样经过预检和审核；`llm_call` 中记录的提供商和成本正确。
- 把事实草稿路由设为不能看图的模型时，保存直接被拒绝。
- 设置页的"测试连接"能用一次真实调用验证 key、模型和看图能力。

## 2. 已确认的决定（2026-10-08）

1. **配置位置**：在设置页中切换，key 加密入库。只有 OWNER 能修改，修改后立即生效。
2. **按任务分配**：事实草稿和文案各自选择提供商和模型。
3. **方案**：两类适配器——Anthropic 原生适配器，以及通用的 OpenAI 兼容适配器（预设 DeepSeek，也可以填写其他 Base URL）——加上按任务路由。DeepSeek 的输出由我们自己做 schema 校验。
4. **不做**提供商之间的自动故障切换（见 §9）。

## 3. DeepSeek 的已知事实（2026-10 官方文档）

| 项目 | 内容 |
|---|---|
| API 格式 | 兼容 OpenAI 格式，Base URL `https://api.deepseek.com`（另有 Anthropic 格式端点，本设计不使用） |
| 模型 | `deepseek-flash`（DeepSeek-V4.1-Flash，**支持图片输入**）；`deepseek-v4-pro`（**不支持图片输入**） |
| 结构化输出 | 只有 `response_format: {type: "json_object"}`，**不强制 schema**；提示词中必须包含 "json"；偶尔会返回空内容 |
| 价格（每百万 token，美元，分峰谷时段） | flash：输入（缓存未命中）0.15 / 0.3，输出 0.6 / 1.2；v4-pro：输入 0.66 / 1.32，输出 1.98 / 3.96 |

第三方的模型和价格会变，所以本设计中模型 ID、看图能力和价格都是**可编辑的数据**，不写死在代码里。

## 4. 数据模型

迁移：新增 `V16__llm_provider.sql`（当前最新为 `V15__publication.sql`）。`llm_call.purpose` 没有 check 约束，新增 `CONNECTION_TEST` 不需要改表。

```sql
create table llm_provider (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  name text not null,
  kind text not null check (kind in ('ANTHROPIC','OPENAI_COMPATIBLE')),
  base_url text,                         -- ANTHROPIC 时为 null
  credentials_encrypted text not null,   -- CredentialCipher，AAD = tenantId:llm:{id}
  status text not null default 'ACTIVE',
  updated_at timestamptz not null default now(),
  unique (tenant_id, name));

create table llm_route (
  tenant_id bigint not null references tenant(id),
  purpose text not null check (purpose in ('FACT_DRAFT','COPY')),
  provider_id bigint not null references llm_provider(id),
  model text not null,
  supports_images boolean not null,
  input_per_mtok numeric(10,4) not null,
  output_per_mtok numeric(10,4) not null,
  cache_read_per_mtok numeric(10,4) not null default 0,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, purpose));

alter table llm_call add column provider text;   -- 例如 'ANTHROPIC'、'OPENAI_COMPATIBLE:DeepSeek'
```

- `OPENAI_COMPATIBLE` 必须填写 `base_url`，而且只接受 `https://`。唯一例外是 local profile 下允许 `http://localhost` 和 `http://host.docker.internal`，用于本地模拟。
- key 的处理与 Woo 凭证完全一致：AES-GCM 加密入库；API 响应中只返回 `hasKey`；日志和 `audit_log` 中都不出现 key。编辑时 key 留空，表示保留原 key。
- **价格和看图能力放在路由上**，提供商表只保存连接信息。两个任务使用同一个模型时，价格要录入两次，这是有意的取舍。
- **兼容回退**：某个 purpose 没有路由时，使用"内置默认路由"：Anthropic，key 取 `.env` 中的 `ANTHROPIC_API_KEY`，模型 `claude-opus-5-5`，支持图片，价格 4 / 20 / 0.20。这与 C3 现在的行为一致。

**预设**（只在设置页"新增"时预填，可以编辑，不入库，作为后端常量）：

| 预设 | kind | Base URL | 模型（看图 / 输入 / 输出 / 缓存命中，$/MTok） |
|---|---|---|---|
| Anthropic | ANTHROPIC | — | `claude-opus-5-5`（是 / 4.00 / 20.00 / 0.20） |
| DeepSeek | OPENAI_COMPATIBLE | `https://api.deepseek.com` | `deepseek-flash`（是 / 0.30 / 1.20 / 0.006）；`deepseek-v4-pro`（否 / 1.32 / 3.96 / 0.044） |
| 自定义 OpenAI 兼容 | OPENAI_COMPATIBLE | 用户填写 | 用户填写 |

DeepSeek 的预设价格取**高峰价**（保守估算），不按时段计算。

## 5. 架构与调用流程

```
FactDraftTaskHandler / CopyGenerationTaskHandler
        │  LlmGateway.complete(LlmRequest)          ← 接口不变
        ▼
RoutingLlmGateway (@Primary)
        │  按 request.purpose 读取 llm_route（无路由时用内置默认路由）
        │  看图检查：request 带图片，而 route.supportsImages 为 false
        │           → LLM_MODEL_NO_VISION（不可重试）
        ▼
ProviderClient（按 provider id 加 updated_at 缓存；key 或配置变更后重建）
   ├─ AnthropicProviderClient       ← 由现有 AnthropicLlmGateway 改造而来
   └─ OpenAiCompatibleProviderClient ← 新写
        │
        ▼
LlmCallRecorder（llm_call 增加 provider 列；成本按路由上的价格计算）
```

- `LlmGateway`、`LlmRequest`、`LlmResult` 和各个 `LlmException` 子类的签名都不变；`LlmResult.model` 返回实际使用的模型。
- `ProviderClient` 是 platform 内部接口：`<T> LlmResult<T> complete(LlmRequest<T> request, ResolvedRoute route) throws LlmException`。`ResolvedRoute` 包含 provider 的 kind、名称、Base URL、明文 key（只在内存中存在）、模型、看图能力和价格。

### 5.1 Anthropic 适配器

沿用 C3 的实现（官方 SDK、服务端 structured output、拒答回退、stop_reason 处理、成本记录），只是 key 和模型改为从 `ResolvedRoute` 读取。"Opus 5.5 不能关闭 thinking、用 effort 控制深度"等规则仍然只在这个适配器内生效。

### 5.2 OpenAI 兼容适配器

- **请求**：`POST {baseUrl}/chat/completions`，鉴权方式为 `Authorization: Bearer {key}`。
  - `messages`：
    - system 消息：模板中的 system prompt 后面追加一段，内容为 `Respond with a single JSON object that conforms to this JSON Schema:` 加上 schema。这同时满足了 DeepSeek "提示词中必须包含 json"的要求。
    - user 消息：content 是一个数组，图片在前，形式为 `{type:"image_url", image_url:{url:"data:image/jpeg;base64,…"}}`；文本在后。
  - 其他字段：`model`；`max_tokens = request.maxTokens`；`response_format: {type:"json_object"}`；`stream: false`。
  - `request.effort` 在这个适配器中被忽略，没有对应的通用参数。
- **schema 生成**：用 `com.github.victools:jsonschema-generator`（Apache-2.0）从输出类型（`FactDraftResult`、`CopyDraft`）生成 JSON Schema，并沿用字段上的 `@JsonPropertyDescription`。生成的 schema 按输出类型缓存。
- **本地校验**：用 Jackson 把回复内容解析为输出类型（允许出现未知字段），然后检查必填字段：record 中没有标注 JSpecify `@Nullable` 的字段不能为 null，`List` 字段不能为 null（允许为空列表）。
- **校验失败时重试一次**：以下三种情况会把同一请求再发一次，并在末尾追加一条 user 消息，说明哪里不合格、要求重新输出：内容为空、不是合法的 JSON、必填字段缺失。第二次仍然失败时，抛出 `LlmException("LLM_INVALID_OUTPUT", retryable=false)`，界面提示用户手工填写。两次调用都分别写入 `llm_call`。
- **finish_reason 映射**：`length` 对应 `LlmTruncatedException`（不可重试，与 C3 修复后的行为一致）；`content_filter` 对应 `LlmRefusedException`；`stop` 时进入解析和校验。
- **HTTP 错误映射**：401、403 对应不可重试的 `LLM_CONFIG`；400、404、422 对应不可重试的 `LLM_BAD_REQUEST`；429、5xx 和连接错误在适配器内退避重试 2 次（1 秒、3 秒），仍然失败时抛可重试的 `LLM_UNAVAILABLE`，由任务队列稍后再试。连接超时 10 秒，读取超时 120 秒。
- **用量与成本**：从 `usage.prompt_tokens`、`usage.completion_tokens` 和 `usage.prompt_cache_hit_tokens`（DeepSeek 特有，没有时为 0）计算。成本 = （输入 − 缓存命中）× 输入单价 + 缓存命中 × 缓存单价 + 输出 × 输出单价，单位为每百万 token。

## 6. 设置 API 与界面

所有端点都需要 **OWNER** 权限，所有写操作都写审计，审计中不包含 key。

| 端点 | 说明 |
|---|---|
| `GET /api/v1/settings/llm` | 返回 `{providers:[{id,name,kind,baseUrl,hasKey,status}], routes:[{purpose,providerId,model,supportsImages,inputPerMtok,outputPerMtok,cacheReadPerMtok, usingDefault}], presets:[…]}`。没有配置路由的 purpose 也会出现在列表中，此时 `usingDefault=true` |
| `POST /api/v1/settings/llm/providers` | 新增提供商：`{name, kind, baseUrl, apiKey}`，key 必填 |
| `PUT /api/v1/settings/llm/providers/{id}` | 编辑提供商；apiKey 为空时保留原 key |
| `DELETE /api/v1/settings/llm/providers/{id}` | 有路由引用时返回 409 `PROVIDER_IN_USE` |
| `PUT /api/v1/settings/llm/routes/{purpose}` | 保存路由。purpose 为 FACT_DRAFT 且 `supportsImages=false` 时返回 422 `ROUTE_REQUIRES_VISION`；价格不能为负数 |
| `DELETE /api/v1/settings/llm/routes/{purpose}` | 删除路由，回到内置默认路由 |
| `POST /api/v1/settings/llm/routes/{purpose}/test` | 用当前路由发一次真实的小请求，返回 `{ok, model, latencyMs, costUsd, code?, message?}` |

- **测试连接**用一个最小的结构化输出类型（例如 `record Ping(String answer)`）发出请求。FACT_DRAFT 的路由额外附带一张 64×64 的测试图（图中有一个大写字母），并要求模型说出这个字母，以此验证模型确实能看图。测试调用同样写入 `llm_call`，purpose 记为 `CONNECTION_TEST`，`LlmPurpose` 需要新增这个枚举值。
- **设置页 `/settings/llm`**（中英双语，只有 OWNER 可见，设置菜单中新增一项"AI 模型"）：
  - 提供商区：列表，"新增"按钮（先选预设，再填名称、Base URL 和 key），编辑、删除。
  - 路由区：两行（事实草稿、文案），每行有提供商和模型下拉框（模型可以手填，也可以从预设中选，选中后自动带出看图能力和价格）、看图勾选框、三个价格输入框、保存按钮和测试连接按钮。
  - 没有配置路由的行显示"使用 `.env` 默认（Claude）"。
  - 事实草稿一行如果没有勾选"支持看图"，直接提示"事实草稿需要能看图的模型"。

## 7. 错误处理汇总

| 情况 | 结果 |
|---|---|
| 事实草稿路由选了不能看图的模型 | 保存时返回 422 `ROUTE_REQUIRES_VISION`；调用时如果仍带图片，抛 `LLM_MODEL_NO_VISION`（不可重试），用于防止库表被直接修改 |
| key 错误或已失效 | `LLM_CONFIG`，不可重试；任务失败，界面提示到设置页检查 |
| DeepSeek 返回空内容或不合格的 JSON | 重试一次，仍失败则 `LLM_INVALID_OUTPUT`，不可重试，提示手工填写 |
| 被内容过滤或拒答 | `LLM_REFUSED`，不可重试 |
| 输出被截断 | `LLM_TRUNCATED`，不可重试 |
| 429、5xx、网络错误 | 适配器内重试 2 次，仍失败时抛可重试的 `LLM_UNAVAILABLE`，交给任务队列退避重试 |
| 删除仍被路由使用的提供商 | 409 `PROVIDER_IN_USE` |
| 没有配置路由，而且 `.env` 中也没有 key | `LLM_NOT_CONFIGURED`，与现在的行为一致 |

## 8. 测试策略

| 层 | 内容 |
|---|---|
| OpenAI 兼容适配器（WireMock） | 请求结构（图片在文本之前、`json_object`、system 中带 schema、Bearer 鉴权）；正常解析和成本计算（含缓存命中）；空内容后重试成功；连续两次不合格得到 `LLM_INVALID_OUTPUT`，并写入两行 llm_call；必填字段缺失时重试；`length`、`content_filter`、401、400；429 重试后成功；429 一直失败得到 `LLM_UNAVAILABLE` |
| 路由层 | 按 purpose 分发到不同的 provider；没有路由时回退到 `.env` 默认；带图片但路由不能看图时报 `LLM_MODEL_NO_VISION`；修改 key 后客户端重建（检查 updated_at）；`llm_call.provider` 填写正确 |
| 设置 API | 非 OWNER 返回 403；GET 和审计中都不出现 key；编辑时 key 留空保留原值；事实草稿选不能看图的模型返回 422；删除被使用的 provider 返回 409；`OPENAI_COMPATIBLE` 缺少 base_url 或使用非 https 时返回 422；测试连接的成功和失败两条路径 |
| 现有测试 | 使用 `FakeLlmGateway` 的测试（事实草稿、文案、政策重渲染等）保持不变；`AnthropicLlmGatewayTest` 改为测试 `AnthropicProviderClient`，断言内容不变 |
| 前端（Vitest） | 选择预设后带出的字段；事实草稿的看图提示逻辑；i18n 覆盖清单加入新增的错误码和字段 |

## 9. 不在范围内

- **提供商之间的自动故障切换**（例如 Claude 失败后自动改用 DeepSeek）。失败时由用户重试或手工填写，不悄悄换模型。原因是不同模型的产出质量不同，悄悄切换会让审核标准前后不一致。
- 按 SKU 或商品品类选择不同的模型。
- 按时段计算 DeepSeek 的峰谷价格。
- 使用 DeepSeek 的 Anthropic 格式端点。
- 流式输出、Batch API。
- 针对不同模型维护多套提示词。两个模板都是与模型无关的英文指令，OpenAI 兼容路径只在末尾追加 schema。

## 10. 需要同步更新的文档

- `docs/superpowers/specs/2026-10-07-content-module-design.md` §15 第 2 项：由"已确认：Claude API"改为"LLM 提供商可配置（Anthropic / OpenAI 兼容，如 DeepSeek），按任务路由，默认 Claude"，并引用本规格。
- `CLAUDE.md` 的硬规则："Opus 5.5 不能关闭 thinking"限定为只在 Anthropic 适配器中适用；新增"OpenAI 兼容提供商的输出必须经过本地 schema 校验"。
- `README.md`：AI 模型设置页的用法，以及 DeepSeek 的配置示例。
