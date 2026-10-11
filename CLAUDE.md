# Kiano Growth OS

KianosMart（Ghana 的 WooCommerce 店铺，专营 Morgan Electronics，约 150 个 SKU）的增长操作系统。
**WooCommerce 是唯一的交易主系统**，Kiano 是它之上的数据与自动化层。当前阶段（Phase 1a，Sprint C1–C6）
只开发物料生成模块（content），在笔记本本地运行。

店铺基础设施在另一个仓库 `D:\GHANA\claude\KianosMart`，那个仓库有自己的 CLAUDE.md 和规则。

## 文档（先读这些）

- `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`：**当前架构**（v1.0 / v1.1 只作历史参考）
- `docs/superpowers/specs/2026-10-07-content-module-design.md`：物料生成模块规格（C1–C6）
- `docs/superpowers/plans/`：各 Sprint 的实施计划，每个 Sprint 单独一份
- `docs/HANDOFF-*.md`：会话交接记录

## 技术栈

- `kiano-api`：Spring Boot 4 + Java 21（Jackson 3：`tools.jackson.*`），模块化单体（Spring Modulith 校验边界），MyBatis-Plus，Flyway
- `kiano-web`：Next.js（App Router）+ TypeScript + Tailwind，pnpm，Vitest
- PostgreSQL 16（同时用作任务队列，`FOR UPDATE SKIP LOCKED`）、MinIO（S3 兼容，用 AWS SDK v2 访问）
- C2 起：`kiano-worker`（同一代码库，profile=worker）在笔记本上拉取 GPU 任务；ComfyUI 原生装在 Windows 上
  （RTX 4090 Laptop，**16 GB 显存**），不进 Docker；C5 起视频片段优先用本地 H3（`H3_I2V` 工作流，经 worker 的 ComfyUIExecutor），不可用时退到 MiniMax H3 接口（api 内的 H3JobRunner），两者都不可用时用实拍代替

## 目录结构

```
kiano-api/src/main/java/com/kiano/
  platform/   开放模块：auth、web(错误格式)、audit、crypto、integration、queue、storage
  commerce/   Commerce Port、Woo 适配器、商品同步；根包 = 对外 API
  content/    分层、拍摄清单、实拍导入与质检、补拍清单（之后加生成、审核、发布）
kiano-api/src/main/resources/db/migration/   Flyway 迁移（V1 platform、V2 commerce、V3 content…）
kiano-web/src/app/   页面；kiano-web/src/lib/   API 客户端和纯函数（有单元测试）
docker-compose.yml   默认只启动 postgres + minio；--profile app 再加上 api + web
```

## 命令

```bash
docker compose up -d                                  # 启动 postgres(5433) + minio(9000/9001)，需先启动 Docker Desktop
cd kiano-api && ./mvnw spring-boot:run                # api，端口 8081，会读取 ../.env
cd kiano-api && ./mvnw -q verify                      # 后端全部测试：*Test + *IT（failsafe），需要 Docker；worker 运行时先停掉它（jar 被锁）
cd kiano-api && ./mvnw -q test -Dtest=ClassName       # 单个测试类
cd kiano-web && pnpm dev                              # web，端口 3000
cd kiano-web && pnpm vitest run && pnpm lint && pnpm build
docker compose --profile app up -d --build            # 全容器运行
./scripts/run-worker.ps1                              # kiano-worker，Windows 原生（读 .env 的 KIANO_WORKER_TOKEN，jar 缺失时自动构建）
```

本地联调 Woo 时使用 KianosMart 的本地 Docker 环境（`http://localhost:8080`），步骤见 README。

## 硬规则

