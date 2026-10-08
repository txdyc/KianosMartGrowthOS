# C4b kiano-connector 插件 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 在 KianosMart 仓库实现 WordPress 插件 `kiano-connector`（架构 v1.2 §7），包含以下几部分：
- 前端生成访客 ID 和会话 ID，记录首次和最后一次落地的触点（click id、utm）。
- 下单时把这些数据写入订单 meta。
- 结账页增加 GhanaPost GPS 数字地址，并校验 Ghana 电话号码。
- 4 个自定义订单状态（待确认、已确认、已发货、拒收）。COD 订单直接进入"待确认"，并处理库存、邮件和 Analytics 统计。

这样投放开始之后的网站订单都带有完整的归因数据，Phase 1b 的订单同步和 COD 运营可以直接使用。

**Architecture：**
- 纯 PHP 插件，没有构建步骤，运行时**不依赖 composer**。
- 可测试的逻辑放在纯函数类中（`Kiano_Phone`、`Kiano_Gps`、`Kiano_Touch`），WordPress 钩子只做一层很薄的连接。
- 前端只有两个原生 JS 文件：`tracker.js` 负责采集，`checkout-hints.js` 负责结账提示。纯逻辑部分写成 node 可以 `require` 的形式。
- 订单数据一律通过 `WC_Order` CRUD 读写，兼容 HPOS。

**代码位置：**
- 插件代码在 **KianosMart 仓库** `D:\GHANA\claude\KianosMart\wp-content/plugins/kiano-connector/`。
- 测试工具在 KianosMart 仓库 `tests/kiano-connector/`，**不挂载进容器**。
- 本计划文件和最后的文档更新在 KianosMartGrowthOS 仓库。

**Tech Stack：** PHP 8.3（wordpress:php8.3-fpm-alpine），WooCommerce 11.0.1（本地实测：**区块结账**，HPOS 已启用，Order Attribution 已启用），WordPress 7.1。PHPUnit 11 在 Docker 的 `composer:2` 和 `php:8.3-cli` 中运行。JS 测试用 Node 22 自带的 `node --test`，不需要安装依赖。

**Spec：** `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md` §7（插件）、§10（归因）、§13.1（状态机）、§23.2（数据保护）。

## Global Constraints

- **KianosMart 仓库的规则优先**，见该仓库的 CLAUDE.md：
  - 不修改 WordPress 核心、Storefront 和第三方插件。
  - 新插件 = 新目录 + `docker-compose.yml` 中 `wordpress` 服务下的 bind mount。插件带有 JS，所以 `nginx` 服务也要加**只读**挂载。
  - 机密只放在 `.env`。shell 脚本使用 LF 换行。
  - **先在 staging 验证，再上生产**；部署 VPS 只能通过 `scripts/deploy.sh`。**实施者不得部署 staging 或生产 VPS**：本地验收完成后交给用户操作。
  - 提交信息沿用 KianosMart 仓库现有的风格，即英文祈使句（例如 `Add kiano-connector plugin skeleton`），不使用 conventional commits 前缀。
- **运行时不能引入 composer 和 `vendor/`**：插件目录整个被挂载给 php-fpm，`vendor/` 中的 PHP 文件可以通过 HTTP 直接执行（phpunit 的 `eval-stdin.php` 就是著名的 RCE）。所以 PHPUnit 和 composer 只放在仓库根目录的 `tests/kiano-connector/`，这个目录不挂载进任何容器，`vendor/` 加入 `.gitignore`。插件目录中只放运行时代码和模板。
- **HPOS**：
  - 订单只能通过 `wc_get_order()`、`$order->get_meta()`、`$order->update_meta_data()`、`$order->save()` 读写；**禁止**对订单使用 `get_post_meta`、`update_post_meta` 和 `WP_Query`。
  - 在 `before_woocommerce_init` 中用 `FeaturesUtil::declare_compatibility` 声明兼容 `custom_order_tables` 和 `cart_checkout_blocks`。
- **cookie 都是不可信输入**：
  - `kiano_vid` 和 `kiano_sid` 必须符合 UUID v4 的格式，否则丢弃。
  - 触点 JSON 只保留白名单中的键，值只能是字符串，每个值截断到 200 字符，landing URL 截断到 500 字符，然后经过 `sanitize_text_field`。
  - landing URL **去掉查询串，只保留白名单参数**（utm_*、fbclid、gclid），避免把 email 之类的 PII 写进订单。
  - 输出到页面时一律转义（`esc_html`、`esc_attr`、`wp_json_encode`）。
