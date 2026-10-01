# 参考项目学习文档 · BBPlayer

> 🗄️ **历史文档（已归档、冻结）。** 本文记录的是写作当时的设计/计划，正文不再随代码更新；文中类名、文件路径、行号、数量以当前代码为准。现状请看 [架构设计篇](../03-架构设计篇.md)、待办看 [功能优化篇 §10](../05-功能优化篇.md)。

> 版本：v1.0（读码实录）
> 日期：2026-08-23
> 方法：通过 GitHub API 拉取完整文件树 + 读取关键源文件，非二手资料
> 定位：BiliSeek **音频外壳与信息架构的主要参考**；**但其播放引擎是原生 Kotlin，可直接复用**

---

## 0. 一句话结论（本文最重要的发现）

选型文档写「BBPlayer（TS）只能参考、在 Kotlin 中重写」。**这句话对最值钱的部分是错的。**

BBPlayer 的音频引擎 `packages/orpheus` 是一个**自研的 Expo 原生模块，约 100 个 Kotlin 文件，基于 Media3 `MediaLibraryService` + ExoPlayer**，涵盖播放服务、B 站取流、WBI 签名、SPL 歌词、桌面歌词/状态栏歌词、下载导出 m4a、响度均衡、睡眠定时。整个仓库 **MIT 授权**。

也就是说：**BiliSeek 可以直接把这些 Kotlin 文件搬进来**（MIT 与 GPLv3 兼容，保留原版权声明即可），而不是「读思路重写」。这大幅降低 P1/P2 阶段的成本。

真正只能「参考重写」的，是 TypeScript 那半边——信息架构、页面组织、歌单/库的数据建模。

---

## 1. 事实卡片

| 项目 | 实测值 |
| --- | --- |
| 仓库 | `bbplayer-app/BBPlayer`，默认分支 **`dev`**（不是 main） |
| 授权 | **MIT**（`Copyright (c) 2025 Roitium.`） |
| 主语言 | TypeScript（GitHub 统计口径），实际含大量 Kotlin |
| 规模 | 880 个文件 |
| 活跃度 | 844 star，最后推送 2026-08-23 |
| 官网/文档 | https://bbplayer.roitium.com ，仓库内 `apps/docs`（VitePress），含 SPL 规范 |
| 自我定位 | 「本地优先的 Bilibili 音频播放器」 |

---

## 2. 仓库结构：pnpm monorepo

```
apps/       460 文件
  mobile/       Expo + React Native 主应用
  backend/      Cloudflare Worker（wrangler + drizzle）
  docs/         VitePress 文档站（含 SPL.md 规范）
packages/   302 文件
  orpheus/                  130   ★ 自研原生播放模块（Kotlin）
  react-native-bottom-tabs/  62   原生底栏
  expo-wavy-slider/          30   波浪进度条
  logs/                      17
  splash/                    17
  image-theme-colors/        15   封面取色
  heatmap/                   10   播放热力图
  bottom-tabs-react-navigation/ 9
  native/                     9
  eslint-plugin/              3
```

> 注意 `apps/backend` 的存在：BBPlayer 有一个**自建后端**（Cloudflare Worker + D1/drizzle），用于歌单分享、统计上报、更新检查。**BiliSeek 不应引入后端**——纯本地是我们「轻量、纯净」定位的一部分，一旦有服务端就要处理隐私政策、成本和可用性。BBPlayer 为此专门写了 `PRIVACY.md`。

---

## 3. `packages/orpheus` —— 可直接复用的 Kotlin 播放引擎

路径：`packages/orpheus/android/src/main/java/expo/modules/orpheus/`

### 3.1 文件清单（按体积排序，节选）

