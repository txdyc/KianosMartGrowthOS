# C6 STANDARD 批量处理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 把约 135 个 STANDARD SKU 编入批次，由系统自动推进。人只在两处介入：
- **事实锁定**：在键盘驱动的工作台上逐个确认事实。
- **物料审核**：在键盘驱动的工作台上逐个审核物料。

系统自动推进的部分：
- 实拍齐全的 SKU 自动启动图片流水线，自动生成事实草稿。
- 物料全部审核通过后，自动发布到 staging。
- OWNER 在看板上勾选，批量发布到生产。

看板实时显示每个阶段的数量、卡住的原因、剩余耗时预估和成本。系统记录每个 SKU 的有效人工时间，用来验收"平均审核时间 ≤ 3 分钟"。

**Architecture：**
- 新增 `content/batch` 包。
- **BatchOrchestrator**：api 内的一个定时任务，每 30 秒按每个批次项的实际状态推导它所处的阶段，并执行下一步可以自动执行的动作（幂等）：
  1. 启动图片流水线（同时在跑的数量受上限控制）。
  2. 生成事实草稿。
  3. 发布到 staging。
- **限流靠三处**：
  - `generation_job` 和 `platform_task` 增加优先级：批次任务排在交互任务之后。
  - 可选的夜间窗口：窗口外不领取批次的 GPU 任务。
  - 同时运行的批次流水线数量上限，以及发布之间的最小间隔。
- **人工时间**由前端的活跃计时器上报，服务端截断后累加到 `review_session`。

**Tech Stack：** 沿用现有技术栈，不引入新的依赖。

**Spec：** `docs/superpowers/specs/2026-10-07-content-module-design.md`：§1（每个 SKU 的审核耗时：STANDARD ≤ 3 分钟）、§2.1、§5、§9.2（G1–G3）、§10.1、§12（GPU 单并发，批量任务可以安排在夜间运行）、§14 C6。

**前置依赖：** C5 已经合并。C5 的 V21 会给 `generation_job` 增加 `external_ref`，C6 的迁移从 **V22** 开始；如果开工时最新版本号已经变化，就顺延，但不能修改已经提交的迁移。

## Global Constraints

C1–C5 计划中的 Global Constraints 继续有效。下面只列 C6 新增的部分。

- **2026-10-09 用户确认的决定**：
  1. **自动发布到 staging，生产由 OWNER 批量确认**：SKU 的物料全部通过后，系统以 SYSTEM 身份发布到 staging；看板列出 staging 已经成功的 SKU，由 OWNER 勾选后批量发布到生产（仍然只有 OWNER 能发布生产，并且要经过 staging 闸门）。
  2. **优先级加上可选的夜间窗口**：批次的 GPU 任务和平台任务优先级都低于交互任务；创建批次时可以勾选"只在夜间窗口运行 GPU 任务"，窗口默认是 22:00–07:00（Africa/Accra 时间，也就是 UTC），写在配置中。
  3. **审核时间 = 事实确认时间 + 物料审核时间**：两个工作台都只累计**有效操作时间**，空闲超过 60 秒的时段不计入；验收看每个 SKU 两者之和的平均值 ≤ 180 秒。
- **批次只收 STANDARD SKU**：HERO 有自己的物料（广告图、视频），继续逐个处理。创建批次时混入 HERO 商品返回 422 `BATCH_HERO_NOT_ALLOWED`。一个商品同一时间只能在一个进行中的批次里，否则返回 409 `PRODUCT_IN_ACTIVE_BATCH`。
- **阶段一律从实际状态推导，不另外存一份状态**：
  - 阶段由 source_media、generation_run、product_fact_sheet、asset、publication 推导，规则唯一的实现是 `BatchStageResolver`（纯函数）。
  - 批次项只保存编排时用到的引用（runId、publicationId）和时间戳。
  - 这样人在批次之外的操作（例如在商品页上手动锁定事实），批次也能看到。
- **阶段**（按顺序）：
  1. `WAITING_SHOTS`：G1 未通过。
  2. `IMAGES`：图片流水线正在运行。
  3. `FACTS_DRAFTING`：没有可用的事实来源，或者事实草稿正在生成。
  4. `AWAITING_FACT_LOCK`：等待人工锁定事实。
  5. `GENERATING`：事实已锁定，文案、信息图或参数图正在生成。
  6. `IN_REVIEW`：有物料待审核。
  7. `STAGING`：已发布到 staging，或正在发布。
  8. `READY_FOR_PRODUCTION`：staging 已经 APPLIED。
  9. `PRODUCTION`：已发布到生产，或正在发布。
  10. `DONE`：生产已经 APPLIED。

  另有 `BLOCKED(reason)`：需要人处理，原因码见 Task 3。图片流水线和事实草稿这两条线并行推进；推导时取两条线中较慢的那条作为当前阶段，看板上同时显示两条线各自的状态。
- **优先级的取值**：交互任务为 0（默认，也就是现有行为），批次任务为 10。领取顺序改为 `order by priority, id`。批次图片流水线的**下游任务继承父任务的优先级、`batch_id` 和 `night_only`**。人在审核时点"重新生成"产生的任务算交互任务，优先级为 0。
- **限流**（`kiano.batch.*`，全部写在配置中）：
  - `max-active-pipelines=3`：同时在跑的批次图片流水线数，这样 SKU 会一个接一个地完成，审核可以尽早开始。
  - `max-queued-fact-drafts=5`
  - `publish-interval=10s`：批量发布 staging 和生产时，每两个之间的最小间隔，用来保护共享的 VPS 和 Woo。
  - `night-window=22:00-07:00`，`zone=Africa/Accra`
  - `tick=30s`
