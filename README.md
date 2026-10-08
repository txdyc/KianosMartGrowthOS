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
cd kiano-api && ./mvnw -q verify       # 后端全部测试：*Test（surefire）+ *IT（failsafe），需要 Docker
cd kiano-api && ./mvnw -q test -Dtest=ClassName   # 单个测试类
# 真实 ComfyUI 链路测试（需 ComfyUI 在 8188、GPU 空闲；默认被 comfy-live 标签排除）
KIANO_COMFY_LIVE=1 ./mvnw -q test -Dtest=ComfyLiveIT -Dsurefire.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
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

## 发布到 WooCommerce（C3）

### LLM 配置与成本（可配置提供商，2026-10-08 起）

- **默认路由**：没有设置路由的任务继续用 `.env` 的 `ANTHROPIC_API_KEY`（只读环境变量，
  **不入库**）和模型 `claude-opus-5-5`（Anthropic，支持看图，可服务端拒答回退）。
- **自定义提供商**：以 OWNER 登录后进入 **设置 → AI 模型（`/settings/llm`）**，可新增
  Anthropic 或 OpenAI 兼容提供商（预设含 DeepSeek），并分别选择“事实草稿”（必须能看图）
  和“文案”的提供商与模型。提供商 key 用 AES-GCM 加密入库；页面上 key 输入框留空表示
  保留已存的 key。改完立即生效。
- **DeepSeek 示例**：设置页用 **DeepSeek 预设**新增提供商（页面填入 key，Base URL
  自动带出 `https://api.deepseek.com`）。在“任务路由 → 文案”行选择该提供商、
  模型选 `deepseek-flash`（看图/价格自动带出），然后点**测试连接**验证；事实草稿行选择
  `deepseek-v4-pro` 时不支持看图，会被拒绝保存。
- 预设价格取 **DeepSeek 高峰价**（DeepSeek 按峰谷时段计价，这里用保守的高峰价，不入库）。
- 每次调用都写一行 `llm_call`（含 `provider` 列：`ANTHROPIC` 或
  `OPENAI_COMPATIBLE:{name}`）。查询成本：

  ```sql
  -- 按提供商与用途汇总
  select provider, purpose, round(sum(cost_usd), 4) as cost_usd
  from llm_call group by 1, 2 order by 3 desc;

  select purpose, count(*), round(sum(cost_usd), 4) as cost_usd
  from llm_call group by 1 order by 2 desc;
  ```

### 操作顺序（一个 SKU）

1. **事实**：商品页 → Facts → “AI 生成草稿”（Claude 看 P5 铭牌照和 PROMO 宣传图），
   人工核对每个字段的来源徽标，勾选 6 项确认后**锁定**。
2. **文案与制图**：锁定会归档旧文案并自动生成 6 种文案（标题、短/长描述、SEO、
   Google Shopping、WhatsApp）、PAGE_INFO 信息图与 PAGE_SPEC 参数图，全部进入审核看板
   （`/review`，支持 E 编辑文本）。
3. **审核**：逐一通过；预检标记（FACT_MISMATCH 等）会显示在卡片上。
4. **店铺政策**：设置 → 店铺政策（OWNER）填写 5 个分区。未填完整时文案照常生成和
   审核，但**不允许发布**。
5. **发布**：商品页 → 发布 → 先 **Staging**（成功后再能点生产），然后**生产**
   （仅 OWNER）。发布前自动检查前置条件。

### Staging 配置（本地 Docker 店铺）

1. 本地 Woo 店铺（KianosMart 仓库）里创建 Application Password（Shop Manager 用户），
   步骤同上面“连接 KianosMart 本地 WooCommerce”，Base URL 填
   `http://host.docker.internal:8080`。
2. 设置 → 集成 → Staging 区块填入并测试连接。
3. 生产区块填真实站的凭据。

### 生产发布前的检查清单

1. 生产环境的 Shop Manager Application Password 由用户在 WordPress 后台创建，
   只在设置页中输入，不写进任何文件。
2. Cloudflare 的 WAF 要对 Kiano 服务器的 IP 放行 `/wp-json/wc/` 和
   `/wp-json/wp/v2/media`。
3. 生产站已启用 Rank Math（写入 `rank_math_title` / `rank_math_description`）。

### 回滚

- 每次发布先保存商品快照；中途失败自动按快照恢复并删除本次上传的媒体，publication
  记为 FAILED；若恢复也失败会标记 `needsAttention`（界面红色提示）。
- 发布后手动回滚：发布历史里点“回滚”。如果 Woo 后台在这之后改过该商品，需要
  二次确认“强制回滚”（`WOO_CHANGED_SINCE_PUBLISH`）。
- 政策或事实变更后，长描述自动重渲染（政策变更不调用 LLM），商品进入
  “需要重新发布”列表（顶部导航徽标可见）。

## 文档

- `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`：当前架构
- `docs/superpowers/specs/2026-10-07-content-module-design.md`：物料生成模块规格（C1–C6）
- `docs/superpowers/plans/`：各 Sprint 实施计划