- **ID 必须在前端生成**（v1.2 §7.1，原因是 Cloudflare 和页面缓存）：服务端**从不**设置 `kiano_*` cookie，只在下单时读取。JS 被拦截时订单就没有这些 meta，不影响下单。
- **cookie 规格**：
  - `kiano_vid`：365 天。
  - `kiano_sid`：30 分钟，每次页面加载都续期。
  - `kiano_ft`（首次触点）：只在不存在时写入，7 天。
  - `kiano_lt`（最后触点）：只在"非直接访问"时覆盖，7 天。"非直接访问"指 URL 中带有 utm_*、fbclid 或 gclid，或者 referrer 来自其他域名（v1.2 §10.3 的"最后一次非直接访问触点"）。
  - 所有 cookie 都设置 `path=/`、`SameSite=Lax`，HTTPS 下加 `Secure`。
  - 触点 cookie 的值是 base64url 编码的 JSON，每个不超过 1 KB。
- **状态 slug**：`wc-kiano-awaiting`、`wc-kiano-confirmed`、`wc-kiano-dispatched`、`wc-kiano-refused`。`post_status` 列最长 20 个字符，其中最长的 `wc-kiano-dispatched` 是 19 个字符，**不能改得更长**。
- **与 WooCommerce 内置 Order Attribution 并存**，不修改也不删除 `_wc_order_attribution_*`。
- **只支持区块结账**（本店实际使用的就是区块结账）。经典结账的钩子也接上，因为成本很低，但验收只做区块结账。
- **相对 v1.2 §7 的有意扩展**（本计划确认后写回 v1.2，见 Task 8）：
  - 订单 meta 在 `_kiano_vid`、`_kiano_sid`、`_kiano_first_touch`、`_kiano_last_touch` 之外，增加以下几项：
    - `_kiano_fbc`、`_kiano_fbp`：下单时读取 Meta Pixel 的 cookie。这比落地时记录更可靠，因为 Pixel 可能在落地之后才写 cookie。
    - `_kiano_phone_e164`：规范化后的电话号码，**不修改**客户填写的原始号码。
    - `_kiano_gps_address`：规范化后的 GPS 地址，优先取 shipping，其次取 billing。
    - `_kiano_plugin_version`
  - GPS 字段使用 Additional Checkout Fields API（字段 ID 为 `kiano/gps-address`，location 为 `address`）。Woo 自己会把它存进 `_wc_billing/kiano/gps-address` 和 `_wc_shipping/kiano/gps-address`，并显示在后台订单页和邮件中；插件另外镜像一份到 `_kiano_gps_address`，作为 Kiano 读取时的稳定契约。

## 已在本地 WC 11.0.1 源码中确认的钩子

| 用途 | 钩子 / API |
|---|---|
| 区块结账写订单 | `woocommerce_store_api_checkout_update_order_from_request( $order, $request )`（`CheckoutTrait.php:247`，在 `$order->save()` 之前触发） |
| 经典结账写订单 | `woocommerce_checkout_create_order( $order, $data )` |
| GPS 字段 | `woocommerce_register_additional_checkout_field( [... 'sanitize_callback', 'validate_callback', 'attributes'] )`（`CheckoutFields.php`） |
| 区块结账校验电话 | `woocommerce_blocks_validate_location_address_fields( WP_Error $errors, array $fields, string $group )`。`$fields` 是完整的 billing_address 或 shipping_address，包含 `phone` 和 `country`（`Checkout.php:317`、`CheckoutFields.php:1075`） |
| 经典结账校验 | `woocommerce_after_checkout_validation( $data, $errors )` |
| COD 初始状态 | `woocommerce_cod_process_payment_order_status`（`class-wc-gateway-cod.php:322`） |
| 库存 | `wc_maybe_reduce_stock_levels` 和 `wc_maybe_increase_stock_levels`，两者都靠 `_order_stock_reduced` 实现幂等；`wc_release_stock_for_order` 释放预留库存 |
| Analytics 排除状态 | option `woocommerce_excluded_report_order_statuses`，默认值为 `['pending','failed','cancelled']`（`Admin/API/Reports/DataStore.php:845`）。通过 `option_…` 和 `default_option_…` 两个过滤器追加 `kiano-refused`，**不写数据库** |
| 旧版报表和仪表盘 | `woocommerce_reports_order_statuses` |

## Review Focus

1. **COD 订单进入 awaiting 时库存没有扣减**：Woo 只在 processing、on-hold、completed 时扣库存，自定义状态必须自己挂钩。awaiting 和 confirmed 时扣减，refused 时回补，cancelled 由核心回补；靠 `_order_stock_reduced` 保证重复转换不会多扣或多补。测试放在 Task 5。
2. **COD 订单进入 awaiting 后，店主收不到"新订单"邮件**：核心只在 pending 转到 processing、on-hold 或 completed 时发送 New Order 邮件。必须把 `woocommerce_order_status_pending_to_kiano-awaiting` 加入 `woocommerce_email_actions`，并挂上 `WC_Email_New_Order::trigger`。测试放在 Task 6。
3. **cookie 注入**：伪造的 `kiano_ft` 中含有 `<script>`、超长值、非字符串值或额外的键，伪造的 `kiano_vid` 不是 UUID。这些写进订单 meta 之前都必须被清理或丢弃。测试放在 Task 2 和 Task 4。
4. **电话校验误伤**：billing 国家不是 GH 时不能校验；`024 123 4567`、`+233 24 123 4567`、`233241234567`、`0241234567` 都必须通过；`12345` 和 `+234…`（尼日利亚号码）必须被拒绝。错误信息要显示在对应的字段组上（billing 或 shipping）。测试放在 Task 2 和 Task 5。
5. **缓存页面上 ID 是否稳定**：同一个浏览器多次访问，`kiano_vid` 保持不变；`kiano_sid` 在 30 分钟无活动后更换；直接访问不覆盖 `kiano_lt`。测试放在 Task 3。

