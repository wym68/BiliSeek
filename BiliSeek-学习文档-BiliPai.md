# 参考项目学习文档 · BiliPai

> 版本：v1.0（读码实录）
> 日期：2026-08-23
> 方法：通过 GitHub API 拉取完整文件树 + 读取关键源文件，非二手资料
> 定位：BiliPai 是 BiliSeek **播放内核与网络接入层的主要代码来源**（GPLv3，可直接复用 Kotlin）

---

## 1. 事实卡片

| 项目 | 实测值 |
| --- | --- |
| 仓库 | `jay3-yy/BiliPai`，默认分支 `main` |
| 授权 | **GPL-3.0**（传染性 copyleft） |
| 语言 | Kotlin |
| 规模 | 2944 个文件；`app/src/main/java/` 下 **1129 个 .kt** |
| 包名 | `com.android.purebilibili`（项目前身为 PureBilibili，改名未改包） |
| 活跃度 | 4317 star，最后推送 2026-08-23（本文写作当天，仍在高频迭代） |
| 官方文档 | `docs/wiki/`（ARCHITECTURE / FEATURE_MATRIX / ROADMAP + 完整 ui-design 体系） |

### 构建基线（摘自其 `docs/wiki/ARCHITECTURE.md`，2026-08-15 校对）

| 项 | 值 |
| --- | --- |
| SDK | **minSdk 26、targetSdk 35、compileSdk 37**，仅 `arm64-v8a` |
| 工具链 | AGP 9.3.1、Gradle 9.5、Kotlin 2.4、JDK 21 |
| 媒体 | **Media3 1.10.1**、DASH/HLS、MediaSession |
| UI | Compose BOM 2026.06.00、Material3 1.5.0-alpha25、Navigation3 1.2.0-alpha07 |
| 第三方视觉 | Miuix（小米风组件）、Haze（模糊）、Compose Cupertino |

> 这条直接回答了 BiliSeek 待决问题 5：**minSdk 26 / targetSdk 35 是被验证可行的组合**，与我们的建议一致。

---

## 2. 模块结构：**「模块化」是假象，务必修正预期**

选型文档原先写「BiliPai 是模块化结构，利于只摘取需要的播放/网络模块」。实测结论相反：

```
app/                    2544 文件   ← 几乎所有产品逻辑都在这里
design-system/           149 文件
docs/                     60 文件
danmaku-engine/           52 文件
plugins/                  34 文件（示例插件）
dolby-ffmpeg-decoder/     12 文件
plugin-sdk/                7 文件
network-core/              5 文件   ← 只有 3 个 policy 类！
settings-core/             3 文件   ← 只有 1 个 policy 类！
```

`network-core/` 的全部内容：

```
policy/HomeFeedAnonymizerPolicy.kt     首页推荐匿名化
policy/NetworkFallbackPolicy.kt        网络回退
policy/NetworkProxyPolicy.kt           代理策略
```

**真正的网络层在 `app/core/network/`，真正的播放器在 `app/feature/video/`。** 抽出的独立模块只是几个可单测的纯函数策略类，不是可移植的功能模块。

### app 内部分层（`app/src/main/java/com/android/purebilibili/`）

| 包 | .kt 数 | 备注 |
| --- | --- | --- |
| `feature/video` | **272** | 播放器主战场 |
| `feature/home` | 109 | BiliSeek 不需要 |
| `feature/settings` | 90 | |
| `feature/dynamic` | 54 | 动态，不需要 |
| `core/ui` | 49 | |
| `data/model` | 47 | **需要**（DASH/PlayUrl/ViewInfo 数据模型） |
| `data/repository` | 45 | **部分需要** |
| `feature/download` | 29 | **需要**（音频下载） |
| `core/store` | 21 | TokenManager / SettingsManager |
| `feature/audio` | **19** | **听视频模式 + 歌词系统** |
| `feature/search` | 19 | **需要** |
| `feature/login` | 12 | **需要** |
| `core/network` | 11 | **需要**（WBI / 签名 / 拦截器） |
| `core/player` | 4 | Hi-Res 渲染器、媒体缓存 |
| `domain/usecase` | 2 | 几乎没用上 domain 层 |

---

## 3. 关键实现拆解

### 3.1 DASH 取流：**自己拼本地 MPD**

`feature/video/playback/dash/LocalDashManifestBuilder.kt`（3.3 KB）

B 站 playurl 返回的是 DASH 的 JSON 而非标准 MPD。BiliPai 的做法是**用字符串拼一份标准 MPD 喂给 Media3**：

