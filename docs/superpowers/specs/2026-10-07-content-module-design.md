# Kiano Growth OS — 物料生成模块（content）设计规格

| 项目 | 内容 |
|---|---|
| 日期 | 2026-10-07 |
| 状态 | 设计已确认，待评审规格 |
| 上级文档 | `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md` |
| 对应 Sprint | C1–C6 |

---

## 1. 背景与目标

KianosMart 每个 SKU 目前只有一张宣传图和实物。网站商品页、Google Merchant / Meta 商品目录、广告投放都缺少物料，这是增长方案落地的第一个瓶颈。因此路线图调整为先建设物料生成模块，再建设利润、广告分析和 WhatsApp 模块。

**目标：** 用"实拍 + 本地 AI 增强 + 确定性模板"的流水线，为约 150 个 SKU 批量生产可审核、可追溯、可复现的商品页和广告物料，并发布到 WooCommerce。

**成功标准：**
- 15 个 HERO SKU：商品页全套物料上线 Woo，并导出广告静态图和短视频包。
- 其余 STANDARD SKU：商品图组和文案上线 Woo。
- 每个已发布物料都能追溯到实拍来源、事实版本、模板或工作流版本。
- 每个 SKU 的审核耗时：STANDARD ≤ 3 分钟，HERO ≤ 10 分钟。

## 2. 范围

### 2.1 分层覆盖

| 层级 | SKU 数 | 实拍 | 商品图组 | 文案 | 广告静态图 | 短视频 |
|---|---|---|:-:|:-:|:-:|:-:|
| HERO | 10–15 | P1–P9 + V1–V3 | ✓ | ✓ | ✓ | ✓ |
| STANDARD | 其余 | P1–P8 | ✓ | ✓ | — | — |

层级保存在 `product_profile.content_tier`，可以随时调整；调整后补齐缺失的物料。

### 2.2 非目标（本模块第一版不做）

- 自动上传素材到 Meta / TikTok / Google 广告平台（只导出 ZIP + CSV）
- 素材投放效果分析（等 v1.2 的 S4 广告分析模块上线后，通过 `ad.creative_asset_id` 关联）
- 纯 AI 生成的产品镜头（产品像素必须来自实拍）
- 多语言文案（第一版只有英语）
- 社交媒体自动发布与排期

## 3. 设计原则

1. **AI 只改环境，不改产品。** 产品像素来自实拍抠图；AI 只生成背景、光影和场景。产品的形状、按钮、Logo 不允许 AI 重画。
2. **文字一律用确定性模板渲染。** 价格、COD、MoMo、配送、参数等文字用 HTML/CSS 模板经无头 Chromium 渲染，不让图像模型画字。价格从 Woo 实时读取。
3. **事实先锁定，后生成。** 文案、信息图、参数图只能引用已锁定的事实表。
4. **实拍标准化。** 固定的拍摄清单让流水线可以批量处理。
5. **真实为主。** 视频中 AI 生成的时长占比不超过 30%（增长方案"70% 真实 / 30% AI"）。
6. **全程可追溯、可复现。** 每个物料记录来源、版本、模型和 seed。

## 4. 架构

### 4.1 组件

```
┌─────────────── Docker Compose（本地阶段：笔记本）───────────────┐
│ kiano-web (Next.js)      实拍上传、事实确认、审核看板、发布       │
│ kiano-api (Spring Boot)                                          │
│   ├ platform : 认证、tenant、任务队列(PG)、审计、对象存储          │
│   ├ commerce : Commerce Port → WooCommerceAdapter                │
│   └ content  : 流水线编排、素材库、审核、发布                     │
│        执行器（api 内）: LlmExecutor, TemplateRenderer, H3Executor │
│ PostgreSQL 16 │ MinIO（S3 兼容）                                  │
└───────────────────────────▲──────────────────────────────────────┘
                            │ HTTPS：租用任务 / 回报结果 / 预签名 URL
┌───────────────────────────┴──────────────────────────────────────┐
│ kiano-worker（同一代码库，profile=worker，笔记本 Windows 上运行）   │
│   执行器: ComfyUIExecutor → ComfyUI (Windows 原生, RTX 4090 16GB)   │
│           FfmpegExecutor                                         │
└──────────────────────────────────────────────────────────────────┘
```

