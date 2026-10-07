# C1 骨架与素材导入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 搭好 Kiano 的平台骨架（认证、tenant、凭证加密、PG 任务队列、审计、对象存储），把 WooCommerce 的商品、变体、分类和价格只读同步进来，并支持按 SKU 分层、按文件夹批量导入实拍、自动质检和生成补拍清单。

**Architecture：** 单仓库，包含 `kiano-api`（Spring Boot 模块化单体，分 `platform` / `commerce` / `content` 三个模块，用 Spring Modulith 校验模块边界）和 `kiano-web`（Next.js 内部后台）。PostgreSQL 16 和 MinIO 用根目录的 Docker Compose 启动。浏览器直接调用 api（CORS + httpOnly cookie 里的 JWT）。Woo 同步作为 PG 任务队列里的任务运行。实拍文件逐个上传到 api，api 依次完成去重、质检、写入 MinIO、入库。

**Tech Stack：** Java 21、Spring Boot 4.1.x（Spring Framework 7、Spring Security 7、Jackson 3）、Spring Modulith 2.1.x、MyBatis-Plus（`mybatis-plus-spring-boot4-starter` 3.5.17+）、Flyway、PostgreSQL 16、AWS SDK v2 S3 客户端（对接 MinIO）、metadata-extractor（EXIF）、ffprobe、Testcontainers 2.x、WireMock 3.13.x；Next.js（App Router）+ TypeScript + Tailwind、自建的轻量 i18n 字典（zh / en）、Vitest；pnpm。

**Spec：** `docs/superpowers/specs/2026-10-07-content-module-design.md`（C1 部分：§2.1、§6、§8、§13、§14），上级架构 `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md`（§11、§12.1、§20、§22、§23.1）。

## Global Constraints

- Java 21；**Spring Boot 4**（4.1.x 的最新补丁版本，用户 2026-10-07 确认，取代 v1.2 中写的 Spring Boot 3）；ORM 用 MyBatis-Plus；PostgreSQL 16；对象存储用 S3 兼容的 MinIO；前端用 Next.js + TypeScript。
- Boot 4 的注意点：
  - Jackson 3 的包名是 `tools.jackson.*`，例如 `tools.jackson.databind.JsonNode`、`ObjectMapper`；注解仍在 `com.fasterxml.jackson.annotation`。
  - 自动配置按模块拆分了：Flyway 必须引入 `spring-boot-starter-flyway`，Web 是 `spring-boot-starter-webmvc`，测试用对应的 `*-test` starter。
  - 可空标注用 JSpecify 的 `org.jspecify.annotations.Nullable`。
  - Testcontainers 2.x 的容器类放在各自模块的包下（例如 `org.testcontainers.postgresql.PostgreSQLContainer`）。
  - 遇到 Boot 3 → 4 的 API 差异时，以 Spring 官方迁移指南为准；**不要因为兼容问题退回 Boot 3，也不要换掉 MyBatis-Plus**。真的走不通时停下来报告。
- 不引入 RabbitMQ、Quartz、ClickHouse、Redis、多 Agent 编排。异步任务一律走 PostgreSQL 队列表（`FOR UPDATE SKIP LOCKED`）+ Spring Scheduler。
- 除 `tenant`、`shot_requirement`、`product_category` 外，所有表都有 `tenant_id`；时间统一存 `timestamptz`（UTC），JVM 默认时区在 `main` 中设为 UTC；外部 ID 唯一约束为 `(tenant_id, external_id)`。
- 所有查询都显式带 `tenant_id` 参数。不使用 MyBatis-Plus 的多租户插件。
- C1 对 WooCommerce **只读**：只发 GET 请求。不碰 Paystack。
- REST 前缀为 `/api/v1`，统一错误格式 `{ "code", "message", "traceId", "details" }`。认证用 JWT，角色只有 `OWNER | OPERATOR | VIEWER`，权限 OWNER ⊃ OPERATOR ⊃ VIEWER。
- 集成凭证在应用层加密后存储（AES-256-GCM），密钥来自环境变量。凭证不以明文进入数据库、Git、日志、API 响应或 `audit_log`。
- 机密只放在 `.env`（不提交）；`.env.example` 记录所有键名。
- 模块之间只通过应用服务接口调用。`platform` 是开放模块，`commerce` 和 `content` 只暴露根包里的类型，由 `ApplicationModules.verify()` 测试强制检查。
- 实拍文件命名 `{SKU}_{镜头编号}.jpg|.mp4`，例如 `MG-BL200_P5.jpg`。镜头编号 P1–P9、V1–V3。
- 质检：照片短边 ≥ 2000px；清晰度用拉普拉斯方差；过曝或欠曝比例超标判为不合格。**阈值全部可配置**，在手工样板阶段标定。不合格的镜头记为 `RESHOOT`。
- 视频：竖屏 9:16，1080p 或 4K，30fps。
- `source_media` 有约束 `UNIQUE(tenant_id, sha256)`，重复导入按 sha256 去重。
- 金额币种为 GHS，界面显示格式为 `GH₵ 299`。
- **相对规格的有意偏差**（已在本计划中定下，后续 Sprint 沿用）：`source_media.status` 增加 `SUPERSEDED`（同一镜头重拍后旧版本的状态）；新增镜头编号 `PROMO`（现有宣传图，kind = `PROMO_IMAGE`，不进清单、不质检）；`category` 增加 `slug` 和 `parent_external_id`；`product` 增加 `price`（Woo 当前生效价）和 `parent_id`；`source_media` 增加 `original_file_name`、`thumb_object_key`、`content_type`、`size_bytes`。
- **界面和拍摄指引都是中英双语**（用户 2026-10-07 确认）：
  - 界面右上角可以切换 中文 / English。首次访问时按浏览器语言选择（`zh*` 用中文，其余用英文），选择结果存进 cookie `kiano_locale`。
  - 所有界面文字都通过 `t(key)` 输出，不允许在 JSX 里直接写文字。`zh` 和 `en` 两个字典的键必须完全一致，由类型和测试共同保证。
  - 拍摄指引在数据库中存 `guidance_en` 和 `guidance_zh` 两列，界面显示当前语言的那一列。补拍清单 CSV 同时输出两列。
  - 后端的 `message` 一律用英文，供日志和排错使用。界面按 `code`（错误码、`QcReason`、`ShotState` 等枚举）从字典中取当前语言的文字，字典里没有的 `code` 才回退显示 `message`。
- Commit 信息采用 conventional commits 格式，结尾加一行 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## Review Focus

1. **手机竖拍视频带旋转元数据**：编码尺寸是 1920×1080，但带 `rotation=-90`。必须按 1080×1920 竖屏处理，不能误报 `NOT_PORTRAIT`。测试放在 Task 10（`FfprobeJsonParserTest`）。
2. **JPEG 的 EXIF orientation = 6**：手机竖拍照片的像素是横向存储的。记录的宽高和生成的缩略图都必须是竖向。测试放在 Task 10（`PhotoQcTest`）和 Task 11（`MediaImportServiceTest`）。
3. **价格归一化造成误报改价**：Woo 返回的空字符串 `""` 应映射为 `null`；`"299"` 和库里的 `299.00` 必须视为相等（用 `compareTo`，不用 `equals`）。否则每次同步都会产生虚假的 `PRODUCT_PRICE_CHANGED`。测试放在 Task 6 和 Task 8。
4. **文件夹里的杂项文件和大小写变体**：`.DS_Store`、`._MG-BL200_P1.jpg`、`Thumbs.db`、`desktop.ini` 在前端直接跳过；`mg-bl200_p1.JPG`、`.jpeg` 能正确解析；SKU 本身带下划线时按最后一个 `_` 切分；HEIC 文件给出可操作的提示。测试放在 Task 10 和 Task 15。
5. **Woo 凭证错误或被拦截时必须给出可操作的错误**：401 时提示检查 Application Password（站点是 http 时还要设置 `WP_ENVIRONMENT_TYPE=local`）；Cloudflare 返回 HTML 时提示配置 WAF 放行。同步任务直接 FAILED、不重试，界面显示原因。测试放在 Task 6 和 Task 8。

---

## 文件结构

```
.gitignore  .env.example  docker-compose.yml  README.md  CLAUDE.md
kiano-api/
  pom.xml  mvnw  Dockerfile
  src/main/java/com/kiano/
    KianoApplication.java
    platform/                     # OPEN 模块
      package-info.java           # @ApplicationModule(type = OPEN)
      web/      ApiException, ApiError, GlobalExceptionHandler, TraceIdFilter
      auth/     Role, CurrentUser, CurrentUserArgumentResolver, JwtService, SecurityConfig,
                AuthController, AppUserEntity, AppUserMapper, BootstrapOwnerRunner, SecurityProperties
      audit/    ActorType, AuditEntry, AuditLog, AuditLogEntity, AuditLogMapper
      crypto/   CredentialCipher
      integration/ IntegrationStore, StoredIntegration, IntegrationEntity, IntegrationMapper
      queue/    TaskQueue, TaskHandler, TaskContext, TaskView, TaskStatus, TaskDispatcher,
                NonRetryableTaskException, QueueProperties
      storage/  ObjectStorage, S3ObjectStorage, StorageProperties, StorageConfig
    commerce/                     # 根包 = 对外 API
      CommercePort, CommerceProduct, CommerceCategory, CommerceException,
      ProductCatalog, ProductView, ProductPriceChanged
      woo/      WooCredentials, WooCommerceAdapter, CommercePortFactory, WooProperties
      sync/     ProductSyncService, SyncResult, ProductSyncTaskHandler, ProductSyncScheduler
      persistence/ ProductEntity, ProductMapper, CategoryEntity, CategoryMapper, ProductCatalogImpl
      web/      WooIntegrationController, SyncController
    content/
      ContentTier
      profile/  ProductProfileEntity, ProductProfileMapper, ProductProfileService, ProfileController
      shots/    ShotRequirementService, ShotRequirementView, ShotStatusService, ProductShotStatus,
                ShotStatusLine, ShotState, ReshootLine, ContentProductSummary
      media/    ShotFileName, ParsedShotFile, MediaKind, InvalidShotFileNameException,
                MediaImportService, ImportResult, SourceMediaEntity, SourceMediaMapper, Thumbnails
      qc/       QcReason, QcProperties, PhotoQc, PhotoQcResult, VideoInfo, VideoProbe,
                FfprobeVideoProbe, FfprobeJsonParser, VideoQc, UnreadableMediaException
      web/      ContentProductController, SourceMediaController, ReshootController
  src/main/resources/
    application.yml
    db/migration/ V1__platform.sql  V2__commerce.sql  V3__content.sql
  src/test/java/com/kiano/...      # 与 main 对应
  src/test/resources/application-test.yml, woo/*.json, ffprobe/*.json
kiano-web/
  package.json  Dockerfile  next.config.ts  vitest.config.ts
  src/lib/      api.ts  format.ts  import.ts  types.ts  (+ *.test.ts)
  src/i18n/     en.ts  zh.ts  index.tsx  i18n.test.ts
  src/app/      login/page.tsx
                (app)/layout.tsx  (app)/products/page.tsx  (app)/products/[id]/page.tsx
                (app)/import/page.tsx  (app)/reshoot/page.tsx  (app)/settings/integrations/page.tsx
```

---

### Task 1: 仓库与后端骨架

**Files:**
- Create: `.gitignore`, `.env.example`, `docker-compose.yml`
- Create: `kiano-api/pom.xml`, `kiano-api/mvnw`（及 `.mvn/`），`kiano-api/src/main/java/com/kiano/KianoApplication.java`, `kiano-api/src/main/java/com/kiano/platform/package-info.java`
- Create: `kiano-api/src/main/resources/application.yml`, `kiano-api/src/main/resources/db/migration/V1__platform.sql`
- Test: `kiano-api/src/test/java/com/kiano/TestcontainersConfiguration.java`, `KianoApplicationTests.java`, `ModularityTests.java`, `src/test/resources/application-test.yml`