---

## 文件结构（KianosMart 仓库）

```
wp-content/plugins/kiano-connector/
  kiano-connector.php                 插件头、常量、require、FeaturesUtil 兼容声明
  includes/
    class-kiano-phone.php             纯函数：Ghana 号码 → E.164 | null
    class-kiano-gps.php               纯函数：GPS 地址规范化与校验
    class-kiano-touch.php             纯函数：解码并清理 cookie 中的触点 / UUID
    class-kiano-tracking.php          加载 tracker.js；下单时把 cookie 写入订单 meta
    class-kiano-checkout.php          GPS 字段、电话校验、加载 checkout-hints.js
    class-kiano-statuses.php          注册状态、COD 过滤器、库存、Analytics、批量操作
    class-kiano-emails.php            邮件动作与邮件类注册
    emails/class-kiano-email-awaiting.php
    emails/class-kiano-email-dispatched.php
  assets/js/tracker.js                采集（纯逻辑通过 module.exports 导出给 node 测试）
  assets/js/checkout-hints.js         电话格式提示，COD 订单提示填写 GPS
  templates/emails/{customer-awaiting,customer-dispatched}.php（+ plain/）
tests/kiano-connector/                不挂载进容器
  composer.json  phpunit.xml  .gitignore(vendor/)
  unit/{PhoneTest,GpsTest,TouchTest}.php
  js/{tracker,checkout-hints}.test.mjs
  integration/run.php  integration/lib.php  integration/test-*.php   （用 wp eval-file 执行）
  run-unit.sh  run-integration.sh  run-js.sh
docs/kiano-connector.md               数据契约（meta 键、状态、cookie）和运维说明
docker-compose.yml                    wordpress（读写）和 nginx（只读）各加一行挂载
```

---

### Task 1: 插件骨架、挂载与测试工具

**Files:**
- Create: `wp-content/plugins/kiano-connector/kiano-connector.php`、`tests/kiano-connector/{composer.json,phpunit.xml,.gitignore,run-unit.sh,run-js.sh,run-integration.sh}`、`tests/kiano-connector/integration/{run.php,lib.php,test-bootstrap.php}`
- Modify: `docker-compose.yml`

**Interfaces:**
- 插件头：`Plugin Name: Kiano Connector`，`Requires PHP: 8.3`，`WC requires at least: 9.6`，`Text Domain: kiano-connector`；定义 `KIANO_CONNECTOR_VERSION = '0.1.0'`。WooCommerce 未激活时，在后台显示提示并直接返回，不加载其余代码。
- 在 `before_woocommerce_init` 中调用 `\Automattic\WooCommerce\Utilities\FeaturesUtil::declare_compatibility('custom_order_tables', __FILE__, true)`，`cart_checkout_blocks` 同样声明。
- docker-compose：
  - `wordpress` 的 volumes 中加 `- ./wp-content/plugins/kiano-connector:/var/www/html/wp-content/plugins/kiano-connector`。`wpcli` 通过 `*wp-volumes` 锚点自动继承。
  - `nginx` 中加同一路径，后缀 `:ro`。
- 三个测试脚本：
  - `run-unit.sh`：`docker run --rm -v "$PWD/tests/kiano-connector:/t" -v "$PWD/wp-content/plugins/kiano-connector:/plugin:ro" -w /t composer:2 sh -c 'composer install -q && vendor/bin/phpunit'`。phpunit 的 bootstrap 只 require 三个纯函数类，并定义一个 `sanitize_text_field` 的最小桩函数（strip_tags 加 trim）。
  - `run-js.sh`：`node --test tests/kiano-connector/js/`。
  - `run-integration.sh`：`docker compose run --rm -v "$PWD/tests/kiano-connector/integration:/kiano-tests:ro" wpcli wp eval-file /kiano-tests/run.php`。
- `integration/lib.php` 是一个极简的断言框架，提供 `kt_assert_same($exp, $act, $msg)`、`kt_test($name, callable)` 和汇总输出；有失败时 `exit(1)`。它还提供以下测试夹具：`kt_product($stock)` 创建一个管理库存的简单商品；`kt_order($product, $qty, $method)` 用 `wc_create_order` 创建订单；`kt_cleanup()` 把本次创建的订单和商品 `->delete(true)`（这些只是本地测试夹具）；`kt_capture_mail()` 通过 `pre_wp_mail` 拦截邮件并记录下来，不真正发送。

