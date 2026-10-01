# 补 Room schema 6/7 + 接上 -101 刷新重试

日期：2026-10-01
范围：`app/schemas/`（只加文件）；`:core:network` 的 `CookieRefresher` / `BiliRepository` / `LoginManager` / `BiliNetwork` 与 `internal/` 下的策略、编排；相关文档与过时注释。`:app` 只改一处注释。

## 背景

《功能优化篇》§10.0 欠账表里两条「可以直接排期」的代码缺口：

1. **Room schema `6.json` / `7.json` 未提交**。DB 已是 v7，`app/schemas/` 只有 1–5，`MigrationTest` 里 5→6、6→7 两条用例因缺 schema 跑不起来。
2. **`-101` 自动续期重试未接线**。`resolveRetryAction(-101) = REFRESH_AND_RETRY` 没有调用方：读请求 `-101` 落成普通失败（`code=-101`），写请求抛 `WriteError.NeedLogin`，不会自动刷新 Cookie 再重试。

## 一、Schema 补齐

v6 与 v7 是同一个 squash 提交（`9fe2b64`，PR #8）一起引入的，版本号从 5 直接跳到 7——**代码从来没有处在过 v6 状态**，不能直接从现有代码生成 6.json。
v6 与 v7 的唯一区别是 `ai_summary` 上 `index_ai_summary_bvid_cid` 是否 unique（见 `MIGRATION_6_7`：表结构与数据都不动，只把唯一索引换成普通索引）。

做法：

1. 临时把 `AiSummaryEntity` 的索引改成 `Index(value = ["bvid", "cid"], unique = true)`、`version = 6`，跑 KSP（`:app:kspDebugKotlin`）产出 `6.json`。
2. 还原这两处，再跑一次 KSP 产出 `7.json`。
3. 核对：
   - `1.json`–`5.json` 无改动（`git status` 只多出两个新文件）；
   - `6.json` 与 `7.json` 只差 `version`、`identityHash`、该索引的 `unique` 与建索引语句；
   - `6.json` 里 `ai_summary` 的建表语句与 `MIGRATION_5_6` 一致。
4. 本机模拟器 `BiliSeek_API35`（无界面启动）只跑 `MigrationTest`：纯本地 SQLite，测试进程不启动 `MainActivity`、`BiliSeekApp` 无联网初始化，不会访问 B 站。用 `adb install -r` 覆盖安装 + `am instrument` 只跑这一个类，**不用** Gradle 的 `connectedDebugAndroidTest`——它跑完会卸载 App，清掉模拟器上 debug 包的数据。

## 二、-101 刷新重试

### 2.1 关键事实

- `CookieRefresher` 的第一步 `cookie/info` 本身就要求有效的 SESSDATA。按公开文档，登录态彻底失效时它也回 `-101`。所以「刷新 + 重试」**只能救回还能续期的登录态**；彻底失效的只能重新扫码。
- `CookieRefresher` 目前没有并发保护。动态页一次会并发好几个请求，同时撞 `-101` 会拿同一个 `refresh_token` 并发刷新多次——正是它自己注释里说的「最容易被判定为异常的行为」。
- `relation()`、`seriesList()`、`seriesArchives()` 需要登录，却完全不走风控码分流（`-352` 也不进冷却）；`relation()` 的注释还写着「-101 / -352 由 interceptor 链提前抛成 SearchException」，这样的拦截器并不存在。`seriesList` / `seriesArchives` 目前在 `:app` 里没有调用方。

### 2.2 已定决策

- **只在 passport 确认时登出**：只有 passport 自己（`cookie/info` 或 `cookie/refresh`）也回 `-101`，才清本地登录态。网络失败、`-352`、passport 说不用刷新，都只让本次请求失败，不登出。
- **`relation` / `seriesList` / `seriesArchives` 一起接上**：改走现有的 `accountRead` 统一出口，同时获得 `-101` 刷新重试、`-352` 进冷却、`-412` 重种凭据。

### 2.3 方案选择