| 文件 | 大小 | 内容 |
| --- | --- | --- |
| `ExpoOrpheusModule.kt` | 55 KB | JS ↔ 原生桥接层（**BiliSeek 不需要，这是 RN 特有的**） |
| `service/OrpheusMusicService.kt` | 28 KB | **播放服务主体**，`MediaLibraryService` |
| `manager/FloatingLyricsManager.kt` | 17 KB | 桌面歌词（悬浮窗） |
| `util/ExportDownloadsHelper.kt` | 15 KB | **导出 m4a（封面/元数据/内嵌歌词）** |
| `util/GeneralStorage.kt` | 9.8 KB | 设置存储 |
| `manager/LyriconBackend.kt` | 8.2 KB | Lyricon 歌词协议后端 |
| `view/LyricView.kt` | 7.9 KB | 歌词视图 |
| `manager/LyricsRuntimeConsumer.kt` | 7.6 KB | 歌词运行时消费 |
| `manager/MeizuStatusBarLyricsBackend.kt` | 7.1 KB | 魅族状态栏歌词 |
| `util/DownloadUtil.kt` | 6.9 KB | 下载工具 |
| `manager/CoverDownloadManager.kt` | 6.1 KB | 封面下载 |
| `service/ShuffleManager.kt` | 5.7 KB | 随机播放 |
| `bilibili/BilibiliRepository.kt` | 4.8 KB | **B 站取流** |
| `manager/UnifiedLyricsManager.kt` | 3.8 KB | 歌词后端统一调度 |
| `manager/SpectrumManager.kt` | 3.5 KB | 频谱可视化 |
| `manager/StatusBarLyricsManager.kt` | 2.8 KB | 状态栏歌词 |
| `service/OrpheusDownloadService.kt` | 2.5 KB | 下载前台服务 |
| `manager/LyricsTimeline.kt` | 2.5 KB | 歌词时间轴 |
| `bilibili/WbiUtil.kt` | 2.4 KB | **WBI 签名** |
| `manager/SuperLyricBackend.kt` | 2.3 KB | SuperLyric（Xposed 系）后端 |
| `manager/CachedUriManager.kt` | 2.3 KB | **播放 URL 缓存** |
| `util/SplConverter.kt` | 2.3 KB | **SPL → LRC 转换** |
| `bilibili/{BilibiliApi,BilibiliModels,NetworkModule}.kt` | — | Retrofit 接口与模型 |
| `util/{LoudnessStorage,Volume}.kt` | — | **响度均衡** |
| `util/SleepTimeController.kt` | — | 睡眠定时 |
| `util/CustomCommands.kt` | — | MediaSession 自定义指令 |
| `util/DirectoryPickerContract.kt` | — | SAF 目录选择 |
| `model/{LyricsModels,TrackRecord}.kt` | — | 数据模型 |
| `network/OkHttpClientManager.kt` | — | OkHttp 装配 |

### 3.2 播放服务

`service/OrpheusMusicService.kt` 关键装配：

```kotlin
class OrpheusMusicService : MediaLibraryService() {
    val mediaSourceFactory = DefaultMediaSourceFactory(this)
    player = ExoPlayer.Builder(this, renderersFactory)
        .setAudioAttributes(...)
        .setHandleAudioBecomingNoisy(true)   // 拔耳机自动暂停
        ...
}
```

注意它用的是 **`MediaLibraryService`** 而不是 BiliPai 的 `MediaSessionService`。前者多支持一层「媒体库浏览」（Android Auto / 系统媒体浏览器可以进来翻歌单）。

> **对 BiliSeek 的取舍**：BiliSeek 是「搜索驱动、无推荐」，媒体库浏览的价值不大，且 `MediaLibraryService` 要求实现 `onGetLibraryRoot` / `onGetChildren` 一套回调。**MVP 建议跟 BiliPai 走 `MediaSessionService`**，等确有 Android Auto 需求再升级。

### 3.3 响度均衡（BiliSeek 选型文档漏掉的功能）

```kotlin
val volumeData = LoudnessStorage.getLoudnessData(uri)
val isLoudnessNormalizationEnabled = GeneralStorage.isLoudnessNormalizationEnabled()
val gain = if (measuredI == 0.0) 1.0f else calculateLoudnessGain(measuredI, target)
```

响度数据的来源很巧妙——**B 站 playurl 接口本身就返回 `volume` 字段**（见下节 `resolveAudioUrl` 的返回值 `Pair<String, VolumeData?>`），里面带 `measured_i`（EBU R128 积分响度）等测量值，不需要自己分析音频。

> 对一个「把 B 站当音频库来听」的 App，响度均衡是刚需——B 站投稿音量参差极大。**建议 BiliSeek 补进 P1**，实现成本极低（数据是白送的）。

### 3.4 B 站取流：比 BiliPai 简单得多的路径

`bilibili/BilibiliRepository.kt` —— 因为只听不看，它**不拼 MPD，直接拿 URL**：

```kotlin
fun resolveAudioUrl(
    bvid: String, cid: Long?, audioQuality: Int,
    enableDolby: Boolean, enableHiRes: Boolean, cookie: String?
): Pair<String, VolumeData?> {
    val rawParams = mapOf(
        "bvid" to bvid, "cid" to cidInternal,
        "fnval" to 4048,        // DASH + 8K + HDR + 杜比 + Hi-Res 位掩码
        "fnver" to 0,
        "fourk" to 1,
        "qlt" to audioQuality,
        "voice_balance" to 1    // 请求响度均衡数据
    )
    val signedParams = WbiUtil.sign(rawParams, imgKey, subKey)
    ...
    // 优先级：dolby → flac(Hi-Res) → 精确匹配 id → dash.audio[0]
    // dash 缺失时兜底 durl[0].url
}
```