- [ ] **Step 1: 写一个失败的冒烟测试**：`integration/test-bootstrap.php` 断言 `defined('KIANO_CONNECTOR_VERSION')`，并断言 HPOS 兼容声明生效，即 `FeaturesUtil::get_compatible_plugins_for_feature('custom_order_tables')['compatible']` 中包含 `kiano-connector/kiano-connector.php`。
- [ ] **Step 2: 运行 `./tests/kiano-connector/run-integration.sh`，预期失败**（插件尚不存在）
- [ ] **Step 3: 实现**：创建插件文件和挂载，执行 `docker compose up -d`（重建 wordpress 和 nginx 的挂载），然后执行 `docker compose run --rm wpcli wp plugin activate kiano-connector`。
- [ ] **Step 4: 再次运行，预期通过**；同时用 `run-unit.sh` 和 `run-js.sh` 确认空的测试套件能跑起来。
- [ ] **Step 5: Commit**（KianosMart 仓库）— `Add kiano-connector plugin skeleton with HPOS compatibility and test tooling`

---

### Task 2: 纯函数——电话号码、GPS 地址与触点清理

**Files:**
- Create: `includes/class-kiano-{phone,gps,touch}.php`
- Test: `tests/kiano-connector/unit/{PhoneTest,GpsTest,TouchTest}.php`

**Interfaces:**
- `Kiano_Phone::to_e164(string $raw): ?string`：
  1. 去掉空格、`-`、`.`、`(`、`)`。
  2. 用 `^(?:\+?233|0)?([235]\d{8})$` 匹配，返回 `+233` 加上捕获到的 9 位数字。
  3. 不匹配时返回 null。
  4. `+233 0241234567` 这种多写了 0 的情况也接受，先去掉 `+2330` 中的那个 0。
- `Kiano_Gps::normalize(string $raw): ?string`：
  1. 转成大写，去掉空格。
  2. 用 `^([A-Z]{2})-?(\d{3,4})-?(\d{4})$` 匹配，返回 `XX-NNN(N)-NNNN`。
  3. 不匹配时返回 null；空字符串也返回 null。调用方要区分"未填写"和"格式错误"。
- `Kiano_Touch`：
  - `uuid(?string $raw): ?string` 只接受小写的 UUID v4。
  - `decode(?string $cookie): ?array`：
    1. 先 base64url 解码，再 `json_decode`，结果必须是数组。
    2. 只保留白名单中的键：`ts`、`landing`、`ref`、`utm_source`、`utm_medium`、`utm_campaign`、`utm_content`、`utm_term`、`fbclid`、`gclid`。
    3. 每个值都必须是标量，转为字符串后 `sanitize_text_field`，并截断到 200 字符（`landing` 为 500）。
    4. `landing` 再用 `clean_landing` 处理。`ts` 必须是 ISO-8601 时间，否则删掉这个键。
    5. 处理后为空，或者解码失败时，返回 null。
  - `clean_landing(string $url): string`：只保留 scheme、host 和 path，以及白名单中的查询参数。
  - `cookie_value(string $name): ?string` 从 `$_COOKIE` 中读取并 `wp_unslash`；单元测试中改为传入数组，签名写成 `from(array $cookies, string $name)`。

- [ ] **Step 1: 写失败的测试**

```php
// PhoneTest（Review Focus 4）
public function test_accepts_common_ghana_formats(): void   // 024 123 4567 / +233 24 123 4567 / 233241234567 / 0241234567 / +233 0241234567 → +233241234567
public function test_rejects_short_foreign_and_garbage(): void   // 12345 / +2348012345678 / 0141234567 / "abc" → null
// GpsTest
public function test_normalizes_case_spaces_and_hyphens(): void  // "ga1838164" → "GA-183-8164"；"gs 0151 6411" → "GS-0151-6411"
public function test_rejects_bad_format(): void                  // "G-183-8164" / "GA-18-81" / "" → null
// TouchTest（Review Focus 3）
public function test_decode_whitelists_truncates_and_strips_tags(): void   // 额外的键被丢弃；<script> 被去除；300 字符截断到 200
public function test_decode_rejects_non_array_or_bad_base64(): void
public function test_landing_drops_non_whitelisted_query(): void           // ?email=a@b.c&utm_source=fb → 只保留 utm_source
public function test_uuid_rejects_non_v4(): void
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./tests/kiano-connector/run-unit.sh`
- [ ] **Step 5: Commit** — `Add phone, GhanaPost GPS and touch sanitizers for kiano-connector`

---

### Task 3: 前端采集 tracker.js

**Files:**
- Create: `assets/js/tracker.js`、`includes/class-kiano-tracking.php`（本任务只负责加载脚本）
- Test: `tests/kiano-connector/js/tracker.test.mjs`

