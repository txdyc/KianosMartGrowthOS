# Kiano Growth OS

KianosMart（Ghana 的 WooCommerce 店铺，专营 Morgan Electronics，约 150 个 SKU）的增长操作系统。
**WooCommerce 是唯一的交易主系统**，Kiano 是它之上的数据与自动化层。当前阶段（Phase 1a，Sprint C1–C6）
只开发物料生成模块（content），在笔记本本地运行。

## 前置条件

- Docker Desktop（PostgreSQL、MinIO、全容器运行都依赖它）
- JDK 21（后端本地开发）
- Node 22 + pnpm 10（前端本地开发；`corepack enable` 即可）
- 本地开发后端需要 ffprobe：`winget install Gyan.FFmpeg`（全容器方式不需要，镜像内已含）
- **worker 需要 ffmpeg 与 ffprobe**（C5 视频合成本地执行）：同样来自 `winget install Gyan.FFmpeg`，
  路径可用 `KIANO_FFMPEG_PATH` / `KIANO_FFPROBE_PATH` 覆盖
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

## 广告静态图（C4a）

HERO 商品在事实锁定后：文案（overlay/headline/primaryText）由 LLM 按 4 个 hook 生成进入审核看板；
**价格/划线价、COD/MoMo/配送 badge、保修一律由模板渲染**（数据来源：Woo 镜像 + 政策设置分区 badge + 锁定事实），
LLM 永不写这些文字。审核通过后渲染成 12 张图（4 hook × 1:1/4:5/9:16），9:16 符合安全区（y∈[269,1248]）。

- **文案**：商品页 → Ads 面板 → 生成文案 / 单 hook 重新生成（审核看板也支持编辑与 REGENERATE）。
- **渲染**：Ads 面板 → 渲染单 hook；demo 的底图自动取 V1 视频最清晰帧。
- **导出**：顶部导航 **广告 Ads**（`/ads`）→ 选择 HERO 商品（或全部）→ 导出；
  后台生成 ZIP（12 张图 + `manifest.csv`，UTF-8 BOM + RFC4180），完成后可下载；缺图的 SKU 列在 skipped。
- **改价**：Woo 改价同步后 pricehook 只读价三张自动 STALE 重渲染；只有价格变化且基础要素一致时自动通过审核，
  并出现在“替换清单”里（旧文件 + 新文件一并列出，可复制文件名到广告平台替换）。

## 短视频（C5）

HERO 商品在事实锁定后可以生成 3 条 1080×1920 竖屏短视频（demo、problem、unboxing，15–30 秒）：

1. 商品页 **Videos 面板 → 生成脚本**：LLM 按商品写 3 组脚本（hook ≤ 40 字，字幕每条 ≤ 42 字）进入审核看板，逐条通过。
2. **生成视频**：选实拍时间窗、渲染字幕/结尾卡 PNG、选配乐，然后由 worker 的 FfmpegExecutor 合成。
   前提：V1/V2/V3 已验收、审核通过的 PAGE_MAIN/PAGE_SCENE、政策 badge 完整、曲库里有启用曲目；
   缺什么会在面板上逐条列出（409 `VIDEO_PRECONDITIONS`）。
3. **审核**：审核看板选“视频”过滤，逐条播放（可在有配乐 / 无配乐之间切换）；展开“调整剪辑”可改每段实拍的入点/出点，
   保存后按新剪辑重新合成新版本。AI 占比超过 30% 的剪辑无法保存，合成完成的超标视频直接记为 REJECTED。

- **结构**：hook（3 秒）→ body（实拍演示）→ captions（卖点字幕，5 秒）→ 结尾卡（4 秒，价格/COD/MoMo/"Order on WhatsApp"）。
  文字一律由模板渲染成 PNG 叠加；实拍段保留原声，结尾卡和 H3 段静音，配乐循环并在最后 1.5 秒淡出，响度归一化到 -14 LUFS。
- **曲库**：**设置 → 曲库**（OWNER）上传，必须填写许可证类型/来源/证明；只接受 `CC0`、`ROYALTY_FREE_COMMERCIAL`、`OWNED`
  （CC-BY 这类需要署名的许可不允许）。曲目只能停用，不能删除。上传上限 20 MB、10–300 秒（mp3/m4a/wav）。
