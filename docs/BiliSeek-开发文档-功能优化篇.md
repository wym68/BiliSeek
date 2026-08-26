# BiliSeek 项目开发文档 · 功能优化篇（M5–M7）

> 版本：v1.0  
> 日期：2026-08-25  
> 前置文档：《功能选型篇》v0.2、《架构设计篇》v0.2、《MVP 收尾总结》  
> 范围：MVP（M0–M4）之后的功能优化阶段，拆为 M5 / M6 / M7 三个里程碑。  
> 用途：本文回答「MVP 之后先做什么、为什么是它、怎么验」。判据部分与《验收清单》同格式，完成时逐条勾选。

---

## 1. 为什么是这三件事

MVP 闭环已达成，但**闭环不等于好用**。逐个文件读过现有代码之后，当前状态是「能跑通，但每一步都硌手」。下面每一条都注明了代码位置——这些是读码撞出来的，不是从文档抄的。

| # | 现状 | 代码位置 |
| --- | --- | --- |
| 1 | **播放器没有进度条**。`PlayerConnection` 门面里根本不存在 `seekTo`，UI 只有一行文本 `"${positionMs/1000}s / ${durationMs/1000}s"` | `PlayerContainer.kt:136` |
| 2 | **全程没有封面图**。`coverUrl` 一直在存，`normalizeCoverUrl()` 也写好了，但依赖表里没有任何图片加载库，这些 URL 从未被渲染过 | `libs.versions.toml` |
| 3 | **时长恒为 0**。`PlayUrlResolver` 已解出 `durationMs`，没有回写路径，界面显示「61s / 0s」 | `PlayUrlResolver.kt` |
| 4 | **断网点播放要等约 85 秒才报错**。`ensureReady()` 全程持 `mutex`，后台预热与用户主动请求共用同一份「5 次指数退避」预算 | `AuthGate.kt:96-128` |
| 5 | **搜索无结果 = 白屏**。`showEmptyState` 要求 `keyword.isBlank()`，所以「有关键词但 0 结果」时 `emptyContent` 被调用却什么都不渲染 | `MainActivity.kt:93` |
| 6 | **AV 号 / b23 短链认了但没用**。`parseSearchInput()` 正确返回 `VideoAid` / `ShortLink`，被 `else ->` 分支退回了关键词搜索 | `AppViewModel.kt:181` |
| 7 | **加歌单固定选第一个** | `AppViewModel.kt:113` |
| 8 | **队列看不见全貌**。只有「队列 N / M」文本；后端 `reorder()` 早已实现却没有 UI | `PlayerContainer.kt:268`、`LibraryRepository.kt:46` |
| 9 | **Cookie 刷新完全未实现**。三个接口定义齐全，全局 grep 无任何调用点，缺 `correspondPath`（RSA-OAEP）与 `refresh_csrf`（HTML 解析）两步 | `BiliApi.kt:93-117` |

**目标**：把「第一次上手到日常使用」这条路径上的硌手点全部抹平，同时补掉 P0 的 Cookie 刷新。

---

## 2. 边界与约束

### 2.1 首启空状态保持现状

新用户无搜索历史、无最近播放，首页空状态只有一句 slogan。**不加引导页、不加内置示例搜索词。**

理由：「没有推荐」是产品身份（见《功能选型篇》§1）。写死的关键词 chip 也是一种编辑推荐——它和算法推荐的区别只是谁来选，不是有没有人选。新用户的上手路径由 M7 的**外部分享入口**承担，那是真实需求驱动的入口，不是我们替他挑内容。

### 2.2 数据库不动

M5–M7 所需字段（`TrackEntity.durationSec` / `coverUrl` / `PlaylistTrackEntity.sortKey`）在 v2 schema 里全都有。`BiliSeekDatabase` 保持 `version = 2`，**不新增迁移**。

### 2.3 每个里程碑都要留扩展位

后续功能（歌词、响度均衡、音质选择、下载导出）不在本阶段实现，但要在门面与 UI 结构上留好接入点，避免届时返工。具体见 §3.5。

---

## 3. M5 — 播放器基本功

> 收口目标：进度条能拖、封面能看见、时长不再是 0、能定时睡觉、能调倍速。

### 3.1 seek

