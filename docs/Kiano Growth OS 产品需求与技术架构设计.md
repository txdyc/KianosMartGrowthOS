Kiano Growth OS
===============

产品需求与技术架构设计 v1.0
----------------

**项目定位：** 面向 KianosMart 的 AI 驱动电商增长操作系统  
**第一阶段业务：** Ghana / Morgan Electronics / WooCommerce  
**长期方向：** 可演化为 Ghana/Africa 多商户 E-commerce Growth SaaS

* * *

1. 产品愿景
   =======

Kiano Growth OS 不应该成为另一个 ERP，也不是单纯的数据 Dashboard。

它的核心任务是把：

**商品 → 内容 → 广告 → 流量 → WhatsApp → 订单 → 支付 → 配送 → Review → CRM → 复购 → 利润**

串成一个完整的数据闭环。

最终系统每天应该回答三个问题：

> 1. 今天什么商品最值得卖？
> 
> 2. 今天什么广告最值得继续投入？
> 
> 3. 今天我应该做哪 3–5 件事情才能提高利润？

因此产品的核心输出不是“数据”，而是：

> **Data → Insight → Recommendation → Action**

* * *

2. 产品总体架构
   =========
   
                             KIANO GROWTH OS
                                    │
           ┌────────────────────────┼────────────────────────┐
           │                        │                        │
           ▼                        ▼                        ▼
   
     PRODUCT INTELLIGENCE     MARKETING INTELLIGENCE     SALES INTELLIGENCE
   
           │                        │                        │
           │                 ┌──────┴──────┐                 │
           │                 │             │                 │
   
     WooCommerce           META          GOOGLE           WHATSAPP
   
           │                 │             │                 │
           └─────────────────┼─────────────┼─────────────────┘
                             │
                             ▼
                      DATA / EVENT LAYER
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
            OLTP           Analytics        Event Store
           PostgreSQL      PostgreSQL/      + Redis
                           ClickHouse*
                             │
                             ▼
                         AI ENGINE
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
   
     Product Agent      Marketing Agent    Sales Agent
   
            │                │                │
            └────────────────┼────────────────┘
                             ▼
                      DECISION ENGINE
                             │
                             ▼
                     APPROVAL / WORKFLOW
                             │
                             ▼
                      EXECUTION ENGINE
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
           WooCommerce     Meta/Google    WhatsApp
* MVP 阶段不需要 ClickHouse，PostgreSQL 足够。

* * *

3. 一个非常重要的架构决定
   ==============

WooCommerce 不要被替代
-----------------

第一阶段：
    WooCommerce = Commerce System of Record

    Kiano Growth OS = Intelligence + Automation Layer

WooCommerce 官方当前推荐的新 REST API 集成使用 `/wp-json/wc/v3/`，可以访问和修改商品、订单、客户、优惠券等数据；WooCommerce 也支持 Webhooks 监听订单、商品、客户等变化。

因此不建议第一阶段把：

> Products  
> Orders  
> Customers  
> Coupons

完全迁移到自己的系统。

Kiano 只保存一份：

> **自己的增长数据模型 + 必要的业务快照**

这样以后即使 KianosMart 从 WooCommerce 迁移到 Shopify、自研商城或者其他平台，也不会把 Growth OS 一起绑死。

* * *

4. 系统模块划分
   =========

Module 01 — Tenant & Workspace
------------------------------

第一版虽然只有 KianosMart 一个商户，也必须把租户概念设计进去。
    Tenant
     └── Workspace
          ├── Users
          ├── Roles
          ├── Integrations
          ├── Stores
          └── Settings

### 核心功能

* 商户

* 用户

* Role

* Permission

* Store

* Integration

* API Key

* Secret

* Timezone

* Currency

* Business settings

### Role

    OWNER
    ADMIN
    MARKETING
    SALES
    OPERATOR
    VIEWER
    AI_AGENT

* * *

5. Module 02 — Product Intelligence
   ===================================

这是 Kiano Growth OS 的基础。
功能
--

### 商品同步

WooCommerce → Kiano

同步：

* product

* SKU

* category

* brand

* price

* sale price

* stock

* images

* attributes

* description

* permalink

### 商品增强

AI 自动生成：

* SEO title

* product title

* short description

* long description

* specification

* meta description

* Facebook copy

* Instagram caption

* TikTok caption

* WhatsApp sales copy

* Google Shopping title

### 商品评分

每一个 SKU 自动形成：
    Product Score
    ├── Margin Score
    ├── Conversion Score
    ├── Traffic Score
    ├── Ad Score
    ├── Stock Score
    ├── Review Score
    └── Growth Score

最终：

> Hero SKU / Profit SKU / Long-tail / Slow mover

自动分类。

* * *

6. Module 03 — Cost & Profit Intelligence
   =========================================

这是整个系统最核心的模块之一。

普通电商系统主要看：

> Sales / Revenue / ROAS

Kiano 必须看：

> **Contribution Profit**

* * *

Cost Model
----------

    Product Revenue
    - Product Cost
    - Delivery Cost
    - Payment Fee
    - COD Failure Reserve
    - Return Cost
    - Discount
    - Packaging Cost
    - Advertising Cost
    ----------------------
    Contribution Profit

* * *

每个 SKU 保存
---------

    selling_price
    product_cost
    average_delivery_cost
    payment_fee_rate
    cod_failure_rate
    average_return_cost
    packaging_cost
    maximum_cac
    target_margin

然后自动计算：
    Maximum CAC
    Target CAC
    Actual CAC
    Profit After CAC

* * *