**架构**
- WooCommerce 是唯一的交易主系统。Kiano 不自建订单，不发起收款，**不接管 Paystack webhook**。对 Woo 的写操作只经由 Commerce Port。
- 不引入 RabbitMQ、Quartz、ClickHouse，不做多 Agent 编排。异步任务用 PostgreSQL 队列表 + Spring Scheduler。
- 模块之间只通过应用服务接口调用。`ModularityTests` 必须通过。`commerce`/`content` 只能使用其他模块根包里的类型。
- 除 `tenant`、`shot_requirement`、`product_category` 外，所有表都有 `tenant_id`，查询要显式带上 tenant_id（不使用 MyBatis-Plus 多租户插件）。时间存 `timestamptz`（UTC，JVM 默认时区为 UTC）。外部 ID 唯一约束为 `(tenant_id, external_id)`。
- 改表结构只能新增 Flyway 迁移，不修改已经提交的迁移。
- API 前缀为 `/api/v1`，错误统一为 `{ code, message, traceId, details }`，业务错误抛 `ApiException`。角色只有 OWNER ⊃ OPERATOR ⊃ VIEWER。

**物料原则**（规格 §3）
1. AI 只改环境，不改产品：产品像素来自实拍抠图，不允许 AI 重画产品的外形、按钮或 Logo。
2. 价格、COD、MoMo、配送、参数等文字一律用 HTML/CSS 模板渲染，不让图像模型画字。价格从 Woo 实时读取，显示格式为 `GH₵ 299`。
3. 事实表锁定后才能生成文案、信息图和参数图。
4. 视频中 AI 生成的时长占比 ≤ 30%，超过的视频不进入审核。
5. 每个物料都记录来源、事实版本、模板或工作流版本、模型和 seed，可追溯、可复现。重新生成总是产生新版本，不覆盖旧版本。

**图片流水线（C2，规格 §4/§5/§12）**
- kiano-worker 与 ComfyUI 在 Windows 上原生运行，不进 Docker；GPU 任务**单并发**，每个任务记录 `gpu_seconds`。
- worker 用 bearer token 访问 `/api/v1/worker/**`：api 只存 SHA-256（`KIANO_WORKER_TOKEN_SHA256`），明文只在 `.env`（`scripts/run-worker.ps1` 读取）。
- ComfyUI 工作流和模型绑定是**数据**（`comfy_workflow` + manifest），注册时校验输出契约与商用许可证白名单（MIT、Apache-2.0、BSD-2/3-Clause、CreativeML-OpenRAIL++-M）；换版本 = 注册 + 激活，不改代码。
- 白底主图、多角度图、开箱图是确定性合成（Java2D，不过 AI）；抠图（CUTOUT）和场景图（SCENE）走 ComfyUI。
- 执行器不可用或租约超时：任务转 `WAITING_EXECUTOR`（不增加 attempts），恢复后自动继续；worker 停止 90 秒后其租约同样回收。
- 预检（precheck）只标记排序和提示，不直接拦截（视频 AI 占比除外）；预检与场景图的阈值在配置里，样板阶段标定。

**安全与许可证**
- 机密只放在 `.env`（不提交），`.env.example` 记录所有键名。集成凭证用 AES-GCM 加密后入库。凭证不能出现在日志、API 响应或 `audit_log` 中。
- 所有人工、系统和 AI 的写动作都写 `audit_log`。
- 模型、字体、音乐必须允许商用（例如 BRIA RMBG 是非商用许可，不能用；BiRefNet 是 MIT 许可）。许可证记录在 `comfy_workflow.model_refs` 中。字体只用 OFL 许可。
- 发布到 Woo 必须先在 staging 验证，再上生产（KianosMart 仓库的规则）。

