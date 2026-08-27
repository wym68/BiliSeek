# BiliSeek 项目开发文档 · 功能优化篇（M5–M8）

> 版本：v1.1（2026-08-26 补入 M8）  
> 日期：2026-08-25 / 2026-08-26  
> 前置文档：《功能选型篇》v0.2、《架构设计篇》v0.2、《MVP 收尾总结》  
> 范围：MVP（M0–M4）之后的功能优化阶段，拆为 M5 / M6 / M7 / M8 四个里程碑。
> M5–M7 已完成并验收（含 Android 16 真机实跑），M8 为下一阶段规划。  
> 用途：本文回答「MVP 之后先做什么、为什么是它、怎么验」。判据部分与《验收清单》同格式，完成时逐条勾选。

---

## 1. 为什么是这三件事（M5–M7）

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

### 2.4 大会员相关功能：先实现，只验逻辑

**开发者手上没有大会员账号。** 无损（Hi-Res / FLAC）、杜比全景声这类需要会员权限的能力，
在真机上永远只能走到「回退 AAC」那一支，正向路径实测无门。

口径定死为三条：

1. **照常实现，不因为验不了就搁置。** 缺的是账号，不是设计——B 站的接口、轨道结构、
   降级规则都是公开的，实现完整了，将来拿到账号就是「开一次看看」的事。
2. **验证降级为逻辑层**：纯函数单测 + 从轨道挑选到 MPD 组装的端到端断言。
   凡是能在没有账号的情况下判定对错的，都必须有测试钉住——**这是这类代码唯一的担保**。
3. **验收记录里如实标注为「未验证」，不写成通过。** 逻辑测试证明的是「按设计走对了」，
   不是「在真机上放出声了」。两者混为一谈，等于把没测的当测过的。

具体到 M8：

| 能力 | 逻辑验证 | 真机待验 |
| --- | --- | --- |
| 无损 / 杜比轨挑选 | `QualityPolicyTest`（含 type/codecs 校验、拿不到时回退 AAC） | 正向命中后声音确实是无损 |
| 无损进 MPD | `LosslessManifestTest`（挑对了**且真写进了 manifest**，视频轨不被挤掉） | 同上 |
| 倍速 > 1.5× 回退 | `QualityPolicyTest` + 模拟器实测缓存 key 变化 | — |
| FLAC 输入缓冲 | `FlacBufferPolicyTest`（只动 FLAC、不缩小、缺省值不漏抬） | 特定机型上不再 `Insufficient capacity` |

> FLAC 缓冲那条尤其典型：判定逻辑本可以埋在 `MediaCodecAdapter.Factory` 里没人看得见，
> 抽成纯函数**正是因为验不到真机**——实测无门时，至少让逻辑本身可被检验。

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

## 6. M5–M7 验收判据

每个里程碑独立验收，产出 `docs/验收记录/M{n}-{日期}.md`，格式沿用 M0–M4。
（M8 的判据见 §8.8。）

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

## 7. M5–M7 的收尾

三个里程碑完成后：

1. ✅ 更新 `README.md` 的能力表格。
2. ✅ 更新《功能选型篇》的 P1 清单（已拆成「已完成 / 待办」）。
3. ✅ 对着《MVP 收尾总结》§4 的遗留项逐条勾销，剩余项重新排优先级。
4. ✅ **真机复验**：§6.2–6.4 的判据原本都写着「真机」，实际是在模拟器上验的。
   2026-08-26 已在 PJD110 / Android 16 上跑通《手动测试指南》Part B 步骤 1–20，
   见[真机验收记录](验收记录/真机-2026-08-26.md)。
   **仍欠 Part B 的 B6（步骤 21–27）**：耳机、蓝牙、音频焦点、长时稳定性、流量、Hi-Res、低端机。

---

## 8. M8 — 设置页与音质，外加三项补完

> 版本：规划稿（2026-08-26）  
> 收口目标：设置页做实、音质这条链路从接口打通到 UI，顺带修掉真机上暴露的三个既有缺口。

### 8.1 为什么是这几件事

M5–M7 抹平了「第一次上手到日常使用」的硌手点，真机也跑通了。但再读一遍代码，有三处很扎眼——**都是「东西已经在了，只差最后一步」**：