**采用：调用点包一层 + 纯函数编排 + 刷新单飞。** 与现有「错误码在调用点经纯函数分流」的约定一致；编排写成纯函数（同 `runWriteRequest`，协作者以 lambda 注入），可离线单测。

否决：

- **OkHttp 拦截器里看 JSON code 并重放**：违反「错误码不进拦截器」；要缓冲解析每个响应体；拦截器是阻塞线程；重放绕过 `RequestThrottle` / `RiskCooldown`。
- **在每个 `when (resolveRetryAction)` 里内联分支**：每处仍要把请求改成可重放的 lambda，刷新 / 重打 / 登出逻辑复制约 15 份，且 `BiliRepository` 依赖 `Log` / `Context`，无法 JVM 单测。

### 2.4 设计

#### A1. `CookieRefresher`：单飞 + 短时记忆 + 新结果 `Expired`

- 加一把 `Mutex`：同一时刻只有一次刷新在跑。启动检查（`refreshIfNeeded()`）、`-111`、`-101` 三个入口共用这把锁与同一份记忆。
- 新增 `internal suspend fun refreshAfterRejection(rejected: BiliSession): RefreshOutcome`。拿到锁后按纯函数 `resolveRefreshDecision` 决定：
  1. 本地已无登录 → `NotLoggedIn`（不发请求）；
  2. 当前 SESSDATA ≠ 发请求时用的 → 等锁期间已被别的请求续上（或换了账号）→ 直接当 `Refreshed`（不发请求，调用方用新登录态重打）；
  3. 同一份登录态 10 分钟内已问过 passport → 复用上次结果（不发请求）；
  4. 否则才真去走 `cookie/info → correspond → cookie/refresh → confirm/refresh`，结果记入记忆（登录态、结果、时间）。

  `refreshIfNeeded()` 走同一把锁，判断时没有「发请求时的登录态」，只看 1、3、4。
- 记忆时长 `10 分钟`：挡住「同一页面几个请求同时撞 -101」和「连续下拉刷新」反复打 passport，又不至于让常驻进程从此不再尝试续期。
- `RefreshOutcome` 新增 `Expired`：`cookie/info` 或 `cookie/refresh` 返回 `-101`，即 passport 确认这份登录态已失效。passport 返回码 → 结果的映射抽成纯函数：`-352` → `Blocked`，`-101` → `Expired`，其他非 0 → `Failed`。
- passport 各步的 HTTP 失败同样有口径：HTTP 412 → `Blocked`（与 `viewConclusion` 对 HTTP 412 的口径一致），其他非 2xx → `Failed`。无论它以非 2xx 响应（`correspond`、`cookie/refresh`）还是 Retrofit 抛出的 `HttpException`（`cookie/info`）出现。
- 刷新流程里抛出的异常（取消除外）也折成结果并记入记忆，不再往外抛：`HttpException` 按上一条映射，其他 → `Failed`。否则 passport 回 HTTP 412 时结果记不进记忆，每个撞 `-101` 的请求都会再打一次 passport；也顺带消除启动检查遇到这类异常时启动协程无人捕获的崩溃风险。

> ⚠️ **公开 API 变更**：`RefreshOutcome` 是公开的 sealed 接口，新增一个分支。`:app` 只在启动检查里对它打日志（`"$outcome"`），不会编译失败。其余新增均为 `internal`。

#### A2. 刷新之后的处置：纯函数 `resolveRefreshFollowUp`

| 刷新结果 | 处置 |
| --- | --- |
| `Refreshed` | 重打一次（仅一次） |
| `Expired` | 登出 |
| `Blocked`（passport `-352`） | 进全局 `RiskCooldown`，不重打 |
| `NotNeeded` / `NotLoggedIn` / `NotConfigured` / `Failed` | 放弃，本次请求照常失败 |

A1 的 `resolveRefreshDecision`、A2 的 `resolveRefreshFollowUp`、passport 码映射同放 `internal/SessionRefreshPolicy.kt`。

#### A3. 编排

