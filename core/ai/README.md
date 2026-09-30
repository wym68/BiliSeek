# :core:ai

AI 总结能力模块：把「带时间戳的原文」交给用户配置的大模型，产出 Markdown 总结。设计与任务拆分见
[`docs/10-AI总结-实现工单.md`](../../docs/10-AI总结-实现工单.md)。

## 边界

- **不认识 B 站**：不依赖 `:core:network` / `:core:player`。原文从 `TranscriptSource` 进来（字幕、本地识别都由 `:app` 接线）。
- **不依赖 Android API**：源码只用 JVM 库（OkHttp、kotlinx.serialization、coroutines），纯函数与 HTTP 客户端都能在 JVM 单测里跑。Keystore、通知、前台服务都在 `:app`。
- **自己的网络客户端**：调用方传入的 `OkHttpClient` 必须从空白 builder 构造——无 CookieJar、无 B 站拦截器。绝不把 B 站 Cookie 带给第三方 AI 主机（工单 §3.6）。
- **测试不联网**：所有 AI 请求在测试里只打 `MockWebServer`（`AGENTS.md`）。

## 公开 API

见工单 §3.5。其余类型一律 `internal`；新增公开类型视为 API 变更。

## 新增一家 AI 服务

- **已支持的协议**（目前只有 OpenAI 兼容 `/chat/completions`）：在 `ProviderCatalog` 加一条 `ProviderPreset`。
  - `id` 稳定不变（落库用）；`baseUrl` 以官方文档为准。
  - 有已知怪癖就填 `chatCapabilities`（思考内容格式、是否接受 temperature、请求体上限、额外参数）。
  - 厂商用 HTTP 200 + 业务错误码表达失败的，填 `errorCodeMap`，并注明码值来源与是否真机核对过。
- **新协议**（Anthropic Messages、Gemini 等）：
  1. `ChatProtocol` 加枚举值；
  2. 实现 `ChatClient`（非流式 + 流式）；
  3. `ProviderFactory` 加一行分派；
  4. 加预设；
  5. 用 MockWebServer 写适配器测试：请求形状、流式解析、错误映射到 `AiError`。

上层 `Summarizer`、`:app` 的界面与存储都不需要改。