- ComfyUI 原生运行在 Windows 上，不放进 Docker，以避开 WSL2 GPU 直通的复杂度。
- kiano-worker 从第一天起就是独立进程，**只主动拉取任务**。迁移到 VPS 后，worker 从笔记本通过 HTTPS 拉取 VPS 上的任务，笔记本不需要暴露任何端口，执行器代码也不用改。
- 较轻的执行器（LLM、模板渲染、H3 API 调用）运行在 kiano-api 内；需要 GPU 或大量本地文件处理的（ComfyUI、FFmpeg）运行在 worker 内。

### 4.2 Worker 协议

```
POST /api/v1/worker/lease          {workerId, capabilities:[COMFYUI,FFMPEG], max:1}
                                   → 200 {job, inputUrls(预签名 GET), outputUploadUrls(预签名 PUT)} | 204
POST /api/v1/worker/jobs/{id}/heartbeat   每 30 秒；超过 10 分钟没有心跳，租约失效，任务重新排队
POST /api/v1/worker/jobs/{id}/complete    {outputs:[{objectKey, width, height, durationS, sha256}], gpuSeconds}
POST /api/v1/worker/jobs/{id}/fail        {error, retryable}
```

Worker 使用独立的 API token 认证，权限只限 worker 端点。

### 4.3 执行器接口

```java
interface GenerationExecutor {
    ExecutorType type();                       // COMFYUI | H3 | LLM | TEMPLATE | FFMPEG
    JobResult execute(GenerationJob job, ExecutionContext ctx) throws ExecutorException;
}
```

`ExecutorException` 区分可重试（超时、执行器不可用、429）和不可重试（输入无效、内容安全拒绝）两类。

## 5. 流水线

### 5.1 每个 SKU 的任务依赖图

```
[同步商品/分层] ─┐
[实拍导入+质检] ─┼─▶ cutout(P1..P8) ─┬─▶ white_main ──────────────┐
                │                    ├─▶ white_angles              │
                │                    └─▶ scene × N ────────────────┤
[宣传图+P5铭牌] ─┴─▶ fact_draft(LLM) ─▶ ◆事实锁定(人工)◆            │
                                          ├─▶ copy_*(LLM)          │
                                          ├─▶ infographic(TEMPLATE)│
                                          └─▶ spec_image(TEMPLATE) │
       (HERO) scene/white + 事实 + 实时价格 ─▶ ad_static × 12(TEMPLATE)
       (HERO) V1–V3 + white/scene + 事实 ─▶ h3_clips(H3) ─▶ video × 3(FFMPEG + TEMPLATE 结尾卡)
                                          ▼
                              自动预检 → 人工审核 → 发布
```

- 每个节点是一个 `generation_job`；后续任务在前置任务成功后自动入队。
- 事实锁定是唯一的人工阻塞点：锁定之前，文案、信息图、参数图、广告图、视频都不会入队。
- 图像类任务（cutout、white、scene）不依赖事实锁定，可以先跑。

### 5.2 执行器不可用

ComfyUI 无法连接时（例如笔记本合盖），任务标记为 `WAITING_EXECUTOR`，不消耗重试次数；worker 恢复后自动继续。

## 6. 拍摄清单

**通用要求**
- A2 白色或浅灰卡纸做背景；自然光或两盏柔光灯。
- 手机用主摄 1x 镜头，不用广角；锁定曝光；产品占画面约 70%；撕掉保护膜，擦干净灰尘。
- 文件命名 `{SKU}_{镜头编号}.jpg|.mp4`，例如 `MG-BL200_P5.jpg`，支持按文件夹批量导入。