- **读路径**：`internal/LoginRefresh.kt` 的 `runWithLoginRefresh`。
  1. 先记下当前登录态，再打一次请求；
  2. 只有 `code == -101` 才走 A1，再按 A2 处置；
  3. 重打的结果原样返回，**无论再回什么都不再重打**；
  4. `-352` / `-412` 等原样交回调用点现有分流——`-352` 永远不会触发刷新。

  `BiliRepository` 里一个私有薄封装 `refreshingOn101 { … }` 绑定 `sessionStore` / `cookieRefresher` / 登出回调 / `riskCooldown`。**包在限速闸门外面**：重打同样排队、吃最小间隔；刷新期间也不占着该桶的锁。
- **写路径**：在 `runWriteRequest` 里，`-111` 与 `-101` 共用 A1 + A2 与**同一次**重打机会（`-111` → 刷新 → 重打 → 又回 `-101`，不再刷新，总共两次）。`refresh` 绑定为 `cookieRefresher.refreshAfterRejection(发请求时的登录态)`，新增 `logOut` 入参。处置：
  - `RETRY_ONCE` → 用新 jct 重打一次；
  - `LOG_OUT` → 登出，抛 `WriteError.NeedLogin`（「登录已失效，请重新登录」）；
  - `COOL_DOWN` → 抛 `WriteError.Blocked`（`writeRequest` 现有的 catch 会记入冷却）；
  - `GIVE_UP` → 照现有逻辑按最终返回码抛错。

  `-111` 路径因此有两处**行为变化**：刷新时 passport 回 `-352`，从「不进冷却」变为「进冷却」；passport 确认登录态失效时同样登出。

#### B. 调用点

**接**（读路径各包一行 `refreshingOn101 { … }`）：

- `search`
- `wbiRequest`（→ UP 主搜索、空间投稿、动态详情四级降级）
- `accountRead`（→ 收藏夹目录 / 内容 / 首条封面、订阅合集、追更合集、动态流、UP 动态、关注列表、稍后再看），以及改走它的 `relation`、`seriesList`、`seriesArchives`
- 消息中心：`privateSessions`、`replyFeed`、`atFeed`、`likeFeed`、`followerFeed`、`loadMoreSessionMessages`
- `archiveRelation`、`emotePanel`、`playerSubtitles`、`viewConclusion`、`comments`、`subReplies`
- 写路径：全部经 `writeRequest` → `runWriteRequest`，自动覆盖

**不接**：

| 接口 | 原因 |
| --- | --- |
| `danmakuSegment` | 公开接口，protobuf 响应，`-101` 不适用 |
| `messageUnread`（角标轮询）、`fillSessionPeers` | best-effort、失败静默是它们的约定；不让后台轮询去碰 passport。死登录态会被下一个前台账号请求发现并处理 |
| `pages` / `videoView` / `userCard` | 公开接口；它们不走风控码分流（`-352` 不进冷却）是存量缺口，记进《功能优化篇》§10 欠账表另排 |

#### C. 登出与用户可见行为

登出走 `LoginManager` 新增的 `internal suspend fun expire()`：

1. 清 `SessionStore`——与手动登出一样只清账号，buvid / ticket 保留；
2. `LoginState` 置为 `Failed("登录已过期，请重新扫码")`。各页登录面板复用现成样式，显示「登录失败：登录已过期，请重新扫码」+「重试」，`:app` 不改 UI。

不能只清 `SessionStore`：本进程里扫过码的话 `LoginState` 停在 `Success`，登录面板会显示「登录成功」而没有任何按钮，用户无法重新登录。
`expire()` 由 `BiliNetwork` 作为回调交给 `BiliRepository`（构造函数本就是 `internal`），不新增公开 API。`BiliNetwork` 里把 `loginManager` 的声明挪到 `repository` 之前。