**Interfaces:**
- `tracker.js` 是一个 IIFE。纯逻辑通过 `if (typeof module !== 'undefined') module.exports = {...}` 导出：
  - `uuidv4(rand)`：生成 UUID，rand 可以注入，便于测试。
  - `isNonDirect(url, referrer, ownHost)`
  - `buildTouch(url, referrer, now)`：返回 `{ts, landing, ref, utm_*, fbclid, gclid}`，`ref` 只取 referrer 的 host。
  - `encode(obj)` 和 `decode(str)`：base64url 编码的 JSON。
  - `plan(cookies, url, referrer, ownHost, now, rand)`：返回 `{set: [{name, value, maxAgeSeconds}]}`，这是全部 cookie 规则的唯一实现，DOM 代码只负责执行它。
- 浏览器端在 `document.cookie` 上执行 `plan` 的结果；HTTPS 下加 `Secure`。
- PHP 端：`Kiano_Tracking::enqueue()` 在 `wp_enqueue_scripts` 中加载 tracker.js，所有前台页面都加载，`strategy: defer`，版本号为 `KIANO_CONNECTOR_VERSION`。**不使用 `wp_localize_script` 注入任何按用户变化的数据**，这样页面缓存是安全的。

- [ ] **Step 1: 写失败的测试**（Review Focus 5）

```js
test('first visit sets vid(365d), sid(30m), ft and lt when utm present')
test('repeat visit keeps vid, refreshes sid max-age')
test('direct visit (no params, same-host or empty referrer) does not overwrite lt, and does not set ft if exists')
test('external referrer counts as non-direct; ref stores host only')
test('landing keeps only whitelisted params')       // 与 PHP 端的 clean_landing 规则一致
test('touch cookie stays under 1KB with long utm values (truncated to 200)')
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./tests/kiano-connector/run-js.sh`
- [ ] **Step 5: 浏览器验证**：访问 `http://localhost:8080/?utm_source=fb&utm_content=123&fbclid=abc`，cookie 中出现 vid、sid、ft、lt；再直接访问首页，vid 不变，lt 也不变。
- [ ] **Step 6: Commit** — `Add first-party visitor/session tracker with first and last touch cookies`

---

### Task 4: 下单时把触点写入订单 meta

**Files:**
- Modify: `includes/class-kiano-tracking.php`
- Test: `tests/kiano-connector/integration/test-tracking.php`

**Interfaces:**
- `Kiano_Tracking::capture(WC_Order $order, array $cookies): void`。这是纯数据入口，测试中直接调用：
  - 用 `Kiano_Touch` 清理 cookie 后，写入 `_kiano_vid`、`_kiano_sid`、`_kiano_first_touch` 和 `_kiano_last_touch`（后两者是 `wp_json_encode` 后的 JSON）。
  - 读取 `_fbc` 和 `_fbp` cookie：格式必须是 `fb.\d.\d+.[\w-]+`，长度 ≤ 255，写入 `_kiano_fbc` 和 `_kiano_fbp`。
  - 写入 `_kiano_plugin_version`。
  - 值无效时不写这个键，不写空字符串。
  - **只写 meta，不调用 `save()`**：两个钩子的调用方随后都会保存订单。
- 钩子：
  - `woocommerce_store_api_checkout_update_order_from_request`（区块结账）和 `woocommerce_checkout_create_order`（经典结账）都调用 `capture($order, $_COOKIE)`。
  - 区块结账在付款失败重试时会多次触发，`capture` 每次覆盖写入，结果相同，所以是幂等的。

- [ ] **Step 1: 写失败的测试**

```php
kt_test('capture writes sanitized meta via HPOS CRUD', ...)      // 保存后用 wc_get_order 重新读取，各项 meta 都正确；HPOS 表中有数据，并且没有用 get_post_meta 读取
kt_test('forged cookies are dropped or cleaned', ...)            // Review Focus 3：vid="x"、ft 中含 <script> 和额外的键
kt_test('missing cookies write nothing', ...)
kt_test('hooks are registered for block and classic checkout', ...)   // has_action(...) 返回值 !== false
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./tests/kiano-connector/run-integration.sh`
- [ ] **Step 5: Commit** — `Write visitor, session, touch and Meta click cookies to order meta at checkout`

---

### Task 5: 结账字段与自定义状态（含库存）

**Files:**
- Create: `includes/class-kiano-checkout.php`、`includes/class-kiano-statuses.php`、`assets/js/checkout-hints.js`
- Test: `tests/kiano-connector/integration/{test-checkout.php,test-statuses.php}`、`tests/kiano-connector/js/checkout-hints.test.mjs`