**Interfaces:**
- Produces: `TestcontainersConfiguration`（`@TestConfiguration`），提供 `@ServiceConnection PostgreSQLContainer("postgres:16-alpine")`，以及一个 MinIO 容器（镜像 tag 与 compose 中一致），通过 `DynamicPropertyRegistrar` 注册 `kiano.storage.endpoint`、`kiano.storage.public-endpoint`、`kiano.storage.access-key`、`kiano.storage.secret-key`。之后所有 `@SpringBootTest` 都使用 `@Import(TestcontainersConfiguration.class) @ActiveProfiles("test")`。
- Produces: 表 `tenant`、`store`、`app_user`、`integration`、`audit_log`、`platform_task`，以及种子 tenant（`slug = 'kianosmart'`）。

- [ ] **Step 1: 写根目录文件**

`docker-compose.yml` 默认只包含 `postgres`（`postgres:16-alpine`，端口 `5433:5432`，volume `pg_data`）和 `minio`（固定为一个具体的 `minio/minio:RELEASE.*` tag；MinIO 社区版镜像从 2025 年末起不再更新，所以去 Docker Hub 查最新 tag 后写死，测试里也用同一个 tag；`9000` 为 API 端口，`9001` 为控制台端口；volume `minio_data`）。`api` 和 `web` 服务在 Task 16 加入，放在 `profiles: ["app"]` 下。

`.env.example` 的键（本地开发的值可以直接写进去，生产环境的值必须替换）：
```
POSTGRES_DB=kiano
POSTGRES_USER=kiano
POSTGRES_PASSWORD=kiano-local
POSTGRES_PORT=5433
MINIO_ROOT_USER=kiano
MINIO_ROOT_PASSWORD=kiano-local-secret
KIANO_DB_URL=jdbc:postgresql://localhost:5433/kiano?stringtype=unspecified
KIANO_STORAGE_ENDPOINT=http://localhost:9000
KIANO_STORAGE_PUBLIC_ENDPOINT=http://localhost:9000
KIANO_STORAGE_BUCKET=kiano
KIANO_JWT_SECRET=           # ≥ 32 字节随机串：openssl rand -base64 48
KIANO_CRYPTO_KEY=           # 32 字节的 base64：openssl rand -base64 32
KIANO_COOKIE_SECURE=false
KIANO_BOOTSTRAP_OWNER_EMAIL=
KIANO_BOOTSTRAP_OWNER_PASSWORD=
KIANO_WEB_ORIGIN=http://localhost:3000
KIANO_FFPROBE_PATH=ffprobe
NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
```
`.gitignore` 至少包含：`.env`、`kiano-api/target/`、`kiano-web/node_modules/`、`kiano-web/.next/`、`*.log`。

- [ ] **Step 2: 生成 kiano-api 工程**

在 start.spring.io 选 Maven、Java 21、Spring Boot 4.1.x，groupId 为 `com.kiano`，artifactId 为 `kiano-api`，包名 `com.kiano`。依赖：Spring Web（webmvc）、Security、OAuth2 Resource Server、Validation、Flyway（会生成 `spring-boot-starter-flyway`，另外手动加 `flyway-database-postgresql`）、PostgreSQL Driver、Actuator、Spring Modulith、Testcontainers。确认生成的 pom 中有 `spring-boot-testcontainers`，以及 Testcontainers 2.x 的 `testcontainers-postgresql`、`testcontainers-minio`、`testcontainers-junit-jupiter`。另外手动加入：`com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`（或更新版本）、`software.amazon.awssdk:s3`（用 BOM 管理版本）、`com.drewnoakes:metadata-extractor`、`org.apache.commons:commons-csv`，以及 test 范围的 `org.wiremock:wiremock-standalone:3.13.x` 和 `org.apache.commons:commons-imaging`。执行 `mvn -N wrapper:wrapper` 生成 `mvnw`。

`KianoApplication.main` 在 `SpringApplication.run` 之前调用 `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))`，并加上 `@EnableScheduling`。`platform/package-info.java` 标注 `@org.springframework.modulith.ApplicationModule(type = ApplicationModule.Type.OPEN)`。

`application.yml` 的要点：
```yaml
spring:
  config.import: optional:file:../.env[.properties]
  datasource: { url: "${KIANO_DB_URL}", username: "${POSTGRES_USER}", password: "${POSTGRES_PASSWORD}" }
  servlet.multipart: { max-file-size: 2GB, max-request-size: 2GB }
server.port: 8081
mybatis-plus.configuration.map-underscore-to-camel-case: true
kiano:
  security: { jwt-secret: "${KIANO_JWT_SECRET}", crypto-key: "${KIANO_CRYPTO_KEY}", cookie-secure: "${KIANO_COOKIE_SECURE:false}", token-ttl: 12h }
  bootstrap: { owner-email: "${KIANO_BOOTSTRAP_OWNER_EMAIL:}", owner-password: "${KIANO_BOOTSTRAP_OWNER_PASSWORD:}" }
  web.origin: "${KIANO_WEB_ORIGIN:http://localhost:3000}"
  storage: { endpoint: "${KIANO_STORAGE_ENDPOINT}", public-endpoint: "${KIANO_STORAGE_PUBLIC_ENDPOINT}", bucket: "${KIANO_STORAGE_BUCKET:kiano}", access-key: "${MINIO_ROOT_USER}", secret-key: "${MINIO_ROOT_PASSWORD}", region: us-east-1 }
  queue: { enabled: true, poll-interval: 2s, lease: 15m }
  commerce.sync-cron: "0 0 * * * *"
  media.ffprobe-path: "${KIANO_FFPROBE_PATH:ffprobe}"
```
JDBC URL 带 `stringtype=unspecified`，这样 String 类型的参数可以直接写入 `jsonb` 列。`application-test.yml` 写死测试用的 `jwt-secret` 和 `crypto-key`，设置 `kiano.queue.enabled: false`，datasource 交给 `@ServiceConnection`。

- [ ] **Step 3: 写 `V1__platform.sql`**

```sql
create table tenant (
  id bigint generated always as identity primary key,
  name text not null, slug text not null unique,
  currency char(3) not null default 'GHS', timezone text not null default 'Africa/Accra',
  status text not null default 'ACTIVE', created_at timestamptz not null default now());
insert into tenant (name, slug) values ('KianosMart', 'kianosmart');

create table store (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), platform text not null,
  base_url text not null, status text not null default 'ACTIVE',
  unique (tenant_id, platform));

create table app_user (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  email text not null, name text not null, password_hash text not null,
  role text not null check (role in ('OWNER','OPERATOR','VIEWER')),
  status text not null default 'ACTIVE', created_at timestamptz not null default now(),
  unique (tenant_id, email));           -- email 一律小写存储

create table integration (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), provider text not null,
  account_ref text not null, credentials_encrypted text not null,
  scopes text, api_version text, status text not null default 'ACTIVE',
  last_sync_at timestamptz, updated_at timestamptz not null default now(),
  unique (tenant_id, provider));

create table audit_log (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  actor_type text not null check (actor_type in ('USER','AI','RULE','SYSTEM')),
  actor_id text, action text not null, target_type text, target_id text,
  before_json jsonb, after_json jsonb, reason text, source text,
  created_at timestamptz not null default now());
create index audit_log_target on audit_log (tenant_id, target_type, target_id, created_at desc);

create table platform_task (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), type text not null,
  payload jsonb not null default '{}', dedupe_key text,
  status text not null check (status in ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  attempts int not null default 0, max_attempts int not null default 3,
  run_after timestamptz not null default now(), locked_by text, locked_until timestamptz,
  result jsonb, last_error text,
  created_at timestamptz not null default now(), started_at timestamptz, finished_at timestamptz);
create unique index platform_task_dedupe on platform_task (tenant_id, type, dedupe_key)
  where dedupe_key is not null and status in ('QUEUED','RUNNING');
create index platform_task_ready on platform_task (run_after) where status = 'QUEUED';
create index platform_task_latest on platform_task (tenant_id, type, id desc);
```

- [ ] **Step 4: 写测试**

```java
// KianoApplicationTests
@Test void contextLoads_andSeedsTenant() {
  assertThat(jdbc.queryForObject("select count(*) from tenant where slug='kianosmart'", Integer.class)).isEqualTo(1);
}
// ModularityTests
@Test void modulesRespectBoundaries() { ApplicationModules.of(KianoApplication.class).verify(); }
```

- [ ] **Step 5: 启动 Docker Desktop，然后运行测试**

Run: `cd kiano-api && ./mvnw -q test`
Expected: 两个测试 PASS（首次运行会拉取镜像）。

- [ ] **Step 6: Commit**

```bash
git add .gitignore .env.example docker-compose.yml kiano-api
git commit -m "chore: scaffold kiano-api with platform schema and testcontainers"
```

---

### Task 2: 统一错误格式与认证

**Files:**
- Create: `platform/web/{ApiException,ApiError,GlobalExceptionHandler,TraceIdFilter}.java`
- Create: `platform/auth/{Role,CurrentUser,CurrentUserArgumentResolver,JwtService,SecurityConfig,SecurityProperties,AuthController,AppUserEntity,AppUserMapper,BootstrapOwnerRunner}.java`
- Test: `platform/auth/AuthControllerTest.java`, `platform/auth/BootstrapOwnerRunnerTest.java`，以及测试源码里的 `platform/auth/TestOperatorController.java`

**Interfaces:**
- Produces: `ApiException(HttpStatus status, String code, String message)` 和 `ApiException(HttpStatus, String code, String message, Map<String,Object> details)`。所有业务错误都抛这个异常。
- Produces: `record ApiError(String code, String message, String traceId, Map<String,Object> details)`。
- Produces: 通用错误码：`UNAUTHENTICATED`(401)、`FORBIDDEN`(403)、`NOT_FOUND`(404)、`VALIDATION_FAILED`(400，`details.fields` 为 字段 → 消息)、`INTERNAL_ERROR`(500)。
- Produces: `enum Role { OWNER, OPERATOR, VIEWER }`；`record CurrentUser(long userId, long tenantId, Role role, String email)`，可以直接作为 controller 方法参数注入。
- Produces: 方法级权限 `@PreAuthorize("hasRole('OPERATOR')")` 等；`RoleHierarchy` 为 `ROLE_OWNER > ROLE_OPERATOR > ROLE_VIEWER`。
- Produces: 端点 `POST /api/v1/auth/login {email,password}` → 200 `{userId,email,name,role}`，并设置 cookie；`POST /api/v1/auth/logout` → 204；`GET /api/v1/auth/me` → 同 login 的响应。

- [ ] **Step 1: 写失败的测试**

```java
@Test void login_setsHttpOnlyCookie()      // Set-Cookie 含 kiano_token=…; HttpOnly; SameSite=Lax; Path=/; Max-Age=43200
@Test void login_isCaseInsensitiveOnEmail() // "Owner@Example.test" 能登录 owner@example.test
@Test void login_wrongPassword_returns401() // body.code == "AUTH_INVALID_CREDENTIALS", body.traceId 非空，响应头 X-Trace-Id == body.traceId
@Test void me_withoutToken_returns401()     // code == "UNAUTHENTICATED"
@Test void me_withCookie_returnsUser()      // role == "OWNER"
@Test void me_withBearerHeader_returnsUser()
@Test void logout_expiresCookie()           // Max-Age=0
@Test void viewer_onOperatorEndpoint_returns403()  // POST /api/v1/_test/operator → code "FORBIDDEN"
@Test void owner_onOperatorEndpoint_returns200()   // 验证角色层级
@Test void corsPreflight_fromWebOrigin_allowsCredentials() // Access-Control-Allow-Credentials: true
// BootstrapOwnerRunnerTest
@Test void createsOwnerOnce_whenNoUsers()   // run() 调两次 → 只有 1 个 OWNER；邮箱为空时不创建
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./mvnw -q test -Dtest='AuthControllerTest,BootstrapOwnerRunnerTest'`
Expected: FAIL（类不存在，编译失败）

