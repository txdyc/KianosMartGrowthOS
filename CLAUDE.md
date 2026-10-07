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
  （RTX 4090 Laptop，**16 GB 显存**），不进 Docker；视频片段用 MiniMax H3 API

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
cd kiano-api && ./mvnw -q verify                      # 后端全部测试（Testcontainers，需要 Docker）
cd kiano-api && ./mvnw -q test -Dtest=ClassName       # 单个测试类
cd kiano-web && pnpm dev                              # web，端口 3000
cd kiano-web && pnpm vitest run && pnpm lint && pnpm build
docker compose --profile app up -d --build            # 全容器运行
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

**安全与许可证**
- 机密只放在 `.env`（不提交），`.env.example` 记录所有键名。集成凭证用 AES-GCM 加密后入库。凭证不能出现在日志、API 响应或 `audit_log` 中。
- 所有人工、系统和 AI 的写动作都写 `audit_log`。
- 模型、字体、音乐必须允许商用（例如 BRIA RMBG 是非商用许可，不能用；BiRefNet 是 MIT 许可）。许可证记录在 `comfy_workflow.model_refs` 中。字体只用 OFL 许可。
- 发布到 Woo 必须先在 staging 验证，再上生产（KianosMart 仓库的规则）。

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