要点：

1. **`fnval = 4048`** —— 这个魔数决定服务端返回哪些流。BiliPai 侧散落在 ApiClient 里，这里一目了然。
2. **`voice_balance = 1`** —— 显式索取响度数据。
3. **降级链完整**：`dash` 为空时回退 `durl`（老式单文件 MP4），BiliSeek 也应保留这条兜底。
4. `cid` 可空，为空时先调 `getPageList` 取 `data[0].cid`（即第一个分 P）。

### 3.5 WBI 密钥缓存策略：比 BiliPai 更省事

```kotlin
private fun getTodayDateStr() = SimpleDateFormat("yyyyMMdd", ...).format(Date())

@Synchronized
private fun getWbiKeys(): Pair<String, String> {
    if (cachedImgKey != null && today == cachedDateStr) return ...   // 按自然日缓存
    val response = api.getNavInfo().execute()
    ...
}
```

**按自然日缓存**（B 站的 WBI key 本来就是每日轮换），比 BiliPai 的「24 小时 + 预刷新阈值 + Mutex 双检」简单得多，效果等价。

> **建议 BiliSeek 采用这个更简单的版本**，但保留 BiliPai 的持久化（冷启动不必先打一次 nav）。

### 3.6 播放 URL 缓存

`manager/CachedUriManager.kt` —— 正是我们之前建议的「缓存 playurl 响应，切模式时不重新请求」。B 站播放 URL 有有效期（约 2 小时），这个管理器负责缓存与失效重取。

### 3.7 SPL 逐字歌词

BBPlayer 自己定了一套 **SPL（Salt Player Lyrics）规范**，文档在 `apps/docs/docs/SPL.md`，是 LRC 的超集。`util/SplConverter.kt` 做 SPL → 标准 LRC 降级：

```kotlin
// 行首 [mm:ss.ms] 块  → 保留（标准 LRC 行时间戳，支持重复行）
// 行体 [mm:ss.ms] / <mm:ss.ms> → 剥离（逐字时间戳）
// 元数据行 [ti:Title] → 原样保留（只剥 <...> 形式，避免误伤）
private val LEADING_TIMESTAMPS_REGEX = Regex("^(?:\\[\\d{1,3}:\\d{1,2}\\.\\d{1,6}\\])+")
private val INLINE_TIMESTAMP_REGEX = Regex("(?:\\[...\\])|(?:<...>)")
```

支持两种逐字写法（`[mm:ss.ms]` 与 `<mm:ss.ms>`），逐字进度 + 罗马音 + 翻译三层。

### 3.8 桌面歌词与状态栏歌词：四套后端

`manager/UnifiedLyricsManager.kt` 统一调度四个后端：

| 后端 | 机制 |
| --- | --- |
| `FloatingLyricsManager` | 自绘悬浮窗（需 `SYSTEM_ALERT_WINDOW` 权限） |
| `StatusBarLyricsManager` | 通用状态栏歌词 |
| `MeizuStatusBarLyricsBackend` | 魅族 Flyme 私有接口 |
| `SuperLyricBackend` | SuperLyric（Xposed 模块）协议 |
| `LyriconBackend` | Lyricon 协议（AIDL 跨进程） |

> ⚠️ **授权待核实**：`packages/orpheus/android/src/main/java/io/github/proify/lyricon/` 下约 30 个 Kotlin 文件 + 6 个 AIDL 文件，**包名不属于 BBPlayer**（`io.github.proify`），是 vendored 的第三方 Lyricon 协议实现。仓库根 LICENSE 是 MIT，但**这部分的原始授权需要单独回溯确认**后才能取用。BiliSeek 若做桌面歌词（P2），务必先查清这一层。

---

## 4. TypeScript 侧：信息架构（这部分才需要重写）

### 4.1 三个底部 Tab

`apps/mobile/src/app/(tabs)/_layout.tsx`：

```
主页 (home)      → index.tsx
音乐库 (bookshelf) → library/[tab].tsx
设置 (cog)        → settings/index.tsx
```

**没有第四个 Tab，没有推荐流。** 与 BiliSeek 定位一致。

### 4.2 主页构成 —— 直接回答 BiliSeek 的「冷启动空状态」问题

`apps/mobile/src/app/(tabs)/index.tsx` 的实际组成：