| # | 现状 | 代码位置 |
| --- | --- | --- |
| 1 | **「设置」页里一个设置项都没有**。`settingsContent` 只塞了一个 `LoginPanel`，选型篇 P1 的「默认模式设置」「流量策略」没有落脚点 | `MainActivity.kt:153` |
| 2 | **`preferHiRes` 是条死参数**。`resolve()` 收了它、还把它算进缓存 key，但 `toMedia()` 里写死 `collectTracks(dash, preferHiRes = false)`——UI 就算传 `true` 也选不到 Hi-Res 轨 | `PlayUrlResolver.kt:38`、`:79` |
| 3 | **`measuredLoudness` 白拿着没用**。`ResolvedMedia` 一路带着它，全项目无消费点。B 站投稿音量参差极大，而这个数据是接口白送的 | `PlayUrlResolver.kt:17` |

外加真机实跑（见[真机验收记录](验收记录/真机-2026-08-26.md) §C）暴露的两条与 M7 遗留的一条，一并在本里程碑清掉。

**不做的**：收藏夹同步、歌词、下载导出 m4a 留 M9+；低端机降级不做——手上没有 2–4 GB 的老设备，做完也验不了，属于「写了等于没写」。Cookie 公钥仍留空，保持 `NotConfigured`。

### 8.2 SettingsStore（其余全挂在它上面，先做）

新建 `app/data/SettingsStore.kt`，照 `SessionStore.kt:26` 的现成写法（`preferencesDataStore` + `load()` + `StateFlow`）。DataStore 已在用，不引新库。

```kotlin
data class AppSettings(
    val defaultPresentation: DefaultPresentation = AUDIO_ONLY,  // 出厂守身份：默认只听
    val lastPresentationWasVideo: Boolean = false,              // 「记住上次」用
    val allowVideoOnMetered: Boolean = false,                   // 流量策略
    val audioQuality: AudioQuality = AUTO,
    val loudnessNormalization: Boolean = true,
)
```

`AppViewModel` 已是 `AndroidViewModel`（`AppViewModel.kt:58`），在构造处与 `network` / `library` 并列即可。

UI 新建 `app/ui/SettingsScreen.kt`，分四组：播放 / 网络 / 音质 / 账号。**账号一组直接复用现有 `LoginPanel`**（`LoginScreen.kt:23`），不重写。

### 8.3 流量策略与默认模式

- 判定抽成纯函数 `resolveVideoGate(settings, isMetered): VideoGate`（`Allow` / `BlockedByMetered`），放 `app/data/VideoGatePolicy.kt`，配单测——与 `ReorderPolicy` / `QueuePolicy` 同一惯例。
- 计量网络用 `ConnectivityManager.isActiveNetworkMetered` 在 VM 里读，**策略本身保持纯函数**。
- 被拦时给一次性放行（「仍要看」），**不写回设置**：一次例外不该变成永久开关。
- 「记住上次」：`onPresentationChange` 时写 `lastPresentationWasVideo`；起播后是否自动切 INLINE **必须在 UI 层决定**——`surfaceView` 由 `AppShell` 持有，VM 拿不到。

### 8.4 音质选择与 Hi-Res 打通（本里程碑的主体）

设计在《架构设计篇》§4.4 已定好，照它执行。

**取流层**（`core/player/internal/`）：

- `toMedia(data)` 改为 `toMedia(data, preferHiRes)`，把 `resolve()` 的参数真正传下去——**就是 §8.1 那个死值**。
- `collectTracks`（`LocalDashManifestBuilder.kt:49`）已有 flac 分支，补杜比：DTO 里 `Dash.dolby` 已存在（`BiliDto.kt:95`），条件是 `type > 0 且 codecs 含 ec-3/eac3`。
- 新建 `internal/QualityPolicy.kt`：纯函数 `resolveEffectiveQuality(requested, speed, available)`，落实 §4.4 的边界——**倍速 > 1.5× 时 Hi-Res/杜比自动回退 AAC**。回退原因用密封类暴露（`不可用 / 倍速不兼容 / 无可播放音频`），配单测。

**门面层**（`PlayerConnection.kt`）：