- **审计**：
  - 编排器的每个自动动作都写审计，actor 为 SYSTEM，source 为 `BATCH`：`BATCH_PIPELINE_STARTED`、`BATCH_FACT_DRAFT_QUEUED`、`BATCH_STAGING_REQUESTED`、`BATCH_ITEM_BLOCKED`。
  - 人的操作也写审计：`BATCH_CREATED`、`BATCH_PAUSED`、`BATCH_RESUMED`、`BATCH_CANCELLED`、`BATCH_PRODUCTION_REQUESTED`。
- **staging 商品必须存在**：staging 按 SKU 查找商品（C3）。staging 中缺少这个 SKU 时，批次项变为 `BLOCKED(STAGING_PRODUCT_MISSING)`，看板上提示用 WooCommerce 自带的 CSV 导出和导入，把商品补进本地 staging（操作步骤写进 README）。**C6 不实现自动建商品。**
- **相对规格的有意扩展**：
  - 新增表 `content_batch`、`content_batch_item`、`review_session`。
  - `generation_job` 增加 `priority`、`batch_id`、`night_only` 三列；`platform_task` 增加 `priority` 列。
  - `PublicationService` 增加以 SYSTEM 身份发布 staging 的入口。

## Review Focus

1. **批次的下游任务丢失优先级或夜间标记**：cutout 完成后产生的白底、多角度、场景任务如果回到默认优先级 0，或者丢掉 `night_only`，批次的场景图就会插到 HERO 前面，或者在白天占用 GPU。可重试的失败和 WAITING_EXECUTOR 恢复后，优先级也必须保持不变。测试放在 Task 1 和 Task 2。
2. **批次运行期间有人在做交互操作**：已经排了 100 个批次任务后，新建的 HERO 任务和 HERO 文案任务都要先被领取；夜间窗口外，批次的 GPU 任务一个都不能被领取，但交互任务照常领取。测试放在 Task 1。
3. **批次中途商品状态变化**：镜头被标为补拍、商品被改成 HERO、Woo 里删除了商品、事实被重新锁定。这些情况下批次项要变为 BLOCKED 并写明原因，或者回退到正确的阶段；编排器不能抛异常导致整个批次停住。测试放在 Task 3。
4. **编排器重复执行**：两轮 tick 重叠，或者 api 在 tick 中途重启，都不能导致同一 SKU 启动两次流水线、重复入队事实草稿或重复发布。编排器使用 PostgreSQL advisory lock，并依赖现有的防重机制：`PIPELINE_RUNNING`、任务去重键、`PUBLISH_IN_PROGRESS`。测试放在 Task 3。
5. **审核时间统计失真**：标签页一直开着没人操作、同时开两个标签页、会话没有结束就关掉页面、心跳伪造了很大的增量，都不能让平均值失真。每次心跳最多累加 20 秒；只有至少有一个已结束会话的 SKU 才计入平均。测试放在 Task 5。

---

## 文件结构

```
kiano-api/src/main/resources/db/migration/
  V22__job_priority.sql        （Task 1）
  V23__content_batch.sql       （Task 2）
  V24__review_session.sql      （Task 5）
kiano-api/src/main/java/com/kiano/
  platform/queue/ TaskQueue（enqueue 增加优先级和 runAfter）, TaskDispatcher（order by priority, id）
  content/generation/ GenerationJobStore（优先级、夜间窗口过滤）, WorkerController
  content/pipeline/ PipelineService（startForBatch）, PipelineCompletionHandler（继承优先级）
  content/publish/ PublicationService（requestSystem）
  content/batch/
    BatchProperties, NightWindow
    BatchService, BatchController, BatchEntity/ItemEntity(+Mapper)
    BatchStage, BatchStageResolver, BatchSnapshotReader
    BatchOrchestrator
    EtaCalculator, BatchProgressService
    ReviewSessionService, ReviewSessionController
    BatchQueueService（事实队列与审核队列）
    BatchPublishService
kiano-web/src/
  app/(app)/batches/page.tsx  app/(app)/batches/[id]/page.tsx
  app/(app)/batches/[id]/facts/page.tsx  app/(app)/batches/[id]/review/page.tsx
  lib/batch.ts (+test)  lib/activeTimer.ts (+test)  lib/keys.ts (+test)  lib/types.ts  i18n/*
```

---

### Task 1: 任务优先级与夜间窗口

**Files:**
- Create: `db/migration/V22__job_priority.sql`、`content/batch/{BatchProperties,NightWindow}.java`
- Modify: `content/generation/{GenerationJobStore,WorkerController}.java`、`platform/queue/{TaskQueue,TaskDispatcher}.java`、`application.yml`（`kiano.batch.*`）
- Test: `content/batch/NightWindowTest.java`、`content/generation/GenerationJobStoreTest.java`、`platform/queue/TaskQueueTest.java`（追加用例）

**Interfaces:**
- V22 的内容：
```sql
alter table generation_job add column priority smallint not null default 0,
  add column batch_id bigint, add column night_only boolean not null default false;
drop index generation_job_ready;
create index generation_job_ready on generation_job (executor, priority, id) where status = 'QUEUED';
alter table platform_task add column priority smallint not null default 0;
drop index platform_task_ready;
create index platform_task_ready on platform_task (priority, run_after) where status = 'QUEUED';
```
  `batch_id` 的外键在 V23 建表之后再加。