**Interfaces（结账）：**
- 在 `woocommerce_init` 中调用 `woocommerce_register_additional_checkout_field`：
  - `id` 为 `kiano/gps-address`，`label` 为 `GhanaPost GPS address`，`optionalLabel` 为 `GhanaPost GPS address (optional)`，`location` 为 `address`，`required` 为 false。
  - `attributes` 为 `['autocomplete' => 'off', 'title' => 'e.g. GA-183-8164', 'maxLength' => 14]`。
  - `sanitize_callback`：用 `Kiano_Gps::normalize` 规范化，无法规范化时保留原值，交给校验处理。
  - `validate_callback`：非空并且无法规范化时，返回 `new WP_Error('kiano_invalid_gps', 'Enter a valid GhanaPost GPS address, e.g. GA-183-8164.')`。
- `woocommerce_blocks_validate_location_address_fields`：`$fields['country'] === 'GH'` 且 `$fields['phone']` 不为空，并且 `Kiano_Phone::to_e164` 返回 null 时，执行 `$errors->add('kiano_invalid_phone', 'Enter a Ghana phone number, e.g. 024 123 4567.')`。经典结账在 `woocommerce_after_checkout_validation` 中执行同样的检查。
- `woocommerce_store_api_checkout_update_order_from_request` 和 `woocommerce_checkout_create_order` 中：
  - 写入 `_kiano_phone_e164`，取 billing 电话；billing 电话无效时取 shipping 电话。
  - 写入 `_kiano_gps_address`：通过 `CheckoutFields::get_field_from_object('kiano/gps-address', $order, 'shipping')` 读取，shipping 没有时读 billing。
- `checkout-hints.js` 只在结账页加载。纯函数 `phoneHint(value, country)` 和 `needsGpsNudge(paymentMethod, gps)` 导出给 node 测试。DOM 部分订阅 `wp.data.select('wc/store/cart')` 中的 billing 地址，以及 `wc/store/payment` 中当前选择的支付方式：电话格式不对时，在电话输入框下方显示提示；选择 COD 但没有填写 GPS 时，在 GPS 字段下方显示提示 "Strongly recommended for Cash on Delivery – helps the rider find you."。**前端只负责提示，以服务端校验为准。**

**Interfaces（状态）：**
- `init` 中调用 `register_post_status`，4 个状态的 label 分别是 `Awaiting confirmation`、`Confirmed`、`Dispatched`、`Refused`，参数 `public=false, exclude_from_search=false, show_in_admin_all_list=true, show_in_admin_status_list=true`，并提供 `label_count`。
- `wc_order_statuses` 过滤器：把 awaiting 和 confirmed 插在 `wc-processing` 之后，dispatched 插在 `wc-completed` 之前，refused 插在 `wc-cancelled` 之后。
- `woocommerce_cod_process_payment_order_status`：订单不含可下载商品时，返回 `kiano-awaiting`；含有可下载商品时保持原值。
- **库存**（Review Focus 1）：
  - `woocommerce_order_status_kiano-awaiting`、`kiano-confirmed`、`kiano-dispatched` 三个动作都挂上 `wc_maybe_reduce_stock_levels`。awaiting 另外挂上 `wc_release_stock_for_order`，优先级 11。
  - `woocommerce_order_status_kiano-refused` 挂上 `wc_maybe_increase_stock_levels`。
- **Analytics**：用 `option_woocommerce_excluded_report_order_statuses` 和 `default_option_woocommerce_excluded_report_order_statuses` 两个过滤器追加 `kiano-refused`（结果去重）。`woocommerce_reports_order_statuses` 中追加 awaiting、confirmed、dispatched。
- **后台批量操作**：
  - 在 HPOS 订单列表 `bulk_actions-woocommerce_page_wc-orders` 和旧版列表 `bulk_actions-edit-shop_order` 中，增加 "Change status to confirmed / dispatched / refused" 三项。
  - 处理函数是 `handle_bulk_actions-woocommerce_page_wc-orders` 和 `handle_bulk_actions-edit-shop_order`：检查 `current_user_can('edit_shop_orders')`，然后对每个订单 `wc_get_order($id)->update_status(...)`，备注为 "Bulk status change"。
- **REST**：自定义状态注册之后，Woo REST v3 的 status 枚举会自动包含它们，Phase 1b 的 Commerce Port 会依赖这一点，所以要用测试锁定。

- [ ] **Step 1: 写失败的测试**