- 加 `var qualityProvider: (() -> AudioQuality)?`，照 `resumePositionProvider`（`:67`）/ `durationRecorder`（`:77`）的解耦惯例——播放内核不认识设置存储。
- `play()` 里改调 `resolver.resolve(bvid, cid, preferHiRes = ...)`（当前在 `:166`）。
- 切倍速时若生效的是 Hi-Res 且新倍速 > 1.5×，重新取流降级。缓存 key 已含 `preferHiRes`，两份天然分开，不会互相污染。
- `PlaybackState` 加 `audioQuality` 与 `qualityFallback`，播放页显示**实际生效**的音质与回退原因，而不是用户请求的那个。

**解码兼容**（`PlaybackService.kt:42`）：

- 新建 `internal/HiResCompatibleRenderersFactory.kt`，把 FLAC 解码器最小输入缓冲抬到 1 MiB，在 `ExoPlayer.Builder` 上 `setRenderersFactory(...)`。
- 依据：部分设备只给 `c2.android.flac.decoder` 分配 32 KiB，B 站 Hi-Res 单帧会超，抛 `InsufficientCapacityException`（《架构设计篇》§4.4 边界 2，照搬 BiliPai）。
- ⚠️ **这条合入时验不了**——需要真机 + 大会员账号。写的时候就要接受它带着「未验证」标记进仓库，别在验收记录里含糊过去。

### 8.5 响度均衡

- `VolumeInfo` 里 `measured_i` 与 `target_i` **都有**（`BiliDto.kt:76-77`），把 `targetI` 一并带进 `ResolvedMedia`。
- 新建纯函数 `internal/LoudnessPolicy.kt`：`loudnessGain(measuredI, targetI): Float`，配单测。两个硬边界：
  - `measuredI == 0.0`（无数据）→ `1.0f`
  - **结果 clamp 到 (0, 1]**——`Player.setVolume` 只能衰减不能增益，算出 >1 的增益若不夹回，表现是「开了响度均衡但毫无变化」，最难排查的那种。
- `play()` 取流后按设置套用；开关关掉时恢复 `1.0f`。

### 8.6 三项补完

#### 队列内非首条不进播放历史

根因已定位：所有队列推进（`skipToIndex` `:253`、`skipToNext` `:259`、播完自动推进 `onTrackEnded` `:271`）都收敛到 `play(item, fromQueue = true)`，而 `recordPlay` 只在 VM 的三个**起播入口**调（`AppViewModel.kt:407 / 421 / 483`）。

- `PlayerConnection` 加 `var trackStartRecorder: (suspend (PlaybackRequest.Bili) -> Unit)?`，在 `play()` 里**取流之前**调用。顺序不能反——`AppViewModel.kt:476` 的注释写明了原因：历史行必须先落库，取流后的时长回写走「更新已有行」的窄接口，行不存在时那次写会静默丢掉。
- `PlaybackRequest.Bili` 补 `author` 与 `durationSec`，让回调一处写全，然后**删掉 VM 里那三处手动 `recordPlay`**。不补字段的话回调只能写残缺行，反而不如现状。

#### 搜索结果行的时长口径

搜索行直接显示接口原样的 `mm:ss`（分钟不进位，如 `667:37`），而歌单与播放页走的是 `h:mm:ss`。改成过一遍现成的 `parseDurationText`（`SearchInput.kt`）+ `formatDuration`（`Formatting.kt:9`）。
**边界**：解析失败返回 0 时**回退显示原文**，不要把无法解析的时长显示成 `00:00`。

#### 全屏入口重做

c006079 把全屏整个移除了（手势有缺陷），但《架构设计篇》§7.2 的 overlay 方案保留、`PresentationMode.FULLSCREEN` 在 core 里也留着且有单测——UI 层重新接上即可。

- **入口改成显式按钮**（看模式下视频区角落），**不再用 `detectVerticalDragGestures`**——那正是当初的缺陷来源。
- 结构照 §7.2：`AppShell.kt:75` 的 `Box` 里加 `FullscreenPlayerOverlay` 分支，Scaffold 原样不动。不走「把底部导航逐个隐掉」那条路。
- **SurfaceView 单父容器约束必须正面处理**：`PlayerSurface.kt:20` 的 factory 虽会先 `removeView`，但全屏与内联同时渲染时会抢父容器。做法是全屏时让内联那份**不渲染** `PlayerSurface`（`PlayerContainer.kt:253` 的分支加条件），保证同一时刻只有一个宿主。c006079 的提交说明把这个隐患写得很清楚，重做时不能装作没看见。
- 返回键恢复分级：FULLSCREEN → EXPANDED → COLLAPSED（`AppShell.kt:71` 的 `BackHandler`）。
- 沉浸式由 overlay 自己在进入/退出时开关系统栏。

