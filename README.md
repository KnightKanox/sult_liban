# 理伴 Android

理伴是一个 Android 消费决策助手。点击悬浮球后，应用在内存中截屏，使用内置 ML Kit 中文 OCR 识别全屏文字，默认再由文本 LLM 提取主商品名称、型号和价格。本地核对提取字段与 OCR 证据后，结合预算、目标和历史给出消费提醒；价格搜索随后原位更新。

## 隐私和数据流

- 原始截图和 Bitmap 不上传、不保存；OCR 全文不写入文件、Room 或日志。
- 默认开启“使用 LLM 提取商品信息”：全屏 OCR 文字、行号和位置会发送给已配置的 LLM；可在设置关闭。请避开包含聊天、地址、账户等信息的页面。
- OCR 按文字框垂直重叠归行，行内从左到右、整行从上到下排列；保留原始行号和坐标，一次交给 LLM。
- 结构化字段为原始标题、简洁商品名、品牌、具体品类、大类、可选属性及价格条件。名称允许重组有原文依据的词组，品牌不能单独充当商品名。
- 属性使用标准 key 与中文 label，按商品类型提取；特殊属性可扩展。区分商品固有属性、已选规格、仅出现的可选项和不明确属性。不要求所有商品都有材质，不猜测未选规格。
- 名称、属性和金额须经原文证据校验；低置信度仍直接分析。LLM 校验失败、超时或返回不明确时自动使用本地完整标题结果，仅缺少有效商品名或价格时要求补充信息。
- 独立估价只接收商品信息和最多三条搜索摘要，不提供页面价格，避免把页面价格当作市场参考。
- 型号及规格可核对、搜索证据达到三个独立主域名时直接生成参考区间，不再调用估价 LLM。没有唯一型号的商品仅标注同类参考，不能升级成已验证同款报价。
- 搜索不足或未配置时才调用 OpenAI-compatible 文本模型估价，并明确标识为“搜索辅助估价”或“模型知识估价”。
- 模型估价只用于展示，不能降低本地风险等级，也不能单独触发“建议购买”。
- 预置配置首次运行后使用 Android Keystore AES-GCM 加密保存。内置密钥可从 APK 提取，含预置密钥的 APK 仅用于受控联调。

## 构建

- Android Studio（支持 AGP 9.4）
- Gradle Wrapper 9.6.0
- Android SDK Platform 36.1 / Build Tools 36
- JDK 17 或 Android Studio Embedded JDK
- `minSdk 35`、`targetSdk 36`

复制 `local.properties.example` 为 `local.properties`，只填写 SDK 路径。复制 `api-presets.example.properties` 为 `api-presets.local.properties` 可配置 APK 预置服务；后者已被 Git 忽略，真实密钥不要提交。构建时通过 BuildConfig 注入，首次运行加密导入，不覆盖已有服务配置。清除后不会自动恢复；可在设置中点击“恢复预置配置”。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

当前工作区路径含中文，必要时可运行：

```powershell
.\build-competition.ps1
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 客户端接口

LLM 使用 OpenAI-compatible Chat Completions 文本接口。预置 Endpoint 为 `https://api.deepseek.com/v1/chat/completions`，模型为 `deepseek-v4-flash`。对 DeepSeek V4 显式关闭思考模式；结构化 OCR 提取上限 1800 tokens，独立估价上限 800 tokens。两者使用不同提示词与输出协议，禁止图片内容段。

智谱搜索默认配置：

- Base URL：`https://open.bigmodel.cn/api`
- 搜索：`POST /paas/v4/web_search`
- 阅读：`POST /paas/v4/reader`
- 预置引擎：`search_pro_quark`；价格来源不足时尝试 `search_pro_sogou`。其他首选引擎不足时先尝试夸克。
- 10 条结果、`content_size=high`
- 查询优先使用型号、品牌、具体品类及确定属性，未选/不明确规格和营销词不进入查询；无型号且结果不足时第二引擎可扩大到同类搜索。
- 保留无链接摘要用于诊断和辅助估价；缺链接、错误型号、旧促销不能作为搜索验证价格。多个子域名只计一个独立来源。

接口字段以 [智谱联网搜索.md](智谱联网搜索.md) 和 [智谱网页阅读.md](智谱网页阅读.md) 为准。

## 超时与来源

- 纯本地模式目标 P50 小于 1 秒；开启 LLM 提取会增加一次网络请求，提取阶段上限 5 秒，不再承诺 1 秒初判。
- 搜索硬超时 3 秒，网页阅读总预算 1.2 秒。
- 整个价格增强链路最迟 5 秒结束。
- 搜索缓存 2 小时；模型估价缓存 24 小时。
- 价格来源为 `SEARCH_VERIFIED`、`SEARCH_ASSISTED_ESTIMATE`、`MODEL_ESTIMATE` 或 `UNAVAILABLE`。

本轮采用新的商品提取协议，不新增旧字段兼容或数据迁移。价格缓存按完整商品属性、金额、价格类型及规格绑定隔离，不清除已有设备数据。

可选真实服务测试：在运行 JVM 测试的进程内设置 `LIBAN_LIVE_LLM_KEY` 和 `LIBAN_LIVE_SEARCH_KEY`。测试仅发送构造的 OCR 商品文字，未设置变量时跳过，不读取或输出真实用户页面。