**C3 内容生成与发布（facts → copy → publish）**
- LLM 出口统一走 `platform/llm` 的 `LlmGateway`（唯一的实现是 `RoutingLlmGateway`：按 `LlmRequest.purpose` 解析 `llm_route`，未配置的用途用 `.env` 的 Claude 默认，默认模型 `claude-opus-5-5`），每次调用写 `llm_call`（含 `cost_usd` 与 `provider`）。提供商 key 只以 `CredentialCipher` 密文入库（`llm_provider`）；API 响应、`audit_log` 和错误信息中都不能出现明文 key。
- Opus 5.5 不能关闭 thinking（不发 `ThinkingConfigDisabled` / `budgetTokens`）——**仅适用于 Anthropic 适配器**；OpenAI 兼容提供商的输出必须经过本地 JSON Schema 生成与校验（`OutputSchemas` / `OutputValidator`，不合格重试一次）；拒绝（refusal）与截断（max_tokens）不可重试。
- FACT_DRAFT 路由必须支持看图（保存时 422 `ROUTE_REQUIRES_VISION`，调用时再检查一次 `LLM_MODEL_NO_VISION`）。
- **LLM 输出必须转义**：模板只用 JMustache `{{ }}`，禁止 `{{{ }}}`；LLM 只产纯文本片段，参数表/政策块/HTML 结构由模板确定性渲染。Woo 的 `name` 与 Rank Math meta 写纯文本，`description` 用转义后的 HTML。
- 事实先锁定（G2，6 项确认）再生成文案/INFO/SPEC；锁定新版本时旧版本按 DRAFT/IN_REVIEW/APPROVED→ARCHIVED、PUBLISHED→STALE 处理。
- 政策文本只来自设置页（5 个分区）；政策未填完整时文案可生成和审核，但发布返回 `POLICY_INCOMPLETE`。
- 发布写入（CommercePublisher）保存快照后可回滚；生产发布必须同商品同资产集已在 staging APPLIED（否则 409 `STAGING_REQUIRED`）；回滚检测 Woo 侧修改（`WOO_CHANGED_SINCE_PUBLISH`，除非 `force`）。

**C4a 广告静态图（2026-10-08，规格 §7.3/§7.5/§10.2/§10.3）**
- **广告图上除了文案（overlay/headline/primaryText）一切文字来自数据**：价格与划线价/Ends 由 `PriceDisplay`（Woo 镜像 + 渲染时刻判断促销是否进行中）渲染，COD/MoMo/配送来自政策设置分区的 `badge`，保修来自锁定事实；LLM 只写三字段，输出含价格即标记 `PRICE_IN_COPY`。
- 广告文案走 `AD_COPY`（TEXT 资产，variant=hook），必须 APPROVED 才能渲染；demo 底图自动取 ACCEPTED 的 V1 视频最清晰帧（`FrameExtractor`），审核 REGENERATE 时换下一个候选帧。
- 渲染成 12 张 `AD_STATIC`（价格不由 LLM 参与）；9:16 文字必须落在 y∈[269,1248]，装不下缩小字号最多两档，仍越界标记 `TEXT_OUTSIDE_SAFE_AREA` 但保存。
- 只有 pricehook 的 3 个尺寸 `depends_on_price=true`；Woo 改价后这些版本 STALE/重渲染，新旧模板版本、AD_COPY、底图均相同且模板仍 APPROVED 时自动通过（审计 `AD_AUTO_APPROVED_PRICE_CHANGE`），否则进 IN_REVIEW。
- 导出 = 每 SKU 12 张全 APPROVED 才打包（`manifest.csv` 严格列序 + UTF-8 BOM + RFC4180），缺图 SKU 进 `skipped`；替换清单只列出导出过且已 STALE 的旧版。
- 硬规则重述：**价格/COD/MoMo/配送/保修一律模板渲染，LLM 永不输出这些文字。**