7. Module 04 — Marketing Intelligence
   =====================================

连接：

### Meta

* Facebook

* Instagram

* Meta Ads

### Google

* Google Ads

* Merchant Center

### TikTok

第一版只做数据导入，后续再做完整 API 自动化。

* * *

8. Meta Integration
   ===================

Meta Marketing API 的 Insights API 可以按广告账户、Campaign、Ad Set、Ad 等层级获得广告表现数据，包括 impressions、clicks、spend、reach、CTR、actions、conversion value 等；Meta 的公开 Marketing API 集合也支持 campaign/adset/ad 的读取和修改。
Kiano Connector
---------------

    MetaConnector
    ├── authenticate()
    ├── getAdAccounts()
    ├── getCampaigns()
    ├── getAdSets()
    ├── getAds()
    ├── getInsights()
    ├── getCreatives()
    └── syncDaily()

### 数据同步频率

初期：

> 每 2–4 小时

后期：

> 15–60 分钟

不要一开始追求 realtime。

* * *

9. Meta 数据模型
   ============
   
    ad_account
    campaign
    ad_set
    ad
    creative
    ad_daily_metric

核心指标：
    date
    impressions
    reach
    clicks
    outbound_clicks
    spend
    ctr
    cpc
    cpm
    frequency
    actions
    conversion_value

进一步增加：
    landing_page_views
    add_to_cart
    checkout
    purchase
    purchase_value

这些最好结合自己的网站事件进行二次归因，而不能完全依赖平台提供的 conversion 字段。

* * *

10. Google Ads Integration
    ==========================

Google Ads API 当前通过 GoogleAdsService 的 Search / SearchStream + GAQL 获取广告对象和性能数据，可以从 Campaign 一直查询到更细粒度资源；Google 官方建议使用 GAQL 组织查询。

因此 Kiano 不应该给每个报表写一套硬编码 SQL/API。

应该设计：
    GoogleAdsQueryBuilder
            ↓
    GAQL
            ↓
    GoogleAdsConnector
            ↓
    Normalized Metrics

### 需要获取

* Campaign

* Ad Group

* Ad

* Keyword

* Shopping product

* Performance Max asset/product performance

* Cost

* Click

* Impression

* Conversion

* Conversion value

* * *

11. Google Merchant Center Integration
    ======================================

Google Merchant API 当前支持 programmatically 管理 product inputs / products，并可管理 data sources；Google 将 data sources 用于商品、库存、促销、评价等数据。

因此系统应该建立：
    WooCommerce
          ↓
    Product Normalizer
          ↓
    Google Product Feed
          ↓
    Merchant Center

第一版不一定直接用 Merchant API。

可以：

> XML / CSV / Content API/feed

先跑通。

第二阶段再切 Merchant API。

* * *

12. Module 05 — Tracking & Attribution
    ======================================

这是 Growth OS 能否真正产生价值的关键。

网站必须统一生成：
    session_id
    visitor_id
    customer_id
    order_id

广告需要保存：
    utm_source
    utm_medium
    utm_campaign
    utm_content
    utm_term
    fbclid
    gclid

* * *

13. Customer Journey
    ====================

例如：
    Facebook Ad
       ↓
    Landing Page
       ↓
    Product View
       ↓
    Add Cart
       ↓
    Checkout
       ↓
    Payment
       ↓
    Order
       ↓
    Delivery
       ↓
    Review

系统应该能够回答：

> 哪一个 Ad 最终带来了利润？

而不仅仅是：

> 哪一个 Ad 带来了 click？

* * *

14. Attribution Model
    =====================

第一版提供：

### Last Touch

最简单、最稳定。

第二阶段：

### First Touch

### Linear

### Time Decay

### Data-driven Attribution

但不要一开始就开发复杂 attribution model。

* * *

15. Module 06 — WhatsApp CRM & Sales
    ====================================

这是 KianosMart 的核心竞争力之一。

Meta 的官方 WhatsApp Business Platform Cloud API 支持程序化发送文本、图片、视频和模板消息，并通过 Webhooks 接收消息/状态；Meta 官方的 Postman 集合还支持 WABA subscription、message API 等能力。

* * *

16. WhatsApp CRM 数据结构
    =====================
    
    Customer
       │
       ├── WhatsApp Contact
       │
       ├── Conversation
       │      └── Messages
       │
       ├── Orders
       │
       ├── Tickets
       │
       └── Customer Tags

### Customer Tags

例如：
    NEW
    HIGH_VALUE
    COD
    REPEAT_BUYER
    BLENDER_INTEREST
    KITCHEN
    PRICE_SENSITIVE
    ABANDONED_CART
    DORMANT
    VIP

* * *

17. WhatsApp AI Sales Agent
    ===========================

推荐采用：
    User
     ↓
    WhatsApp Cloud API
     ↓
    Webhook
     ↓
    Message Router
     ↓
    Conversation Manager
     ↓
    AI Orchestrator
     ↓
    Knowledge Retrieval
     ↓
    Tool Calls
     ↓
    Response
     ↓
    WhatsApp API

* * *

18. AI 不应该直接访问数据库
    =================

应该使用 Tool Layer：
    get_product()
    get_price()
    get_stock()
    get_delivery_fee()
    get_customer()
    get_customer_orders()
    create_order()
    create_payment_link()
    check_payment_status()
    apply_coupon()
    handoff_to_human()

例如 AI：
    Customer:
    "How much is the Morgan blender?"

AI 不应该自己猜价格。

必须：
    get_product("Morgan blender")
            ↓
    current_price
            ↓
    AI response