| 元素 | 来源 |
| --- | --- |
| **搜索框** | `Searchbar`（react-native-paper） |
| **搜索建议 / 历史** | `SearchSuggestions`，`SEARCH_HISTORY_KEY = 'bilibili_search_history'`，`MAX_SEARCH_HISTORY = 10` |
| **最近播放歌单** | `useRecentPlaylists()` |
| **播放热力图** | `WeeklyHeatMap` + `usePlayHistoryHeatmap()`（周视图，类似 GitHub 贡献图） |
| **问候语** | `getGreetingMsg()`（按时段） |
| **当前播放条** | `NowPlayingBar` |
| 用户信息 | `usePersonalInformation()` |

> **这正是我们建议的方案的成品**：无算法推荐，但屏幕不空——全是用户自己的行为数据（搜索历史 / 最近播放 / 播放统计）。**BiliSeek 可以照搬这个组合**，热力图甚至有现成的 `packages/heatmap`（MIT）。

### 4.3 搜索输入的智能路由

`apps/mobile/src/utils/search.ts` 导出 `matchSearchStrategies` / `navigateWithSearchStrategy`。README 确认支持：**BV 号 / AV 号 / b23.tv 短链**直接解析跳转，否则走关键词搜索。

> BiliSeek 的搜索框应当同样支持粘贴链接直达——这是「搜索即入口」体验的一部分。

### 4.4 核心洞察：**一切皆歌单**

看路由表就明白它的数据建模：

```
playlist/local/[id]                        本地歌单
playlist/remote/favorite/[id]              B 站收藏夹
playlist/remote/collection/[id]            订阅合集
playlist/remote/uploader/[mid]             UP 主投稿
playlist/remote/multipage/[bvid]      ★    多 P 视频
playlist/remote/search-result/global/[query]  ★ 全站搜索结果
playlist/remote/search-result/fav/[query]     收藏夹内搜索
playlist/remote/toview                     稍后再看
playlist/recently                          最近播放
```

**搜索结果本身就是一个「远程歌单」，多 P 视频也是一个「远程歌单」。** 所有列表页共用同一套 track list 组件、同一套选择/批量操作、同一套「加入播放队列」逻辑。

> **这直接回答了 BiliSeek 的「分 P / 合集怎么转条目」待决问题**：
> 搜索结果里一个 BV **折叠为一条**；点进去若是多 P，则展开成一个「歌单」页面。既不污染搜索结果，也不丢失分 P 内容。这是被验证过的方案，建议采纳。

### 4.5 lib 分层

```
lib/api/
  bilibili/{api,client,wbi,utils,garb}.ts    B 站（garb = 装扮/主题）
  netease/{api,crypto,request,utils}.ts      网易云（含加密算法）
  qqmusic/api.ts    kugou/api.ts             QQ 音乐 / 酷狗
  bbplayer/client.ts                         自家后端
lib/services/
  playlistService / trackService / artistService
  lyricService                               歌词聚合
  externalPlaylistService                    外部歌单导入
  syncLocalToBilibiliService            ★    本地歌单反向同步到 B 站收藏夹
  analyticsService / updateService / genKey
lib/db/
  schema.ts + db.ts                          Drizzle ORM + expo-sqlite
  migrations/{independentAccount,playHistory,playHistoryMilliseconds,
              sortKeysV2,sortKeysV3,state}.ts
```

两个值得注意的点：

1. **`syncLocalToBilibiliService`** —— 双向同步，本地歌单可以写回 B 站收藏夹。选型文档 4.2 只写了「B 站收藏夹 / 订阅合集同步（登录后拉取）」，是单向的。双向是否要做，值得单独决策（写操作会增加账号风控暴露面）。
2. **6 个数据库迁移文件** —— 说明歌单/排序/播放历史的 schema 演进了很多次（`sortKeysV2` → `sortKeysV3`）。**BiliSeek 从一开始就应该把 schema 迁移机制建起来**，别等到需要时才补。

### 4.6 登录

`app/settings/bilibili-account/`：

```
qrcode-login.tsx                    扫码
phone-login.tsx                     短信
  _components/InputPhoneStep.tsx
  _components/GeetestVerifyStep.tsx  ← 极验验证码
  _components/InputCodeStep.tsx
  _components/SuccessStep.tsx
index.tsx                           （README 提及还支持手动填 Cookie）
```

短信登录被拆成 4 步组件，中间必过极验——**这正好印证了我们把短信登录降为「待定」的判断**：光是验证码这一环就是独立工作量。

---

## 5. 对 BiliSeek 的取用清单

### 5.1 可直接复用的 Kotlin（MIT，仅需保留版权声明）