- `NightWindow.isOpen(LocalTime start, LocalTime end, LocalTime now) -> boolean`：支持跨越午夜的窗口，即 start > end 的情况；start == end 表示全天开放。`BatchProperties.nightOpen(Instant now)` 先按 `zone` 换算，再调用它。
- `GenerationJobStore`：
  - `create(...)` 增加一个重载，带 `JobPriority priority`，其中 `record JobPriority(int priority, @Nullable Long batchId, boolean nightOnly)`，`JobPriority.INTERACTIVE` 表示 `(0, null, false)`。原来的 create 方法委托给它，传 INTERACTIVE。
  - `lease(workerId, capabilities, boolean nightOpen)`：SQL 改为 `... where status='QUEUED' and run_after <= now() and executor in (...) and (night_only = false or ?) order by priority, id ...`。`WorkerController.lease` 传入 `batchProperties.nightOpen(Instant.now())`；C5 的 `H3JobRunner` 同样处理。
  - 重试、`park`、从 WAITING_EXECUTOR 恢复这几条路径都**不改动** priority、batch_id 和 night_only。
- `TaskQueue.enqueue(tenantId, type, payload, dedupeKey, int priority, @Nullable Instant runAfter)`：原方法委托给它，传 `(0, null)`。`TaskDispatcher.claim` 改为 `order by priority, id`。

- [ ] **Step 1: 写失败的测试**

```java
// NightWindowTest
@Test void wrapsMidnight_22to07()           // 23:30 和 06:59 在窗口内，07:00 和 21:59 不在
@Test void sameDayWindow_and_equalBoundsMeansAlwaysOpen()
// GenerationJobStoreTest（Review Focus 2）
@Test void lease_interactiveBeforeEarlierBatchJobs()           // 先建 3 个 priority=10 的任务，再建 1 个 priority=0 的任务 → 先领到 priority=0
@Test void lease_nightOnlySkippedOutsideWindow_interactiveStillLeased()
@Test void retryAndWaitingRecovery_keepPriorityBatchAndNightFlag()   // Review Focus 1
// TaskQueueTest
@Test void claim_interactiveTaskBeforeQueuedBatchTasks()
@Test void enqueue_runAfterDelaysClaim()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='NightWindowTest,GenerationJobStoreTest,TaskQueueTest,WorkerControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): job/task priorities and an optional night window for batch GPU work"`

---

### Task 2: 批次模型、创建与批次流水线入口

**Files:**
- Create: `db/migration/V23__content_batch.sql`、`content/batch/{BatchEntity,BatchItemEntity,BatchMapper,BatchItemMapper,BatchService,BatchController}.java`
- Modify: `content/pipeline/{PipelineService,PipelineCompletionHandler}.java`、`content/asset/ReviewService.java`（重新生成时显式使用 INTERACTIVE）
- Test: `content/batch/BatchServiceTest.java`、`content/pipeline/BatchPipelineTest.java`

**Interfaces:**
- V23 的内容：
```sql
create table content_batch (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), name text not null,
  status text not null check (status in ('RUNNING','PAUSED','DONE','CANCELLED')),
  night_only boolean not null default false,
  created_by bigint references app_user(id), created_at timestamptz not null default now(),
  finished_at timestamptz);
create table content_batch_item (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  batch_id bigint not null references content_batch(id), product_id bigint not null references product(id),
  position int not null,
  image_run_id bigint references generation_run(id),
  staging_publication_id bigint references publication(id),
  production_publication_id bigint references publication(id),
  blocked_reason text, blocked_details jsonb,
  added_at timestamptz not null default now(), done_at timestamptz,
  unique (batch_id, product_id));
alter table generation_job add constraint generation_job_batch_fk
  foreign key (batch_id) references content_batch(id);
```
- `BatchService`：
  - `create(CurrentUser user, String name, List<Long> productIds, boolean nightOnly) -> BatchView`，OPERATOR 可用。
    - 校验：商品是顶层商品，否则 422 `NOT_TOP_LEVEL`；tier 是 STANDARD，否则 422 `BATCH_HERO_NOT_ALLOWED`，`details.productIds` 列出不符合的商品；商品不在其他 RUNNING 或 PAUSED 的批次里，否则 409 `PRODUCT_IN_ACTIVE_BATCH`。
    - `position` 按传入的顺序编号。
  - `pause`、`resume`、`cancel(user, batchId)`：
    - 只有 RUNNING 和 PAUSED 之间可以互相切换。
    - cancel 会把这个批次中**还没有被领取**的任务（`generation_job` 中 QUEUED 或 WAITING_EXECUTOR 状态，并且 `batch_id = ?`）改为 CANCELLED；已经完成的物料保留。
  - `candidates(tenantId) -> List<Candidate>`：列出 STANDARD 顶层商品，`record Candidate(long productId, String sku, String name, boolean shotsComplete, @Nullable Long activeBatchId, String stage)`，供创建页选择商品。
- `PipelineService.startForBatch(long tenantId, long productId, long batchId, boolean nightOnly) -> long runId`：
  - 与 `start` 共用同一段私有实现：校验相同，cutout 任务使用 `JobPriority(10, batchId, nightOnly)`。
  - 审计 actor 为 SYSTEM，source 为 `BATCH`。
  - `generation_run.created_by` 为 null。
