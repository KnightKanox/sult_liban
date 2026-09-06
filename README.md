# 理伴 Android 比赛版

理伴是一个 Android 消费决策助手：用户在购物、外卖或网页页面点击悬浮球，应用通过 MediaProjection 取得当前画面，调用多模态模型识别商品，并综合预算、储蓄目标、购买历史、冲动信号和参考价格返回建议。

## 工程要求

- Android Studio（支持 AGP 9.4）
- Gradle 9.6（工程 Wrapper 已固定为 9.6.0）
- Android SDK Platform 36.1 / Build Tools 36
- JDK 17 或兼容的 Android Studio Embedded JDK
- Android 15/16 真机；API 36 模拟器用于页面和数据库回归

复制 `local.properties.example` 为 `local.properties`，填写本机 SDK 路径。外部服务未配置时应用仍可运行三套 Demo，但结果会显示“演示回退数据”。

```properties
LIBAN_LLM_BASE_URL=https://provider.example/v1/chat/completions
LIBAN_LLM_API_KEY=short-lived-key
LIBAN_LLM_MODEL=multimodal-model
LIBAN_PRICE_BASE_URL=https://provider.example/price/search
LIBAN_PRICE_API_KEY=short-lived-key
```

LLM 接口采用 OpenAI-compatible Chat Completions 结构并要求返回 JSON。物价接口接受 `q` 和 `page_price_cents` 查询参数，返回：

```json
{
  "availability": "AVAILABLE",
  "reference_low_cents": 249900,
  "reference_high_cents": 269900,
  "page_price_cents": 289900,
  "premium_ratio": 0.074,
  "source": "LIVE"
}
```

## 构建与测试

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

当前工作区路径含中文。部分 Windows JDK 会在 JVM 单测子进程中错误编码这类路径；仓库提供了一键构建脚本，通过临时 `R:` 映射规避该问题，并在结束后自动释放盘符：

```powershell
.\build-competition.ps1
```

Wrapper 使用腾讯 Gradle 镜像以匹配本机已部署缓存；如需切回官方源，将 `gradle/wrapper/gradle-wrapper.properties` 中域名改为 `services.gradle.org` 即可。

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。密钥会进入 APK，仅可使用比赛专用、短期、限额密钥，赛后立即轮换。

## 隐私与系统行为

- 屏幕截图只在内存中压缩和上传，不写入磁盘、数据库或日志。
- Android 14+ 的 MediaProjection 授权仅用于当前持续识屏会话；锁屏、系统停止或新投屏会话开始后必须重新授权。
- 悬浮窗、通知和投屏均由用户显式开启，常驻通知可随时停止服务。
- 受保护页面或近全黑截图会转入手动商品信息输入。

完整演示步骤见 [DEMO_RUNBOOK.md](DEMO_RUNBOOK.md)。原始产品说明保留在 `理伴_Android比赛版实现计划_V1.1.md`，规范架构图为 `理伴_系统架构图.png`。