```kotlin
fun buildLocalDashManifest(
    durationMs: Long, minBufferTimeMs: Long,
    videoTracks: List<DashVideo>, audioTracks: List<DashAudio>
): String
```

- 视频轨和音频轨各成一个 `<AdaptationSet contentType="video|audio">`
- 每条流一个 `<Representation>`，内含 `<BaseURL>` 和 `<SegmentBase indexRange=... ><Initialization range=.../></SegmentBase>`
- 手写 `escapeXml()`（B 站 URL 里有 `&`，不转义会崩）
- 时长格式 `PT%.3fS`

产物封装成 `AdaptiveDashPlaybackSource(manifest, videoTracks, audioTracks, playbackQualityMode)`。

> **对 BiliSeek 的意义**：这是「音视频同轴」的地基。因为音视频在**同一个 manifest、同一个 MediaSource** 里，切换看/听才可能不重建播放器。

### 3.2 听 ⇄ 看切换：`setTrackTypeDisabled`，而且**它已经写好了**

`feature/video/player/MiniPlayerManager.kt`（105 KB / 2752 行）第 332 行：

```kotlin
internal fun resolveTrackSelectionParametersForBackground(
    currentTrackSelectionParameters: TrackSelectionParameters,
    shouldDisableVideoTrack: Boolean
): TrackSelectionParameters {
    if (!shouldDisableVideoTrack) return currentTrackSelectionParameters
    return currentTrackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .setMaxVideoSize(0, 0)
        .build()
}
```

配套的一整套策略纯函数（全部 `internal fun shouldXxx(...)`，可单测）：

| 函数 | 作用 |
| --- | --- |
| `shouldDisableVideoTrackOnEnterBackground` | 进后台是否关视频轨 |
| `shouldClearVideoSurfaceOnEnterBackground` | 关轨后清 surface（注释：「关视频轨后一律清 surface」） |
| `shouldTrimDanmakuCachesOnEnterBackground` | 关轨时顺带清弹幕缓存 |
| `shouldRefreshVideoFrameOnEnterForeground` | 回前台恢复画面 |
| `shouldKickPlaybackAfterForegroundTrackRestore` | 恢复轨道后是否需要踢一脚播放 |
| `shouldRetainBackgroundAudioSession` | 保留后台音频会话 |
| `shouldPauseBufferingOnEnterBackground` | 后台是否暂停缓冲 |

**这套东西就是 BiliSeek「锁屏/切后台自动降回纯音频」的成品实现**，而且它同时也是「一键切看」的机制——只要在前台把 `shouldDisableVideoTrack` 翻转成用户控制的 mode 即可。

> **重要结论（供选型文档参考）**：我们把「无缝切换」降级为「允许短暂缓冲」是个保守决定，但从代码看，**无缝几乎是免费的**——前提是听模式的 manifest 里也包含视频轨。禁用轨道后 ExoPlayer 不会下载视频分片，省流量目标不受影响；代价仅仅是解析时多保留一份视频流元数据。是否要把这条捡回来，值得再决策一次。

### 3.3 后台播放与 PiP：**冲突已被解决**

我们此前担心「切后台降音频」会和 PiP 打架。BiliPai 的解法（`MiniPlayerManager.kt` 第 88–135 行）：

```kotlin
internal fun shouldKeepPlaybackForPipTransition(
    isSystemPipActive: Boolean, hasPendingPlaybackRoutePip: Boolean
): Boolean = isSystemPipActive || hasPendingPlaybackRoutePip

internal fun shouldContinueBackgroundAudioByPolicy(
    backgroundPlaybackEnabled: Boolean,
    mode: SettingsManager.MiniPlayerMode,
    isActive: Boolean,
    isLeavingByNavigation: Boolean,
    stopPlaybackOnExit: Boolean,
    shouldKeepPlaybackForPipTransition: Boolean = false
): Boolean { ... }
```

`MiniPlayerMode` 是四态枚举：`OFF / IN_APP_ONLY / SYSTEM_PIP / IN_APP_AND_SYSTEM_PIP`。当模式含 PiP 且 `shouldKeepPlaybackForPipTransition` 为真时，**不走后台音频降级路径**——正是我们建议的「PiP 属于可见，不降级」。

### 3.4 前台服务

`feature/video/player/PlaybackService.kt`（6.7 KB）：`MediaSessionService` 子类，`onGetSession` 直接返回 `MiniPlayerManager` 持有的 session。