| 编号 | 镜头 | STANDARD | HERO |
|---|---|:-:|:-:|
| P1 | 正面，平视 | ✓ | ✓ |
| P2 / P3 | 左前 45° / 右前 45° | ✓ | ✓ |
| P4 | 侧面 | ✓ | ✓ |
| P5 | 背面 + 铭牌特写（用于核对参数） | ✓ | ✓ |
| P6 | 顶部、开口或内部 | ✓ | ✓ |
| P7 | 控制面板、按钮特写 | ✓ | ✓ |
| P8 | 配件全家福 + 包装盒 | ✓ | ✓ |
| P9 | 尺寸参照（手持或旁边放 1.5L 水瓶） | — | ✓ |
| V1 | 操作演示 10–20 秒，保留原声 | — | ✓ |
| V2 | 360° 慢速环绕 | — | ✓ |
| V3 | 开箱 | — | ✓ |

视频：竖屏 9:16，1080p 或 4K，30fps，用三脚架或稳定器。

**V1 品类演示脚本**（`shot_requirement.guidance_zh` / `guidance_en`，按品类维护）

| 品类 | 演示内容 |
|---|---|
| 搅拌机 | 辣椒、番茄、洋葱 → 打成酱 |
| 电水壶 | 注水 → 烧开 → 自动断电 |
| 电饭煲 / 电煮锅 | 放米 → 启动 → 煮好的米饭 |
| 熨斗 / 挂烫机 | 熨平一件衬衫 |
| 空气炸锅 | 薯条或鸡翅，少油 |
| 风扇 | 开机、调档、摇头 |

**自动质检**：分辨率 ≥ 2000px（短边）；清晰度（拉普拉斯方差）低于阈值判为模糊；过曝或欠曝比例超标。不合格的镜头标记为 `RESHOOT` 并生成补拍清单。阈值在手工样板阶段标定。

## 7. 生成规格

### 7.1 商品页图片（全部 SKU）

| spec_code | 数量 | 规格 | 用途 |
|---|---|---|---|
| PAGE_MAIN | 1 | 1600×1600 JPEG，纯白 #FFFFFF，产品占 80–85%，**无文字、水印、边框** | Woo 主图、Google `image_link`、Meta 目录主图 |
| PAGE_ANGLE | 4–6 | 同上规格 | 图组、Google `additional_image_link` |
| PAGE_SCENE | STANDARD 2 / HERO 4 | 1600×1600，产品像素来自实拍 | 图组，也可用作广告底图 |
| PAGE_INFO | 1–2 | 1600×1600，模板渲染 | 图组后部 |
| PAGE_SPEC | 1 | 1600×1600，模板渲染，数据来自事实表 | 图组 |
| PAGE_INBOX | 1 | 由 P8 处理得到 | 图组 |

每个商品图组合计不超过 10 张，顺序为：MAIN → ANGLE → SCENE → INBOX → INFO → SPEC。

场景图要求：符合 Ghana 家庭语境；电器插头必须是英式 G 型；不出现其他品牌标识。

### 7.2 商品文案（全部 SKU，英语，Ghana 语境）

| spec_code | 规则 |
|---|---|
| COPY_TITLE | `Morgan {型号} {品类} {关键规格} – {核心卖点}` |
| COPY_SHORT | 3–5 条利益点 |
| COPY_LONG | 为什么值得买 + 参数表 + FAQ 3–5 条；配送、COD、MoMo、保修、退货部分插入 POLICY_BLOCK 模板 |
| COPY_SEO | title ≤ 60 字符，meta description ≤ 155 字符 |
| COPY_GSHOP | Google Shopping 标题：品牌 + 品类 + 关键属性前置 |
| COPY_WA | WhatsApp 销售短文案，价格用 `{{price}}` 占位 |