- [ ] **Step 3: 实现**

- `SecurityConfig`：无状态 session；关闭 CSRF（cookie 为 SameSite=Lax，CORS 只允许 `kiano.web.origin` 且带 credentials）；放行 `/api/v1/auth/login` 和 `/actuator/health`；其余请求都要认证。自定义 `BearerTokenResolver`：先读 `Authorization: Bearer`，没有再读 cookie `kiano_token`。自定义 401/403 的 entry point 和 handler，输出 `ApiError`。启用 `@EnableMethodSecurity`。
- `JwtService.issue(AppUserEntity) -> String`：HS256（`NimbusJwtEncoder` + `ImmutableSecret`），claims 为 `sub=userId`、`tid`、`role`、`email`，有效期取 `kiano.security.token-ttl`。`jwt-secret` 不足 32 字节时启动失败（`IllegalStateException`）。JWT 解码器用同一个密钥。
- `TraceIdFilter`：每个请求生成一个 UUID，放进 MDC `traceId` 和响应头 `X-Trace-Id`；`GlobalExceptionHandler` 从 MDC 读取。
- `BootstrapOwnerRunner`（`ApplicationRunner`）：找到 slug 为 `kianosmart` 的 tenant；如果该 tenant 下没有任何用户，且配置了 owner 邮箱和密码，就创建一个 OWNER（密码用 `BCryptPasswordEncoder` 哈希，name 取邮箱 `@` 前的部分）。
- 登录失败时不区分"用户不存在"和"密码错误"。

- [ ] **Step 4: 运行测试，确认通过**

Run: `./mvnw -q test -Dtest='AuthControllerTest,BootstrapOwnerRunnerTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kiano-api
git commit -m "feat(platform): JWT cookie auth, roles, unified error format"
```

---

### Task 3: 审计日志、凭证加密与集成存储

**Files:**
- Create: `platform/audit/{ActorType,AuditEntry,AuditLog,AuditLogEntity,AuditLogMapper}.java`
- Create: `platform/crypto/CredentialCipher.java`
- Create: `platform/integration/{IntegrationStore,StoredIntegration,IntegrationEntity,IntegrationMapper}.java`
- Test: `CredentialCipherTest.java`、`AuditLogTest.java`、`IntegrationStoreTest.java`

**Interfaces:**
- Produces: `enum ActorType { USER, AI, RULE, SYSTEM }`；`record AuditEntry(long tenantId, ActorType actorType, String actorId, String action, String targetType, String targetId, Object before, Object after, String reason, String source)`；`AuditLog.record(AuditEntry)`。`before`/`after` 用 Jackson 序列化写入 jsonb，允许为 null；`record` 加入调用方的事务。
- Produces: `CredentialCipher.encrypt(String plaintext, String aad) -> String`、`decrypt(String ciphertext, String aad) -> String`。格式为 `"v1:" + base64(iv[12] || ciphertext+tag)`；算法 AES/GCM/NoPadding，tag 128 位；密钥为 `kiano.security.crypto-key` base64 解码后的 32 字节，长度不对时启动失败。
- Produces: `IntegrationStore.save(long tenantId, String provider, String accountRef, Object credentials)`：按 `(tenant_id, provider)` upsert，credentials 先序列化为 JSON 再加密，AAD = `tenantId + ":" + provider`。
- Produces: `IntegrationStore.find(long tenantId, String provider) -> Optional<StoredIntegration>`、`IntegrationStore.findAllActive(String provider) -> List<StoredIntegration>`、`IntegrationStore.credentials(StoredIntegration, Class<T>) -> T`、`IntegrationStore.markSynced(long tenantId, String provider, Instant at)`。
- Produces: `record StoredIntegration(long id, long tenantId, String provider, String accountRef, String status, Instant lastSyncAt, Instant updatedAt)`。这个 record 不包含凭证。

- [ ] **Step 1: 写失败的测试**

```java
// CredentialCipherTest（纯单元测试）
@Test void roundTrip()                      // decrypt(encrypt("s3cret","1:WOO"),"1:WOO") == "s3cret"
@Test void sameInput_differentCiphertext()  // 两次 encrypt 结果不同（随机 IV）
@Test void wrongAad_fails()                 // AAD 不同 → 抛异常
@Test void tamperedCiphertext_fails()
@Test void keyNot32Bytes_rejected()         // 构造时抛 IllegalStateException
// AuditLogTest（SpringBootTest）
@Test void record_persistsJsonBeforeAfter() // 读回的 after_json->>'contentTier' == "HERO"
// IntegrationStoreTest（SpringBootTest）
@Test void save_thenReadCredentials()       // credentials(find(...), WooCreds.class).password() == "app pass"
@Test void storedColumn_hasNoPlaintext()    // credentials_encrypted 以 "v1:" 开头，且不包含 "app pass"
@Test void saveTwice_upsertsSingleRow()
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./mvnw -q test -Dtest='CredentialCipherTest,AuditLogTest,IntegrationStoreTest'`
Expected: FAIL

- [ ] **Step 3: 实现上述三个组件**（Mapper 用 MyBatis-Plus 的 `BaseMapper`；jsonb 字段在实体中用 `String` 存放）

- [ ] **Step 4: 运行测试，确认通过**

- [ ] **Step 5: Commit**

```bash
git add kiano-api
git commit -m "feat(platform): audit log, AES-GCM credential cipher, integration store"
```

---

### Task 4: PostgreSQL 任务队列

**Files:**
- Create: `platform/queue/{TaskQueue,TaskHandler,TaskContext,TaskView,TaskStatus,TaskDispatcher,NonRetryableTaskException,QueueProperties}.java`
- Test: `platform/queue/TaskQueueTest.java`

**Interfaces:**
- Produces: `TaskQueue.enqueue(long tenantId, String type, Object payload, @Nullable String dedupeKey) -> Optional<Long>`。如果同一 `(tenant, type, dedupeKey)` 已有 QUEUED 或 RUNNING 的任务，返回 `Optional.empty()`（靠部分唯一索引冲突判断，不先查再插）。
- Produces: `TaskQueue.latest(long tenantId, String type) -> Optional<TaskView>`；`record TaskView(long id, String type, TaskStatus status, int attempts, JsonNode result, String lastError, Instant createdAt, Instant startedAt, Instant finishedAt)`（`JsonNode` 为 `tools.jackson.databind.JsonNode`，下同）。
- Produces: `interface TaskHandler { String type(); Object handle(TaskContext ctx) throws Exception; }`，返回值序列化后写入 `result`；`record TaskContext(long taskId, long tenantId, JsonNode payload, int attempt)`。
- Produces: `TaskDispatcher.pollOnce() -> boolean`：认领并执行一个任务，没有任务时返回 false。`kiano.queue.enabled=true` 时由 `@Scheduled(fixedDelayString = "${kiano.queue.poll-interval}")` 调用。
- Produces: `NonRetryableTaskException(String message)`。

认领 SQL（先回收租约过期的 RUNNING 任务，再认领）：
```sql
update platform_task set status='QUEUED', locked_by=null, locked_until=null
 where status='RUNNING' and locked_until < now();
update platform_task t set status='RUNNING', attempts=t.attempts+1, locked_by=:workerId,
       locked_until=now() + :lease, started_at=now()
 where t.id = (select id from platform_task where status='QUEUED' and run_after <= now()
               order by id for update skip locked limit 1)
returning *;
```
失败处理：抛出 `NonRetryableTaskException`、找不到 handler、或 `attempts >= max_attempts` 时，状态设为 FAILED 并写入 `finished_at`；否则重新设为 QUEUED，`run_after = now() + 30s × 2^(attempts-1)`。两种情况都写 `last_error`（异常消息，截断到 2000 字符）。handler 不在认领的事务中执行。

- [ ] **Step 1: 写失败的测试**（SpringBootTest，测试中注册两个 handler bean：`ok` 返回 `Map.of("n",1)`；`boom` 按配置抛可重试或不可重试异常）

```java
@Test void enqueue_sameDedupeKeyWhileQueued_returnsEmpty()
@Test void enqueue_afterPreviousSucceeded_allowsNew()
@Test void pollOnce_runsHandler_storesResult()        // SUCCEEDED，result.n == 1，attempts == 1
@Test void retryableFailure_requeuesWithBackoff()     // QUEUED，attempts 1，run_after > now()+25s
@Test void retryableFailure_atMaxAttempts_fails()     // 先把 max_attempts 设为 1 → FAILED，last_error 非空
@Test void nonRetryable_failsImmediately()
@Test void unknownType_failsWithNoHandler()           // last_error 包含 "no handler"
@Test void expiredLease_isReclaimed()                 // 插入 RUNNING 且 locked_until 已过期 → pollOnce 执行它，attempts+1
@Test void concurrentClaims_neverClaimSameTask()      // 1 个任务，两个线程同时 pollOnce → handler 只被调用 1 次
@Test void pollOnce_whenEmpty_returnsFalse()
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./mvnw -q test -Dtest=TaskQueueTest`

- [ ] **Step 3: 实现**

- [ ] **Step 4: 运行测试，确认通过**

- [ ] **Step 5: Commit**

```bash
git add kiano-api
git commit -m "feat(platform): postgres task queue with SKIP LOCKED, leases and backoff"
```

---

### Task 5: 对象存储

**Files:**
- Create: `platform/storage/{ObjectStorage,S3ObjectStorage,StorageProperties,StorageConfig}.java`
- Test: `platform/storage/S3ObjectStorageTest.java`

**Interfaces:**
- Produces: `ObjectStorage.put(String key, Path file, String contentType)`、`put(String key, byte[] bytes, String contentType)`、`exists(String key) -> boolean`、`presignGet(String key, Duration ttl) -> URI`。
- 实现：AWS SDK v2 `S3Client`，`endpointOverride(endpoint)`，`forcePathStyle(true)`，region 取配置值。**`S3Presigner` 使用 `public-endpoint` 签名**：浏览器访问的主机名必须和签名里的主机名一致，api 在容器中运行时 endpoint 是 `http://minio:9000`，而浏览器访问的是 `http://localhost:9000`。
- 启动时如果 bucket 不存在就创建（`ApplicationRunner`）。

- [ ] **Step 1: 写失败的测试**

```java
@Test void bucketCreatedOnStartup()
@Test void put_thenExists()
@Test void presignGet_usesPublicEndpoint_andServesBytes() // URI 的 host:port 等于 public-endpoint；用 java.net.http.HttpClient GET 后，返回的字节与写入的一致
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `./mvnw -q test -Dtest=S3ObjectStorageTest`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**
- [ ] **Step 5: Commit** — `git commit -m "feat(platform): S3-compatible object storage with public-endpoint presigning"`

---

### Task 6: Commerce Port 与 WooCommerce 适配器

**Files:**
- Create: `commerce/{CommercePort,CommerceProduct,CommerceCategory,CommerceException}.java`
- Create: `commerce/woo/{WooCredentials,WooCommerceAdapter,WooProperties}.java`
- Test: `commerce/woo/WooCommerceAdapterTest.java`，`src/test/resources/woo/{products-page1.json,products-page2.json,variations-101.json,categories.json}`

**Interfaces:**
- Produces:
```java
public interface CommercePort {
  List<CommerceCategory> listCategories();
  List<CommerceProduct> listProducts();                         // 全部分页，status=any
  List<CommerceProduct> listVariations(CommerceProduct parent);  // parent.type() == "variable"
  void ping();                                                  // GET products?per_page=1
}
public record CommerceProduct(long externalId, Long parentExternalId, String type, String sku,
    String brand, String name, String slug, BigDecimal regularPrice, BigDecimal salePrice,
    BigDecimal price, Integer stockQty, String stockStatus, String status, String permalink,
    String imageUrl, Instant modifiedAt, List<Long> categoryExternalIds) {}