```kotlin
ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
```

带 fallback 通知（拿不到主通知时先起一个「正在准备播放控件」），避免 Android 14+ 前台服务超时崩溃。

### 3.5 音质选择：Hi-Res / 杜比的真实形态

`feature/video/playback/audio/AudioStreamSelectionPolicy.kt`（7.5 KB）—— 证实了三条独立来源：

```kotlin
dash.audio          // 普通 AAC 数组
dash.dolby?.audio   // 杜比全景声，需 type > 0 且 codecs 含 ec-3/e-ac-3/eac3
dash.flac?.audio    // Hi-Res 无损，单个对象不是数组
```

音质 ID 常量（`AdaptiveDashTrackPolicy.kt`）：

```kotlin
private const val DOLBY_AUDIO_QUALITY_ID  = 30250
private const val HI_RES_AUDIO_QUALITY_ID = 30251
```

**一个意外的坑**：倍速 > 1.5x 时必须放弃 Hi-Res/杜比，回退普通 AAC：

```kotlin
const val PREMIUM_AUDIO_SPEED_COMPATIBILITY_THRESHOLD = 1.5f
fun resolveSpeedCompatibleAudioQualityPreference(...): Int {
    if (playbackSpeed <= speedThreshold) return requestedAudioQuality
    return if (isSpeedSensitivePremiumAudioQuality(requestedAudioQuality)) -1 else requestedAudioQuality
}
```

回退时会给出 `AudioFallbackReason`（`NO_PLAYABLE_AUDIO` / `SPEED_INCOMPATIBLE` / `REQUESTED_UNAVAILABLE`），用于 UI 提示。

### 3.6 Hi-Res FLAC 的设备兼容坑

`core/player/HiResCompatibleRenderersFactory.kt`（3.9 KB），注释写得很清楚：

> 某些设备会给 `c2.android.flac.decoder` 分配 32 KiB 输入缓冲，但 B 站 Hi-Res 音轨的单帧可能超过该大小，最终触发 Media3 `InsufficientCapacityException`。

解法：继承 `DefaultRenderersFactory`，替换 `MediaCodecAudioRenderer`，override `getCodecMaxInputSize` 把 FLAC 的最小输入缓冲抬到 1 MiB。

> 这种「别人踩过的设备坑」是直接复用最大的价值所在——自研根本想不到。

### 3.7 WBI 签名（BiliSeek MVP 已列为直接复用项）

`core/network/WbiUtils.kt`（3.8 KB）——标准实现：

- 64 位 `mixinKeyEncTab` 打乱表，`getMixinKey(imgKey + subKey)` 取前 32 位
- 过滤非法字符 `[!'()*]`
- `encodeURIComponent`：`URLEncoder` 后把 `+`→`%20`、`*`→`%2A`、`%7E`→`~`
- 参数按 key 排序拼 query，末尾接 mixinKey，MD5 得 `w_rid`
- 返回**未编码**的 Map 交给 Retrofit（编码只用于算 hash）

一个值得学的细节——风控指纹参数**默认不注入**：

```kotlin
// WBI 的额外风控指纹参数不再默认注入，避免污染像 playurl 这类
// 对参数更敏感的接口；需要时由调用方显式开启。
fun sign(params, imgKey, subKey, includeRiskFingerprint: Boolean = false)
```

`dm_img_list` / `dm_img_str` / `dm_cover_img_str` / `dm_img_inter` 这四个 WebGL 指纹参数是硬编码的固定值。

`core/network/WbiKeyManager.kt`（6.6 KB）：从 `nav` 接口取 `wbi_img.img_url` / `sub_url` 的文件名部分作为 key；24 小时缓存 + 剩余不足 1 小时预刷新 + `Mutex` 双重检查防并发刷新 + SharedPreferences 持久化。

### 3.8 buvid3 与 Cookie 策略

`core/network/ApiClient.kt`（133 KB）内的 `AppSessionCookieJar`：

```kotlin
var buvid3 = TokenManager.buvid3Cache
if (buvid3.isNullOrEmpty()) {
    buvid3 = UUID.randomUUID().toString() + "infoc"   // 本地生成，非服务端下发
    TokenManager.buvid3Cache = buvid3
}
```

同时也有 `activateBuvid` 接口调用与 `b_3`/`b_4` 字段（服务端 spi 下发）。搜索类接口统一带 `Referer: https://search.bilibili.com/`。

另有 `PlaybackAccountCookieJar`——**播放请求用独立的账号 CookieJar**，与普通请求隔离。