约束：
- 数字和规格必须能在锁定的事实表中找到。
- 禁用无法证明的绝对化表述（例如 "best in Ghana"、"100% guaranteed"），禁用词表可配置。
- 金额格式统一为 `GH₵ 299`。
- POLICY_BLOCK 由店铺政策统一渲染，不按 SKU 由 AI 生成。

### 7.3 广告静态图（HERO）

- 4 个 hook：`pricehook`（价格）、`problem`（痛点/解决）、`demo`（功能演示，取 V1 视频帧）、`trust`（COD / MoMo / 配送 / 保修）。
- 3 种尺寸：1080×1080、1080×1350、1080×1920。每个 SKU 共 12 张。
- 9:16 安全区：顶部约 14%、底部约 35% 不放关键文字。
- 价格显示：只显示当前价格。只有存在真实且有截止日期的促销时，才显示划线原价和截止日期（对应增长方案"不要全站 26% OFF"）。
- 底图来自已通过审核的 PAGE_SCENE 或 PAGE_MAIN；文字叠加由 AD_OVERLAY 模板渲染。

### 7.4 短视频（HERO）

- 每个 SKU 3 条：`demo`（演示型）、`problem`（痛点型）、`unboxing`（开箱型），各 15–30 秒。
- 格式：1080×1920，H.264 + AAC，30fps，烧录英文字幕。
- 结构：0–3 秒 hook（H3 开场或最有冲击力的实拍）→ 3–20 秒实拍演示 → 20–25 秒卖点字幕 → 最后 3–5 秒结尾卡（价格、COD/MoMo、"Order on WhatsApp"，由 END_CARD 模板渲染）。
- H3 只用于图生视频的开场、氛围和 B-roll，输入为已审核的白底图或场景图；提示词遵循 h3-prompt-writing 技能规范；不展示没有经过实拍验证的功能。
- `ai_ratio` = AI 生成片段时长 ÷ 总时长；超过 0.30 不允许进入审核。
- 配乐只使用有商用授权的免版税曲库；TikTok 发布时使用平台商用曲库。

### 7.5 文件命名

导出文件名遵循 v1.2 附录 B：`{SKU}_{角度}_{素材类型}_{尺寸}_v{n}.{ext}`，例如 `MG-BL200_pricehook_real_1080x1350_v1.jpg`。

## 8. 数据模型

约定：所有表包含 `tenant_id`，时间存 UTC，外部 ID 唯一约束为 `(tenant_id, external_id)`。

```
product / category / product_category    -- 来自 v1.2 §11，C1 只同步商品相关字段
product_profile    product_id, sku_role, content_tier(HERO|STANDARD), product_dna_json, updated_at

product_fact_sheet id, tenant_id, product_id, version, facts_json, source_refs[],
                   status(DRAFT|LOCKED|SUPERSEDED), locked_by, locked_at, created_at
                   UNIQUE(product_id, version)
    facts_json: { model, category, capacity, power_w, voltage, material, colour,
                  warranty, in_box[], features[], benefits[], forbidden_claims[] }

shot_requirement   id, code, tier, category(可空=通用), required, guidance_zh, guidance_en   -- 中英双语
source_media       id, tenant_id, product_id, shot_code, kind(PHOTO|VIDEO|PROMO_IMAGE),
                   object_key, width, height, duration_s, sha256, qc_json,
                   status(ACCEPTED|RESHOOT), uploaded_by, uploaded_at
                   UNIQUE(tenant_id, sha256)

asset_spec         code, kind(IMAGE|VIDEO|TEXT), tier, width, height, format, pipeline_ref
template           id, tenant_id, code, kind(AD_OVERLAY|INFOGRAPHIC|SPEC|END_CARD|
                   COPY_PROMPT|POLICY_BLOCK|FACT_PROMPT), version, body, status(DRAFT|APPROVED|RETIRED)
comfy_workflow     id, tenant_id, code, version, workflow_json, model_refs[], status

generation_job     id, tenant_id, product_id, asset_spec_code, variant, executor,
                   input_json, parent_job_ids[], status(QUEUED|LEASED|WAITING_EXECUTOR|
                   SUCCEEDED|FAILED|CANCELLED), attempts, max_attempts, lease_owner,
                   lease_expires_at, gpu_seconds, cost_usd, error, created_at, finished_at

asset              id, tenant_id, product_id, spec_code, variant, version,
                   kind(IMAGE|VIDEO|TEXT), object_key, text_body, width, height, duration_s,
                   status(DRAFT|IN_REVIEW|APPROVED|REJECTED|PUBLISHED|STALE|ARCHIVED),
                   precheck_json, provenance_json, ai_ratio, depends_on_price,
                   price_snapshot, fact_version, file_name, created_at
    provenance_json: { source_media_ids[], fact_version, template{code,version},
                       workflow{code,version}, models[], seed, job_ids[] }

asset_review       id, tenant_id, asset_id, reviewer, decision(APPROVE|REJECT|REGENERATE),
                   reason_codes[], comment, created_at
publication        id, tenant_id, product_id, asset_ids[], target(WOO_PRODUCT|AD_EXPORT),
                   external_ref, before_json, after_json, status(APPLIED|ROLLED_BACK|FAILED),
                   published_by, published_at
audit_log          (v1.2 §11 定义)
```