**C5 短视频（2026-10-09，规格 §7.4/§9.3/§11/§12）**
- **视频上的一切文字由模板渲染成 1080×1920 的 PNG 再叠加**（hook 句、卖点字幕、结尾卡的价格/COD/MoMo/"Order on WhatsApp"），禁止 ffmpeg `drawtext`/libass；字幕 PNG 透明、结尾卡不透明，9:16 安全区与 C4 相同（y∈[269,1248]）。
- **AI 占比在保存和完成两处拦截**：`ai_ratio = H3 片段秒数 ÷ 视频总秒数`，规划与手动保存 EDL 时返回 422 `AI_RATIO_EXCEEDED`；合成完成后超标的资产直接记为 REJECTED（审计 `VIDEO_BLOCKED_AI_RATIO`），不进入审核。
- **H3 只做开场的运镜和氛围**：首帧只能是已审核通过的 PAGE_SCENE / PAGE_MAIN，提示词由版本化模板 `H3_PROMPT_*` 确定性生成（I2VA 结构，两个后端共用同一份），**不出现人手、不演示功能、不出现文字**；H3 自带音频一律丢弃。
- **H3 失败时依次升级**：本地（worker 的 ComfyUIExecutor 执行注册并激活的 `H3_I2V`，`h3.backend` 默认 `LOCAL_FIRST`）→ 接口（api 的 H3JobRunner，`h3.api.enabled=false` 时跳过；接口先用 WireMock 回放验证，真实对接见 C5 计划附录 A）→ 实拍代替（AI 占比归零，`h3.attempts` 与 `h3Fallback.reason` 记录全过程）。视频不会因为 H3 而卡住。worker **不能**领取 `executor=H3` 的任务（接口侧）。
- **曲库许可证白名单**：`license_type` 只能是 `CC0`、`ROYALTY_FREE_COMMERCIAL`、`OWNED`，`license_source` 和 `license_proof` 必填；需要署名的许可（CC-BY）不允许上传（广告中无法署名）；曲目只能停用（RETIRED）不能删除，因为已有视频的 provenance 引用它们。每条视频同时输出 `_nomusic` 无配乐版本，供 TikTok 使用平台商业曲库。
- 剪辑按配方（hook 3s → body → captions 5s → endcard 4s，总 15–30 秒），审核页可手动调整 REAL 段入/出点后重新合成；改价后只重渲染结尾卡，只有价格变化（脚本、EDL、配乐、模板、H3 都没变）时自动通过（审计 `VIDEO_AUTO_APPROVED_PRICE_CHANGE`），否则进入审核。
- **视频规划不在请求里做**：生成和 REGENERATE 只同步检查前置条件，然后入队 `VIDEO_RENDER` 任务（下载、ffmpeg 打分、渲染字幕都在任务里），结果由 `GET .../videos/render-status` 查看。**调整入出点 = 重剪被编辑的那个版本**（`recut`）：复用它记录的全部输入（含 H3 片段、字幕、结尾卡、配乐）和来源记录，不重新下载、打分或选曲；越界检查用该段实际的 `sourceMediaId`，源长度未知直接 422。资产的 `provenance.inputs` 记录的是合成任务实际使用的输入。
- **改价重渲染完成时价格已不是当前价格**（被后一次改价超过）：直接丢弃，不产生版本（审计 `VIDEO_PRICE_RERENDER_SUPERSEDED`）。H3 每日预算要算上已提交未完成的片段；H3 接口关闭时，排队中的接口片段以 `H3_API_DISABLED` 失败并改用实拍。成片复制用对象存储的服务端 copy，不经过堆内存。被 AI 占比拦截的视频写一条无审核人的 `asset_review`（REJECT，`AI_RATIO_EXCEEDED`）。
- 文件名 `{SKU}_{type}_{real|mixed}_1080x1920_v{n}.mp4`（含 H3 段为 `mixed`，否则 `real`）；广告导出可选包含视频（ZIP 含 mp4 与 `_nomusic.mp4`，manifest 每个文件一行）。