`PlayerConnection.kt` 新增门面方法：

```kotlin
fun seekTo(positionMs: Long)      // controller.seekTo，clamp 到 [0, duration]
fun seekBy(deltaMs: Long)         // 相对快进/快退，供后续通知栏按钮复用
```

**坑 1 —— 位置回写的节流。** `recordPositionIfNeeded()`（`PlayerConnection.kt:323`）有 5 秒节流且要求 `isPlaying`。用户拖完进度条立刻切后台被杀，位置会丢。`seekTo` 内部要把 `lastRecordedAt` 归零并强制回写一次。

**坑 2 —— 拖动被轮询覆盖。** `connect()` 里有个 250ms 的 `pushState()` 轮询循环。Compose 侧的 `Slider` 若直接绑定 `state.positionMs`，拖动过程中会被轮询值拽回去。UI 必须用本地拖动态：

```kotlin
var dragging by remember { mutableStateOf(false) }
var dragValue by remember { mutableFloatStateOf(0f) }
value = if (dragging) dragValue else state.positionMs.toFloat()
onValueChange = { dragging = true; dragValue = it }              // 只改本地
onValueChangeFinished = { seekTo(dragValue.toLong()); dragging = false }
```

**UI 落点**：`ExpandedPlayer` 加 `Slider` + 两端 `mm:ss` 文本；`MiniPlayer` 的 `LinearProgressIndicator` 保留为不可拖的细进度条。新增纯函数 `formatDuration(ms): String`（`mm:ss`，超一小时 `h:mm:ss`），配单测。

### 3.2 时长回写

`ResolvedMedia.durationMs` 目前只喂给了 MPD 组装，没有出口。

- `PlayerConnection` 新增回调 `var durationRecorder: (suspend (String, Long, Long) -> Unit)?`，与既有的 `resumePositionProvider` / `positionRecorder`（`PlayerConnection.kt:57-60`）并列——播放内核不认识数据库，这是现有的解耦惯例，照做。
- `play()` 中取流成功后调用它。

**坑 3 —— 不能复用 `recordPlay()`。** 它的 `positionMs` 参数默认 0，调一次就把续播位置抹平了。新增窄接口，照抄 `recordPlayPosition()`（`LibraryRepository.kt:89`）的写法：

```kotlin
suspend fun recordDuration(bvid: String, cid: Long, durationSec: Long) {
    val existing = history.find(bvid, cid) ?: return
    if (durationSec <= 0) return
    history.upsert(existing.copy(durationSec = durationSec))   // 不动 positionMs / playedAt
}
```

`TrackEntity.durationSec` 同样恒为 0（`AppViewModel.kt:122` 硬编码）。搜索结果里有现成的 `SearchVideo.duration` 字符串（形如 `"4:32"`），新增纯函数 `parseDurationText(text): Long` 到 `SearchInput.kt`（该文件已是解析类纯函数的归处），配单测覆盖 `"4:32"` / `"1:02:03"` / 空串 / 脏数据。

### 3.3 封面图

- 版本目录引入 **Coil 3**（`coil-compose` + `coil-network-okhttp`）。
- **坑 4 —— Referer。** B 站图片 CDN（`i0.hdslb.com`）对热链有校验风险。装配自定义 `ImageLoader`，其 OkHttp 复用 `BiliNetwork.mediaRequestHeaders`（`BiliNetwork.kt:63`，已含 UA / Referer / Origin）。这与 `PlaybackService.kt:37` 给 DataSource 注入请求头是同一个道理，不要重新发明。
- **`PlaybackRequest.Bili` 与 `PlaybackState` 目前都没有 `coverUrl` 字段**，需各加一个，否则展开播放器拿不到封面。`AppViewModel` 的三处构造点（`play` / `playPage` / `playPlaylist`）一并带上。
- 渲染落点：搜索结果行左侧 16:9 缩略图（`ResultRow`）、首页「最近播放」、歌单条目、以及 `ExpandedPlayer` 里那个黑框——现在纯音频模式下写着「纯音频模式」四个字，改成显示封面。

### 3.4 睡眠定时器与倍速