- `PipelineCompletionHandler`：所有下游任务的 `create` 都**传入父任务的 JobPriority**（Review Focus 1）。
- 端点：
  - `POST /api/v1/content/batches {name, productIds, nightOnly}`（OPERATOR）→ 201
  - `GET /api/v1/content/batches`
  - `GET /api/v1/content/batches/candidates`
  - `POST /api/v1/content/batches/{id}/pause|resume|cancel`（OPERATOR）

- [ ] **Step 1: 写失败的测试**

```java
// BatchServiceTest
@Test void create_rejectsHeroAndVariations_listsOffenders()
@Test void create_productInAnotherActiveBatch_409()
@Test void cancel_cancelsOnlyUnleasedBatchJobs_keepsAssets()
@Test void pauseResume_transitions_andInvalidTransition409()
// BatchPipelineTest（Review Focus 1）
@Test void startForBatch_cutoutsCarryPriorityBatchAndNightFlag()
@Test void downstreamWhiteAngleSceneJobs_inheritParentPriority()     // 在测试中调用 completion handler，再检查子任务
@Test void reviewRegenerate_createsInteractiveJob()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='BatchServiceTest,BatchPipelineTest,PipelineServiceTest,PipelineCompletionHandlerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): STANDARD content batches with batch-priority image pipelines"`

---

### Task 3: 阶段推导与编排器

**Files:**
- Create: `content/batch/{BatchStage,BatchStageResolver,BatchSnapshotReader,BatchOrchestrator}.java`
- Create: `content/publish/PublishRules.java`（从 PublicationService 中提取出来的必需物料规则）
- Modify: `content/publish/PublicationService.java`（新增 `requestSystem`；改为调用 `PublishRules`）
- Test: `content/batch/{BatchStageResolverTest,BatchOrchestratorTest}.java`、`content/publish/PublicationServiceTest.java`（追加用例）

**Interfaces:**
- `enum BatchStage { WAITING_SHOTS, IMAGES, FACTS_DRAFTING, AWAITING_FACT_LOCK, GENERATING, IN_REVIEW, STAGING, READY_FOR_PRODUCTION, PRODUCTION, DONE, BLOCKED }`
- `record ItemSnapshot(...)`：由 `BatchSnapshotReader.read(tenantId, items)` **批量**读出（每张表一条查询，不按商品逐个查询）。包含以下信息：
  - 商品：是否存在、是否顶层、tier。
  - 镜头：G1 是否通过、是否有 RESHOOT、是否有 P5 或 PROMO 事实来源。
  - 图片：最新一次 generation_run 的状态，以及其中失败的任务数。
  - 事实：事实表状态，以及是否有 FACT_DRAFT 任务在跑或失败了。
  - 物料：必需物料中缺少的、IN_REVIEW 的、生成中的。必需物料的规则必须与发布一致：把 `PublicationService` 中私有的 `missingRequired` 和包内可见的 `liveContent` 提取为公开的静态方法 `PublishRules.missingRequired(List<ReviewItem>)` 和 `PublishRules.liveContent(...)`，发布和批次两处共用，不能复制一份。
  - 发布：staging 和生产两边最新 publication 的状态和错误码。
- `BatchStageResolver.resolve(ItemSnapshot s) -> Resolution`，`record Resolution(BatchStage stage, @Nullable String blockedReason, Map<String,Object> details, BatchStage imageLane, BatchStage factLane)`。
  - **BLOCKED 的原因码**：`PRODUCT_GONE`、`NOW_HERO`、`SHOTS_RESHOOT`、`PIPELINE_FAILED`、`NO_FACT_SOURCES`、`FACT_DRAFT_FAILED`、`STAGING_PRODUCT_MISSING`（publication 的错误为 `WOO_PRODUCT_NOT_FOUND`）、`PUBLISH_FAILED`、`POLICY_INCOMPLETE`。
  - BLOCKED 的优先级高于其他所有阶段。
  - 事实被重新锁定时，会自然回到 `GENERATING` 或 `IN_REVIEW`，不需要特别处理（Review Focus 3）。
- `PublicationService.requestSystem(long tenantId, long productId, long batchId) -> long publicationId`：
  - 只能发布 STAGING；校验与 `request` 相同。
  - `published_by` 为 null，审计 actor 为 SYSTEM。
  - 发布任务使用 `priority=10`，`runAfter` 由调用方传入，用来实现发布间隔。