| 场景 | 读（各账号页） | 写（点赞、评论等） |
| --- | --- | --- |
| 还能续（`Refreshed`） | 无感：续期后重打成功 | 无感 |
| passport 确认失效（`Expired`） | 自动登出，各页回到登录面板；本次请求照常报错 | 自动登出 +「点赞失败：登录已失效，请重新登录」 |
| 刷新撞 `-352` | 进全局冷却，本次失败；冷却期内后续请求本地快速失败 | 「…失败：触发风控(-352)，已进入退避」 |
| 网络失败 / passport 说不用刷 / 其他 | 与现在一样失败，不登出 | 与现在一样 `NeedLogin` |
| 本地本来就未登录 | 不发任何 passport 请求 | 与现在一样（发请求前已拦） |

启动检查拿到 `Expired` 只记日志（`:app` 不改）；之后第一个撞 `-101` 的账号请求复用这条记忆直接完成登出，不再打 passport。已登录时启动即拉动态页，实际上一打开就会完成登出。

### 2.5 测试

全部离线 JVM 单测，不连任何服务器（AGENTS.md）：

- `SessionRefreshPolicyTest`
  - `resolveRefreshDecision`：未登录、登录态已变、记忆命中、记忆过期、记忆属于别的登录态、启动检查（无「发请求时的登录态」）；
  - `resolveRefreshFollowUp`：每种 `RefreshOutcome`；
  - passport 码映射：`-352` → `Blocked`、`-101` → `Expired`、其他 → `Failed`；
  - HTTP 失败与异常映射：HTTP 412 → `Blocked`，其他 HTTP 错误 / 其他异常 → `Failed`。
- `LoginRefreshTest`（`runWithLoginRefresh`）
  - 非 `-101` 只打一次且不调刷新；**`-352` 绝不触发刷新**；
  - `Refreshed` → 恰好两次、返回第二次；重打又回 `-101` → 仍恰好两次；
  - `Expired` → 调登出、只打一次；`Blocked` → 进冷却、只打一次；其余 → 只打一次、无副作用；
  - 交给刷新的是发请求前的登录态（请求过程中登录态变化不影响）。
- `WriteRequestTest` 补充：`-101` 的各种刷新结果；`-111` 与 `-101` 共用一次重打；`-111` 遇 `Blocked` → `Blocked`。
- 现有 `SearchInputTest` 里 `resolveRetryAction(-101) == REFRESH_AND_RETRY` 保持不变。
- 收尾：`./gradlew test` + `./gradlew assembleDebug`。

测不到的：`BiliRepository` / `CookieRefresher` 的接线（依赖 Android `Log` / `Context`）与真实 passport 行为。按 AGENTS.md 只能真人在真机上验证，写进《手动测试指南》，未做之前如实标「未验证」。

### 2.6 文档与注释

代码说了算，文档跟着改：

- `CLAUDE.md`：`-101` 一条改为已接线的描述；删掉 schema 的 Known gap。
- `AGENTS.md` §3：补 `-101` 规则。
- 《架构设计篇》：§3.2 错误码表 `-101` 行；Room 段的「⚠️ 已知缺口」；§10 风险表两行；顺手修 §3.2 里并不存在的 `jsonWriteRequest`。
- 《功能优化篇》：§10.0 删掉两行、移入「已销账」；新增欠账「`pages` / `videoView` / `userCard` 未走风控码分流」；§13 追加 13.19（含总览表一行）。
- `README.md`：登录态续期一行去掉「`-101` 尚未接线」。
- 《手动测试指南》：登录态续期段补 `Expired` 日志与真人验证步骤（在 B 站账号安全里下线这台设备或改密码 → 进账号页 → 预期自动登出、面板提示、`cookie/info` 只打一次）。
- 过时注释：`BiliRepository` 的 `relation()`、`checkMessageRead`（「-101 已由拦截器链处理」）、`watchLater`（「未登录 -101，不重试」）、构造参数注释；`UpSpaceController.loadFollowingState`。

## 三、交付

在新分支上做（如 `fix/schema-6-7-and-101-refresh`），按「schema」「-101」分两个提交；提交与推送等用户确认后再做。
