# 理伴 Android

理伴是一个 Android 消费决策助手。用户在购物页面点击悬浮球后，应用通过 MediaProjection 在内存中取得画面，使用随 APK 内置的 ML Kit 中文 OCR 识别商品和价格，再结合预算、储蓄目标、消费历史与促销信号先给出本地结论。价格证据随后原位更新，不阻塞反馈。

## 隐私和数据流

- 原始截图、Bitmap 和 OCR 全文不写入文件、Room 或日志，也绝不发送到网络。
- 网络请求只包含商品名称、型号、页面价格和最多三条必要的搜索摘要。
- 搜索证据达到三个独立来源时直接生成参考区间，不调用 LLM。
- 搜索不足或未配置时才调用 OpenAI-compatible 文本模型估价，并明确标识为“搜索辅助估价”或“模型知识估价”。
- 模型估价只用于展示，不能降低本地风险等级，也不能单独触发“建议购买”。
- API Key 使用 Android Keystore 生成的 AES-GCM 密钥加密，配置只保存在当前设备。

## 构建

- Android Studio（支持 AGP 9.4）
- Gradle Wrapper 9.6.0
- Android SDK Platform 36.1 / Build Tools 36
- JDK 17 或 Android Studio Embedded JDK
- `minSdk 35`、`targetSdk 36`

复制 `local.properties.example` 为 `local.properties`，只填写 SDK 路径。服务地址、模型名和密钥均在应用“设置”页填写，不再进入 BuildConfig。

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

LLM 支持 OpenAI-compatible Chat Completions 文本接口，Endpoint 应填写完整的 `/v1/chat/completions` 地址。模型固定返回 `KNOWN/UNKNOWN`、人民币分区间、置信度和简短依据。

智谱搜索默认配置：

- Base URL：`https://open.bigmodel.cn/api`
- 搜索：`POST /paas/v4/web_search`
- 阅读：`POST /paas/v4/reader`
- 引擎：`search_pro`
- 10 条结果、`content_size=high`

接口字段以 [智谱联网搜索.md](智谱联网搜索.md) 和 [智谱网页阅读.md](智谱网页阅读.md) 为准。

## 超时与来源

- 本地 OCR 与初步决策目标 P50 小于 1 秒。
- 搜索硬超时 3 秒，网页阅读总预算 1.2 秒。
- 整个价格增强链路最迟 5 秒结束。
- 搜索缓存 2 小时；模型估价缓存 24 小时。
- 价格来源为 `SEARCH_VERIFIED`、`SEARCH_ASSISTED_ESTIMATE`、`MODEL_ESTIMATE` 或 `UNAVAILABLE`。

历史数据库从 v1 显式迁移到 v2，旧预算、目标、交易和决策均保留；旧决策在界面标记为“旧版记录”。
