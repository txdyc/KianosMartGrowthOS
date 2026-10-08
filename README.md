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

## 图片流水线与 kiano-worker（C2）

生成商品图需要三样东西同时在线：api（Docker 里的 `--profile app`）、
ComfyUI（Windows 原生）、kiano-worker（Windows 原生，GPU 任务不进 Docker）。

### 准备（一次性）

1. **worker token**：`.env` 里配一对值，api 只存哈希、worker 拿明文：

   ```powershell
   # 生成 token 并算出 SHA-256
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
   $token = [Convert]::ToHexString($bytes).ToLower()
   $sha = [Security.Cryptography.SHA256]::Create()
   $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token))).Replace("-", "").ToLower()
   $token   # -> .env 的 KIANO_WORKER_TOKEN
   $hash    # -> .env 的 KIANO_WORKER_TOKEN_SHA256
   ```

   改完 `.env` 重建 api：`docker compose --profile app up -d --force-recreate api`。

2. **ComfyUI**：装在 `D:\ComfyUI`，端口 8188（`cd D:\ComfyUI; .\run_nvidia_gpu.bat`）。
   基线工作流需要的模型在 manifest 里声明，首次运行由 ComfyUI 下载；
   许可证必须商用可用（白名单：MIT、Apache-2.0、BSD-2/3-Clause、CreativeML-OpenRAIL++-M，
   例如 BiRefNet 是 MIT 可以用，BRIA RMBG 非商用不能用）。

3. **tesseract（可选）**：api 容器内已自带，只有本地裸跑 api 才需要装：
   `winget install UB-Mannheim.TesseractOCR`。

### 启动 worker

```powershell
.\scripts\run-worker.ps1
```

脚本从 `.env` 读 `KIANO_WORKER_TOKEN`，jar 不存在时自动构建
（`cd kiano-api; .\mvnw.cmd -DskipTests package`），然后以 worker profile 启动
（`-Dloader.main=com.kiano.worker.KianoWorkerApplication`）。
之后在商品页点“生成商品图”即可；ComfyUI 不可用时任务进入 `WAITING_EXECUTOR`，
恢复后自动继续，不消耗 attempts。

注意：worker 以 `java -jar` 方式占用 `kiano-api/target/*.jar`，
**worker 运行期间不能执行 `mvnw verify` / `package`**（Windows 文件锁会让 repackage
失败）。先停掉 worker 再跑构建。

### 工作流版本管理（OWNER）

基线工作流（CUTOUT / SCENE v1）随代码注册。样板阶段定稿后注册新版本即可替换，不需要改代码：

```powershell
# 登录拿会话 cookie
curl.exe -s -c cookie.txt -H "Content-Type: application/json" `
  -d '{\"email\":\"owner@kiano.local\",\"password\":\"<密码>\"}' `
  http://localhost:8081/api/v1/auth/login

# 注册新版本（multipart：workflow = ComfyUI API 格式导出，manifest = 绑定与模型清单）
curl.exe -s -b cookie.txt `
  -F "workflow=@scene-v2.json;type=application/json" `
  -F "manifest=@scene-v2-manifest.json;type=application/json" `
  http://localhost:8081/api/v1/content/workflows

# 激活某个版本
curl.exe -s -b cookie.txt -X POST http://localhost:8081/api/v1/content/workflows/<id>/activate
```

注册时校验 manifest 的输出契约和模型许可证白名单，不合规返回 422。
预检与场景图的各项阈值都在配置里（`kiano.content.qc.*`、工作流参数），
在样板阶段标定，不写死在代码里。

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
