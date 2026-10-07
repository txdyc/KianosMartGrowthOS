import type { MessageKey } from "./en";

/**
 * Chinese dictionary. Must define a value for every key of the English
 * dictionary — a missing key is a compile error here.
 */
export const zh: Record<MessageKey, string> = {
  // navigation
  "nav.products": "商品",
  "nav.import": "导入",
  "nav.reshoot": "补拍清单",
  "nav.settings": "设置",
  "nav.logout": "退出登录",

  // login
  "login.title": "登录",
  "login.email": "邮箱",
  "login.password": "密码",
  "login.submit": "登录",

  // ShotState
  "state.OK": "合格",
  "state.RESHOOT": "需补拍",
  "state.MISSING": "未拍摄",

  // ContentTier
  "tier.HERO": "主推（HERO）",
  "tier.STANDARD": "标准（STANDARD）",
  "tier.all": "全部",

  // TaskStatus
  "task.QUEUED": "排队中",
  "task.RUNNING": "进行中",
  "task.SUCCEEDED": "已完成",
  "task.FAILED": "失败",
  "stock.instock": "有货",
  "stock.outofstock": "缺货",
  "stock.onbackorder": "可预订",

  // ImportResult.outcome
  "outcome.IMPORTED": "已导入",
  "outcome.DUPLICATE": "重复文件",

  // QcReason
  "qc.LOW_RESOLUTION": "分辨率不足",
  "qc.BLURRY": "画面模糊",
  "qc.OVEREXPOSED": "过曝",
  "qc.UNDEREXPOSED": "欠曝",
  "qc.NOT_PORTRAIT": "不是竖构图",
  "qc.FPS_OUT_OF_RANGE": "帧率超出范围",
  "qc.DURATION_OUT_OF_RANGE": "时长超出范围",

  // JobStep
  "step.CUTOUT": "抠图",
  "step.SCENE_INPUT": "场景输入",
  "step.WHITE_MAIN": "主图白底",
  "step.WHITE_ANGLE": "角度图白底",
  "step.INBOX": "开箱图",
  "step.SCENE": "场景图",

  // JobStatus
  "job.QUEUED": "排队中",
  "job.LEASED": "执行中",
  "job.WAITING_EXECUTOR": "等待执行器",
  "job.SUCCEEDED": "已完成",
  "job.FAILED": "失败",
  "job.CANCELLED": "已取消",

  // RunStatus
  "run.RUNNING": "进行中",
  "run.DONE": "已完成",
  "run.PARTIAL": "部分完成",

  // PrecheckFlag
  "precheck.PRODUCT_MISMATCH": "产品与实拍图不一致",
  "precheck.AI_TEXT": "疑似 AI 文字",
  "precheck.EDGE_NOT_WHITE": "边缘非纯白",
  "precheck.OCCUPANCY_OUT_OF_RANGE": "产品占比超出范围",

  // RejectReason
  "reject.PRODUCT_MISMATCH": "产品不符",
  "reject.AI_ARTIFACT": "AI 伪影",
  "reject.WRONG_FACT": "事实错误",
  "reject.TEXT_ERROR": "文字错误",
  "reject.STYLE": "风格问题",
  "reject.LOW_QUALITY": "质量不佳",
  "reject.POLICY": "政策违规",

  // image pipeline panel
  "pipeline.generate": "生成商品图",
  "pipeline.starting": "启动中…",
  "pipeline.workerOnline": "Worker 在线",
  "pipeline.workerOffline": "Worker 离线",
  "pipeline.lastSeen": "最后在线 {time}",
  "pipeline.comfyAvailable": "ComfyUI 可用",
  "pipeline.comfyUnavailable": "ComfyUI 不可用",
  "pipeline.confirmTitle": "镜头还不完整",
  "pipeline.confirmBody": "部分必拍镜头缺失或需补拍。仍要启动图片流水线吗？",
  "pipeline.confirmGo": "仍然生成",
  "pipeline.confirmCancel": "取消",
  "pipeline.latestRun": "最近一次运行",
  "pipeline.noRun": "还没有运行过流水线。",
  "pipeline.retry": "重试",
  "pipeline.attempts": "尝试 {n}/{m}",
  "pipeline.reviewLink": "去审核（{n} 张待审）",

  // import
  "import.summary": "已导入 {n} 个文件",
  "import.pickFolder": "选择文件夹",
  "import.pickFiles": "选择文件",
  "import.willImport": "将导入 {n} 个，跳过 {m} 个",
  "import.start": "开始导入",
  "import.importing": "导入中…",
  "import.accepted": "合格",
  "import.reshoot": "需补拍",
  "import.duplicates": "重复文件",
  "import.failed": "失败",
  "col.file": "文件",
  "col.shot": "镜头",
  "col.result": "结果",
  "col.status": "状态",
  "status.ACCEPTED": "合格",
  "status.RESHOOT": "需补拍",
  "col.state": "状态",
  "col.guidance": "拍摄指引",

  // products list
  "products.searchPlaceholder": "搜索 SKU 或名称",
  "sync.now": "立即同步",
  "sync.last": "上次同步",
  "sync.never": "尚未同步",
  "col.image": "图片",
  "col.sku": "SKU",
  "col.name": "名称",
  "col.price": "价格",
  "col.stock": "库存",
  "col.tier": "等级",
  "col.shots": "镜头",

  // product detail
  "detail.back": "返回商品列表",
  "detail.complete": "已完成",
  "detail.incomplete": "未完成",

  // reshoot list
  "reshoot.downloadCsv": "下载 CSV",
  "reshoot.csvNote": "CSV 中同时包含中英文指引。",
  "reshoot.empty": "没有需要补拍的镜头。",

  // integration settings
  "settings.heading": "WooCommerce 集成",
  "settings.baseUrl": "Base URL",
  "settings.username": "用户名",
  "settings.appPassword": "Application Password",
  "settings.passwordKeep": "留空表示保持已保存的密码。",
  "settings.save": "保存",
  "settings.saved": "已保存。",
  "settings.test": "测试连接",
  "settings.testing": "测试中…",
  "settings.testOk": "连接成功。",
  "settings.testFailed": "连接失败。",
  "settings.guide":
    "在 WordPress 中创建：用户 → Shop Manager 用户 → Application Passwords → Add New。站点使用 http 时，需在 wp-config.php 中设置 WP_ENVIRONMENT_TYPE=local，否则 WordPress 会拒绝 REST API 认证。如果 Kiano 运行在 Docker 中、店铺在同一台电脑上，Base URL 请填 http://host.docker.internal:8080——在容器里，localhost 指的是容器自己。",

  // generic
  "loading": "加载中…",

  // error codes
  "error.UNAUTHENTICATED": "请先登录。",
  "error.FORBIDDEN": "没有权限执行此操作。",
  "error.NOT_FOUND": "内容不存在。",
  "error.VALIDATION_FAILED": "请求参数有误。",
  "error.INTERNAL_ERROR": "出错了，请重试。",
  "error.AUTH_INVALID_CREDENTIALS": "邮箱或密码不正确。",
  "error.WOO_NOT_CONFIGURED": "请先在 设置 → 集成 中连接 WooCommerce。",
  "error.WOO_AUTH_FAILED":
    "WooCommerce 拒绝了凭据。请检查 Application Password（为 Shop Manager 用户创建）；如果站点是 http，请在 wp-config.php 中设置 WP_ENVIRONMENT_TYPE=local。",
  "error.WOO_BLOCKED": "请求被站点防火墙（WAF）拦截。请将服务器 IP 加入白名单，或为 REST API 放行该规则。",
  "error.WOO_UNAVAILABLE": "无法连接 WooCommerce 站点。请检查 Base URL 和站点是否在线。",
  "error.NOT_TOP_LEVEL_PRODUCT": "仅适用于顶层商品。",
  "error.INVALID_FILE_NAME": "文件名需要形如 SKU_P1.jpg（镜头编号 P1–P9、PROMO 或 V1–V3）。",
  "error.UNSUPPORTED_FILE_TYPE": "不支持的文件类型。照片须为 JPG，视频须为 MP4 或 MOV。",
  "error.UNSUPPORTED_FILE_TYPE.heic":
    "不支持 HEIC 格式。请导出为 JPEG（iPhone：设置 › 相机 › 格式 › 兼容性最佳）。",
  "error.UNKNOWN_SKU": "没有这个 SKU 的商品。请先同步商品，或修改文件名。",
  "error.UNREADABLE_MEDIA": "文件无法读取，可能已损坏或不是真实的照片/视频。",
  "error.PIPELINE_RUNNING": "该商品已有一个流水线正在运行。",
  "error.SHOTS_NOT_READY": "请先导入全部必拍镜头（ACCEPTED）再生成图片。",
  "error.WORKFLOW_NOT_ACTIVE": "没有已激活的工作流版本，请让管理员注册并激活。",
  "error.JOB_NOT_FAILED": "只有失败的任务才能重试。",
  "error.ASSET_NOT_IN_REVIEW": "该资产已经审核过了。",
  "error.LEASE_LOST": "任务租约已丢失，worker 已停止该任务。",
  "error.OUTPUT_MISSING": "worker 没有上传预期的输出文件。",
  "error.WORKFLOW_INVALID": "工作流 JSON 不符合契约。",
};