public record CommerceCategory(long externalId, Long parentExternalId, String name, String slug) {}
public class CommerceException extends RuntimeException { String code(); boolean retryable(); }
public record WooCredentials(String baseUrl, String username, String applicationPassword) {}
```
- `WooCommerceAdapter(WooCredentials, WooProperties)` 实现 `CommercePort`。用 `RestClient`，HTTP Basic 认证（`username:applicationPassword`），连接超时 10 秒、读取超时 60 秒。路径前缀 `{baseUrl 去掉末尾 /}/wp-json/wc/v3`。分页参数 `per_page=100&page=N`，翻页直到 N > 响应头 `X-WP-TotalPages`。商品请求带 `status=any`；分类请求用 `/products/categories`；变体请求用 `/products/{id}/variations`。
- 字段映射：价格字符串为 `""` 或 null 时映射为 `null`，否则 `new BigDecimal(s).setScale(2)`；`images[0].src` 映射为 imageUrl，图片列表为空时为 null；`date_modified_gmt` 按 UTC 解析为 Instant；`brands[0].name` 映射为 brand，没有时为 null；`parent_id == 0` 映射为 null。变体的 `type` 固定为 `"variation"`，`parentExternalId` 为父商品 ID；变体没有 `name` 时用 `parent.name() + " - " + attributes[].option 用 ", " 连接`。
- 错误：429 和 5xx 视为可重试，在适配器内最多尝试 `WooProperties.maxAttempts`（默认 3）次，退避时间 `WooProperties.backoff`（默认 2 秒，测试中设为 10 毫秒），仍然失败就抛 `CommerceException("WOO_UNAVAILABLE", retryable=true)`。401 和 403 抛 `CommerceException("WOO_AUTH_FAILED", retryable=false)`，消息为 `"WooCommerce rejected the credentials. Check the username and Application Password; on an http:// site WordPress also needs WP_ENVIRONMENT_TYPE=local."`。响应的 Content-Type 不是 JSON 时（例如 Cloudflare 质询页）抛 `CommerceException("WOO_BLOCKED", retryable=false)`，消息为 `"WooCommerce returned a non-JSON response (likely a Cloudflare challenge). Allow the Kiano server IP for /wp-json/wc/ in the WAF."`。

- [ ] **Step 1: 写失败的测试**（WireMock）

```java
@Test void listProducts_followsTotalPagesHeader()     // 第 1 页 X-WP-TotalPages: 2 → 一共请求 2 次，合并为 3 个商品
@Test void listProducts_sendsBasicAuthStatusAnyPerPage100()
@Test void mapsPrices_emptyStringToNull_andScale2()   // regular "299" → 299.00；sale "" → null
@Test void mapsImagesBrandParentAndModifiedUtc()      // images 为 [] → null；parent_id 0 → null；"2026-10-01T08:00:00" → 2026-10-01T08:00:00Z
@Test void listVariations_setsTypeParentAndFallbackName() // "Morgan Fan - Black"
@Test void baseUrlTrailingSlash_isNormalised()
@Test void retriesOn429_thenSucceeds()
@Test void persistent500_throwsRetryable()            // 一共请求 3 次
@Test void on401_throwsAuthFailedWithHint()           // retryable false；消息包含 "WP_ENVIRONMENT_TYPE=local"
@Test void htmlResponse_throwsBlockedWithWafHint()    // 403 + text/html → code WOO_BLOCKED
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `./mvnw -q test -Dtest=WooCommerceAdapterTest`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**
- [ ] **Step 5: Commit** — `git commit -m "feat(commerce): read-only WooCommerce adapter behind CommercePort"`

---

### Task 7: Woo 集成配置 API

**Files:**
- Create: `commerce/woo/CommercePortFactory.java`、`commerce/web/WooIntegrationController.java`
- Test: `commerce/web/WooIntegrationControllerTest.java`

**Interfaces:**
- Consumes: `IntegrationStore`、`CredentialCipher`（经由 store）、`AuditLog`、`WooCommerceAdapter`。
- Produces: `CommercePortFactory.forTenant(long tenantId) -> CommercePort`；没有配置集成时抛 `ApiException(409, "WOO_NOT_CONFIGURED", ...)`。provider 常量为 `"WOOCOMMERCE"`。
- Produces: `PUT /api/v1/integrations/woocommerce {baseUrl, username, applicationPassword}`（OWNER）→ 200 `{baseUrl, username, configured:true, lastSyncAt}`。同时按 `(tenant_id, platform='WOOCOMMERCE')` upsert `store.base_url`。`applicationPassword` 为空时保留原密码，只更新其余字段。
- Produces: `GET /api/v1/integrations/woocommerce`（OWNER）→ 返回同样的结构，从不返回密码；未配置时为 `{configured:false}`。
- Produces: `POST /api/v1/integrations/woocommerce/test`（OWNER）→ 200 `{ok:true}`；ping 失败时返回 200 `{ok:false, code, message}`。
- 审计：`action = "INTEGRATION_UPDATED"`，`targetType = "integration"`，before/after 只包含 `baseUrl` 和 `username`。

- [ ] **Step 1: 写失败的测试**

```java
@Test void owner_put_thenGet_neverReturnsPassword()   // 响应 JSON 中没有 applicationPassword 字段
@Test void put_upsertsStoreRow()
@Test void put_blankPassword_keepsExisting()
@Test void operator_put_returns403()
@Test void test_ok_againstWireMock()
@Test void test_reportsAuthFailure()                  // {ok:false, code:"WOO_AUTH_FAILED"}
@Test void audit_hasNoSecret()                        // audit_log 中所有列都不包含密码明文
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest=WooIntegrationControllerTest`
- [ ] **Step 5: Commit** — `git commit -m "feat(commerce): WooCommerce integration settings with encrypted credentials"`

---

### Task 8: 商品只读同步

**Files:**
- Create: `kiano-api/src/main/resources/db/migration/V2__commerce.sql`
- Create: `commerce/{ProductCatalog,ProductView,ProductPriceChanged}.java`
- Create: `commerce/persistence/{ProductEntity,ProductMapper,CategoryEntity,CategoryMapper,ProductCatalogImpl}.java`
- Create: `commerce/sync/{ProductSyncService,SyncResult,ProductSyncTaskHandler,ProductSyncScheduler}.java`、`commerce/web/SyncController.java`
- Test: `commerce/sync/ProductSyncServiceTest.java`、`commerce/web/SyncControllerTest.java`、`commerce/persistence/ProductCatalogTest.java`

**Interfaces:**
- Consumes: `CommercePortFactory.forTenant`、`TaskQueue`、`TaskHandler`、`AuditLog`、`IntegrationStore.findAllActive/markSynced`。
- Produces:
```java
public interface ProductCatalog {
  Optional<ProductView> findById(long tenantId, long productId);
  Optional<ProductView> findBySku(long tenantId, String sku);   // trim + 不区分大小写；排除 status='missing'
  List<ProductView> listTopLevel(long tenantId);                // parent_id 为 null，且 status <> 'missing'，按 sku 排序
}
public record ProductView(long id, Long parentId, String type, String sku, String name,
    BigDecimal regularPrice, BigDecimal salePrice, BigDecimal price, Integer stockQty,
    String stockStatus, String status, String imageUrl, List<String> categorySlugs) {}
public record ProductPriceChanged(long tenantId, long productId, String sku,
    BigDecimal oldRegular, BigDecimal newRegular, BigDecimal oldSale, BigDecimal newSale) {}
public record SyncResult(int categories, int products, int variations, int priceChanges, int markedMissing) {}
```
- `ProductSyncService.syncAll(long tenantId) -> SyncResult`：先把 Woo 的数据全部取到内存，再在**一个事务**里按顺序处理：upsert 分类（第二遍回填 `parent_external_id`）→ upsert 商品 → 对 `type='variable'` 的商品拉取变体并 upsert（设置 `parent_id`）→ 重建每个商品的 `product_category` → 本轮没有出现的商品设为 `status='missing'`。商品以前是 missing、本轮重新出现时，status 恢复为 Woo 返回的值。价格比较用 `compareTo`，null 只和 null 相等；`regular_price` 或 `sale_price` 变化时写 `audit_log`（actor SYSTEM，`action="PRODUCT_PRICE_CHANGED"`，`targetType="product"`，`source="WOO_SYNC"`），并发布 `ProductPriceChanged`（C4 会用 `@TransactionalEventListener` 消费）。新插入的商品不算改价。事务成功后调用 `markSynced`。
- 任务类型 `"WOO_PRODUCT_SYNC"`，dedupeKey `"woo-product-sync"`，handler 返回 `SyncResult`；遇到 `CommerceException` 且 `retryable()==false` 时转为 `NonRetryableTaskException`，消息使用 CommerceException 的消息。
- `ProductSyncScheduler`：按 `kiano.commerce.sync-cron` 定时运行，为每个有活跃 WOOCOMMERCE 集成的 tenant 入队一次同步。
- 端点：`POST /api/v1/commerce/sync`（OPERATOR）→ 202 `{taskId}`，已有排队中的同步时返回 202 `{alreadyQueued:true}`，未配置时返回 409 `WOO_NOT_CONFIGURED`。`GET /api/v1/commerce/sync/latest`（VIEWER）→ `TaskView`，从未同步过时返回 204。

`V2__commerce.sql`：
```sql
create table category (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), external_id bigint not null,
  parent_external_id bigint, name text not null, slug text not null,
  synced_at timestamptz not null, unique (tenant_id, external_id));

create table product (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), store_id bigint not null references store(id),
  external_id bigint not null, parent_external_id bigint, parent_id bigint references product(id),
  type text not null, sku text, brand text, name text not null, slug text,
  regular_price numeric(12,2), sale_price numeric(12,2), price numeric(12,2),
  stock_qty int, stock_status text, status text not null,
  permalink text, image_url text, woo_modified_at timestamptz, synced_at timestamptz not null,
  unique (tenant_id, external_id));
create index product_sku_ci on product (tenant_id, lower(sku));
create index product_parent on product (parent_id);

create table product_category (
  product_id bigint not null references product(id) on delete cascade,
  category_id bigint not null references category(id) on delete cascade,
  primary key (product_id, category_id));
```

- [ ] **Step 1: 写失败的测试**（用 `@MockitoBean CommercePortFactory` 返回一个可编程的假 `CommercePort`；测试数据为 2 个分类、2 个 simple 商品、1 个 variable 商品带 2 个变体）