- **文件名**：`{SKU}_{type}_{real|mixed}_1080x1920_v{n}.mp4`；每条视频同时输出 `_nomusic` 无配乐版本供 TikTok 使用平台商用曲库。
- **改价**：Woo 改价后视频变 STALE，只重渲染结尾卡；只有价格变化时自动通过，剪辑或脚本有变化则进入审核；替换清单会同时列出视频。
- **导出**：Ads 页面导出时勾选“包含短视频”，ZIP 中每个 SKU 多 6 个文件（3 条 mp4 + 3 条 `_nomusic.mp4`），manifest 按文件补行。
- **H3 开关与预算**（`kiano.content.video.h3.*`）：
  - `backend`：`LOCAL_FIRST`（默认）/ `LOCAL_ONLY` / `API_ONLY` / `OFF`；本地要求注册并激活 `H3_I2V` 工作流（许可证白名单照常校验）。
  - `api.enabled`：默认 `false`；接口先用 WireMock 回放验证（C5 计划附录 A 是拿到 API 文档和 key 后的真实对接）。
  - `api.price-per-second-usd` 与 `api.daily-budget-usd`（默认 10 美元/天）：超出预算的片段改用实拍，AI 占比归零。
  - `local.local-max-wait`（默认 2 小时）/ `api.max-wait`（默认 15 分钟）：本地和接口等待超时后升级到下一级或实拍，视频不会因为 H3 卡住。

## STANDARD 批量处理（C6）

把约 135 个 STANDARD SKU 编入批次，系统自动推进，人只在两处介入（事实锁定、物料审核）：

1. **新建批次**：顶部导航 **批次 Batches**（`/batches`）→ 左侧是批次列表，右侧勾选候选 SKU（可按关键字筛选、只看实拍齐全的、全选筛选结果），
   可以勾选“GPU 任务仅在夜间窗口运行”；创建后进入看板。
2. **事实确认工作台**：看板 → **进入事实确认**（`/batches/{id}/facts`）。键盘：`Enter` 全部确认并锁定（6 项必填字段都有值才可用）、
   `J`/`K` 切换字段、`E` 编辑当前字段、`S` 跳过、`?` 帮助；左侧是 P5 铭牌和宣传图（可切换、点开大图）。
3. **物料审核工作台**：看板 → **进入物料审核**（`/batches/{id}/review`）。键盘：`←`/`→` 切换物料、`空格` 通过、`X` 驳回（数字 `1–7` 选原因）、
   `G` 重新生成、`A` 通过剩余全部、`N` 下一个 SKU、`?` 帮助。带预检标记的物料排在最前并高亮。
4. **自动推进**：实拍齐全的 SKU 自动启动图片流水线（同时最多 `kiano.batch.max-active-pipelines=3` 条）、自动生成事实草稿；
   物料全部通过后系统以 SYSTEM 身份自动发布到 staging。
5. **批量发布生产**：只有 OWNER 能在看板上勾选“可发布生产”的 SKU，二次确认后批量发布；发布任务之间按 `publish-interval`（默认 10 秒）错开，
   每个 SKU 照常经过 staging 闸门。

- **限流与优先级**：交互任务优先级 0、批次任务 10（批次流水线的下游任务继承优先级、`batch_id` 与夜间标记）；审核页“重新生成”永远是交互任务。
- **夜间窗口**：`kiano.batch.night-window=22:00-07:00`、`zone=Africa/Accra`；勾选夜间模式的批次，窗口外不领取它的 GPU 任务（交互任务照常领取）。
- **卡住的项目**：看板逐条列出原因。`STAGING_PRODUCT_MISSING` 表示本地 staging 里没有这个 SKU，用 WooCommerce 自带流程补：
  **WooCommerce → Products → Export** 导出生效商品（CSV），再到本地 staging 店铺 **Products → Import** 导入这份 CSV（按 SKU 新建商品即可），
  然后点看板上的“重试 staging”。`PUBLISH_FAILED` 也可点“重试 staging”，下一轮编排器会重新发布。
- **审核时间**：两个工作台只累计**有效操作时间**（每 15 秒心跳一次，单次最多累加 20 秒；与上次心跳间隔超过 60 秒的补发增量按 0 计，页面不可见时不计时）。
  看板显示每个 SKU“事实确认 + 物料审核”的平均值、P90 与目标 180 秒；不足 5 个 SKU 时显示“数据不足”。

## 文档

- `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`：当前架构
- `docs/superpowers/specs/2026-10-07-content-module-design.md`：物料生成模块规格（C1–C6）
- `docs/superpowers/plans/`：各 Sprint 实施计划