- `BatchOrchestrator`：`@Scheduled(fixedDelayString="${kiano.batch.tick}")`，只在非 worker 的 profile 中运行。
  - 每个租户先执行 `select pg_try_advisory_xact_lock(hashtext('kiano-batch:' || tenantId))`，拿不到锁就跳过这一轮（Review Focus 4）。
  - 只处理 RUNNING 状态的批次。对每个批次项先推导阶段，再执行下面的动作，每个动作都必须幂等：
    1. **启动图片流水线**：阶段为 `WAITING_SHOTS` 之后、G1 已通过、`image_run_id` 为空，并且该批次正在运行的流水线少于 `max-active-pipelines` 时，调用 `startForBatch` 并记下 runId；返回 `PIPELINE_RUNNING` 时，把已有的 run 记下来。
    2. **生成事实草稿**：有事实来源、没有事实表、也没有在跑的 FACT_DRAFT 任务，并且这一轮新入队的少于 `max-queued-fact-drafts` 时，入队 `FACT_DRAFT`，priority=10，去重键与现有的相同（`fact-draft:{productId}`）。
    3. **发布 staging**：阶段为 `IN_REVIEW` 并且必需物料全部通过，也就是缺少的为空、IN_REVIEW 的为 0，同时 `staging_publication_id` 为空，或者指向的 publication 已经 FAILED 并且用户在看板上点了"重试"时，调用 `requestSystem`。`runAfter` 取 `max(now, 该租户上一次批次发布的 runAfter + publish-interval)`。
    4. **结束批次**：所有批次项都为 `DONE` 时，批次状态设为 DONE，并写入 `finished_at`。
  - 单个批次项出现异常时，记录日志并写审计 `BATCH_ITEM_BLOCKED`（原因 `INTERNAL`），然后继续处理下一个，不能停在那里（Review Focus 3）。
  - 动作和对应的审计见 Global Constraints。

- [ ] **Step 1: 写失败的测试**

```java
// BatchStageResolverTest（纯函数，每个阶段和每种 BLOCKED 原因各一个用例）
@Test void lanesRunInParallel_stageIsTheSlowerLane()
@Test void reshootAfterImages_blockedShotsReshoot()
@Test void tierChangedToHero_blockedNowHero()
@Test void stagingWooNotFound_blockedStagingProductMissing()
@Test void factsRelocked_backToGenerating()
// BatchOrchestratorTest（真实的 PG，Woo 部分用 WireMock）
@Test void tick_startsAtMostMaxActivePipelines_thenNextAsOneFinishes()
@Test void tick_queuesFactDraftsWithBatchPriority_respectingCap()
@Test void tick_allApproved_requestsStagingAsSystem_spacedByPublishInterval()
@Test void concurrentTicks_neverDoubleStartOrDoublePublish()        // Review Focus 4：两个线程同时执行 tick
@Test void oneItemThrows_othersStillProgress_itemBlockedInternal()
@Test void pausedBatch_noActions()
@Test void allDone_batchMarkedDone()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='BatchStageResolverTest,BatchOrchestratorTest,PublicationServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): batch stage resolution and an idempotent orchestrator with rate limits"`

---

### Task 4: 进度看板与耗时预估

**Files:**
- Create: `content/batch/{EtaCalculator,BatchProgressService}.java`
- Modify: `content/batch/BatchController.java`
- Test: `content/batch/{EtaCalculatorTest,BatchProgressServiceTest}.java`

**Interfaces:**
- `EtaCalculator.estimate(EtaInput in) -> Eta`（纯函数）。
  - 输入：`record EtaInput(Map<JobStep,Integer> remainingGpuJobs, Map<JobStep,Double> avgGpuSeconds, int remainingFactDrafts, int remainingCopyRuns, double avgFactDraftSeconds, double avgCopySeconds, int awaitingHumanItems, double avgHumanSecondsPerItem, boolean nightOnly, NightWindowSpec window, Instant now)`。
  - 输出：`record Eta(double gpuSeconds, double llmSeconds, double humanSeconds, Instant gpuFinishAt)`。
  - `remainingGpuJobs` 包括：已经入队但没完成的任务，加上还没启动流水线的 SKU 按计划推算的任务数（每个 SKU 1 张白底图、可用的多角度图数、场景图数和配件图）。
  - `avgGpuSeconds` 取每种步骤最近 50 个成功任务的 `gpu_seconds` 平均值；没有历史数据时用配置 `kiano.batch.default-gpu-seconds.{STEP}` 中的默认值。
  - `nightOnly` 时，`gpuFinishAt` 只累计窗口内的时长，跨越多个夜晚时顺延到后面的窗口。
  - LLM 的平均耗时取 `llm_call.latency_ms`，按用途分别计算。
- `BatchProgressService.progress(long tenantId, long batchId) -> BatchProgress`，其中 `record BatchProgress(BatchView batch, Map<BatchStage,Integer> stageCounts, List<ItemRow> items, Eta eta, BigDecimal llmCostUsd, double gpuSecondsUsed, ReviewTimeStats reviewTime)`：
  - `ItemRow` 包含：sku、阶段、图片线和事实线各自的状态、BLOCKED 原因及 details、staging 和生产两边的状态。
  - `llmCostUsd` 是批次内商品在 `created_at` 之后产生的 `llm_call.cost_usd` 之和。
  - `reviewTime` 由 Task 5 提供。
- 端点：`GET /api/v1/content/batches/{id}/progress`（VIEWER）。

- [ ] **Step 1: 写失败的测试**

```java
@Test void eta_sumsRemainingJobsTimesStepAverages()
@Test void eta_noHistory_usesConfiguredDefaults()
@Test void eta_nightOnly_finishSpansWindowsAcrossNights()      // 剩余 GPU 时间 12 小时，窗口每晚 9 小时，22:00 开始 → 第二晚 01:00 完成
@Test void progress_countsStagesAndListsBlockedWithReasons()
@Test void progress_costOnlyCountsBatchProductsSinceCreation()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='EtaCalculatorTest,BatchProgressServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): batch progress dashboard data with GPU/LLM/human ETA and cost"`

---

### Task 5: 审核会话与有效人工时间

**Files:**
- Create: `db/migration/V24__review_session.sql`、`content/batch/{ReviewSessionService,ReviewSessionController}.java`
- Test: `content/batch/ReviewSessionServiceTest.java`