```java
@Test void firstSync_insertsEverything()          // SyncResult(2, 3, 2, 0, 0)；变体的 parent_id 指向 variable 商品；product_category 行齐全
@Test void secondIdenticalSync_isNoOp()           // 行数不变，priceChanges == 0（库中 299.00 对比 Woo "299"）
@Test void priceChange_auditsAndPublishesEvent()  // @RecordApplicationEvents：1 个 ProductPriceChanged，old 299.00 → new 279.00；audit_log 有 1 行
@Test void absentProduct_markedMissing_thenRestored()
@Test void portFailureMidSync_rollsBackEverything() // listVariations 抛异常 → 库中无变化，没有商品被标为 missing
@Test void nonRetryableCommerceError_failsTask()    // WOO_AUTH_FAILED → 执行 pollOnce 后任务 FAILED，last_error 包含 "Application Password"
// ProductCatalogTest
@Test void findBySku_isTrimmedCaseInsensitive()   // " mg-bl200 " 能找到 MG-BL200
@Test void findBySku_variation_returnsParentId()
@Test void listTopLevel_excludesVariationsAndMissing_includesCategorySlugs()
// SyncControllerTest
@Test void postSync_twice_secondAlreadyQueued()
@Test void postSync_notConfigured_returns409()
@Test void viewer_postSync_returns403()
@Test void latest_returnsTaskView()
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `./mvnw -q test -Dtest='ProductSyncServiceTest,ProductCatalogTest,SyncControllerTest'`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**；再运行 `./mvnw -q test -Dtest=ModularityTests`，确认模块边界检查仍然通过
- [ ] **Step 5: Commit** — `git commit -m "feat(commerce): read-only product/variation/category sync with price-change events"`

---

### Task 9: content 模块的数据表与 SKU 分层

**Files:**
- Create: `kiano-api/src/main/resources/db/migration/V3__content.sql`
- Create: `content/ContentTier.java`、`content/media/MediaKind.java`、`content/profile/{ProductProfileEntity,ProductProfileMapper,ProductProfileService,ProfileController}.java`
- Create: `content/shots/{ShotRequirementService,ShotRequirementView}.java`
- Test: `content/profile/ProfileControllerTest.java`、`content/shots/ShotRequirementServiceTest.java`

**Interfaces:**
- Consumes: `ProductCatalog.findById`、`AuditLog`。
- Produces: `enum ContentTier { HERO, STANDARD }`。
- Produces: `ProductProfileService.tierOf(long tenantId, long productId) -> ContentTier`（没有 profile 记录时为 STANDARD）；`tiersFor(long tenantId) -> Map<Long, ContentTier>`；`setTier(CurrentUser user, long productId, ContentTier tier) -> ContentTier`（审计 `action="PRODUCT_PROFILE_UPDATED"`，`targetType="product"`，before/after 为 `{contentTier}`）。
- Produces: `PATCH /api/v1/products/{id}/profile {contentTier}`（OPERATOR）→ 200 `{productId, contentTier}`；商品不存在时 404 `NOT_FOUND`；目标是变体时 422 `NOT_TOP_LEVEL_PRODUCT`；tier 非法时 400 `VALIDATION_FAILED`。
- Produces: `ShotRequirementService.requiredFor(ContentTier tier, List<String> categorySlugs) -> List<ShotRequirementView>`，按 `sort_order` 排序；`record ShotRequirementView(String code, MediaKind kind, boolean required, String guidanceEn, String guidanceZh)`（本任务创建 `enum MediaKind { PHOTO, VIDEO, PROMO_IMAGE }`，Task 10 起沿用）。tier 规则：`shot_requirement.tier='STANDARD'` 的行适用于两种 tier，`'HERO'` 的行只适用于 HERO。category 规则：如果存在 `category` 不为 null 且某个商品分类 slug 包含该值（不区分大小写）的行，就用这一行的两种语言的 guidance 替换通用行；多个匹配时取 `sort_order` 最小的一行。

`V3__content.sql`：
```sql
create table product_profile (
  product_id bigint primary key references product(id),
  tenant_id bigint not null references tenant(id),
  sku_role text, role_source text not null default 'MANUAL',
  content_tier text not null default 'STANDARD' check (content_tier in ('HERO','STANDARD')),
  product_dna_json jsonb, updated_at timestamptz not null default now());

create table shot_requirement (
  id bigint generated always as identity primary key,
  code text not null, kind text not null check (kind in ('PHOTO','VIDEO')),
  tier text not null check (tier in ('STANDARD','HERO')),
  category text, required boolean not null default true,
  guidance_en text not null, guidance_zh text not null, sort_order int not null);
create unique index shot_requirement_code_cat on shot_requirement (code, coalesce(category, ''));

insert into shot_requirement (code, kind, tier, category, guidance_en, guidance_zh, sort_order) values
 ('P1','PHOTO','STANDARD',null,'Front, eye level','正面，平视',10),
 ('P2','PHOTO','STANDARD',null,'Front-left 45°','左前 45°',20),
 ('P3','PHOTO','STANDARD',null,'Front-right 45°','右前 45°',30),
 ('P4','PHOTO','STANDARD',null,'Side','侧面',40),
 ('P5','PHOTO','STANDARD',null,'Back + rating-plate close-up (used to verify specs)','背面 + 铭牌特写（用于核对参数）',50),
 ('P6','PHOTO','STANDARD',null,'Top, opening or inside','顶部、开口或内部',60),
 ('P7','PHOTO','STANDARD',null,'Control panel / buttons close-up','控制面板、按钮特写',70),
 ('P8','PHOTO','STANDARD',null,'All accessories + packaging box','配件全家福 + 包装盒',80),
 ('P9','PHOTO','HERO',null,'Size reference (in hand, or next to a 1.5L water bottle)','尺寸参照（手持或旁边放 1.5L 水瓶）',90),
 ('V1','VIDEO','HERO',null,'Operation demo, 10–20 s, keep original sound','操作演示 10–20 秒，保留原声',100),
 ('V2','VIDEO','HERO',null,'360° slow orbit','360° 慢速环绕',110),
 ('V3','VIDEO','HERO',null,'Unboxing','开箱',120),
 ('V1','VIDEO','HERO','blender','Pepper, tomato, onion → blended into sauce','辣椒、番茄、洋葱 → 打成酱',101),
 ('V1','VIDEO','HERO','kettle','Fill with water → boil → automatic shut-off','注水 → 烧开 → 自动断电',102),
 ('V1','VIDEO','HERO','rice-cooker','Add rice → start → cooked rice','放米 → 启动 → 煮好的米饭',103),
 ('V1','VIDEO','HERO','cooker','Add rice → start → cooked rice','放米 → 启动 → 煮好的米饭',104),
 ('V1','VIDEO','HERO','iron','Iron a shirt smooth','熨平一件衬衫',105),
 ('V1','VIDEO','HERO','steamer','Iron a shirt smooth','熨平一件衬衫',106),
 ('V1','VIDEO','HERO','air-fryer','Fries or chicken wings, little oil','薯条或鸡翅，少油',107),
 ('V1','VIDEO','HERO','fan','Switch on, change speed, oscillate','开机、调档、摇头',108);

create table source_media (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  shot_code text not null, kind text not null check (kind in ('PHOTO','VIDEO','PROMO_IMAGE')),
  original_file_name text not null, object_key text not null, thumb_object_key text,
  content_type text not null, size_bytes bigint not null,
  width int, height int, duration_s numeric(8,2), sha256 char(64) not null,
  qc_json jsonb not null default '{}',
  status text not null check (status in ('ACCEPTED','RESHOOT','SUPERSEDED')),
  uploaded_by bigint references app_user(id), uploaded_at timestamptz not null default now(),
  unique (tenant_id, sha256));
create index source_media_current on source_media (tenant_id, product_id, shot_code)
  where status <> 'SUPERSEDED';
```
（`guidance_zh` 原样取自规格 §6 的表格，`guidance_en` 是它的译文。`kind` 列不允许 `PROMO_IMAGE`，因为宣传图不进拍摄清单。）

- [ ] **Step 1: 写失败的测试**

```java
// ShotRequirementServiceTest
@Test void standard_isP1toP8()   // codes == [P1..P8]
@Test void hero_isP1toP9_V1toV3()
@Test void categoryOverride_kettle()   // slugs ["electric-kettles"] → V1 guidanceEn 以 "Fill with water" 开头，guidanceZh == "注水 → 烧开 → 自动断电"
@Test void noCategoryMatch_usesGeneric()
@Test void everyRow_hasBothLanguages()  // 所有行的 guidanceEn 和 guidanceZh 都非空
// ProfileControllerTest（商品通过 ProductMapper 直接插入测试数据）
@Test void defaultTier_isStandard()
@Test void patch_setsHero_andAudits()   // audit before {contentTier:STANDARD} after {contentTier:HERO}
@Test void patch_variation_returns422()
@Test void patch_unknownProduct_returns404()
@Test void patch_invalidTier_returns400()
@Test void viewer_patch_returns403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='ShotRequirementServiceTest,ProfileControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): SKU tiering and shot checklist requirements"`

---

### Task 10: 文件名解析与质检（纯逻辑）

**Files:**
- Create: `content/media/{ShotFileName,ParsedShotFile,InvalidShotFileNameException}.java`
- Create: `content/qc/{QcReason,QcProperties,PhotoQc,PhotoQcResult,VideoInfo,VideoProbe,FfprobeVideoProbe,FfprobeJsonParser,VideoQc,UnreadableMediaException}.java`
- Test: `ShotFileNameTest.java`、`PhotoQcTest.java`、`FfprobeJsonParserTest.java`、`VideoQcTest.java`；`src/test/resources/ffprobe/{portrait-sidedata.json,portrait-tag.json,landscape.json}`

**Interfaces:**
- Produces: `ShotFileName.parse(String fileName) -> ParsedShotFile` 和 `record ParsedShotFile(String sku, String shotCode, MediaKind kind, String extension)`。解析规则：去掉目录部分；名字以 `.` 开头时拒绝；在最后一个 `.` 处拆出扩展名，扩展名转小写；在最后一个 `_` 处拆出 SKU 和镜头编号，镜头编号转大写后必须匹配 `^(P[1-9]|V[1-3]|PROMO)$`；SKU 去除首尾空白后不能为空，大小写保持原样。允许的扩展名：P* 和 PROMO 为 `jpg|jpeg`，V* 为 `mp4|mov`。失败时抛 `InvalidShotFileNameException(String code, String message, String extension)`，code 为 `INVALID_FILE_NAME` 或 `UNSUPPORTED_FILE_TYPE`，`extension` 为小写扩展名，没有扩展名时为 null。界面根据 `extension == "heic"` 显示本地化的 HEIC 提示。`heic|heif` 的消息必须是 `"HEIC is not supported. Export as JPEG (iPhone: Settings › Camera › Formats › Most Compatible)."`。
- Produces: `enum QcReason { LOW_RESOLUTION, BLURRY, OVEREXPOSED, UNDEREXPOSED, NOT_PORTRAIT, FPS_OUT_OF_RANGE, DURATION_OUT_OF_RANGE }`。
- Produces: `QcProperties`（前缀 `kiano.content.qc`），默认值写进 `application.yml`：
```yaml
kiano.content.qc:
  photo: { min-short-side: 2000, analysis-long-side: 1024, blur-min-variance: 60,
           clip-high: 250, clip-low: 5, max-over-ratio: 0.30, max-under-ratio: 0.30 }
  video: { min-short-side: 1080, fps-min: 29.0, fps-max: 31.0, v1-min-seconds: 10, v1-max-seconds: 20 }
```
- Produces: `PhotoQc.evaluate(Path jpeg, boolean applyRules) -> PhotoQcResult`；`record PhotoQcResult(int width, int height, int exifOrientation, double blurVariance, double overRatio, double underRatio, List<QcReason> reasons, byte[] thumbnailJpeg)`。宽高是按 EXIF orientation 旋转后的**显示**尺寸，orientation 为 5–8 时宽高互换。`applyRules=false`（PROMO）时 `reasons` 为空。文件无法解码时抛 `UnreadableMediaException`。
  - 算法：用 `ImageReader` 从文件头读出原始宽高（不完全解码）；读取时设 `setSourceSubsampling(s, s, 0, 0)`，其中 `s = max(1, ceil(长边 / analysis-long-side))`；转为灰度，亮度 = 0.299R + 0.587G + 0.114B；对内部像素做 4 邻域拉普拉斯 `[0,1,0;1,-4,1;0,1,0]`，结果的方差即 `blurVariance`；`overRatio` = 亮度 ≥ clip-high 的像素占比，`underRatio` = 亮度 ≤ clip-low 的像素占比。规则：短边 < min-short-side 判 LOW_RESOLUTION；方差 < blur-min-variance 判 BLURRY；`overRatio` 超过上限判 OVEREXPOSED，`underRatio` 超过上限判 UNDEREXPOSED。
  - 缩略图：把降采样后的图按 EXIF orientation 的 8 种取值做旋转或镜像，再缩放到长边 400px，编码为 JPEG。EXIF 用 metadata-extractor 的 `ExifIFD0Directory.TAG_ORIENTATION` 读取，缺失时按 1 处理。