```php
// test-checkout.php
kt_test('gps field registered with sanitize+validate', ...)     // 直接调用两个回调："ga1838164" 变为 "GA-183-8164"，"G-1" 返回 WP_Error
kt_test('phone validation only for GH and rejects bad numbers', ...)   // Review Focus 4：do_action 触发校验钩子；GH 加 "12345" 时有错误；NG 加 "12345" 时没有错误
kt_test('order gets _kiano_phone_e164 and _kiano_gps_address', ...)
// test-statuses.php
kt_test('cod process_payment → kiano-awaiting and stock reduced once', ...)     // 库存 10，qty 2：process_payment 之后变为 8；awaiting 转 confirmed 后仍为 8
kt_test('refused restores stock once; cancelled from awaiting restores', ...)
kt_test('prepaid processing → dispatched does not double-reduce', ...)
kt_test('statuses present in wc_get_order_statuses and REST schema enum', ...)  // 用 rest_do_request(OPTIONS /wc/v3/orders) 读取 schema
kt_test('analytics excluded statuses include kiano-refused without DB write', ...)   // get_option 结果包含 kiano-refused；数据库中原始的 option 行不变
kt_test('slug lengths ≤ 20', ...)
```
```js
// checkout-hints.test.mjs
test('phoneHint: GH bad → message, GH good → null, non-GH → null')
test('needsGpsNudge: cod+empty → true, cod+value → false, paystack → false')
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `run-integration.sh && run-js.sh && run-unit.sh`
- [ ] **Step 5: 本地启用 COD**（只改本地卷，按 Kiano 的 CLAUDE.md，通过 WP-CLI 完成）：`docker compose run --rm wpcli wp option patch update woocommerce_cod_settings enabled yes`（option 不存在时先用 `wp eval` 读取 `WC_Gateway_COD` 的默认设置，再写入）。
- [ ] **Step 6: Commit** — `Add GhanaPost GPS field, Ghana phone validation and Kiano order statuses with stock handling`

---

### Task 6: 邮件

**Files:**
- Create: `includes/class-kiano-emails.php`、`includes/emails/class-kiano-email-{awaiting,dispatched}.php`、`templates/emails/{customer-awaiting,customer-dispatched}.php`、`templates/emails/plain/` 中对应的两个文件
- Test: `tests/kiano-connector/integration/test-emails.php`

**Interfaces:**
- `woocommerce_email_actions` 过滤器追加 `woocommerce_order_status_pending_to_kiano-awaiting` 和 `woocommerce_order_status_kiano-dispatched`。
- **店主的新订单邮件**（Review Focus 2）：在 `woocommerce_order_status_pending_to_kiano-awaiting_notification` 上挂 `WC()->mailer()->emails['WC_Email_New_Order']->trigger($order_id)`。
- **客户邮件**通过 `woocommerce_email_classes` 注册，两个类都继承 `WC_Email`，`customer_email = true`，模板路径为插件的 `templates/`，因此主题可以覆盖：
  - `Kiano_Email_Awaiting`：id 为 `kiano_customer_awaiting`，标题 "Order received – we'll confirm shortly"，正文说明会通过 WhatsApp 或电话确认，并附上订单摘要。触发动作是 `woocommerce_order_status_pending_to_kiano-awaiting_notification`。
  - `Kiano_Email_Dispatched`：id 为 `kiano_customer_dispatched`，标题 "Your order is on its way"。触发动作是 `woocommerce_order_status_kiano-dispatched_notification`。
- confirmed 和 refused 两个状态**不发邮件**：确认通过 WhatsApp 完成，那是 Phase 1b 的工作；拒收不通知客户。
- 两封客户邮件都可以在 WooCommerce → Settings → Emails 中开关，默认开启。

- [ ] **Step 1: 写失败的测试**（使用 `kt_capture_mail()`）

```php
kt_test('cod order → admin New Order + customer awaiting mail', ...)
kt_test('dispatched → customer dispatched mail only', ...)
kt_test('confirmed/refused → no customer mail', ...)
kt_test('email templates escape customer name', ...)      // 名字为 "<b>x</b>" 时，邮件正文中是 "&lt;b&gt;"
```

- [ ] **Step 2–4: 先确认失败 → 实现 → 确认通过**　Run: `./tests/kiano-connector/run-integration.sh`
- [ ] **Step 5: Commit** — `Send admin and customer emails for Kiano awaiting and dispatched statuses`

---

### Task 7: 本地端到端验收（区块结账）

**前提：** 本地 Woo 中有一个管理库存的商品，COD 已启用，Paystack 处于测试模式（如果需要验证预付流程，测试 key 由用户在 Paystack 插件设置中自行填写，**实施者不经手**）。

- [ ] **Step 1:** 在内置浏览器中打开 `http://localhost:8080/?utm_source=facebook&utm_medium=paid&utm_campaign=c4b-test&utm_content=120000000001&fbclid=TEST123`，加入购物车。
- [ ] **Step 2:** 进入结账页：
  1. 电话填 `12345`，提交后应在电话字段附近报错；改为 `024 123 4567`。
  2. 选择 COD，GPS 留空时应出现建议填写的提示；填 `ga1838164`。
  3. 下单。
- [ ] **Step 3:** 用 WP-CLI 读取这个订单：
  - 状态为 `kiano-awaiting`，库存已经扣减。
  - `_kiano_vid` 和 `_kiano_sid` 是 UUID。
  - `_kiano_last_touch.utm_content = 120000000001`，`fbclid = TEST123`。
  - `_kiano_phone_e164 = +233241234567`，`_kiano_gps_address = GA-183-8164`。
  - 内置的 `_wc_order_attribution_*` 仍然存在。
  - 后台订单页显示 GPS 地址。
  - 被拦截的邮件中有新订单邮件和 awaiting 邮件（本地没有 SMTP 时，用 `wp eval` 查看 `kt_capture_mail` 的记录或 Woo 的邮件日志）。
