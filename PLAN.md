# 修复 GenWebTicket -111：已登录态下 csrf 传 bili_jct

## Context

**真机日志实锤根因**（2026-09-11 抓包）：App 处于已登录态（Cookie 带 `SESSDATA` / `bili_jct=5cf1bb2b…` / `DedeUserID`），但 `GenWebTicket` 请求的查询参数是 `csrf=`（空）→ B 站网关 CSRF 中间件判定 **-111（csrf 校验失败）**。

规则：bapis 网关要求 `csrf` 参数 == `bili_jct` cookie；未登录时两者皆空可放行，**已登录时 csrf 为空必 -111**。

时间线完全吻合历史现象：
- 登录前（csrf 空 + 无 bili_jct cookie）→ 正常，包括早期所有验证；
- 扫码登录后 → GenWebTicket 稳定 -111（含 05-功能优化篇 §13.12「同代码几小时前正常」，即登录测试引入之后）；
- spi_v2 与 GenWebTicket 同客户端同网络，前者成功后者 -111 —— 因为 spi_v2 无 csrf 校验。

**上一轮 buvid3 注入修复依然正确且已生效**（本次日志可见 `buvid3/buvid4/b_nut` 已随请求发出，cookie 形态与真实浏览器对齐），予以保留，但不是 -111 的直接根因。

项目内既有约定：所有其他 csrf 消费方均传 `bili_jct`（`CookieRefresher.kt:60/101`、`WriteRequest.kt:43`、`BiliApi.cookieInfo`），唯独 `AuthGate` 硬编码 `csrf = ""`——单点遗漏。

## Approach

给 `AuthGate` 注入会话提供者，`fetchCredentials()` 调 `genWebTicket` 时传 `sessionProvider().biliJct`：

- 已登录 → csrf = 当前 bili_jct，与 CookieJar 自动携带的 `bili_jct` cookie 一致 → 通过校验；
- 未登录 → 空串，行为与现状完全一致；
- jct 轮转（`CookieRefresher` 更新 `sessionStore.current`）后下次种植自动取新值，无需额外处理。

装配模式复用 `BiliCookieJar` 的既有写法：`sessionProvider: () -> BiliSession` lambda 注入，构造顺序无问题（`BiliNetwork` 中 `sessionStore` 先于 `authGate` 创建）。

## Files to modify

| 文件 | 改动 |
|---|---|
| `core/network/src/main/java/com/biliseek/core/network/AuthGate.kt` | 构造函数加 `sessionProvider: () -> BiliSession = { BiliSession.EMPTY }`；`fetchCredentials()` 中 `genWebTicket` 的 csrf 实参改为 `sessionProvider().biliJct`，并补注释说明 -111 约定 |
| `core/network/src/main/java/com/biliseek/core/network/BiliNetwork.kt` | `AuthGate(...)` 构造处传 `{ sessionStore.current }` |

`BiliApi.genWebTicket` 的 `csrf: String = ""` 默认值保留不动（接口签名不变，只是不再依赖默认值）。

## Reuse

- `BiliSession.biliJct` / `BiliSession.EMPTY`（`SessionStore.kt:9-20`）
- `SessionStore.current` 同步读取（`SessionStore.kt:36`）
- 同款 provider 注入模式：`BiliCookieJar`（`BiliNetwork.kt:41`、`Interceptors.kt:37`）
- csrf = bili_jct 约定先例：`CookieRefresher.kt:60`、`internal/WriteRequest.kt:43`

## Steps

- [ ] `AuthGate` 构造函数加 `sessionProvider: () -> BiliSession = { BiliSession.EMPTY }`（默认空 → 未接线处行为不变，便于测试）
- [ ] `fetchCredentials()`：`api.genWebTicket(..., csrf = sessionProvider().biliJct)`，注释标明「bapis 网关 CSRF 校验：csrf 参数必须等于 bili_jct cookie，已登录时空串必 -111（真机实证 2026-09-11）」
- [ ] `BiliNetwork`：`AuthGate(context.applicationContext, api) { sessionStore.current }`
- [ ] `./gradlew :core:network:testDebugUnitTest` + `:app:assembleDebug` 通过
- [ ] 真机覆盖安装，冷启动验证（见下）

## Verification

1. **真机 logcat**（`adb logcat -s BiliSeekAuth BiliSeekHttp`）：
   - GenWebTicket 请求 URL 中 `csrf=5cf1bb2b…`（与 Cookie 头 `bili_jct` 一致）；
   - 响应 `code=0` 且带 `ticket` + `nav`；
   - `BiliSeekAuth: 风控凭据种植完成`。
2. **功能恢复**：搜索、稍后再看、收藏夹等依赖风控凭据的功能可用（真人操作）。
3. **回归**：退出登录后冷启动，GenWebTicket 仍 `csrf=` 空且成功（未登录路径不变）。
4. 单元测试：`core:network` 既有 159 个测试保持通过（本改动点在 AuthGate，无 JVM 单测覆盖——依赖 Context/DataStore，按 AGENTS.md 网络行为由真人验证）。