**Interfaces:**
- V24 的内容：
```sql
create table review_session (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), user_id bigint not null references app_user(id),
  product_id bigint not null references product(id), batch_id bigint references content_batch(id),
  kind text not null check (kind in ('FACT','ASSETS')),
  active_seconds numeric(8,1) not null default 0,
  started_at timestamptz not null default now(), last_heartbeat_at timestamptz not null default now(),
  finished_at timestamptz, outcome text);
create index review_session_product on review_session (tenant_id, product_id, kind);
```
- `ReviewSessionService`：
  - `start(CurrentUser user, long productId, @Nullable Long batchId, String kind) -> long sessionId`
  - `heartbeat(user, sessionId, double activeSecondsDelta)`：
    - 只能更新**自己的**、尚未结束的会话，否则 404 或 409。
    - 增量截断到 `[0, 20]`。
    - 与上一次心跳的间隔超过 60 秒时，这次增量按 0 计（标签页被挂起后补发的增量不算）。
  - `finish(user, sessionId, double finalDelta, String outcome)`：`outcome` 取值为 `LOCKED`、`APPROVED_ALL`、`SKIPPED`、`LEFT`；`finalDelta` 同样截断。
  - `stats(tenantId, batchId) -> ReviewTimeStats`，`record ReviewTimeStats(int skuCount, double avgFactSeconds, double avgAssetSeconds, double avgTotalSeconds, double p90TotalSeconds, int targetSeconds /* 180 */)`。按 SKU 汇总所有已结束的会话（同一 SKU 多次打开时累加，多个标签页的时间也累加）；只有 FACT 和 ASSETS 两种会话都至少结束过一次的 SKU 才计入平均。
- 端点：
  - `POST /api/v1/content/review-sessions {productId, batchId?, kind}` → 201 `{id}`（OPERATOR）
  - `POST /api/v1/content/review-sessions/{id}/heartbeat {activeSeconds}`
  - `POST /api/v1/content/review-sessions/{id}/finish {activeSeconds, outcome}`

- [ ] **Step 1: 写失败的测试**（Review Focus 5）

```java
@Test void heartbeat_clampsDeltaTo20s_andIgnoresAfterSilenceOver60s()
@Test void heartbeat_otherUsersSession_404_finished_409()
@Test void stats_sumsFactAndAssetsPerSku_onlySkusWithBothFinished()
@Test void stats_multipleSessionsSameSku_areSummed()
@Test void unfinishedSessions_excludedFromAverage()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=ReviewSessionServiceTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): review sessions with clamped active time and per-SKU review-time stats"`

---

### Task 6: 事实队列、审核队列与批量生产发布

**Files:**
- Create: `content/batch/{BatchQueueService,BatchPublishService}.java`
- Modify: `content/batch/BatchController.java`
- Test: `content/batch/{BatchQueueServiceTest,BatchPublishServiceTest}.java`

**Interfaces:**
- `BatchQueueService.factQueue(tenantId, batchId, @Nullable Long afterProductId, int limit) -> List<FactQueueItem>`：
  - 返回阶段为 `AWAITING_FACT_LOCK` 的批次项，按 `position` 排序。
  - `record FactQueueItem(long productId, String sku, String name, FactSheetView draft, List<String> plateUrls /* P5 */, List<String> promoUrls, Map<String,String> fieldSources)`，图片 URL 都是有效期 15 分钟的预签名地址。
  - 锁定直接使用现有的 `POST /products/{id}/facts/lock`，不新增锁定接口。
- `BatchQueueService.reviewQueue(tenantId, batchId, @Nullable Long afterProductId, int limit) -> List<ReviewQueueItem>`：
  - 只返回**已经没有生成中物料**、并且有 IN_REVIEW 物料的 SKU，按 `position` 排序。
  - `record ReviewQueueItem(long productId, String sku, String name, List<ReviewItem> assets, FactsJson facts)`，其中 `assets` 复用 `ReviewService.list` 的排序，并且**带预检标记的物料排在前面**。
  - 审核动作复用现有的 `/assets/{id}/review` 和 `/products/{id}/review/approve-remaining`。
- `BatchPublishService.publishProduction(CurrentUser user, long batchId, List<Long> productIds) -> ProductionResult`：
  - 仅限 OWNER，否则 403。
  - 每个商品必须处于 `READY_FOR_PRODUCTION` 阶段，不符合的列入 `rejected`，原因为 `NOT_READY`。
  - 对每个商品调用现有的 `PublicationService.request(user, pid, PRODUCTION)`，staging 闸门照常校验；任务按 `publish-interval` 依次错开 `runAfter`，priority=10。
  - 写入 `production_publication_id`，写审计 `BATCH_PRODUCTION_REQUESTED`，after 中记录商品 ID 列表。
  - `record ProductionResult(List<Long> queued, Map<Long,String> rejected)`。
- 端点：
  - `GET /batches/{id}/fact-queue?after=&limit=`、`GET /batches/{id}/review-queue?after=&limit=`（OPERATOR）
  - `POST /batches/{id}/publish-production {productIds}`（OWNER）
  - `POST /batches/{id}/items/{productId}/retry-staging`（OPERATOR）：清除 FAILED 状态的 staging 引用，下一轮 tick 会重新发布

- [ ] **Step 1: 写失败的测试**