这样可以避免 AI “幻觉价格”。

* * *

19. WhatsApp Human Handoff
    ==========================

这是必须有的。

AI 遇到：
    Refund
    Complaint
    Angry customer
    Warranty dispute
    Missing delivery
    Payment dispute
    Complex negotiation

自动：

> Handoff to Human

并保存：
    handoff_reason
    conversation_summary
    customer_intent
    recommended_action

* * *

20. Module 07 — Order / COD Automation
    ======================================

WooCommerce Webhooks 可监听 order.created / order.updated 等事件，因此 Kiano 可以实时或近实时接收订单变化。

流程：
    NEW ORDER
        ↓
    Validate
        ↓
    COD?
     ┌──┴──┐
    Yes    No
     ↓      ↓
    WhatsApp    Payment
    Confirm     Verification
     ↓            ↓
    Confirmed   Paid
         └───────┬───────┘
                 ↓
              PACKING
                 ↓
             DISPATCHED
                 ↓
              DELIVERED

* * *

21. COD Order Risk Score
    ========================

这个非常值得做。

例如：
    COD Risk Score =

    Historical Failed Orders
    +
    Distance
    +
    Customer History
    +
    Phone Reputation
    +
    Order Value
    +
    Address Completeness
    +
    Repeat/New Customer

输出：
    LOW
    MEDIUM
    HIGH

高风险订单：

> 必须 WhatsApp + Phone 双确认。

* * *

22. Module 08 — Paystack Payment Integration
    ============================================

Paystack 当前提供 Transaction Initialize / Verify API；其 Ghana Mobile Money 支付通过 Mobile Money channel，并需要通过 webhook 获得最终状态。

因此：
    Kiano
      ↓
    POST /transaction/initialize
      ↓
    Paystack Checkout
      ↓
    Customer pays
      ↓
    Paystack Webhook
      ↓
    Kiano
      ↓
    Order = PAID

Paystack 官方建议使用 Webhook 获取最终状态，并要求验证 `x-paystack-signature`；当前 Paystack 还提供 Webhook Events API，可以查看和重新发送 webhook 事件，这对生产环境故障恢复很有价值。

* * *

23. Paystack Connector
    ======================
    
    PaystackConnector
    ├── initializeTransaction()
    ├── verifyTransaction()
    ├── getTransaction()
    ├── handleWebhook()
    └── reconcile()

### 最重要的机制

    payment_reference

必须保证幂等。

同一个 reference：

> 永远不能创建两笔订单。

* * *

24. Module 09 — AI Content Factory
    ==================================

输入：
    Product ID

输出：
    Product Description
    SEO Content
    Facebook Copy
    Instagram Copy
    TikTok Hook
    TikTok Script
    UGC Script
    WhatsApp Copy
    Video Storyboard
    Image Prompt

* * *

25. Content Pipeline
    ====================
    
    Product
     ↓
    Product DNA
     ↓
    AI Brief Generator
     ↓
    Creative Variants
     ↓
    Human Review
     ↓
    Asset Generation
     ↓
    Publish
     ↓
    Performance Tracking

每个 Hero SKU 建立：
    10 hooks
    5 UGC scripts
    5 product demos
    5 promotional copies
    3 comparison angles
    3 lifestyle angles

* * *

26. Product DNA
    ===============

这是 AI 系统的核心知识对象：
    {
      "product_id": 123,
      "brand": "Morgan",
      "category": "Blender",
      "price": 299,
      "features": [],
      "benefits": [],
      "target_audience": [],
      "pain_points": [],
      "objections": [],
      "content_angles": [],
      "competitive_points": [],
      "restrictions": []
    }

这个 Product DNA 将同时被：

> Product Agent  
> SEO Agent  
> Ad Agent  
> WhatsApp Agent  
> Content Agent

使用。

* * *

27. Module 10 — AI Marketing Analyst
    ====================================

每天自动分析：
    Yesterday
    Last 7 Days
    Last 30 Days

发现：
    High CAC
    Low CTR
    High CPC
    High ATC / Low Purchase
    High Traffic / Low Conversion
    High ROAS / Low Margin
    Low Stock / High Demand

* * *

28. AI Recommendation Engine
    ============================

例如生成：
    Recommendation #001

    Product:
    Morgan Blender

    Problem:
    CAC decreased 32% over last 7 days.

    Current CAC:
    GHS 32

    Maximum CAC:
    GHS 85

    Recommendation:
    Increase Meta budget by 20%.

    Confidence:
    87%

    Expected impact:
    +15–25% orders

    Action:
    [Approve]
    [Reject]
    [Review]

* * *

29. AI 不直接执行关键操作
    ================

采用：
    AI
     ↓
    Recommendation
     ↓
    Policy Engine
     ↓
    Human Approval
     ↓
    Execution

后期对低风险规则开放：
    if stock == 0
        pause_ad()

    if payment_failed
        notify_customer()

    if order_delivered
        send_review_request()

而高风险操作：
    increase_budget
    change_price
    pause_campaign
    refund
    send_mass_campaign

必须审批。

* * *

30. AI Agent 总体架构
    =================

建议不要做一个“万能 Agent”。

采用 Multi-Agent：
                        AI ORCHESTRATOR
                               │
           ┌───────────────────┼───────────────────┐
           │                   │                   │
           ▼                   ▼                   ▼
     PRODUCT AGENT       MARKETING AGENT       SALES AGENT
           │                   │                   │
           │             ┌─────┴─────┐             │
           │             ▼           ▼             │
           │         ADS ANALYST   SEO AGENT       │
           │                                      │
           └──────────────────┬───────────────────┘
                              ▼
                      DECISION AGENT
                              │
                       POLICY ENGINE
                              │
                        ACTION ENGINE