- 新增 `core/player/internal/SleepTimerPolicy.kt`：纯函数 + 数据类，描述「剩余毫秒」与「播完当前曲目再停」两种终止条件。与 `ResumePolicy` / `QueuePolicy` 的现有模式一致，配单测。
- 计时器状态**归 `PlayerConnection` 持有**，不放 ViewModel。理由与「播放器归 Service 持有」相同：用户设了定时器就会锁屏走开，状态不能绑 UI 生命周期。
- `PlaybackState` 增加 `sleepTimerRemainingMs: Long?` 与 `speed: Float`。倍速走 `controller.setPlaybackSpeed()`。
- UI：定时 15 / 30 / 60 分钟 + 播完当前；倍速 0.75× / 1.0× / 1.25× / 1.5× / 2.0×。

### 3.5 扩展位

`ExpandedPlayer` 的控制区目前是两个写死的 `Row`（`PlayerControls` / `QueueControls`）。改成由动作列表驱动渲染，后续「歌词」「音质」「下载」直接往列表里加项，不再改布局代码。

同时在门面上留好接口而**不实现**：

| 后续功能 | 已就位的东西 | 缺什么 |
| --- | --- | --- |
| 响度均衡 | `ResolvedMedia.measuredLoudness` 已在拿数据 | 应用侧的增益处理 |
| 音质选择 | `PlayUrlResolver.resolve(preferHiRes)` 参数已存在 | 从未被外部传过 `true`，且无 UI |

---

## 4. M6 — 等待与错误体感 + Cookie 刷新

> 收口目标：断网时秒级报错而不是 85 秒；错误能重试；不再周期性掉登录。

### 4.1 拆两类重试策略（最高性价比的一项）

**现状**（`AuthGate.kt:96-128`）：`ensureReady()` 整个函数体在 `mutex.withLock` 里，内含 `repeat(MAX_ATTEMPTS = 5)` 的指数退避（1s→2s→4s→8s→16s = 31s 纯等待，叠加 `connectTimeout 15s` / `readTimeout 20s` 后接近 85 秒）。用户主动点播放时排在后台预热之后，全程只能干等。

改法：

1. **两条路径分开预算。** `start()`（后台预热）走慢退避、次数多；`awaitReady()`（用户主动请求）走**快失败**——最多 1 次尝试、短超时，失败立刻抛异常。
2. **锁不覆盖退避。** `mutex` 只保护「检查状态 → 写入凭据」的临界区，`delay()` 必须在锁外，否则后台退避期间所有用户请求全被堵死。
3. **保留 `awaitReady()` 现有的核心语义**（`AuthGate.kt:77` 的注释说得很清楚）：失败要抛异常而不是继续挂起，且每次调用会重新触发一轮，网络恢复后用户再点一次即可自愈。这条别改坏了。
4. 退避判定抽成纯函数放 `internal/RetryPolicy.kt`（该文件已是这类策略的归处），配单测。

### 4.2 错误可重试 + 区分错误类型

- `BiliRepository.search()`（`BiliRepository.kt:25`）当前把所有失败都塞进 `Result.failure` 的 message 字符串里。引入 `sealed interface SearchError`：`Blocked`（-352 风控）/ `CredentialsInvalid`（-412）/ `NotActivated`（data 为 null）/ `Network` / `Empty`（code=0 但 0 结果）。
- `SearchUiState.error` 从 `String?` 改为该类型，UI 据此给出不同文案与**不同的可用操作**。

  > ⚠️ **`Blocked` 不给重试按钮。** `resolveRetryAction` 的 `BACKOFF_NO_RETRY` 语义就是绝不重试（见《架构设计篇》§3.2），UI 不能诱导用户猛戳，那会把临时限流推成长期封禁。

- **修白屏**：`showEmptyState` 条件补上「有关键词且 0 结果」的分支，渲染「没有找到『xxx』」而不是空白。
- `ExpandedPlayer` 的取流失败（`PlayerContainer.kt:207` 那行红字）补一个「重试」按钮。

### 4.3 Cookie 刷新（P0 遗留）

接口层已就绪（`BiliApi.kt:93-117`：`cookieInfo` / `cookieRefresh` / `confirmRefresh`，DTO `CookieInfoData` / `RefreshData` 也在）。新建 `core/network/CookieRefresher.kt`，补齐缺失的两步：