### 3.9 登录：默认走 **TV 端扫码**，不是 Web 扫码

`feature/login/LoginViewModel.kt`（46.9 KB）：

```kotlin
fun loadQrCode() { loadTvQrCode() }   // 入口直接指向 TV
```

两套并存：
- **TV 扫码**（默认）：`generateTvQrCode` / `pollTvQrCode`，参数用 `AppSignUtils.signForTvLogin` 签名，返回 `access_token`，标记 `hasHighQualityCredential`
- **Web 扫码**：`generateQrCode` / `pollQrCode`，从 Set-Cookie 里手工抠 `SESSDATA` 和 `bili_jct`

轮询状态码：`86090` 已扫未确认 / `86038` 二维码失效 / `86101` 未扫描。

`core/network/AppSignUtils.kt` 里三组 appkey/appsec 明文硬编码（TV 云视听小电视 / Android / Android HD），并注明「通过某一组 APPKEY 获取的 access_token，之后的 API 调用也必须用同一组」。

> **对 BiliSeek 的意义**：我们已定「MVP 仅扫码」。但要注意这里有两个「扫码」——**TV 扫码拿到的是 access_token（权限更高、可解锁部分高码率），Web 扫码拿到的是纯 Cookie**。选型时需要明确用哪个，或两个都保留。TV 端签名参数固定，风控画像与真实 TV 设备有差异，长期使用的风险需要观察。

`feature/login/LoginRiskPolicy.kt` 处理密码登录的安全中心校验（`code=0` 但 `data.status=2` 时需短信二次验证）——BiliSeek 不做密码登录，可以整个跳过。

### 3.10 音频/听视频模式的现成外壳

`feature/audio/` 只有 19 个文件，但覆盖面出乎意料：

```
library/ListenVideoLibrary{DataSource,Models,Policy}.kt   听视频库
lyrics/{LyricsRepository,LyricsParser,LyricsMatchingPolicy,
        LyricsNetworkProviders,LyricsTimelinePolicy,FileLyricsCache}.kt
player/MusicPlaybackContract.kt
screen/{ListenVideoScreen,MusicDetailScreen,MusicPlayerContent}.kt
viewmodel/{ListenVideoViewModel,MusicViewModel}.kt
```

**BiliPai 已经有完整的歌词系统**（解析、时间轴、网络源匹配、本地缓存）。选型文档把「歌词智能匹配」列为「从 BBPlayer 参考重写 / P2」——实际上 BiliPai 这边就有 Kotlin 现成的。

`MusicPlaybackContract.kt` 的播放源建模值得抄：

```kotlin
sealed interface MusicPlaybackSource {
    data class AudioSong(val sid: Long)                              // au 音频区
    data class VideoAudio(val bvid: String, val cid: Long, ...)      // 视频抽音频
}
```

`resolveMusicPlaybackOwner()` 明确规定两类源分别由 `MINI_PLAYER_MANAGER` 还是 `PLAYER_VIEW_MODEL` 持有播放器——**播放器 owner 归属是这类 App 最容易出乱子的地方**，值得照搬这个显式约定。

---

## 4. 代码质量：值得学的与必须避开的

### ✅ 值得学：`XxxPolicy.kt` 纯函数 + 单测

全项目大量 `internal fun shouldXxx(...)` / `resolveXxx(...)` 无副作用纯函数，从 ViewModel 里剥出来单独放 `*Policy.kt`，配 `*PolicyTest.kt`。决策逻辑因此可测、可读、可复用。**BiliSeek 应当直接沿用这个约定。**

### ❌ 必须避开：God Object

| 文件 | 体积 | 行数 |
| --- | --- | --- |
| `feature/video/viewmodel/VideoPlaybackViewModel.kt` | **378 KB** | **8663** |
| `core/network/ApiClient.kt` | 133 KB | — |
| `feature/video/player/MiniPlayerManager.kt` | 105 KB | 2752 |
| `feature/search/SearchViewModel.kt` | 64 KB | — |
| `feature/login/LoginViewModel.kt` | 47 KB | — |

`VideoPlaybackViewModel` 里甚至混着「保存封面到相册」「下载音频」「加载 UP 主粉丝数」「加载视频 tag」这类完全无关的逻辑，还直接写全限定名调用 `com.android.purebilibili.feature.download.DownloadManager`。

> **这直接回答了 BiliSeek 待决问题 3（摘模块边界）**：见下节。

---