```java
@Test void factQueue_onlyAwaitingLock_inPositionOrder_withPresignedPlates()
@Test void reviewQueue_skipsSkusStillGenerating_flaggedAssetsFirst()
@Test void publishProduction_ownerOnly_notReadyRejected_spacedRunAfter()
@Test void publishProduction_stagingGateStillEnforced()        // staging 的物料集与当前不同时 → 该商品列入 rejected，原因为 STAGING_REQUIRED
@Test void retryStaging_clearsFailedRef_nextTickRepublishes()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='BatchQueueServiceTest,BatchPublishServiceTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): batch fact/review queues and OWNER bulk production publish"`

---

### Task 7: 前端——批次列表、创建页与进度看板

**Files:**
- Create: `kiano-web/src/app/(app)/batches/page.tsx`、`src/app/(app)/batches/[id]/page.tsx`、`src/lib/batch.ts`、`src/lib/batch.test.ts`
- Modify: `src/app/(app)/layout.tsx`（导航中加入"批次 Batches"）、`src/lib/types.ts`、`src/i18n/*`、`i18n.test.ts`

**Interfaces:**
- `lib/batch.ts`：
  - `STAGE_ORDER`
  - `stageLabelKey(stage)`、`blockedReasonKey(code)`
  - `formatDuration(seconds) -> "2h 15m"`
  - `funnel(stageCounts) -> {stage, count, cumulative}[]`
  - `selectableForProduction(items) -> productIds`：只返回 `READY_FOR_PRODUCTION` 的商品。
  - `reviewTimeVerdict(stats) -> "ON_TARGET"|"OVER"|"NOT_ENOUGH_DATA"`：skuCount 小于 5 时为 NOT_ENOUGH_DATA。
- **`/batches` 页面**：左侧是批次列表；右侧是"新建批次"表单：候选 SKU 表格支持按 shotsComplete、关键字筛选，可以全选筛选结果；勾选"只在夜间运行 GPU 任务"；填写批次名称。
- **`/batches/[id]` 看板**（每 10 秒轮询一次 progress）：
  - 阶段漏斗。
  - 三项 ETA：GPU（夜间模式下显示预计完成时刻）、LLM、人工。
  - 已用的 LLM 成本和 GPU 时长。
  - 审核时间卡片：平均值、P90、目标 180 秒，按 `reviewTimeVerdict` 显示颜色。
  - 卡住的项目列表：显示原因和处理建议，例如 STAGING_PRODUCT_MISSING 链接到 README 中的 CSV 导入说明；FAILED 的 staging 有"重试"按钮。
  - 操作：暂停、继续、取消；"进入事实确认""进入物料审核"两个入口按钮。
  - 只有 OWNER 能看到"批量发布到生产"区块：勾选 READY_FOR_PRODUCTION 的商品后二次确认，确认框中显示数量和预计耗时（数量 × publish-interval）。
- 所有文字都通过 `t()` 输出；阶段、原因码和错误码都要加进 i18n 的覆盖清单。

- [ ] **Step 1: 写失败的测试**（`batch.test.ts`：funnel 的累计值、只能选 READY 的商品、formatDuration 的边界值、reviewTimeVerdict 三种结果；i18n 覆盖清单）
- [ ] **Step 2–4: 先确认失败 → 实现 → 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: Commit** — `git commit -m "feat(web): batch list, creation and progress dashboard"`

---

### Task 8: 前端——事实确认工作台与物料审核工作台

**Files:**
- Create: `kiano-web/src/app/(app)/batches/[id]/facts/page.tsx`、`src/app/(app)/batches/[id]/review/page.tsx`、`src/lib/activeTimer.ts (+test)`、`src/lib/keys.ts (+test)`
- Modify: `src/components/FactsPanel.tsx`（提取可复用的字段编辑部分）、`src/i18n/*`

**Interfaces:**
- **`lib/activeTimer.ts`**：`createActiveTimer({now, idleMs=60000, beatMs=15000})` 返回 `{activity(), tick() -> deltaSeconds, flush() -> deltaSeconds}`。
  - 活跃的定义是：距离上一次 activity（键盘、鼠标、滚动）不超过 idleMs。
  - `tick` 返回自上次 tick 以来的活跃秒数，空闲时段不计入。
  - 页面不可见时（`visibilitychange`）不计时。
  - 纯逻辑，时间通过注入的 now 提供，便于测试。
- **`lib/keys.ts`**：`keymap(context) -> Record<string, Action>` 和 `actionFor(event, context)`。快捷键在输入框中失效，Esc 除外。
  - **事实工作台**：`Enter` 确认全部 6 项并锁定（全部字段非空时才可用），`E` 编辑当前字段，`J`/`K` 切换字段，`S` 跳过这个 SKU，`?` 显示快捷键帮助。
  - **审核工作台**：`←`/`→` 切换物料，`Space` 通过当前物料，`X` 驳回（弹出 1–7 选择驳回原因），`G` 重新生成，`A` 通过剩余全部，`N` 下一个 SKU，`?` 显示帮助。
- **事实工作台页面**：
  - 左侧是 P5 铭牌和宣传图，可以放大和切换；右侧是草稿字段，并标注每个字段的来源（FieldSource）。
  - 打开 SKU 时调用 `review-sessions start(kind=FACT)`，每 15 秒用 `tick()` 发一次心跳；锁定或跳过时调用 finish。
  - 锁定成功后自动加载下一个 SKU，并**预取**下一个 SKU 的图片。