| 文件 | 用于 BiliSeek 的 | 优先级 |
| --- | --- | --- |
| `bilibili/{BilibiliApi,BilibiliRepository,WbiUtil,NetworkModule,BilibiliModels}.kt` | 取流与签名（可与 BiliPai 版本二选一） | MVP |
| `manager/CachedUriManager.kt` | playurl 缓存 | MVP |
| `util/{LoudnessStorage,Volume}.kt` | 响度均衡 | P1（建议新增） |
| `util/ExportDownloadsHelper.kt`、`util/DownloadUtil.kt`、`service/OrpheusDownloadService.kt`、`manager/DownloadCache.kt` | 下载缓存 + 导出 m4a | P1 |
| `manager/CoverDownloadManager.kt`、`util/GlideBitmapLoader.kt` | 封面 | P1 |
| `util/SleepTimeController.kt` | 睡眠定时 | P1（建议新增） |
| `service/ShuffleManager.kt` | 随机播放 | P1 |
| `util/SplConverter.kt`、`manager/LyricsTimeline.kt`、`model/LyricsModels.kt` | SPL 歌词 | P2 |
| `manager/{FloatingLyricsManager,StatusBarLyricsManager,MeizuStatusBarLyricsBackend,SuperLyricBackend,UnifiedLyricsManager}.kt`、`view/LyricView.kt` | 桌面/状态栏歌词 | P2（⚠️ Lyricon 部分授权待核） |
| `manager/SpectrumManager.kt` | 频谱可视化 | 可选 |
| `packages/heatmap` | 播放热力图 | P1（空状态用） |
| `packages/image-theme-colors` | 封面取色 | P1（配合莫奈取色） |

### 5.2 只能参考重写的（TypeScript）

- 主页三件套（搜索框 + 搜索历史 + 最近播放）的信息架构
- 「一切皆歌单」的路由与数据建模
- 搜索输入智能路由（BV/AV/b23.tv 解析）
- 歌单/库的 schema 设计与迁移策略
- 外部歌单导入（网易云/QQ 匹配）的匹配算法

### 5.3 明确不取

`ExpoOrpheusModule.kt`（RN 桥接，55 KB 全是无用功）、`apps/backend`（自建后端）、`analyticsService`（统计上报）、B 站装扮/皮肤系统（`garb`，与「纯净」定位冲突）、评论功能。

---

## 6. 授权义务（MIT）

- MIT 与 GPLv3 **单向兼容**：MIT 代码可并入 GPLv3 项目，合并后整体按 GPLv3 发布
- 被并入的每个文件**保留其 MIT 许可头与 `Copyright (c) 2025 Roitium.` 版权声明**
- `NOTICE` / README 中致谢并链接 `https://github.com/bbplayer-app/BBPlayer`
- ⚠️ `io/github/proify/lyricon/**` 非 BBPlayer 原创，取用前需单独确认其原始授权
- `packages/` 下各子包（heatmap、image-theme-colors 等）若有独立 LICENSE 文件，以子包声明为准

---

## 7. 对现有选型文档的修正建议

| # | 现文档表述 | 实测情况 | 建议 |
| --- | --- | --- | --- |
| 1 | 「BBPlayer（TS）只能参考、在 Kotlin 中重写」 | 播放引擎是 ~100 个 Kotlin 文件，MIT | **改写**：Kotlin 部分可直接复用，仅 TS 侧的信息架构需重写 |
| 2 | 4.2 表中「下载导出 m4a」标注「参考重写」 | `ExportDownloadsHelper.kt` 现成 | 改为「直接复用」 |
| 3 | 4.2 表中「SPL 逐字歌词」「桌面/状态栏歌词」标注「参考重写」 | Kotlin 现成（Lyricon 部分待核授权） | 改为「直接复用（部分待核）」 |
| 4 | 无「响度均衡」条目 | playurl 白送响度数据，实现成本极低 | **建议补入 P1** |
| 5 | 无「睡眠定时」条目 | 音频 App 常见刚需，Kotlin 现成 | 建议补入 P1 |
| 6 | 「冷启动空状态」待定 | BBPlayer 主页方案已验证 | 采用：搜索历史(10) + 最近播放 + 播放热力图 |
| 7 | 「分 P / 合集转条目」待定 | 「一切皆歌单」，多 P = 一个远程歌单 | 采用：搜索结果折叠为一条，点入展开为歌单 |
| 8 | 收藏夹同步为单向拉取 | BBPlayer 支持反向同步回 B 站 | 需决策是否做写操作（涉及风控暴露面） |