**C6 批次处理（2026-10-10，规格 §14）**
- **批次只收 STANDARD SKU**（HERO 的广告图与视频逐个处理）；一个商品同一时间只能在一个进行中的批次里。创建批次时混入 HERO 返回 422 `BATCH_HERO_NOT_ALLOWED`。
- **阶段永远从实际状态推导**：source_media / generation_run / product_fact_sheet / asset / publication，唯一的实现是 `BatchStageResolver`（纯函数）；批次项只存编排引用（runId、publicationId），不存阶段——批次之外的人工操作（如商品页锁定事实）批次要能看到。
- **交互任务优先**：交互优先级 0、批次 10，领取顺序 `order by priority, id`；批次流水线的下游任务必须继承 `priority`、`batch_id`、`night_only`；审核页“重新生成”永远是交互任务。夜间窗口（`kiano.batch.night-window` 默认 22:00–07:00，`zone=Africa/Accra`）外不领取批次的 GPU 任务。
- **生产发布始终由 OWNER 确认**：系统只自动发布 staging（`requestSystem`，SYSTEM/BATCH）；批量生产走 `POST /batches/{id}/publish-production`（仅 OWNER），逐 SKU 照常经过 staging 闸门，任务按 `publish-interval` 错开。
- **编排器必须幂等**：每个租户一轮 tick 先取 advisory lock，动作依赖现有防重（`PIPELINE_RUNNING`、任务去重键、`PUBLISH_IN_PROGRESS`）；清空可空列（staging 引用、blocked 原因）要用显式 `set(...null)`，`updateById` 会忽略 null。
- **单个 SKU 出错不影响其他 SKU**：每个批次项在独立事务（`REQUIRES_NEW`）中推进，出错只回滚这一项；业务错误记录它自己的 code 作为卡住原因（如 `COPY_POLICY_OUTDATED`），非业务异常记 `INTERNAL`，审计 `BATCH_ITEM_BLOCKED`；写卡住原因只更新 blocked 两列。看板对快照推导不出的动作错误显示记录下来的原因（文案先找 `batch.blocked.*`，再找 `error.*`）。
- **批次的推进规则**：并发流水线上限在每轮开始前先统计正在运行的；商品页手动启动的流水线由批次接管（adopt）。必需物料被驳回且没有可用版本时卡住为 `ASSET_REJECTED`（不会自己重新生成）；生产发布失败为 `PRODUCTION_FAILED`，OWNER 可在批量生产中重新勾选；批量生产中单个 SKU 的业务错误只进 `rejected`。快照读取器的查询只读取批次内的商品。同一商品不能在一个批次里出现两次（422），并发创建用租户级 advisory lock 串行化。
- **审核时间只累计有效操作时间**：心跳单次 ≤ 20 秒且不超过距上次心跳的实际时间 + 1 秒、与上次心跳间隔 > 60 秒的补发增量按 0 计、页面不可见不计时（隐藏前已累计的时间保留，隐藏时立即补发一次心跳）；心跳读会话时加行锁；只有 FACT 和 ASSETS 两种会话都结束过的 SKU 才计入平均（目标 180 秒/SKU）。

**跨仓库**
- kiano-connector 插件放在 KianosMart 仓库的 `wp-content/plugins/kiano-connector/`，按那个仓库的规则添加 bind mount。
- 不修改 WordPress 核心和第三方插件。本地 Woo 的配置（例如 `WP_ENVIRONMENT_TYPE=local`）用 WP-CLI 写进本地 volume，不改 KianosMart 仓库的文件。

## 开发方式

- 先写测试（TDD）。后端集成测试用 Testcontainers（PostgreSQL、MinIO），Woo 用 WireMock 回放录制的响应。
- 质检和预检的阈值都放在配置里（`kiano.content.qc.*`），在手工样板阶段标定，不写死在代码里。
- Commit 信息采用 conventional commits 格式。

## 协作偏好

- 用户使用中文交流，文档用中文撰写（技术术语保留英文）。代码和注释用英文。
- **界面和拍摄指引是中英双语**：界面文字一律通过 `t(key)` 输出，zh/en 字典键必须一致；拍摄指引存 `guidance_zh` / `guidance_en`；后端 `message` 用英文，界面按 `code` 本地化。
- 重大设计调整先讨论方案和取舍，用户确认后再写入文档。