驳回原因代码：`PRODUCT_MISMATCH`、`AI_ARTIFACT`、`WRONG_FACT`、`TEXT_ERROR`、`STYLE`、`LOW_QUALITY`、`POLICY`。

## 9. 状态机与审核

### 9.1 物料状态机

```
DRAFT ──自动预检──▶ IN_REVIEW ──APPROVE──▶ APPROVED ──发布──▶ PUBLISHED
                       │                                       │
                       ├─REJECT──▶ REJECTED                    ├─价格变动/事实新版本/重拍─▶ STALE
                       └─REGENERATE──▶ 新 version(DRAFT)        │                             │
                                                              └─被新版本替换──▶ ARCHIVED     └─▶ 重新生成
```

- 重新生成总是产生新的 `version`，旧版本保留，不覆盖。
- 一个 SKU 每个 `spec_code + variant` 同一时间最多有一个 PUBLISHED 版本。

### 9.2 审核关卡

| 关卡 | 方式 | 通过条件 |
|---|---|---|
| G1 实拍验收 | 自动 | 必需镜头齐全且质检通过 |
| G2 事实锁定 | 人工 | 审核页并排显示事实表草稿、P5 铭牌和宣传图；必须确认型号、容量、功率、电压、保修、包装内容 |
| G3 物料审核 | 先自动预检，再人工 | 每个物料 APPROVE |

### 9.3 自动预检（结果写入 `precheck_json`，用于排序和提示，不直接拦截；视频 AI 占比除外）

| 检查 | 适用 | 方法 |
|---|---|---|
| 产品一致性 | PAGE_SCENE、AD_STATIC 底图 | 在产品掩膜区域内比较输出图与原始抠图的 SSIM，阈值在样板阶段标定（重打光会降低相似度） |
| AI 乱码文字 | AI 生成的图片 | OCR 检测到文字则标记 |
| 主图合规 | PAGE_MAIN、PAGE_ANGLE | 边缘像素为 #FFFFFF；OCR 无文字；产品占比 80–85% |
| 事实一致 | COPY_* | 文案中的数字和单位必须出现在事实表中；命中禁用词则标记 |
| 长度限制 | COPY_SEO、COPY_TITLE | 字符数超限则标记 |
| AI 时长占比 | VIDEO | > 0.30 **拦截**，不进入审核 |

### 9.4 人工审核清单

- 产品外形、颜色、按钮、Logo 与实拍一致
- 无 AI 伪影（多余部件、畸形的手、乱码文字）
- 场景符合 Ghana 家庭语境，插头为 G 型
- 文字无拼写错误，安全区正确
- 文案只陈述已锁定的事实