* * *

31. Product Agent
    =================

职责：

* 商品质量检查

* 标题优化

* 描述

* SEO

* 产品分类

* Product DNA

* Hero SKU 判断

Tools：
    get_product
    update_product_draft
    generate_copy
    analyze_product
    compare_competitors

* * *

32. Marketing Agent
    ===================

职责：

* 广告分析

* CAC

* ROAS

* CTR

* CPC

* Creative performance

* Budget recommendation

Tools：
    get_meta_insights
    get_google_ads_metrics
    get_product_profit
    get_campaign
    recommend_budget

* * *

33. Sales Agent
    ===============

职责：

* WhatsApp 客服

* 产品推荐

* 销售

* FAQ

* 订单创建

* payment link

* handoff

Tools：
    search_products
    get_product
    check_stock
    calculate_delivery
    create_order
    create_payment_link
    get_customer_history
    handoff_human

* * *

34. Decision Agent
    ==================

它不是客服。

它负责：

> **老板视角的决策。**

例如：
    "Which products should we promote tomorrow?"

Decision Agent 调用：
    Product Data
    +
    Ad Data
    +
    Profit Data
    +
    Stock Data
    +
    Customer Data

然后输出：
    Top 5 Products
    Top 3 Creatives
    Campaign recommendations
    Budget recommendations
    Potential problems

* * *

35. RAG / Knowledge Base
    ========================

AI Agent 必须有自己的 Knowledge Base。

数据来源：
    Product data
    Brand data
    Warranty
    Delivery policy
    Return policy
    Payment policy
    FAQ
    Promotion rules
    Customer service scripts
    Competitor information
    Marketing playbook

建议：
    PostgreSQL = structured facts

    Vector DB = semantic knowledge

MVP 可以直接：

> PostgreSQL + pgvector

不需要单独部署 Pinecone/Weaviate。

* * *

36. 数据库 ER 模型
    =============