### 8.7 建议的落地顺序

分三段，每段自成一个可提交、可验的整体：

1. **M8-A 补完**（队列历史 + 时长口径）——改动小、风险低，先把真机暴露的缺口修掉。
2. **M8-B 设置与音质**（§8.2–8.5）——主体。`SettingsStore` 先落地，其余都挂在它上面。
3. **M8-C 全屏**（§8.6 第三项）——UI 层独立，放最后，出问题不影响前两段。

### 8.8 验收判据

> ✅ **已验收**（2026-08-26，模拟器 API 35）：D-1 ~ D-7、D-9 八条实测通过；
> D-8 因搜索接口被环境限流只有单测，D-10 需大会员 + 特定机型未验证。
> 详见 [M8 验收记录](验收记录/M8-2026-08-26.md)。

#### 自动化

```bash
./gradlew test                        # 新增纯函数全部要有覆盖
./gradlew connectedDebugAndroidTest   # DB 未变更，应保持通过
./gradlew installDebug
```

新增单测清单：`resolveEffectiveQuality`（含倍速 > 1.5× 回退）、`loudnessGain`（无数据 / clamp 上界）、`resolveVideoGate`、`parseDurationText` 的脏数据回退。

#### 手动判据（真机）

| # | 判据 | 说明 |
| --- | --- | --- |
| D-1 | 设置页四组均可用，改完杀进程重开仍生效 | 验 `SettingsStore` 落库 |
| D-2 | 移动网络下切「看」被拦，且「仍要看」可一次性放行 | 放行**不得**写回设置 |
| D-3 | 「记住上次」开启后，起播自动进看模式；关闭时守默认只听 | 产品身份的兜底 |
| ★ D-4 | 选 Hi-Res 后播放页显示**实际生效**音质（大会员账号） | **本里程碑最硬的判据**，验死参数是否真打通 |
| ★ D-5 | Hi-Res 播放中切到 2.0× → 自动回退 AAC 并给出原因 | §4.4 边界 1 |
| D-6 | 开关响度均衡，音量有可感知变化；无响度数据的视频不受影响 | 验 clamp 与 0.0 分支 |
| ★ D-7 | 队列自动推进后的曲目**出现在「最近播放」** | 验 `trackStartRecorder` |
| D-8 | 搜索结果时长为 `h:mm:ss`，无法解析的仍显示原文 | |
| D-9 | 全屏进出画面不闪断，返回键逐级收回 | 验 SurfaceView 单宿主 |
| ⏸ D-10 | Hi-Res FLAC 在真机不崩（`InsufficientCapacityException`） | 需大会员 + 特定机型，合入时大概率**未验证** |

#### 与真机补测的交集

Part B 的 B6（步骤 21–27）与本里程碑并行进行，两处有交集：

- **步骤 26 Hi-Res** 要等 M8-B 落地后才有意义——现在没有任何 UI 能请求 Hi-Res。
- **步骤 25 流量对比**正好顺带验 D-2：移动网络下看模式应被拦。

### 8.9 收尾

1. ✅ 《功能选型篇》P1 清单勾掉：音质选择 / 响度均衡 / 流量策略 / 默认模式设置。
2. ✅ 《架构设计篇》§4.4 与 §7.2 去掉「当前未启用」标注。
3. ✅ 《手动测试指南》补设置页、音质与全屏三个步骤（21–23），真机独有项顺延到 24–30。
4. ✅ 产出 [`docs/验收记录/M8-2026-08-26.md`](验收记录/M8-2026-08-26.md)。

**留给下一阶段的两条尾巴**：无损音质的正向命中与 FLAC 解码兼容都需要大会员账号；
搜索结果时长口径（D-8）只有单测，等真机上顺手看一眼。