### 9.5 审核界面

- 按 SKU 分组的网格视图，每个物料旁并排显示对应的实拍原图。
- 快捷键：A 通过，R 驳回（弹出原因代码），G 重新生成。
- 支持"本 SKU 剩余物料全部通过"。
- 预检有标记的物料排在最前面。

## 10. 发布

### 10.1 发布到 WooCommerce（按 SKU 整体发布）

1. 前置条件：该 SKU 的 PAGE_MAIN、至少 3 张 PAGE_ANGLE、COPY_TITLE、COPY_SHORT、COPY_LONG 都已 APPROVED。
2. 读取 Woo 当前的商品名称、描述、短描述、主图、图组和 SEO 字段，存入 `publication.before_json`。
3. 通过 Commerce Port 上传媒体（`/wp-json/wp/v2/media`）并更新商品（`/wp-json/wc/v3/products/{id}`：`name`、`description`、`short_description`、`images`）。SEO 字段的写入方式取决于站点使用的 SEO 插件，在 C3 确认。
   - 认证：`wp/v2/media` 不接受 WooCommerce 的 consumer key，需要为专用的 Shop Manager 用户创建 WordPress Application Password；Woo 接口可以使用同一凭证。
   - 不使用 Woo `images[].src` 远程抓取方式，因为本地阶段的 MinIO 地址无法被生产站点访问。
4. 成功后，相关物料变为 PUBLISHED，被替换的旧物料变为 ARCHIVED，并写入审计日志。
5. 任何一步失败：将已上传的内容恢复为 `before_json`，`publication.status = FAILED`。
6. 回滚：把 `before_json` 写回 Woo，状态改为 ROLLED_BACK。

每次发布只针对一个环境（local / staging / production），由集成配置决定。按 KianosMart 仓库规则，先在 staging 验证再发布到生产。

### 10.2 广告物料导出

- 按批次导出 ZIP：文件按 §7.5 命名，附 `manifest.csv`（file_name、sku、hook、size、headline、primary_text、price_snapshot、asset_id）。
- 导出记录为 `publication(target=AD_EXPORT)`。

### 10.3 价格变动

- C1 的商品同步发现价格变化后，所有 `depends_on_price = true` 的物料标记为 STALE。
- 重新渲染模板。**如果只有价格变量变化，且模板版本已是 APPROVED，新版本自动通过**，并写入审计日志。
- 生成"需要在广告平台替换的素材"清单（已导出的旧版本 → 新版本文件）。

## 11. 错误处理

| 情况 | 处理 |
|---|---|
| ComfyUI / worker 离线 | `WAITING_EXECUTOR`，不消耗重试次数 |
| 租约超时（10 分钟无心跳） | 任务重新排队，`attempts + 1` |
| 可重试错误（超时、429、5xx） | 指数退避，最多 3 次 |
| 不可重试错误（输入无效、内容安全拒绝） | FAILED，在看板上提示人工处理 |
| H3 生成失败或质量差 | 允许用实拍片段替代该段；人工也可以在审核时选择替换 |
| Woo 写入部分失败 | 按 §10.1 第 5 步恢复 |
| 重复导入同一文件 | 按 `sha256` 去重 |

## 12. 硬件约束（RTX 4090 Laptop，16 GB 显存）

- 图像生成模型使用 fp8 / GGUF 量化版本；在手工样板阶段确定具体模型和工作流。
- **模型许可证必须允许商用。** 例如 BRIA RMBG 系列为非商用许可，BiRefNet 为 MIT 许可；选型时逐个核实，并记录在 `comfy_workflow.model_refs`。
- GPU 任务单并发；批量任务可以安排在夜间运行；运行时需接通电源并使用高性能模式。
- 根据 `gpu_seconds` 的统计估算批次耗时，并显示在进度看板上。
- 字体只使用允许商用的开源字体（OFL 许可）。