- Produces: `interface VideoProbe { VideoInfo probe(Path file); }`；`record VideoInfo(int width, int height, double durationSeconds, double fps)`，宽高为显示尺寸。`FfprobeVideoProbe` 执行 `{ffprobe-path} -v error -print_format json -show_streams -show_format <file>`，超时 30 秒，把输出交给 `FfprobeJsonParser.parse(String json) -> VideoInfo`。解析规则：取第一个 `codec_type=video` 的流；旋转角度取自 `side_data_list[].rotation`，没有时用 `tags.rotate`，角度为 ±90 或 ±270 时宽高互换；fps 由 `avg_frame_rate` 的 `"a/b"` 计算；时长取 `format.duration`。没有视频流或进程失败时抛 `UnreadableMediaException`。
- Produces: `VideoQc.evaluate(String shotCode, VideoInfo info) -> List<QcReason>`：短边 < min-short-side 判 LOW_RESOLUTION；高 ≤ 宽判 NOT_PORTRAIT；fps 不在 [fps-min, fps-max] 判 FPS_OUT_OF_RANGE；`shotCode=="V1"` 且时长不在 [v1-min, v1-max] 判 DURATION_OUT_OF_RANGE。
- Produces: `UnreadableMediaException`（放在 `content/qc`）。

- [ ] **Step 1: 写失败的测试**

```java
// ShotFileNameTest
@ParameterizedTest @CsvSource({
  "MG-BL200_P5.jpg, MG-BL200, P5, PHOTO",
  "mg-bl200_p1.JPG, mg-bl200, P1, PHOTO",
  "MG-BL200_P1.jpeg, MG-BL200, P1, PHOTO",
  "MG_FAN_16_V1.mp4, MG_FAN_16, V1, VIDEO",
  "MG-BL200_v2.MOV, MG-BL200, V2, VIDEO",
  "MG-BL200_PROMO.jpg, MG-BL200, PROMO, PROMO_IMAGE",
  "photos/HERO/MG-BL200_P3.jpg, MG-BL200, P3, PHOTO" })
void parses(...)
@ParameterizedTest @ValueSource(strings = {"MG-BL200.jpg","MG-BL200_P10.jpg","MG-BL200_X1.jpg","_P1.jpg",
  "._MG-BL200_P1.jpg",".DS_Store","MG-BL200_P1 (2).jpg"})
void rejectsInvalidName(String n)          // code INVALID_FILE_NAME
@Test void rejectsVideoExtensionForPhotoShot()   // MG-BL200_P1.mp4 → UNSUPPORTED_FILE_TYPE
@Test void heic_hasActionableMessage()
// PhotoQcTest（测试图在测试中合成，写入 @TempDir）
@Test void sharpCheckerboard_4000x3000_accepted()        // 8px 黑白格；无 reasons；width 4000 height 3000
@Test void boxBlurred_isBlurry()                         // 同一张图做 31×31 均值模糊 → BLURRY
@Test void lowResolution_1500x1000()                     // LOW_RESOLUTION
@Test void allWhite_overexposed_allBlack_underexposed()
@Test void exifOrientation6_reportsPortraitAndPortraitThumb() // 4000×3000 像素，用 commons-imaging ExifRewriter 写入 Orientation=6 → width 3000 height 4000；缩略图解码后 h > w 且长边 400
@Test void promo_noRulesApplied()                        // 1000×1000 时 reasons 为空
@Test void garbageBytes_throwUnreadable()
// FfprobeJsonParserTest
@Test void sideDataRotation_swapsToPortrait()   // coded 1920x1080 + rotation -90 → 1080x1920
@Test void tagRotate_swapsToPortrait()          // tags.rotate "90"
@Test void ntscFrameRate_parsed()               // "30000/1001" → 29.97（±0.01）
@Test void noVideoStream_throwsUnreadable()
// VideoQcTest
@Test void portrait1080p30_v1_15s_ok()
@Test void landscape_notPortrait()
@Test void v1_25s_durationOutOfRange_butV2_25s_ok()
@Test void fps60_outOfRange()
@Test void hd720_lowResolution()
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `./mvnw -q test -Dtest='ShotFileNameTest,PhotoQcTest,FfprobeJsonParserTest,VideoQcTest'`
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行测试，确认通过**
- [ ] **Step 5: Commit** — `git commit -m "feat(content): shot file-name parsing and photo/video QC"`

---

### Task 11: 实拍导入

**Files:**
- Create: `content/media/{MediaImportService,ImportResult,SourceMediaEntity,SourceMediaMapper}.java`、`content/web/SourceMediaController.java`
- Test: `content/media/MediaImportServiceTest.java`、`content/web/SourceMediaControllerTest.java`

**Interfaces:**
- Consumes: `ShotFileName.parse`、`PhotoQc.evaluate`、`VideoProbe.probe`、`VideoQc.evaluate`、`ProductCatalog.findBySku`、`ObjectStorage.put`、`AuditLog.record`。
- Produces: `MediaImportService.importFile(CurrentUser user, String originalFileName, Path tempFile, String contentType) -> ImportResult`。
- Produces: `record ImportResult(String fileName, String outcome /* IMPORTED | DUPLICATE */, long mediaId, long productId, String sku, String shotCode, String status /* ACCEPTED | RESHOOT */, List<QcReason> reasons)`。DUPLICATE 时返回**已存在记录**的 sku、shotCode 和 status。
- Produces: `POST /api/v1/content/source-media`（OPERATOR，multipart 字段名 `file`）→ 200 `ImportResult`；出错时返回 422，`code` 为 `INVALID_FILE_NAME | UNSUPPORTED_FILE_TYPE | UNKNOWN_SKU | UNREADABLE_MEDIA`，`details.fileName` 为文件名，`details.extension` 为扩展名（文件名错误时可能为空）。
- 流程（顺序固定）：
  1. 解析文件名。
  2. `findBySku`。找不到时返回 `UNKNOWN_SKU`；找到的是变体时改用 `parentId` 对应的父商品。
  3. 计算临时文件的 sha256。同一 tenant 下已存在该 sha256 时直接返回 DUPLICATE。
  4. 质检：PHOTO 执行 `PhotoQc(applyRules=true)`；PROMO_IMAGE 执行 `PhotoQc(applyRules=false)`；VIDEO 执行 `probe` 再 `VideoQc`。
  5. 上传原文件，key 为 `t{tenantId}/source-media/{productId}/{sha256}.{ext}`；照片和宣传图另外上传缩略图，key 为 `t{tenantId}/thumbs/{sha256}.jpg`。
  6. 在一个事务中：把同一 `(product_id, shot_code)` 下状态不是 SUPERSEDED 的旧行改为 SUPERSEDED；插入新行，status 为 reasons 为空则 `ACCEPTED`，否则 `RESHOOT`；`qc_json` 存放质检的各项数值和 reasons；写审计 `action="SOURCE_MEDIA_IMPORTED"`、`targetType="source_media"`，after 为 `{sku, shotCode, status, reasons}`。
  7. 插入时遇到 sha256 唯一键冲突（并发导入同一个文件），按 DUPLICATE 返回。
- controller 把 `MultipartFile.transferTo` 到一个临时文件，在 `finally` 中删除。

- [ ] **Step 1: 写失败的测试**（SpringBootTest + MinIO；商品直接插入：`MG-BL200` simple，`MG-FAN16` variable 带变体 `MG-FAN16-BLK`；`@MockitoBean VideoProbe`；JPEG 在测试中合成）

```java
@Test void importGoodPhoto_acceptedStoredAudited()   // IMPORTED/ACCEPTED；原文件和缩略图的对象都存在；audit 有 1 行
@Test void sameBytesAgain_isDuplicate()               // mediaId 相同，source_media 仍只有 1 行
@Test void newTakeOfSameShot_supersedesOld()          // 旧行 SUPERSEDED，新行 ACCEPTED
@Test void lowResPhoto_reshootWithReason()            // RESHOOT [LOW_RESOLUTION]
@Test void variationSku_attachesToParent()            // MG-FAN16-BLK_P1.jpg 的 productId 等于 MG-FAN16 的 id
@Test void exifRotated_storesDisplayDimensions()      // width < height
@Test void video_v1_acceptedViaProbe()                // probe 返回 1080x1920、12 秒、30fps → ACCEPTED，duration_s 为 12.00
@Test void promo_neverReshoot()
// SourceMediaControllerTest
@Test void unknownSku_422()          // code UNKNOWN_SKU，details.fileName
@Test void badName_422()             // INVALID_FILE_NAME
@Test void heic_422()                // UNSUPPORTED_FILE_TYPE，details.extension == "heic"
@Test void corruptJpeg_422()         // UNREADABLE_MEDIA
@Test void viewer_403()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='MediaImportServiceTest,SourceMediaControllerTest'`
- [ ] **Step 5: Commit** — `git commit -m "feat(content): batch-friendly source media import with dedupe, QC and supersede"`

---

### Task 12: 拍摄状态、商品列表与补拍清单

**Files:**
- Create: `content/shots/{ShotStatusService,ProductShotStatus,ShotStatusLine,ShotState,ReshootLine,ContentProductSummary}.java`
- Create: `content/web/{ContentProductController,ReshootController}.java`
- Test: `content/shots/ShotStatusServiceTest.java`、`content/web/ContentProductControllerTest.java`、`content/web/ReshootControllerTest.java`

**Interfaces:**
- Consumes: `ProductCatalog.listTopLevel/findById`、`ProductProfileService.tierOf/tiersFor`、`ShotRequirementService.requiredFor`、`SourceMediaMapper`、`ObjectStorage.presignGet`。
- Produces:
```java
enum ShotState { OK, RESHOOT, MISSING }
record ShotStatusLine(String code, MediaKind kind, boolean required, ShotState state, Long mediaId,
                      List<QcReason> reasons, String guidanceEn, String guidanceZh,
                      String thumbUrl, String mediaUrl) {}
record ProductShotStatus(long productId, String sku, String name, ContentTier tier,
                         List<ShotStatusLine> lines, boolean complete) {}       // complete = 所有 required 行都是 OK（G1）
record ContentProductSummary(long productId, String sku, String name, String type, String status,
    BigDecimal regularPrice, BigDecimal salePrice, BigDecimal price, String stockStatus, String imageUrl,
    ContentTier tier, int required, int ok, int reshoot, int missing, boolean complete) {}
record ReshootLine(String sku, String productName, ContentTier tier, String shotCode, ShotState state,
                   List<QcReason> reasons, String guidanceEn, String guidanceZh) {}