- **审核工作台页面**：
  - 一个 SKU 的全部物料在同一屏：图片网格，加上可以展开的文案。
  - 带预检标记的物料排在前面并高亮。
  - 会话的 kind 为 ASSETS；"通过剩余全部"之后的 outcome 为 `APPROVED_ALL`。
  - 预取下一个 SKU。
- 两个工作台的顶部都显示：本次已用时间、批次的平均审核时间和目标 180 秒、队列中还剩多少个。

- [ ] **Step 1: 写失败的测试**（`activeTimer.test.ts`：空闲超过 60 秒不计、页面隐藏时不计、多次 tick 的增量相加等于活跃总时长；`keys.test.ts`：输入框中快捷键失效但 Esc 有效、事实工作台字段不全时 Enter 不可用、驳回后数字键映射到驳回原因）
- [ ] **Step 2–4: 先确认失败 → 实现 → 运行 `pnpm vitest run && pnpm lint && pnpm build`**
- [ ] **Step 5: 浏览器验证**：用 5 个测试 SKU，只用键盘走完事实确认和物料审核；看板上的审核时间与实际操作时间相符，空闲的时段没有计入。
- [ ] **Step 6: Commit** — `git commit -m "feat(web): keyboard-first fact and review stations with active-time tracking"`

---

### Task 9: 文档与 C6 验收

**Files:**
- Modify:
  - `docs/superpowers/specs/2026-10-07-content-module-design.md`：在 §14 C6 后写明本计划确认的决定，以及审核时间的统计口径。
  - `README.md`：批次的使用流程；夜间窗口；staging 缺少商品时的 CSV 导入步骤（WooCommerce → Products → Export / Import）。
  - `CLAUDE.md`：硬规则加上批次只收 STANDARD；阶段由实际状态推导；交互任务优先；生产发布始终由 OWNER 确认。

- [ ] **Step 1: 更新文档**
- [ ] **Step 2: 运行全部测试**（先停掉 worker）：`cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm lint && pnpm build`
- [ ] **Step 3: 按规格 §14 C6 做验收**。前提：STANDARD SKU 的实拍已经导入；本地 staging 中有这些 SKU；政策设置已经填完整；worker 和 ComfyUI 都已启动。
  1. **试跑批次**：先用 10 个 SKU 建一个批次（不勾选夜间模式）。在看板上看着各阶段推进：同时运行的流水线不超过 3 条；建批次之后，在一个 HERO 商品上点"重新生成"，这个任务会先被领取。
  2. 在事实工作台用键盘锁定 10 个 SKU；在审核工作台审核这 10 个 SKU。
  3. 物料全部通过的 SKU 自动发布到 staging；在 staging 抽查 2 个商品页。
  4. 由 OWNER 勾选 READY 的 SKU，批量发布到生产；在生产环境抽查 2 个商品页。
  5. **正式批次**：其余全部 STANDARD SKU 建一个批次，勾选夜间模式；确认白天不领取这个批次的 GPU 任务；第二天上午检查进度和 ETA 是否合理。
  6. 全部 SKU 进入 DONE 状态之后，在看板上记录审核时间：**每个 SKU 事实确认与物料审核之和的平均值 ≤ 180 秒**。超过时，记下 P90 和耗时最多的环节，作为调优依据。
  7. **验收标准**：全部 STANDARD SKU 的图组和文案已经发布到生产，并且平均审核时间 ≤ 3 分钟（规格 §14 C6）。
- [ ] **Step 4: Commit** — `git commit -m "docs: C6 batch processing in spec, README and CLAUDE.md"`

---

## Self-Review 记录

- **规格覆盖**（对照规格 §14 C6 一行）：
  - 批次编排与限流：Task 1（优先级、夜间窗口）、Task 2（批次和批次流水线）、Task 3（编排器、流水线上限、发布间隔）。
  - 进度看板与耗时预估：Task 4、7。
  - 批量审核：Task 5、6、8（两个工作台和有效时间统计）。
  - 验收"全部发布 + 平均审核时间 ≤ 3 分钟"：Task 9，统计口径按用户确认的"两者之和"。
  - §12 GPU 单并发：worker 的 lease 仍然是 max=1；夜间运行由 Task 1 实现，并且是可选的。
  - §10.1 先 staging 后生产：Task 3（SYSTEM 身份发布 staging）、Task 6（生产仍然走 OWNER 和 staging 闸门）。
- **类型一致性**：
  - `JobPriority` 在 Task 1 定义，在 Task 2 和 3 中使用。
  - `BatchStage` 和 `Resolution` 在 Task 3 定义，在 Task 4、6、7 中使用（前端用 `STAGE_ORDER` 镜像同一套取值）。
  - `PublicationService.requestSystem` 在 Task 3 定义；生产发布在 Task 6 中复用现有的 `request`。
  - `ReviewTimeStats` 在 Task 5 定义，在 Task 4 的 progress 和 Task 7、8 中使用。
  - `BatchProperties.nightOpen` 在 Task 1 定义，在 Task 4 的 ETA 中使用。
- **迁移**：V22（Task 1）、V23（Task 2，同时补上 `generation_job.batch_id` 的外键）、V24（Task 5）。
- **与 C5 的交汇**：`GenerationJobStore` 被 C5（`park`、`external_ref`）和 C6（`priority`）同时修改，所以 C6 必须在 C5 合并之后开工；C5 中 H3 和 FFMPEG 的任务默认是交互优先级。