## 5. 对 BiliSeek 的取用清单

### 5.1 可以「近乎原样搬」的（低耦合、纯逻辑）

| 文件 | 用途 |
| --- | --- |
| `core/network/WbiUtils.kt` | WBI 签名（MVP 接入前提） |
| `core/network/WbiKeyManager.kt` | WBI 密钥缓存/刷新 |
| `core/network/AppSignUtils.kt` | TV/APP 端签名（若做 TV 扫码） |
| `feature/video/playback/dash/LocalDashManifestBuilder.kt` | 本地 MPD 拼装 |
| `feature/video/playback/policy/AdaptiveDashTrackPolicy.kt` | 轨道筛选/编码偏好 |
| `feature/video/playback/audio/AudioStream*.kt` | 音质选择与回退 |
| `core/player/HiResCompatibleRenderersFactory.kt` | FLAC 解码兼容 |
| `MiniPlayerManager.kt` 里的 `shouldXxx` 纯函数群 | 后台/PiP/切轨策略（**只摘函数，不摘类**） |
| `feature/video/player/PlaybackService.kt` | MediaSessionService 骨架 |
| `data/model/response/` 下的 DASH/PlayUrl 模型 | 数据结构 |

### 5.2 需要「摘思路、重写实现」的

| 目标 | 原因 |
| --- | --- |
| 播放器状态机 | 原实现埋在 8663 行的 ViewModel 里，无法剥离 |
| `MiniPlayerManager` 的类本体 | 2752 行、单例、和通知/媒体会话/PiP/播放列表全绑在一起 |
| `ApiClient` | 133 KB，包含 BiliSeek 完全不需要的动态/直播/番剧/专栏接口 |
| 登录 UI 与流程编排 | `LoginViewModel` 含密码/短信/极验/安全中心，我们只要扫码 |

### 5.3 明确不取

动态、直播、番剧、专栏、消息、稍后再看、Anime4K、投屏、插件系统、Miuix 视觉体系、Navigation3 整卡转场（61 个 NavKey 的复杂度对 BiliSeek 是负担）。

### 5.4 摘码边界结论（回答待决问题 3）

> **不要 fork 后做减法。** `app` 模块 2544 个文件、核心逻辑集中在几个 10 万字节级 God Object 里，做减法的成本高于重写。
>
> **正确姿势**：新建干净工程，按上表 5.1 **逐文件复制纯逻辑**（这些文件依赖极浅，多数只依赖 `data.model` 和 kotlin stdlib），播放器/UI 层按 BiliSeek 自己的状态机重写，复用其 `*Policy` 纯函数作为决策依据。

---

## 6. 授权义务（GPLv3）

- 复用其任何源文件 → **BiliSeek 整体必须 GPLv3**（选型文档已确认）
- 每个复制过来的文件保留原版权头，并在文件顶部注明来源与修改
- `NOTICE` / README 致谢并链接 `https://github.com/jay3-yy/BiliPai`
- 发布 APK 必须同时提供对应源码入口
- 注意：`dolby-ffmpeg-decoder` 模块涉及 FFmpeg，若取用需另行核对 FFmpeg 的 LGPL/GPL 分支与杜比解码的专利问题——**BiliSeek 建议整个跳过杜比**

---

## 7. 对现有选型文档的修正建议

| # | 现文档表述 | 实测情况 | 建议 |
| --- | --- | --- | --- |
| 1 | 「BiliPai 是模块化结构，利于只摘取播放/网络模块」 | `network-core` 仅 3 个策略类，真正代码全在 `app` | 改写为「模块拆分很浅，需逐文件摘取纯逻辑，播放器层重写」 |
| 2 | 「一键切看」不强求无缝 | `setTrackTypeDisabled` 已实现且无缝 | 可考虑把「无缝」捡回来，成本极低 |
| 3 | PiP（P1）与自动降音频（MVP）可能冲突 | 已有 `shouldKeepPlaybackForPipTransition` 解法 | 直接采用其判定，冲突不存在 |
| 4 | 歌词系统列为「从 BBPlayer 参考重写」 | BiliPai 有完整 Kotlin 歌词系统 | 改为可从 BiliPai 复用 |
| 5 | 待决问题 5「最低 Android 版本」 | BiliPai 用 minSdk 26 / targetSdk 35 | 可定案 |
| 6 | 待决问题 6「登录沿用 BiliPai 实现」 | 有 TV 扫码和 Web 扫码两套，默认 TV | 需明确选哪套 |