```
- `ShotStatusService.statusFor(long tenantId, long productId, boolean withUrls) -> ProductShotStatus`：每个镜头编号的"当前"媒体是状态不是 SUPERSEDED 的那一行；ACCEPTED 对应 OK，RESHOOT 对应 RESHOOT，没有行对应 MISSING。`withUrls` 为 true 时生成有效期 15 分钟的预签名 URL（`thumbUrl` 只对照片生成）。`summaries(long tenantId, ContentTier tierOrNull, String qOrNull) -> List<ContentProductSummary>`：profile 和当前媒体各用一条查询批量取出，`q` 对 sku 和 name 做不区分大小写的包含匹配。`reshootList(long tenantId, ContentTier tierOrNull) -> List<ReshootLine>`：只包含状态不是 OK 的行，排序为 HERO 在前，然后按 sku，再按 `sort_order`。
- 端点（都需要 VIEWER）：`GET /api/v1/content/products?tier=&q=` → `List<ContentProductSummary>`；`GET /api/v1/content/products/{id}/shots` → `ProductShotStatus`（带 URL）；`GET /api/v1/content/reshoot-list?tier=` → `List<ReshootLine>`；`GET /api/v1/content/reshoot-list.csv?tier=` → `text/csv; charset=UTF-8`，带 `Content-Disposition: attachment; filename="reshoot-list.csv"`，内容以 UTF-8 BOM 开头，用 commons-csv 的 RFC4180 格式，表头固定为 `sku,product_name,tier,shot_code,state,reasons,guidance_zh,guidance_en`，reasons 之间用 `|` 连接。state 和 reasons 输出枚举码；它们的双语说明由界面上的补拍清单页显示。

- [ ] **Step 1: 写失败的测试**（直接向 `source_media` 插入测试数据，不经过导入流程）

```java
@Test void standard_missingP8_incomplete()          // P8 MISSING，complete == false
@Test void reshootLine_carriesReasons()             // P3 RESHOOT [BLURRY]
@Test void allStandardOk_complete_thenHeroIncomplete() // 切换为 HERO 后，P9、V1、V2、V3 为 MISSING
@Test void supersededReshoot_thenAccepted_isOk()
@Test void summaries_countsAndQueryFilter()          // q "bl200" 能匹配 MG-BL200；不包含 missing 状态的商品
@Test void shots_endpoint_urlsUsePublicEndpoint()
@Test void reshootList_tierFilterAndOrdering()
@Test void reshootCsv_headerBomAndQuoting()          // 以 \uFEFF 开头；第一行与表头完全一致；名称 "Kettle, 1.7L" 被加了引号；中文指引原样输出
@Test void shotLines_carryBothGuidanceLanguages()
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./mvnw -q test -Dtest='ShotStatusServiceTest,ContentProductControllerTest,ReshootControllerTest'`
- [ ] **Step 5: 运行全部后端测试**　Run: `./mvnw -q verify`　Expected: 全部 PASS（包括 ModularityTests）
- [ ] **Step 6: Commit** — `git commit -m "feat(content): shot status, product summaries and reshoot list (JSON + CSV)"`

---

### Task 13: Web 骨架、API 客户端、双语与登录

**Files:**
- Create: `kiano-web/`（`pnpm create next-app@latest kiano-web --ts --app --tailwind --eslint --src-dir --use-pnpm --no-import-alias` 之后再把别名 `@/*` 配到 `src/*`）
- Create: `kiano-web/vitest.config.ts`、`src/lib/api.ts`、`src/lib/api.test.ts`、`src/lib/format.ts`、`src/lib/format.test.ts`
- Create: `src/i18n/en.ts`、`src/i18n/zh.ts`、`src/i18n/index.tsx`、`src/i18n/i18n.test.ts`
- Create: `src/app/login/page.tsx`、`src/app/(app)/layout.tsx`、`src/app/page.tsx`（重定向到 `/products`）

**Interfaces:**
- Produces（i18n，不引入第三方库）：
  - `en.ts` 导出 `const en = { ... } as const`，键用点分命名，例如 `nav.products`、`qc.BLURRY`、`state.MISSING`、`error.WOO_AUTH_FAILED`、`import.summary`。`zh.ts` 导出 `const zh: Record<MessageKey, string>`，其中 `type MessageKey = keyof typeof en`。这样漏写一个键就会编译失败。
  - `index.tsx` 导出 `type Locale = 'zh' | 'en'`、`I18nProvider`、`useI18n(): { locale, setLocale(l: Locale), t(key: MessageKey, vars?: Record<string, string | number>): string }`、`detectLocale(cookieValue: string | undefined, navigatorLanguage: string): Locale`，以及 `pickGuidance(locale: Locale, line: { guidanceEn: string; guidanceZh: string }): string`。
  - `t` 把文案中的 `{name}` 替换为 `vars.name`。`setLocale` 写入 cookie `kiano_locale`（`path=/`，有效期 1 年）并让页面重新渲染，同时把 `<html lang>` 更新为 `zh-CN` 或 `en`。
  - `errorText(t, err: ApiError): string`：先查 `error.{code}`，没有对应键时回退为 `err.message`。`UNSUPPORTED_FILE_TYPE` 且 `details.extension === 'heic'` 时使用 `error.UNSUPPORTED_FILE_TYPE.heic`。
  - 字典必须覆盖的枚举：全部 `QcReason`、`ShotState`、`ContentTier`、`TaskStatus`、`ImportResult.outcome`，以及错误码 `UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, VALIDATION_FAILED, INTERNAL_ERROR, AUTH_INVALID_CREDENTIALS, WOO_NOT_CONFIGURED, WOO_AUTH_FAILED, WOO_BLOCKED, WOO_UNAVAILABLE, NOT_TOP_LEVEL_PRODUCT, INVALID_FILE_NAME, UNSUPPORTED_FILE_TYPE, UNKNOWN_SKU, UNREADABLE_MEDIA`。WOO_AUTH_FAILED 和 WOO_BLOCKED 的中文文案要包含与英文消息相同的操作提示（检查 Application Password、http 站点设置 `WP_ENVIRONMENT_TYPE=local`；WAF 放行）。
- Produces: `apiFetch<T>(path: string, init?: RequestInit): Promise<T>`。基址取 `process.env.NEXT_PUBLIC_API_BASE_URL`，固定带 `credentials: 'include'`；body 为对象时自动 JSON 序列化并加 `Content-Type: application/json`（`FormData` 原样发送）；204 返回 `undefined`；响应不是 2xx 时抛 `ApiError`；401 且当前页面不是 `/login` 时跳转到 `/login`。
- Produces: `class ApiError extends Error { status: number; code: string; traceId?: string; details?: Record<string, unknown> }`。
- Produces: `formatGhs(amount: number | string | null | undefined): string`。整数显示为 `GH₵ 299`，有小数时显示为 `GH₵ 299.50`，没有值时显示 `—`。超过三位数的整数部分用逗号分组（如 `GH₵ 1,299`）。
- Produces: `useMe()`（在 `(app)/layout.tsx` 中通过 React context 提供 `{userId, email, name, role}`），以及 `canOperate(role)`、`isOwner(role)`。
- 布局：顶部导航为 商品 Products / 导入 Import / 补拍清单 Reshoot list / 设置 Settings（Settings 只对 OWNER 显示），右侧是语言切换（中文 | English）、当前用户和 Logout。所有导航文字都用 `t()` 输出。登录页也有语言切换。`I18nProvider` 放在根 layout，登录页和 `(app)` 下的页面都能使用。

- [ ] **Step 1: 写失败的测试**（Vitest，用 `vi.stubGlobal('fetch', …)` 模拟 fetch）

```ts
test('formatGhs', () => {
  expect(formatGhs(299)).toBe('GH₵ 299'); expect(formatGhs('299.00')).toBe('GH₵ 299');
  expect(formatGhs(299.5)).toBe('GH₵ 299.50'); expect(formatGhs(1299)).toBe('GH₵ 1,299');
  expect(formatGhs(null)).toBe('—');
});
test('apiFetch sends credentials and JSON body')
test('apiFetch throws ApiError with code/traceId from error body')
test('apiFetch returns undefined on 204')
// i18n.test.ts
test('zh and en have exactly the same keys and no empty values')
test('dictionary covers every backend enum and error code')   // 用测试中列出的完整枚举 / 错误码清单逐个检查 `qc.*`、`state.*`、`tier.*`、`task.*`、`outcome.*`、`error.*` 键都存在
test('t interpolates vars', () => { /* t('import.summary', {n: 3}) 中英文都包含 "3" */ })
test('detectLocale', () => {
  expect(detectLocale('en', 'zh-CN')).toBe('en');      // cookie 优先
  expect(detectLocale(undefined, 'zh-CN')).toBe('zh');
  expect(detectLocale(undefined, 'en-GH')).toBe('en');
  expect(detectLocale('xx', 'fr')).toBe('en');         // 非法 cookie 值被忽略
})
test('errorText falls back to message and handles heic')
test('pickGuidance returns the column for the locale')
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `cd kiano-web && pnpm vitest run`
- [ ] **Step 3: 实现 lib、i18n、登录页和布局**（登录页：邮箱和密码，提交后调用 `/api/v1/auth/login`，成功跳到 `/products`，失败时用 `errorText` 显示错误）
- [ ] **Step 4: 运行测试，确认通过**；`pnpm lint && pnpm build` 都通过
- [ ] **Step 5: 在浏览器中验证**：在 `.env` 中设置 bootstrap owner；`docker compose up -d`；运行 `cd kiano-api && ./mvnw spring-boot:run` 和 `cd kiano-web && pnpm dev`。打开 `http://localhost:3000`，应跳到 `/login`；登录后进入 `/products`（此时可以是空页面），导航显示 Settings；切换为 English 后所有导航文字都变成英文，刷新后仍然是英文；用错误的密码登录，中英文下各显示对应语言的错误；Logout 后回到 `/login`。
- [ ] **Step 6: Commit** — `git commit -m "feat(web): Next.js shell, API client, zh/en i18n and login"`

---

### Task 14: 商品列表与商品拍摄详情页

**Files:**
- Create: `src/app/(app)/products/page.tsx`、`src/app/(app)/products/[id]/page.tsx`、`src/lib/types.ts`

**Interfaces:**
- Consumes: `GET /api/v1/content/products`、`PATCH /api/v1/products/{id}/profile`、`POST /api/v1/commerce/sync`、`GET /api/v1/commerce/sync/latest`、`GET /api/v1/content/products/{id}/shots`。
- Produces: `src/lib/types.ts` 中的 TS 类型 `ContentProductSummary`、`ProductShotStatus`、`ShotStatusLine`、`ImportResult`、`ReshootLine`、`TaskView`，字段名与 Task 8、11、12 的 Java record 完全一致（camelCase）。
- `/products`：顶部显示同步状态（最近一次的 status 和 finishedAt；FAILED 时用红色显示 `lastError`；RUNNING 或 QUEUED 时每 3 秒轮询一次），OPERATOR 及以上显示 "Sync now" 按钮。提供搜索框（`q`）和 tier 筛选（All / HERO / STANDARD）。表格列为 Image、SKU、Name、Price（`formatGhs(price)`；有 salePrice 时把 regularPrice 显示为划线价）、Stock、Tier（OPERATOR 及以上显示为下拉框，修改后立即 PATCH）、Shots（`ok/required`，有 reshoot 时显示红色角标，complete 时显示 ✓）。点击行进入详情页。
- 两个页面的所有文字（表头、按钮、状态、tier、QC 原因、错误）都通过 `t()` / `errorText()` 输出；商品名称保持 Woo 中的原文，不翻译。
- `/products/[id]`：显示商品名、SKU、tier，以及 `complete` 标识（G1）。下方是镜头卡片网格：每张卡片显示 code、`pickGuidance(locale, line)`、state 徽标（OK 绿色、RESHOOT 红色、MISSING 灰色）、reasons（`t('qc.' + reason)`）；照片显示缩略图，点击打开 `mediaUrl`；视频用 `<video preload="metadata" controls src={mediaUrl}>`。