1. **`correspondPath`**：用固定 RSA-OAEP 公钥（SHA-256）加密 `refresh_${timestamp}`，输出 hex。纯函数，可单测。
2. **抓 `refresh_csrf`**：GET `https://www.bilibili.com/correspond/1/{correspondPath}`，从返回 HTML 里取 `id="1-name"` 元素的文本。`BiliApi` 需新增一个返回 `okhttp3.ResponseBody` 的方法——Retrofit 原生支持，不必为此加 HTML 转换器。解析用正则，抽成纯函数配单测（喂真实 HTML 片段）。
3. **完整链路**：`cookieInfo`（判断 `refresh == true`）→ 算 `correspondPath` → 取 `refresh_csrf` → `cookieRefresh` → 从响应头解析新 Cookie（**复用** `LoginManager.kt:112` 的 `parseSessionFromCookies`）→ `sessionStore.save()` → `confirmRefresh`（带**旧** refresh_token）。
4. **触发时机**：`AppViewModel.init` 中 `sessionStore.load()` 之后，若已登录则检查一次。不阻塞 UI，与 `authGate.start()` 同为后台任务。
5. **安全红线**：整条链路遇到 `-352` **绝不重试**，直接放弃并保持现有登录态。

> ⚠️ **验证需要真人先扫一次码。** MVP 收尾时登录完成态从未跑通（见《MVP 收尾总结》§5）。Cookie 刷新的真实验证只能在拿到真实 `refresh_token` 之后进行；在此之前只能验到纯函数单测 + `cookieInfo` 的响应。这一点会在验收记录里如实标注为「未验证」，**不当成通过**。

---

## 5. M7 — 搜索入口 + 歌单与队列

> 收口目标：从 B 站 App 分享过来能接住；加歌单能选；队列看得见、拖得动。

### 5.1 AV 号 / b23 短链直达

补齐 `AppViewModel.submitSearch()`（`AppViewModel.kt:168-183`）的 `else ->` 分支丢掉的两个 case：

- **`SearchIntent.VideoAid`**：新增纯函数 `avToBv(aid: Long): String`（B 站公开的 XOR + Base58 换表算法），放 `SearchInput.kt`，配单测（用几组已知 av↔BV 对照）。**本地换算，不多打一次网络请求**——与项目「不给服务端多余的行为特征」的取向一致。
- **`SearchIntent.ShortLink`**：需要展开重定向。`BiliApi` 加 `@GET suspend fun resolveShortLink(@Url url: String): Response<Unit>`，OkHttp 默认跟随重定向，从 `response.raw().request.url` 取最终地址，再喂回 `parseSearchInput()` 二次解析。**加防护**：只接受重定向落点在 `bilibili.com` 域内，否则退回关键词搜索。

### 5.2 接住外部分享（首次上手的关键路径）

`AndroidManifest.xml` 目前只有 LAUNCHER。给 `MainActivity` 加两个 intent-filter：

- `ACTION_SEND` + `text/plain`：接 B 站 App 的「分享到」。
- `ACTION_VIEW` + `https://b23.tv` / `https://www.bilibili.com/video/*`：接浏览器里的链接。

`MainActivity` 需处理 `onCreate` 与 `onNewIntent` **两处**入口（后者容易漏），把文本喂给已有的解析路径。

> 这是新用户第一次接触本 App 最可能的路径——从 B 站刷到一首歌，分享过来只听音频。§2.1 决定不做内置示例词，上手体验就由这个入口承担。

### 5.3 选择歌单对话框

`AppViewModel.addToPlaylist()`（`AppViewModel.kt:112`）现在是 `playlists.value.firstOrNull()` 硬选第一个。改为：VM 暴露「待加入的条目」状态，UI 弹 `AlertDialog` 列出全部歌单 + 「新建歌单」项，选定后再落库。`LibraryScreen.kt:157` 已有 `NameDialog`，新建分支直接复用。

### 5.4 长按操作菜单

搜索结果长按现在直接加歌单（`MainActivity.kt:102`）。改为弹 `ModalBottomSheet`：加入歌单 / 下一首播放 / 复制链接 / 查看分 P。

