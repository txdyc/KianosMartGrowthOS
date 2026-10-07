# Kiano Growth OS

KianosMart（Ghana 的 WooCommerce 店铺，专营 Morgan Electronics，约 150 个 SKU）的增长操作系统。
**WooCommerce 是唯一的交易主系统**，Kiano 是它之上的数据与自动化层。当前阶段（Phase 1a，Sprint C1–C6）
只开发物料生成模块（content），在笔记本本地运行。

## 前置条件

- Docker Desktop（PostgreSQL、MinIO、全容器运行都依赖它）
- JDK 21（后端本地开发）
- Node 22 + pnpm 10（前端本地开发；`corepack enable` 即可）
- 本地开发后端需要 ffprobe：`winget install Gyan.FFmpeg`（全容器方式不需要，镜像内已含）
- `.env`（不入库，键名见 `.env.example`）

## 全容器运行（推荐）

```bash
docker compose --profile app up -d --build
```

启动 postgres(5433)、minio(9000/9001)、api(8081)、web(3000)。

- 前端：http://localhost:3000 （首次启动会用 `.env` 里的 `KIANO_BOOTSTRAP_OWNER_EMAIL/PASSWORD` 创建 OWNER）
- API 健康：`curl -s localhost:8081/actuator/health` → `{"status":"UP"}`
- API 容器内连 `postgres:5432` / `minio:9000`；浏览器侧地址（presigned URL）仍用 `localhost` 端口映射

只启动基础设施（本地开发时）：`docker compose up -d`

## 本地开发

```bash
docker compose up -d                    # postgres + minio
cd kiano-api && ./mvnw spring-boot:run  # api，8081，读取 ../.env
cd kiano-web && pnpm dev                # web，3000
```

## 测试

```bash
cd kiano-api && ./mvnw -q verify       # 后端全部测试（Testcontainers，需要 Docker）
cd kiano-api && ./mvnw -q test -Dtest=ClassName   # 单个测试类
cd kiano-web && pnpm vitest run && pnpm lint && pnpm build
```

## 连接 KianosMart 本地 WooCommerce

店铺基础设施在另一仓库 `D:\GHANA\claude\KianosMart`（本地 Woo 在 `http://localhost:8080`）。
以下命令只操作该仓库的本地 `wp_data` volume，不修改其文件：

```bash
cd D:\GHANA\claude\KianosMart
docker compose up -d --build
docker compose run --rm wpcli wp config set WP_ENVIRONMENT_TYPE local --type=constant
docker compose run --rm wpcli wp user create kiano-sync kiano-sync@example.test --role=shop_manager
docker compose run --rm wpcli wp user application-password create kiano-sync kiano-local --porcelain
```

- `WP_ENVIRONMENT_TYPE=local` 是必须的：本地 Woo 走 http，否则 WordPress 拒绝 REST API 认证。
- Application Password **只在** Kiano 设置页输入（设置 → 集成），不写进任何文件。
- 如果本地商店没有商品：`wp wc product create --user=1 ...` 创建测试商品（simple/variable 均可）。

连接步骤：Kiano 设置页填入 Base URL、`kiano-sync` 和 Application Password →
Test connection 返回 ok → 商品页 Sync now → 商品、变体、分类、价格同步入库。

Base URL 取决于 api 在哪里运行：

| api 运行方式 | Base URL |
|---|---|
| 全容器（`docker compose --profile app up`） | `http://host.docker.internal:8080` |
| 本地 `./mvnw spring-boot:run` | `http://localhost:8080` |

容器里的 `localhost` 指向 api 容器自己，填 `http://localhost:8080` 会得到
`WooCommerce is unavailable after 3 attempts: I/O error ...`。商品图片地址仍是 Woo 生成的
`http://localhost:8080/...`，浏览器可以正常访问。

## 文档

- `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`：当前架构
- `docs/superpowers/specs/2026-10-07-content-module-design.md`：物料生成模块规格（C1–C6）
- `docs/superpowers/plans/`：各 Sprint 实施计划