## 13. 测试策略

| 层 | 内容 |
|---|---|
| 单元测试 | 物料状态机；事实一致性检查；文件命名生成与解析（与 v1.2 附录 B 往返一致）；价格 STALE 传播；AI 时长占比计算；任务依赖图入队逻辑 |
| 模板金样测试 | 用固定数据渲染每个模板，与基准图做像素比对 |
| 执行器契约测试 | 用模拟 ComfyUI / H3 / LLM 服务回放录制的响应；覆盖超时、429、不可用 |
| Woo 集成测试 | 在 KianosMart 本地 Docker 环境（`http://localhost:8080`）中准备测试商品，验证发布、失败恢复和回滚 |
| 端到端冒烟测试 | 一个 SKU 走完全流程：导入 → 生成 → 审核 → 发布到本地 Woo；真实 ComfyUI 手动执行一次 |

## 14. Sprint 计划与验收

**并行工作（不需要开发，现在开始）**
- 按拍摄清单实拍：先拍 HERO，再分批拍 STANDARD。
- 手工样板：2–3 个 HERO SKU 的全套物料，确定风格、ComfyUI 工作流 v1、模板 v1、视频结构、质检和预检阈值。
- v1.2 Phase 0 清单。

| Sprint | 内容 | 验收标准 |
|---|---|---|
| **C1 骨架与素材导入** | 平台基础（认证、tenant、PG 任务队列、审计、MinIO）；Woo 只读同步（商品、变体、分类、价格）；分层；拍摄清单；批量导入、质检、补拍清单 | Woo 商品 100% 同步；按文件夹导入一个 SKU 的实拍并完成质检；补拍清单正确 |
| **C2 商品图流水线** | kiano-worker 与租约协议；ComfyUI 执行器与工作流（抠图、白底、多角度、场景）；模板渲染（信息图、参数图）；自动预检；图片审核看板 | 一个 HERO SKU 自动生成完整图组；关闭 ComfyUI 后任务进入等待，恢复后继续；预检能标记出被改动的产品 |
| **C3 事实、文案、发布** | 事实草稿与锁定；LLM 文案；POLICY_BLOCK；文案预检与审核；按 SKU 发布到 Woo，失败恢复，回滚 | 15 个 HERO SKU 的商品页在 staging 验证后发布到生产；回滚可用 |
| **C4 广告静态图** | AD_OVERLAY 模板（4 hook × 3 尺寸）；安全区；ZIP + CSV 导出；价格 STALE 与自动重渲染；kiano-connector 插件 | 每个 HERO SKU 12 张广告图导出；在 Woo 修改价格后，相关广告图自动更新并列入替换清单 |
| **C5 短视频** | H3 执行器（异步、成本记录）；FFmpeg 合成（剪辑、字幕、配乐、结尾卡）；AI 占比计算；视频审核 | 每个 HERO SKU 3 条视频；AI 占比超过 30% 的视频被拦截 |
| **C6 STANDARD 批量处理** | 批次编排与限流；进度看板与耗时预估；批量审核 | 全部 STANDARD SKU 的图组和文案发布；STANDARD SKU 的平均审核时间 ≤ 3 分钟 |

C6 之后：Kiano 核心迁移到 VPS，笔记本保留 kiano-worker；然后按 v1.2 继续 S2–S6。

## 15. 待确认事项

1. MiniMax H3 API 的访问方式、配额和单价（用于 `cost_usd` 和预算）。
2. 文案和事实草稿使用的 LLM 提供商（通过 LLM Provider Adapter 接入）。
3. 站点使用的 SEO 插件（决定 SEO 字段如何写入 Woo）。
4. staging 环境：使用 KianosMart 本地 Docker 环境，还是单独搭建一个 staging 站点。
5. 免版税曲库和商用字体的选择。
6. 店铺政策的最终文本（配送时效、COD 范围、保修、退货），供 POLICY_BLOCK 使用。