- [ ] **Step 4:** 在后台把订单批量改为 confirmed，再改为 dispatched，最后改为 refused，确认库存恢复；在 Analytics → Orders 中，refused 的订单不计入统计。
- [ ] **Step 5:** 确认 nginx 能直接提供 `/wp-content/plugins/kiano-connector/assets/js/tracker.js`，状态 200；并确认 `/wp-content/plugins/kiano-connector/includes/class-kiano-phone.php` 不会泄露源码：返回 200 空白或者 403、404 都可以，因为文件开头有 `defined('ABSPATH') || exit;`。
- [ ] **Step 6:** 把验收结果记录到 `docs/kiano-connector.md` 的"验收记录"一节。

---

### Task 8: 文档与交付

**Files:**
- Create: KianosMart 仓库的 `docs/kiano-connector.md`，内容如下：
  - **数据契约**：订单 meta 键、JSON 结构、状态 slug、cookie 规格。
  - **运维说明**：后台批量操作、邮件开关、Analytics 中排除的状态。
  - **部署**：先部署 staging，再部署生产；隐私政策需要补充的内容，见 v1.2 §23.2。
  - **验收记录**。
- Modify:
  - KianosMart 仓库：README，在 "Custom plugins" 一节中加入 kiano-connector 和测试命令。
  - KianosMartGrowthOS 仓库：
    - `docs/Kiano Growth OS 产品需求与技术架构设计 v1.2.md` §7：补充已确认的钩子、扩展的 meta 键（`_kiano_fbc`、`_kiano_fbp`、`_kiano_phone_e164`、`_kiano_gps_address`），以及"只支持区块结账"。
    - `docs/superpowers/specs/2026-10-07-content-module-design.md` §14 C4：注明插件的验收标准是：区块结账的 COD 订单进入 kiano-awaiting，并带有完整的触点 meta、GPS 地址和 E.164 电话号码。

- [ ] **Step 1: 运行全部测试**：`run-unit.sh && run-js.sh && run-integration.sh`
- [ ] **Step 2: 更新文档**
- [ ] **Step 3: Commit**：KianosMart 仓库用 `Document kiano-connector data contract and operations`；KianosMartGrowthOS 仓库用 `docs: kiano-connector contract in v1.2 §7 and C4 acceptance`
- [ ] **Step 4: 交给用户**，由用户完成：
  1. 推送 KianosMart 仓库，并在 staging VPS 上运行 `scripts/deploy.sh`。
  2. 在 staging 用真实手机走一遍 Task 7 的验收流程。
  3. 在生产环境运行 `scripts/deploy.sh`，执行 `wp plugin activate kiano-connector`，然后下一笔 COD 测试订单并取消。
  4. 更新网站隐私政策。

  **实施者不执行以上任何一步。**

---

## Self-Review 记录

- **对照 v1.2 §7 逐条检查**：
  - §7.1 采集：前端生成 ID 由 Task 3 实现；首次和最后一次触点、7 天有效期由 Task 3 实现；写入订单 meta 由 Task 4 实现；与内置 Order Attribution 并存在 Task 4 和 Task 7 中验证。
  - §7.2 结账字段：GPS 地址（可选，COD 订单强烈提示填写）和 Ghana 电话校验与提示，都在 Task 5 中实现。
  - §7.3 自定义状态：4 个状态和 COD 过滤器在 Task 5 中实现；库存、Analytics 在 Task 5 中实现，邮件在 Task 6 中实现；HPOS 兼容在 Task 1、4、5 中实现。
  - 挂载规则（含 nginx 只读挂载）在 Task 1 中实现。
  - §13.1 中"超时未确认 → cancelled"的自动化，以及 WhatsApp 确认，属于 Phase 1b（Kiano 的 M10 模块），不在本计划中。
- **钩子名称都已在本地 WC 11.0.1 源码中确认**（见上表），没有凭记忆推测。
- **类型和名称一致性**：
  - `Kiano_Phone::to_e164`、`Kiano_Gps::normalize`、`Kiano_Touch::decode/uuid/clean_landing` 在 Task 2 定义，在 Task 4、5 中使用。
  - JS 的 `plan()` 和 `phoneHint()`、`needsGpsNudge()` 分别在 Task 3 和 Task 5 中定义，并由各自的测试覆盖。
  - PHP 和 JS 两边 landing URL 的参数白名单必须相同，Task 2 和 Task 3 各有一个测试覆盖。
- **安全**：
  - `vendor/` 不进入挂载目录。
  - 所有 cookie 都经过清理。
  - 订单只通过 CRUD 读写。
  - 邮件模板中的内容都经过转义。
  - 批量操作会检查权限。
  - 插件中没有机密，Paystack 的 key 由用户在后台自行填写。