「下一首播放」需要 `PlayerConnection` 新增 `enqueueNext(request)`——队列是本类私有的 `List<PlaybackRequest>`（`PlayerConnection.kt:52`），插入后要同步修正 `queueIndex`，这段逻辑抽进 `QueuePolicy.kt` 配单测。

### 5.5 队列列表 + 拖拽重排

- `PlaybackState.queue` 已经带着完整队列了，UI 只显示了「队列 N / M」。加一个可展开的队列面板：列出全部、高亮当前、点击跳转（`PlayerConnection` 加 `skipToIndex(i)`）。
- 歌单内拖拽重排：`LibraryRepository.reorder()`（`LibraryRepository.kt:46`，浮点 sortKey 中值插入）**后端已完全就绪**，只差 UI。在 `PlaylistDetail` 的 `LazyColumn` 上接拖拽手势，松手时用相邻两项的 `sortKey` 调 `reorder`。

---

## 6. 验收判据

每个里程碑独立验收，产出 `docs/验收记录/M{n}-{日期}.md`，格式沿用 M0–M4。

### 6.1 自动化

```bash
./gradlew test                        # 单元测试，新增纯函数全部要有覆盖
./gradlew connectedDebugAndroidTest   # 仪器化（DB 未变更，应保持通过）
./gradlew installDebug
```

新增单测清单：`formatDuration` / `parseDurationText` / `avToBv` / 快慢两类重试的退避决策 / `SleepTimerPolicy` / `correspondPath` / `refresh_csrf` 正则 / `enqueueNext` 的 `queueIndex` 修正。

### 6.2 M5 手动判据（真机）

| # | 判据 | 说明 |
| --- | --- | --- |
| A-1 | 拖动进度条→松手位置准确且不回弹 | 验证「轮询覆盖」坑已解 |
| A-2 | 拖完立即杀进程→重进续播位置正确 | 验证「节流」坑已解 |
| A-3 | 搜索结果 / 最近播放 / 歌单 / 播放页四处封面均加载 | 若 403 则 Referer 注入失败 |
| A-4 | 播放历史不再显示 `/ 0s` | |
| A-5 | 设 15 分钟定时器→锁屏→到点停止 | |
| A-6 | 倍速切换后音调正常 | |

### 6.3 M6 手动判据（真机）

| # | 判据 | 说明 |
| --- | --- | --- |
| B-1 | **开飞行模式点播放→5 秒内报错并出现「重试」按钮** | **本里程碑最硬的判据**，当前基线约 85 秒 |
| B-2 | 关飞行模式点重试→自愈 | |
| B-3 | 搜一个必然无结果的乱码串→显示「没有找到」而非白屏 | |
| B-4 | 触发 -352 时**不出现**重试按钮 | 防止诱导用户猛戳 |
| B-5 | 扫码登录后放置至 `cookieInfo` 返回 `refresh=true`，观察自动刷新且未掉登录 | 需真人扫码，见 §4.3 警告 |

### 6.4 M7 手动判据（真机）

| # | 判据 |
| --- | --- |
| C-1 | B 站 App 里分享一个视频到 BiliSeek→直接起播 |
| C-2 | 浏览器打开 b23.tv 短链→选择 BiliSeek 打开→起播 |
| C-3 | 粘贴 `av170001` →直达 |
| C-4 | 长按结果→选歌单→在**指定**歌单里出现 |
| C-5 | 队列面板点任意项跳转正确 |
| C-6 | 歌单内拖拽后重进 App 顺序保持 |

---

## 7. 收尾

三个里程碑完成后：

1. ✅ 更新 `README.md` 的能力表格。
2. ✅ 更新《功能选型篇》的 P1 清单（已拆成「已完成 / 待办」）。
3. ✅ 对着《MVP 收尾总结》§4 的遗留项逐条勾销，剩余项重新排优先级。
4. ✅ **真机复验**：§6.2–6.4 的判据原本都写着「真机」，实际是在模拟器上验的。
   2026-08-26 已在 PJD110 / Android 16 上跑通《手动测试指南》Part B 步骤 1–20，
   见[真机验收记录](验收记录/真机-2026-08-26.md)。
   **仍欠 Part B 的 B6（步骤 21–27）**：耳机、蓝牙、音频焦点、长时稳定性、流量、Hi-Res、低端机。