- [ ] **Step 1: 实现两个页面**（类型和 lib 已有单元测试；页面行为在 Step 3 用浏览器验证）
- [ ] **Step 2: `pnpm lint && pnpm build`**　Expected: 无错误
- [ ] **Step 3: 在浏览器中验证**（后端已用 Task 8 的方式同步过测试商品，或先完成 Task 16 的本地 Woo 准备再验证）：列表能显示商品；把一个商品改为 HERO 后刷新，值保持不变，Shots 分母从 8 变成 12；点击 Sync now 后状态依次变为 QUEUED → RUNNING → SUCCEEDED；把 Woo 密码故意改错后再同步，页面显示带 "Application Password" 的错误；切换语言后，两个页面的状态、QC 原因、拍摄指引和这条错误都换成另一种语言。
- [ ] **Step 4: Commit** — `git commit -m "feat(web): product list with tiering and sync status, shot detail page"`

---

### Task 15: 导入页、补拍清单页、集成设置页

**Files:**
- Create: `src/lib/import.ts`、`src/lib/import.test.ts`
- Create: `src/app/(app)/import/page.tsx`、`src/app/(app)/reshoot/page.tsx`、`src/app/(app)/settings/integrations/page.tsx`

**Interfaces:**
- Produces: `filterImportable(files: File[]): { importable: File[]; skipped: File[] }`。跳过以 `.` 开头的文件、`Thumbs.db` 和 `desktop.ini`（不区分大小写）。其余文件全部交给后端判断，不在前端重复实现文件名规则。
- Produces: `runImports(files: File[], upload: (f: File) => Promise<ImportRow>, concurrency: number, onRow: (row: ImportRow) => void): Promise<void>`，同时最多 `concurrency` 个上传；单个文件失败不影响其余文件。`type ImportRow = { fileName: string; ok: boolean; result?: ImportResult; error?: { code: string; message: string } }`。
- `/import`（OPERATOR 及以上）：`<input type="file" multiple webkitdirectory>`，另外提供一个可以多选文件的输入框。选定后显示"将导入 N 个，跳过 M 个"，确认后用 concurrency=2 上传。逐行显示 文件名 / SKU / 镜头 / 结果（IMPORTED、DUPLICATE 或错误码）/ 状态与 reasons；最后显示汇总（accepted / reshoot / duplicate / failed）。上传时用 `webkitRelativePath` 只是为了显示，实际提交的文件名是 `file.name`。
- `/reshoot`：tier 筛选；按 SKU 分组列出状态不是 OK 的镜头（state、reasons、guidance）；"Download CSV" 链接指向 `${API}/api/v1/content/reshoot-list.csv?tier=`（cookie 会自动带上）。
- `/settings/integrations`（只有 OWNER 可见）：表单字段为 Base URL、Username、Application Password（留空表示不修改）；有 Save 和 Test connection 两个按钮，显示 `{ok, code, message}`。页面上附一段说明：如何在 WordPress 中为 Shop Manager 用户创建 Application Password；站点是 http 时需要设置 `WP_ENVIRONMENT_TYPE=local`。
- 三个页面的所有文字都通过 `t()` / `errorText()` 输出。导入结果中的错误码、outcome、QC 原因，以及补拍清单中的指引（用 `pickGuidance`）都按当前语言显示。"Download CSV" 旁注明 CSV 同时包含中英文指引。

- [ ] **Step 1: 写失败的测试**

```ts
test('filterImportable skips hidden and OS junk files', () => {
  const r = filterImportable([f('MG-BL200_P1.jpg'), f('.DS_Store'), f('._MG-BL200_P1.jpg'), f('Thumbs.db'), f('DESKTOP.INI'), f('notes.txt')]);
  expect(r.importable.map(x => x.name)).toEqual(['MG-BL200_P1.jpg', 'notes.txt']);   // notes.txt 交给后端报 INVALID_FILE_NAME
  expect(r.skipped).toHaveLength(4);
});
test('runImports respects concurrency and continues after a failure')   // 同时进行的上传数从不超过 2；5 个文件 → 回调 onRow 5 次
```

- [ ] **Step 2: 运行测试，确认失败**　Run: `pnpm vitest run src/lib/import.test.ts`
- [ ] **Step 3: 实现 lib 和三个页面**
- [ ] **Step 4: 运行 `pnpm vitest run && pnpm lint && pnpm build`**　Expected: 全部通过
- [ ] **Step 5: 在浏览器中验证**：选择一个文件夹，里面放 `MG-TEST1_P1.jpg`（合格）、`MG-TEST1_P2.jpg`（短边 < 2000）、`.DS_Store`、`UNKNOWN_P1.jpg`、`MG-TEST1_P1.jpg` 的副本 → 页面显示"跳过 1 个"，结果依次为 IMPORTED/ACCEPTED、IMPORTED/RESHOOT、UNKNOWN_SKU、DUPLICATE。补拍清单中 MG-TEST1 的 P2 为 RESHOOT [LOW_RESOLUTION]，P3–P8 为 MISSING。中文界面显示"分辨率不足"之类的中文原因和中文指引，切换到英文后显示英文。CSV 用 Excel 打开时，中文指引不乱码。上传一个 `.heic` 文件，中英文界面都显示导出为 JPEG 的提示。
- [ ] **Step 6: Commit** — `git commit -m "feat(web): folder import, reshoot list and Woo integration settings"`

---

### Task 16: 容器化、文档与 C1 验收

**Files:**
- Create: `kiano-api/Dockerfile`、`kiano-web/Dockerfile`、`README.md`（替换现有内容）
- Modify: `docker-compose.yml`（增加 `api` 和 `web`，放在 `profiles: ["app"]` 下）、`CLAUDE.md`（如果命令有变化）

**Interfaces:**
- `kiano-api/Dockerfile`：多阶段构建。构建阶段用 `eclipse-temurin:21-jdk` 执行 `./mvnw -q -DskipTests package`；运行阶段用 `eclipse-temurin:21-jre`，并 `apt-get install -y --no-install-recommends ffmpeg`（提供 ffprobe）；`EXPOSE 8081`。
- compose 中的 `api` 服务：`env_file: .env`，环境变量覆盖 `KIANO_DB_URL=jdbc:postgresql://postgres:5432/kiano?stringtype=unspecified` 和 `KIANO_STORAGE_ENDPOINT=http://minio:9000`（`KIANO_STORAGE_PUBLIC_ENDPOINT` 保持 `http://localhost:9000`），端口 `8081:8081`。`web` 服务：Next.js standalone 输出，构建参数为 `NEXT_PUBLIC_API_BASE_URL`，端口 `3000:3000`。
- README：前置条件（Docker Desktop、JDK 21、Node 22 + pnpm、本机开发需要 `winget install Gyan.FFmpeg`）；本地开发方式（`docker compose up -d` + `./mvnw spring-boot:run` + `pnpm dev`）；全容器方式（`docker compose --profile app up -d --build`）；测试命令；连接 KianosMart 本地 Woo 的步骤（见下）。

- [ ] **Step 1: 编写 Dockerfile、修改 compose、编写 README**
- [ ] **Step 2: 全容器启动验证**　Run: `docker compose --profile app up -d --build`，然后执行 `curl -s localhost:8081/actuator/health`　Expected: `{"status":"UP"}`；`http://localhost:3000` 能登录
- [ ] **Step 3: 准备 KianosMart 本地 Woo**（在 `D:\GHANA\claude\KianosMart` 中执行；只在本地 `wp_data` volume 中操作，不修改那个仓库的文件）

```bash
docker compose up -d --build
docker compose run --rm wpcli wp config set WP_ENVIRONMENT_TYPE local --type=constant
docker compose run --rm wpcli wp user create kiano-sync kiano-sync@example.test --role=shop_manager
docker compose run --rm wpcli wp user application-password create kiano-sync kiano-local --porcelain
```
如果本地商店没有商品，就用 `wp wc product create --user=1 ...` 创建 2 个 simple 商品（SKU `MG-TEST1`、`MG-TEST2`，价格 299 和 1299）和 1 个 variable 商品（`MG-TEST3`，带 2 个变体）。Application Password 只在 Settings 页面中输入，不写进任何文件或聊天记录。

- [ ] **Step 4: 按规格 §14 的 C1 标准验收，逐条记录结果**
  1. **Woo 商品 100% 同步**：在 Settings 中填入 `http://localhost:8080`、`kiano-sync` 和 app password，Test connection 返回 ok → 执行 Sync now → 得到 SUCCEEDED。`SyncResult.products + variations` 等于 Woo 后台的商品数加变体数（用 `wp wc product list --user=1 --format=count` 加上每个 variable 商品的 `wp wc product_variation list` 计数）；价格逐一核对。在 Woo 中修改一个价格后再次同步，`priceChanges == 1`，`audit_log` 中有对应记录。
  2. **按文件夹导入一个 SKU 并完成质检**：准备 `MG-TEST1` 的 P1–P8（P3 故意拍虚，P5 故意用低分辨率），按文件夹导入，P3 为 RESHOOT [BLURRY]，P5 为 RESHOOT [LOW_RESOLUTION]。真实手机照片的 `blurVariance` 和曝光比例记录在 `qc_json` 中，用来在样板阶段标定阈值。
  3. **补拍清单正确**：清单上恰好是 P3 和 P5；把 MG-TEST1 设为 HERO 后，又多出 P9 和 V1–V3（MISSING）；导入一段竖拍的手机视频作为 `MG-TEST1_V1.mp4`，结果为 ACCEPTED（验证旋转元数据的处理）；CSV 与页面内容一致。
- [ ] **Step 5: 运行全部测试**　Run: `cd kiano-api && ./mvnw -q verify && cd ../kiano-web && pnpm vitest run && pnpm build`　Expected: 全部通过
- [ ] **Step 6: Commit** — `git commit -m "chore: containerise api/web, README and C1 acceptance notes"`

---

## Self-Review 记录

- **规格覆盖**（C1 行）：平台基础由 Task 1–5 实现（认证 T2、tenant T1、凭证加密 T3、PG 队列 T4、审计 T3、MinIO T5）；Woo 只读同步（商品、变体、分类、价格）由 T6–T8 实现；分层由 T9 实现；拍摄清单由 T9 的种子数据和 T12 实现；批量导入和质检由 T10、T11、T15 实现；补拍清单由 T12、T15 实现；验收见 T16。§10.3 要求的"价格变化后标记 STALE"在 C1 只做到写审计并发布 `ProductPriceChanged` 事件，标记 STALE 由 C4 消费该事件实现。v1.2 §20 的 `Idempotency-Key` 请求头在 C1 不需要：导入按 sha256 去重，同步按 dedupeKey 去重，PATCH 本身是幂等的。
- **类型一致性**：`MediaKind` 在 T9 创建，T10 及以后都使用它；`CommercePortFactory` 在 T7 定义，T8 使用；`ProductView.parentId` 在 T11 用于把变体归到父商品；TS 类型与 Java record 字段同名（T14 统一定义）；拍摄指引从 T9 起就是 `guidanceEn` / `guidanceZh` 两个字段，一直传到 T12 的 record、CSV 和前端的 `pickGuidance`。
- **双语覆盖**：界面文字由 T13 的字典和 `t()` 提供，"两个字典的键一致"和"每个后端枚举和错误码都有文案"都有测试保证；拍摄指引由 T9 的种子数据提供两种语言，并有测试保证；T14、T15 的浏览器验证步骤都包含切换语言的检查。
- **有意不做**：worker 和租约协议（C2）、`product_fact_sheet` 等后续表（各 Sprint 用自己的迁移加入）、增量同步（150 个 SKU 每小时全量同步足够）、Idempotency-Key 中间件、登录限流（C1 只在本地运行；迁移到 VPS 时补上）。