MVP 核心 ER：
    erDiagram

        TENANT ||--o{ USER : has
        TENANT ||--o{ STORE : owns
        TENANT ||--o{ INTEGRATION : connects

        STORE ||--o{ PRODUCT : contains
        PRODUCT ||--o{ PRODUCT_COST : has
        PRODUCT ||--o{ PRODUCT_METRIC_DAILY : measures
        PRODUCT ||--o{ CREATIVE : uses
        PRODUCT }o--o{ CATEGORY : belongs_to

        CUSTOMER ||--o{ ORDER : places
        CUSTOMER ||--o{ CONVERSATION : has
        CUSTOMER ||--o{ CUSTOMER_TAG : has

        CONVERSATION ||--o{ MESSAGE : contains

        ORDER ||--o{ ORDER_ITEM : contains
        ORDER ||--o{ PAYMENT : has
        ORDER ||--o{ DELIVERY : has
        ORDER ||--o{ REVIEW : receives

        PRODUCT ||--o{ ORDER_ITEM : sold_as

        CAMPAIGN ||--o{ AD_SET : contains
        AD_SET ||--o{ AD : contains
        AD ||--o{ CREATIVE : uses
        AD ||--o{ AD_METRIC_DAILY : generates

        PRODUCT ||--o{ PRODUCT_AD_MAP : attributed_to
        AD ||--o{ PRODUCT_AD_MAP : promotes

        CUSTOMER ||--o{ ATTRIBUTION_TOUCH : creates
        AD ||--o{ ATTRIBUTION_TOUCH : generates

        AGENT ||--o{ AFFILIATE_ORDER : drives
        ORDER ||--o{ AFFILIATE_ORDER : attributed

        AI_AGENT ||--o{ AI_RUN : executes
        AI_RUN ||--o{ AI_ACTION : produces

        RECOMMENDATION ||--o{ APPROVAL : requires

* * *

37. 核心数据表
    =========

Tenant
------

    tenant
    ---------
    id
    name
    slug
    status
    currency
    timezone
    created_at

Store
-----

    store
    ---------
    id
    tenant_id
    name
    platform
    base_url
    status

* * *

38. Product
    ===========
    
    product
    ---------
    
    id
    tenant_id
    store_id
    external_id
    sku
    brand
    name
    slug
    category_id
    price
    sale_price
    stock
    status
    product_url
    image_url
    product_dna_json
    seo_json
    created_at
    updated_at

* * *

39. Product Cost
    ================
    
    product_cost
    ---------
    
    id
    product_id
    product_cost
    delivery_cost
    payment_fee_rate
    packaging_cost
    return_reserve
    cod_failure_reserve
    target_margin
    maximum_cac
    effective_from
    effective_to

* * *

40. Customer
    ============
    
    customer
    ---------
    
    id
    tenant_id
    external_id
    name
    phone
    email
    whatsapp_number
    location
    first_order_at
    last_order_at
    total_orders
    total_revenue
    total_profit
    customer_score
    cod_risk_score
    created_at
    updated_at

* * *

41. Order
    =========
    
    order
    ---------
    
    id
    tenant_id
    store_id
    external_id
    customer_id
    status
    payment_status
    fulfillment_status
    currency
    subtotal
    discount
    delivery_fee
    total
    product_cost
    delivery_cost
    payment_fee
    marketing_cost
    contribution_profit
    source
    created_at
    updated_at

* * *

42. Order Item
    ==============
    
    order_item
    ---------
    
    id
    order_id
    product_id
    sku
    quantity
    unit_price
    discount
    product_cost
    line_profit

* * *

43. Payment
    ===========
    
    payment
    ---------
    
    id
    order_id
    provider
    reference
    transaction_id
    channel
    amount
    currency
    status
    gateway_response
    paid_at
    metadata_json

* * *

44. WhatsApp Conversation
    =========================
    
    conversation
    ---------
    
    id
    customer_id
    channel
    phone_number
    status
    assigned_to
    ai_enabled
    last_message_at
    intent
    sentiment
    summary

Message：
    message
    ---------
    id
    conversation_id
    direction
    message_type
    content
    provider_message_id
    status
    template_name
    created_at

* * *

45. Advertising Tables
    ======================
    
    campaign
    ---------
    
    id
    tenant_id
    platform
    external_id
    name
    status
    objective
    budget
    created_at

    ad_set
    ---------
    id
    campaign_id
    external_id
    name
    status
    budget
    
    
    ad
    ---------
    id
    ad_set_id
    external_id
    name
    status
    creative_id
    
    
    ad_metric_daily
    ---------
    id
    ad_id
    date
    impressions
    reach
    clicks
    spend
    ctr
    cpc
    cpm
    conversions
    conversion_value

* * *

46. Attribution
    ===============
    
    attribution_touch
    ---------
    
    id
    customer_id
    session_id
    order_id
    channel
    platform
    campaign_id
    ad_id
    utm_source
    utm_medium
    utm_campaign
    utm_content
    gclid
    fbclid
    occurred_at

* * *

47. AI Tables
    =============
    
    ai_agent
    ---------
    
    id
    tenant_id
    name
    type
    model
    system_prompt_version
    status

    ai_run
    ---------
    id
    agent_id
    conversation_id
    task_type
    input_tokens
    output_tokens
    latency_ms
    status
    created_at
    
    
    ai_action
    ---------
    id
    ai_run_id
    action_type
    target_type
    target_id
    payload
    risk_level
    status
    executed_at

* * *

48. Recommendation
    ==================
    
    recommendation
    ---------
    
    id
    tenant_id
    type
    priority
    title
    description
    target_type
    target_id
    confidence
    expected_impact
    status
    created_at

    approval
    ---------
    id
    recommendation_id
    approved_by
    status
    comment
    approved_at

* * *

49. API 总体规范
    ============

采用：
    REST
    JSON
    JWT/OAuth2
    /api/v1

统一：
    GET
    POST
    PUT
    PATCH
    DELETE

错误格式：
    {
      "code": "PRODUCT_NOT_FOUND",
      "message": "Product does not exist",
      "traceId": "abc123",
      "details": {}
    }

* * *

50. Product API
    ===============
    
    GET    /api/v1/products
    GET    /api/v1/products/{id}
    POST   /api/v1/products
    PATCH  /api/v1/products/{id}
    GET    /api/v1/products/{id}/profit
    GET    /api/v1/products/{id}/metrics
    GET    /api/v1/products/{id}/recommendations
    POST   /api/v1/products/{id}/ai/generate-copy
    POST   /api/v1/products/{id}/ai/generate-seo
    POST   /api/v1/products/{id}/ai/generate-content

* * *

51. Analytics API
    =================
    
    GET /api/v1/analytics/dashboard
    GET /api/v1/analytics/sales
    GET /api/v1/analytics/products
    GET /api/v1/analytics/customers
    GET /api/v1/analytics/ads
    GET /api/v1/analytics/profit
    GET /api/v1/analytics/cac
    GET /api/v1/analytics/roas
    GET /api/v1/analytics/aov
    GET /api/v1/analytics/clv

* * *

52. Advertising API
    ===================
    
    GET  /api/v1/ads/campaigns
    GET  /api/v1/ads/campaigns/{id}
    GET  /api/v1/ads/campaigns/{id}/metrics
    POST /api/v1/ads/sync
    POST /api/v1/ads/recommendations
    POST /api/v1/ads/campaigns/{id}/pause
    POST /api/v1/ads/campaigns/{id}/resume
    POST /api/v1/ads/campaigns/{id}/budget

* * *

53. WhatsApp API
    ================
    
    GET  /api/v1/whatsapp/conversations
    GET  /api/v1/whatsapp/conversations/{id}
    POST /api/v1/whatsapp/messages
    POST /api/v1/whatsapp/conversations/{id}/handoff
    POST /api/v1/whatsapp/ai/reply
    POST /api/v1/whatsapp/templates/send

* * *

54. Order API
    =============
    
    GET   /api/v1/orders
    GET   /api/v1/orders/{id}
    POST  /api/v1/orders
    PATCH /api/v1/orders/{id}
    POST  /api/v1/orders/{id}/confirm
    POST  /api/v1/orders/{id}/cancel
    POST  /api/v1/orders/{id}/dispatch
    POST  /api/v1/orders/{id}/deliver

* * *

55. Webhook API
    ===============
    
    POST /api/v1/webhooks/woocommerce
    POST /api/v1/webhooks/paystack
    POST /api/v1/webhooks/meta/whatsapp

以后：
    POST /api/v1/webhooks/meta/ads
    POST /api/v1/webhooks/google/ads

* * *

56. WooCommerce Adapter
    =======================

WooCommerce：
    GET /wp-json/wc/v3/products
    GET /wp-json/wc/v3/orders
    GET /wp-json/wc/v3/customers

Kiano 统一成：
    ProductService
    OrderService
    CustomerService

不要让业务代码直接调用 WooCommerce API。

正确结构：
    Business Service
          ↓
    Commerce Port
          ↓
    WooCommerce Adapter

未来：
    Commerce Port
     ├── WooCommerceAdapter
     ├── ShopifyAdapter
     └── CustomStoreAdapter

* * *

57. Webhook 处理原则
    ================

所有 webhook：
    Receive
     ↓
    Authenticate
     ↓
    Validate
     ↓
    Check Idempotency
     ↓
    Persist Raw Event
     ↓
    Return 200
     ↓
    Async Processing

绝对不要在 webhook HTTP request 中执行长时间 AI 任务。

WooCommerce webhook 本身采用 HTTP POST，并支持 secret/HMAC-SHA256 验证；Paystack 也明确要求 webhook 签名验证，并建议快速返回 200 后再做长任务。

* * *

58. Event-driven Architecture
    =============================

建议定义统一 Event：
    PRODUCT_CREATED
    PRODUCT_UPDATED
    PRODUCT_LOW_STOCK

    CUSTOMER_CREATED
    CUSTOMER_UPDATED

    ORDER_CREATED
    ORDER_CONFIRMED
    ORDER_PAID
    ORDER_CANCELLED
    ORDER_DISPATCHED
    ORDER_DELIVERED

    PAYMENT_SUCCESS
    PAYMENT_FAILED

    WHATSAPP_MESSAGE_RECEIVED
    WHATSAPP_MESSAGE_SENT

    AD_METRIC_UPDATED
    CAMPAIGN_UPDATED

    REVIEW_CREATED

* * *

59. Event Flow 示例
    =================
    
    ORDER_DELIVERED
    
           │
           ├── CRM update
           │
           ├── Customer CLV update
           │
           ├── Review request
           │
           ├── Product sales metric
           │
           └── AI recommendation refresh

这会使系统后期扩展非常容易。

* * *

60. 推荐技术栈
    =========

结合你现有的开发体系，第一版推荐：
    Frontend
    Next.js
    TypeScript

    Backend
    Spring Boot 3
    Java 21

    ORM
    MyBatis-Plus

    Database
    PostgreSQL

    Vector
    pgvector

    Cache
    Redis

    Async Queue
    Redis Streams / RabbitMQ

    Scheduler
    Spring Scheduler / Quartz

    Object Storage
    S3 Compatible

    Reverse Proxy
    Nginx

    Container
    Docker

    Monitoring
    Prometheus
    Grafana

    Logs
    Loki / ELK

    AI Gateway
    统一 LLM Provider Adapter

这样不需要学习一套完全陌生的技术栈。

* * *

61. 为什么不建议一开始上微服务？
    ==================

MVP：
    Next.js
       │
    Spring Boot
       │
    PostgreSQL
    Redis
    Worker

就足够。

不要一开始：
    product-service
    order-service
    crm-service
    ai-service
    ads-service
    analytics-service
    ...

做 10 个微服务。

第一阶段应该：

> **Modular Monolith**

但代码模块必须清晰：
    product
    order
    customer
    marketing
    whatsapp
    payment
    analytics
    ai
    integration

以后真正有性能压力，再拆服务。

* * *

62. MVP 必须首先开发的 10 个功能
    ======================

这是整个项目最重要的部分。
MVP-01 — WooCommerce Product Sync
---------------------------------

自动同步：

> Product / SKU / Price / Stock / Category / Image

完成：
    WooCommerce
          ↓
    Kiano

* * *

MVP-02 — WooCommerce Order Sync
-------------------------------

同步：

> Orders / Customers / Order Items / Status

并建立：
    Order
    Customer
    Product

之间的关系。

* * *

MVP-03 — Cost + Profit Engine
-----------------------------

每个 SKU 输入：

> Product Cost  
> Delivery Cost  
> Payment Fee  
> COD Risk  
> Return Reserve

自动计算：

> Contribution Profit  
> Maximum CAC  
> Profit After Ads

这是整个系统的商业核心。

* * *

MVP-04 — Meta Ads Analytics
---------------------------

自动同步：

> Campaign  
> Ad Set  
> Ad  
> Spend  
> Click  
> Impression  
> CTR  
> CPC  
> Purchases  
> Conversion Value

并关联商品。

* * *

MVP-05 — Unified Dashboard
--------------------------

第一页直接显示：
    Today's Revenue
    Today's Orders
    Today's Ad Spend
    CAC
    AOV
    ROAS
    Contribution Profit

然后：

> Top Products

> Worst Products

> Best Ads

> Problems

* * *

MVP-06 — Tracking & Attribution
-------------------------------

记录：
    UTM
    fbclid
    gclid
    session
    visitor
    product view
    ATC
    checkout
    purchase

让系统第一次真正能够连接：

> Advertisement → Order

* * *

MVP-07 — WhatsApp CRM
---------------------

实现：

> Customer  
> Conversation  
> Message  
> Tag  
> Agent

以及：

> human/AI handoff

* * *

MVP-08 — WhatsApp AI Product Sales Agent
----------------------------------------

第一版不要做超级 Agent。

只做：
    Product Search
    Price
    Stock
    Product FAQ
    Delivery
    Order Creation
    Human Handoff

把 AI 的范围控制住。

* * *

MVP-09 — Paystack Payment Intelligence
--------------------------------------

实现：
    Initialize
    Verify
    Webhook
    Payment Status
    Reconciliation

支持：

> Card / Mobile Money

Paystack 当前 Ghana Mobile Money 支持 MTN、AirtelTigo、Telecel 等支付路径，最终支付状态通过 webhook 返回，因此这个模块可以直接纳入统一订单状态机。

* * *

MVP-10 — AI Daily Growth Advisor
--------------------------------

每天自动生成：

### Top Products

### Top Ads

### Bad Ads

### High CAC

### Low Conversion

### Low Stock

### Recommendations

例如：
    GOOD

    Morgan Blender
    CAC: GHS 31
    Max CAC: GHS 80

    ACTION
    Increase budget 20%

这样 MVP 已经开始具有“Growth OS”的味道。

* * *

63. MVP 暂时不要做的功能
    ================

第一版不要做：
    Dynamic Pricing
    Advanced ML Forecast
    Full Affiliate Platform
    Influencer Marketplace
    Automatic TikTok Ads
    Automatic Video Generation
    Full AI Campaign Creation
    Multi-country tax engine
    Warehouse ERP
    Courier fleet management
    Complex recommendation engine

这些都会让项目迅速失控。

* * *

64. MVP 产品界面
    ============

建议 Dashboard：
    ┌──────────────────────────────────────────────┐
    │ Kiano Growth OS                             │
    ├──────────────────────────────────────────────┤
    │ Revenue       Orders       Ad Spend         │
    │ GH₵4,260      18           GH₵920           │
    │                                              │
    │ CAC           AOV          Profit            │
    │ GH₵51         GH₵237       GH₵560            │
    ├──────────────────────────────────────────────┤
    │ TOP PRODUCTS                                 │
    │ 1. Blender                                   │
    │ 2. Rice Cooker                               │
    │ 3. Air Fryer                                 │
    ├──────────────────────────────────────────────┤
    │ ⚠ PROBLEMS                                   │
    │ Kettle CAC +38%                              │
    │ 3 products low stock                         │
    ├──────────────────────────────────────────────┤
    │ 🤖 AI RECOMMENDATIONS                        │
    │ Increase Blender budget +20%                 │
    │ Pause Kettle Creative #3                     │
    │ Launch WhatsApp retargeting                  │
    └──────────────────────────────────────────────┘

* * *

65. MVP 开发顺序
    ============

Sprint 1
--------

    Project Skeleton
    Authentication
    Tenant
    User
    Store
    Integration
    PostgreSQL
    Redis
    Docker

Sprint 2
--------

    WooCommerce
    Product
    Customer
    Order
    Webhook

Sprint 3
--------

    Cost
    Profit
    SKU Analytics
    Dashboard

Sprint 4
--------

    Meta
    Ads
    Campaign
    Ad
    Metrics

Sprint 5
--------

    Tracking
    Attribution
    UTM
    Session

Sprint 6
--------

    WhatsApp
    Conversation
    Message
    CRM

Sprint 7
--------

    Paystack
    Payment
    Webhook
    Reconciliation

Sprint 8
--------

    AI Product Agent
    AI Sales Agent
    AI Growth Advisor

* * *

66. 三个月后应该达到的状态
    ===============
    
    KianosMart
    
         │
         ▼
    
    Kiano Growth OS
    
         │
         ├── WooCommerce connected
         ├── Meta connected
         ├── Google connected
         ├── WhatsApp connected
         └── Paystack connected
              │
              ▼
         Unified Data
              │
              ▼
         AI Analysis
              │
              ▼
         Business Recommendation

你每天不需要打开：

> Facebook Ads Manager  
> Google Ads  
> WooCommerce  
> Paystack  
> WhatsApp

分别查看。

而是在一个地方看到：

> **昨天赚了多少钱，以及今天应该做什么。**

* * *

67. 第二阶段：Growth Automation
    ==========================

MVP 跑稳定以后增加：

### Product

* AI SEO

* AI copy

* Product scoring

* Competitor pricing

### Marketing

* Creative testing

* Budget recommendation

* Campaign anomaly detection

### Sales

* AI WhatsApp selling

* abandoned cart recovery

* follow-up

### CRM

* segmentation

* customer lifecycle

* CLV

* * *

68. 第三阶段：自动化决策
    ==============

开始出现：
    Rule Engine
    +
    AI Recommendation
    +
    Historical Data

例如：
    IF
    CAC < Target CAC × 0.7
    AND
    Stock > 20
    AND
    Conversion > benchmark

    THEN

    Recommend budget +20%

AI 负责理解上下文。

Rule Engine 负责控制风险。

* * *

69. 第四阶段：SaaS 化
    ===============

等 KianosMart 有足够数据后，再抽象成：
    Tenant A → KianosMart
    Tenant B → Ghana Fashion Store
    Tenant C → Electronics Store
    Tenant D → Beauty Store

用户连接自己的：
    WooCommerce
    Shopify
    Meta
    Google
    WhatsApp
    Paystack

然后订阅：
    Starter
    Growth
    Pro
    Enterprise

* * *

70. 多租户 SaaS 的核心架构
    ==================
    
                        SaaS Control Plane
                               │
                 ┌─────────────┼─────────────┐
                 ▼             ▼             ▼
              Tenant A      Tenant B      Tenant C
                 │             │             │
                 └─────────────┼─────────────┘
                               ▼
                        Shared AI Engine
                               │
                    Shared Integration Layer

所有核心表：
    tenant_id

必须从第一天就设计。

* * *

71. 最值得建立的 5 个“护城河”
    ===================

① Profit Model
--------------

普通广告软件告诉你：

> ROAS 5.2

你的软件告诉你：

> ROAS 5.2，但实际上亏损。

* * *

② Ghana Commerce Data
---------------------

逐步积累：

> Ghana customer  
> Ghana COD  
> Ghana delivery  
> Ghana WhatsApp  
> Ghana product  
> Ghana ad

形成自己的本地数据资产。

* * *

③ Product-to-Ad Attribution
---------------------------

知道：

> 哪个 SKU  
> 哪个 Campaign  
> 哪个 Creative  
> 最终产生利润。

* * *

④ WhatsApp Sales Intelligence
-----------------------------

知道：

> 客户问什么  
> 为什么不买  
> 哪种回答最容易成交。

* * *

⑤ AI Decision Engine
--------------------

最终不是：

> AI Chatbot

而是：

> **AI Business Operator**

* * *

72. 最大的技术风险
    ===========

风险 1：第三方 API 变化
---------------

Meta、Google 都会进行 API/version 迭代。

解决：
    Integration Adapter

所有版本号配置化，不要把外部 API 直接散落在业务代码里。

* * *

风险 2：Webhook 重复
---------------

解决：
    provider_event_id
    UNIQUE

所有 webhook 幂等。

* * *

风险 3：AI 幻觉
----------

解决：

> AI 不拥有事实。

事实来自：
    Product DB
    Order DB
    Policy DB
    Inventory DB

AI 只负责：

> 理解 + 推理 + 生成。

* * *

风险 4：AI 误操作
-----------

使用：
    Tool
    ↓
    Permission
    ↓
    Risk Level
    ↓
    Approval
    ↓
    Execute

* * *

风险 5：数据量增长
----------

MVP：

> PostgreSQL

当每天产生数百万事件后：
    PostgreSQL
         +
    ClickHouse

再考虑数据仓库。

* * *

73. 安全设计
    ========

至少实现：
    HTTPS
    JWT
    RBAC
    API Key Encryption
    Secret Encryption
    Webhook Signature Verification
    Rate Limit
    Audit Log
    Idempotency Key
    IP restrictions

尤其是：

> Meta Token  
> Google OAuth Token  
> Paystack Secret Key  
> WhatsApp credentials

绝不能明文存数据库。

* * *

74. Audit Log
    =============

所有 AI/人工操作必须记录：
    who
    what
    when
    before
    after
    reason
    source

例如：
    2026-10-07 09:12

    AI Agent:
    Marketing Agent

    Action:
    Recommend budget increase

    Target:
    Morgan Blender Campaign

    Old Budget:
    GHS 70/day

    Recommended:
    GHS 84/day

    Approved By:
    Admin

    Status:
    Executed

这对后期 AI 自动操作尤其重要。

* * *

75. 最终产品的核心数据流
    ==============

整个 Kiano Growth OS 最终应该形成：
              PRODUCT
                 │
                 ▼
              CONTENT
                 │
                 ▼
              ADVERTISING
                 │
                 ▼
               TRAFFIC
                 │
           ┌─────┴─────┐
           ▼           ▼
        WEBSITE     WHATSAPP
           │           │
           └─────┬─────┘
                 ▼
               ORDER
                 │
           ┌─────┴─────┐
           ▼           ▼
         COD        PAYSTACK
           │           │
           └─────┬─────┘
                 ▼
              DELIVERY
                 │
                 ▼
               REVIEW
                 │
                 ▼
                CRM
                 │
                 ▼
              RETARGET
                 │
                 ▼
            REPEAT ORDER
                 │
                 ▼
               PROFIT
                 │
                 ▼
            AI DECISION
                 │
                 └──────────────► PRODUCT / AD / SALES

这就是 Kiano Growth OS 的真正闭环。

* * *

76. 最终建议：MVP 的真正边界
    ==================

第一版做到下面这一句话就算成功：

> **“我每天打开 Kiano Growth OS，就能知道哪个商品赚钱、哪个广告赚钱、哪个客户值得跟进，以及今天最值得做什么。”**

而不是追求：

> “AI 能够自动生成所有东西。”

* * *

77. MVP 验收标准
    ============

MVP 上线时，至少满足：

### 商品

> 150+ Morgan SKU 自动同步

### 订单

> WooCommerce Order 自动进入系统

### 支付

> Paystack payment status 自动同步

### 广告

> Meta Campaign/Ad metrics 自动同步

### 数据

> Website → Customer → Order → Revenue → Profit 可关联

### WhatsApp

> 客户消息进入统一 CRM

### AI

> 可以查询真实 Product / Price / Stock

### Dashboard

> 可以看到 Revenue / CAC / ROAS / Profit

### Recommendation

> AI 每日生成增长建议

如果这 9 件事情做到了，就已经不是“一个后台管理系统”，而是真正意义上的：

> **Kiano Growth OS MVP**

* * *

78. 我建议最终采用的产品路线
    ================
    
                        KIANOSMART
                            │
                            ▼
                    KIANO GROWTH OS
                            │
              ┌─────────────┼─────────────┐
              │             │             │
           PRODUCT       MARKETING       SALES
              │             │             │
              └─────────────┼─────────────┘
                            ▼
                     UNIFIED DATA
                            │
                            ▼
                       AI ENGINE
                            │
                            ▼
                  DECISION ENGINE
                            │
                            ▼
                 GROWTH AUTOMATION
                            │
                            ▼
                      SaaS PLATFORM

**第一阶段的正确目标不是“打造一个很大的系统”，而是用 KianosMart 自己的真实订单和广告数据证明：Kiano Growth OS 能够让 CAC 降低、转化率提高、复购率提高和利润提高。**

一旦这个闭环跑通，再把它产品化为 SaaS，商业价值会比单纯经营一个 Morgan 独立站高得多。


