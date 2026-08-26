# BiliSeek 项目开发文档 · 架构设计篇

> 版本：v0.2（评审修订稿）  
> 日期：2026-08-23  
> 前置文档：《BiliSeek-开发文档-功能选型篇》v0.2（回答「做什么、从哪取」）  
> 本文回答「**怎么搭**」——模块边界、播放内核、数据流、移植路径。
>
> 标注约定：**【已定】**= 已讨论确认；**【建议】**= 本文提出，待你过目确认；**【待决】**= 需要更多信息。

---

## 1. 设计原则

三条，用来在后续每个分歧点上做裁决：

1. **播放器生命周期绑 Service，不绑 UI。**  
   「默认只听」意味着用户绝大多数时间在别的 App 里、锁着屏。任何让播放器依附于页面的设计都是错的。
2. **决策逻辑做成纯函数。**  
   沿用 BiliPai 的 `*Policy.kt` 约定：把 `shouldXxx()` / `resolveXxx()` 从有状态组件里剥出来，无副作用、配单测。这是它代码里最值得学的部分，也是避免重蹈 8663 行 God Object 的唯一有效手段。
3. **能不落库就不落库。**  
   远程数据（收藏夹、合集、搜索结果）只做内存缓存；只有用户主动收藏进本地歌单才写数据库。

---

## 2. 模块划分 【已定】

```
:app                    UI、导航、feature 层、DI 装配
 ├── :core:network       风控接入、签名、Cookie、API 定义        ← 主要搬自 BiliPai
 ├── :core:player        取流、DASH 组装、播放内核、Service     ← 搬自 BiliPai + BBPlayer
 └── :design-system      token、主题、动效、自适应组件            ← 搬自 BiliPai
```

四个模块，不再多拆。

| 模块 | 职责 | 明确不承载 |
| --- | --- | --- |
| `:core:network` | buvid / bili_ticket / WBI、Cookie 管理、请求头策略、Retrofit 接口与 DTO | 任何 UI、任何业务状态 |
| `:core:player` | playurl 解析、本地 MPD 组装、URL 缓存、ExoPlayer 装配、`PlaybackService`、播放状态机 | Compose、导航、页面 ViewModel |
| `:design-system` | 设计 token、主题与取色、动效与分级、模糊预算、自适应组件 | 网络、业务状态、页面 ViewModel |
| `:app` | 页面、导航、feature ViewModel、数据库、Repository | — |

**为什么正好是这三个 core**：它们都是要整块移植外来代码的地方。独立成模块有两个实际收益——(a) 可以脱离 Compose 跑纯 JVM 单测；(b) 外来源文件集中，便于按 GPLv3/MIT 分别标注来源与版权头。

> 数据库与 Repository 暂留 `:app`。等 feature 数量上来、确实出现跨 feature 复用时再拆 `:core:data`，不提前建。

### 2.1 公开 API 面 —— **先定边界，再搬代码**

从 BiliPai 移植的代码大量使用 `internal`（`internal fun shouldXxx()`、`internal class PlaybackCoordinator`、`internal data class PlaybackLoadConfig`）。Kotlin 的 `internal` 是 **Gradle 模块级**作用域，这些代码搬进 `:core:player` 后**原样保持 `internal` 即可编译**，只有 `:app` 真正触碰的类型才需要 `public`。

**因此有一条强制纪律：每个 core 模块先把公开 API 面定下来，再往里搬代码。** 否则会演变成「搬一个、开放一个」，模块边界最终形同虚设。

| 模块 | 公开的（`public`） | 其余一律 `internal` |
| --- | --- | --- |
| `:core:player` | `PlaybackService`（manifest 需要）、`PlayerConnection`（facade）、`PlaybackRequest`、`PlaybackState`、`PlaybackMode`、`PresentationMode`、`AudioQualityOption` | 取流、MPD 组装、轨道/音质策略、状态机内部、所有 `*Policy` |
| `:core:network` | `AuthGate`、`BiliApi`（Retrofit 接口）、DTO、`WbiSigner` | CookieJar、拦截器、密钥管理、请求头策略 |
| `:design-system` | 主题入口、token 对象、组件 Composable | 内部渲染细节、策略实现 |

新增公开类型视同 API 变更，需要有意识地做，不能顺手加。

---

## 3. 网络与鉴权层

### 3.1 冷启动 gate 【已定】

所有业务请求必须在风控凭据就绪后才发出：

```
启动
 ├─ 从持久化恢复 buvid3/buvid4、bili_ticket、WBI keys
 ├─ 检查有效期
 │    ├─ 有效 → 直接放行
 │    └─ 失效/缺失 ↓
 ├─ GET  x/frontend/finger/spi_v2          → buvid3 / buvid4
 ├─ POST bapis/…/Ticket/GenWebTicket       → bili_ticket（3 天 TTL）
 │                                          ＋ 顺带返回 WBI img/sub key
 └─ 持久化 → 放行
```

两次请求把三件套备齐。`GenWebTicket` 顺带返回 WBI 密钥，不必再单独打 `nav`。

**关键约束：gate 不阻塞 UI。** 搜索框必须立刻可输入、可点击；请求排队等 gate 放行即可。实现上是一个 `StateFlow<AuthState>`，Repository 层 `first { it is Ready }` 后再发请求，UI 层不感知。

失败处理：gate 失败时**不弹错误**，静默重试（指数退避）；仅当用户主动发起的请求最终失败，才在该请求的位置提示。

### 3.2 拦截器链 【已定】

```
Request
  ↓ HeaderInterceptor      注入 User-Agent / Referer / Origin
  ↓ CookieJar              buvid3、buvid4、bili_ticket、SESSDATA、bili_jct
  ↓ (OkHttp 发出)
  ↓ AuthRetryInterceptor   仅 -101 触发凭据刷新并重试一次
Response
```

**重试策略必须区分两类错误码，不能一视同仁：**

| 错误码 | 含义 | 处理 |
| --- | --- | --- |
| `-101` | 未登录 / 凭据失效 | 刷新 Cookie，**重试一次**；再失败则登出 |
| `-352` | **风控拦截** | **不重试**，直接失败并进入指数退避 |
| `-412` | 请求被拦截（缺指纹） | 不重试，触发 gate 重新种植凭据 |

`-352` 是风控信号而非凭据问题。碰到它就刷新凭据再打一次，会演变成「越被拦越猛打」，把临时限流推成长期封禁。这是本架构里少数几个**做错会主动伤害用户账号**的地方。

**WBI 签名不进拦截器。** 签名必须覆盖最终确定的参数集，而拦截器拿到的是已构造好的 URL，改参数再算签名容易出错、也难测。沿用 BiliPai 的做法：在调用点签好，返回 `Map<String,String>` 用 `@QueryMap` 传入。

```kotlin
// 调用点
val params = WbiSigner.sign(mapOf("bvid" to bvid, "cid" to cid, ...))
api.getPlayUrl(params)
```

### 3.3 Cookie 刷新 【已定】

Web 扫码拿到的 Cookie 会过期。刷新流程比想象中长：

```
1. GET  x/passport-login/web/cookie/info        → 是否需要刷新 + timestamp
2. 本地 RSA-OAEP 加密 timestamp                  → correspondPath
3. GET  www.bilibili.com/correspond/1/{path}    → 解析 HTML 取 refresh_csrf
4. POST x/passport-login/web/cookie/refresh     → 新 Cookie + 新 refresh_token
5. POST x/passport-login/web/confirm/refresh    → 作废旧凭证
```

第 2、3 步是主要工作量，排期时**不要按「一个接口」估**。BiliPai 的 `core/network/TokenRefreshHelper.kt` 有现成实现。

触发时机：启动时检查一次 + 收到鉴权失败错误码时。

---

## 4. 取流层

### 4.1 数据流 【已定】

```
PlaybackRequest(bvid, cid, 音质偏好)
  ↓ 查 UrlCache（TTL 约 2h）
  ↓ miss → playurl 接口
  ↓        fnval=4048  fnver=0  fourk=1  voice_balance=1
  ↓
PlayUrlResponse
  ├─ dash 存在 → 组装本地 MPD ────┐
  ├─ dash 空、durl 有 → 单文件 MP4 ┤→ MediaItem
  └─ 都失败 → html5 降级路径 ─────┘
```

**降级链**：`dash` → `durl`（老式单文件） → `platform=html5 + high_quality=1`（不需登录也能到 1080p，但无高码率音频，仅作最后兜底）。

### 4.2 本地 MPD 组装 —— 无缝切看的地基 【已定】

B 站返回的是 DASH 的 JSON 而非标准 MPD，需自行拼装喂给 Media3。

**硬性要求：manifest 必须同时包含音频和视频两个 `AdaptationSet`，无论当前是听还是看模式。** 这是无缝切轨的前提——两条轨在同一个 MediaSource 里，切换才不需要重建。

省流量靠的是禁用视频轨后 ExoPlayer 不请求视频分片，**不是**靠 manifest 里不放视频。额外开销仅为几百字节的元数据。

拼装要点（照搬 BiliPai `LocalDashManifestBuilder.kt`）：
- 每条流一个 `<Representation>`，内含 `<BaseURL>` 与 `<SegmentBase indexRange=…><Initialization range=…/></SegmentBase>`
- **URL 里的 `&` 必须转义**，否则 XML 解析直接崩
- 时长格式 `PT%.3fS`

### 4.3 URL 缓存 【已定】

playurl 响应整份缓存，TTL 取略小于实际有效期（约 2h）。切模式、重播、从 MiniPlayer 恢复都命中缓存，不重新请求。

### 4.4 音质选择 【已定】✅ M8 已落地

落点：策略在 `core/player/internal/QualityPolicy.kt`（纯函数 + 单测），
取流在 `PlayUrlResolver`，偏好经 `PlayerConnection.preferenceProvider` 注入。

三个独立来源，优先级由用户偏好决定：

```
dash.flac.audio    Hi-Res 无损（ID 30251，需大会员）— 单个对象，非数组
dash.dolby.audio   杜比全景声（ID 30250，需大会员）— 需 type>0 且 codecs 含 ec-3/eac3
dash.audio[]       普通 AAC 数组 — 按 bandwidth 排序
```

两个必须处理的边界：

1. **倍速 > 1.5x 时回退**：Hi-Res/杜比在高倍速下有兼容问题，需自动降到 AAC 并给出提示。
2. **FLAC 解码器输入缓冲**：部分设备给 `c2.android.flac.decoder` 只分配 32 KiB，而 B 站 Hi-Res 单帧可能超过，触发 `InsufficientCapacityException`。需自定义 `RenderersFactory` 把 FLAC 的最小输入缓冲抬到 1 MiB（照搬 BiliPai `HiResCompatibleRenderersFactory.kt`）。

回退时向 UI 暴露原因：`不可用 / 倍速不兼容 / 无可播放音频`。

---

## 5. 播放内核

### 5.1 播放器归属 【已定】

**`PlaybackService`（`MediaSessionService` 子类）创建并持有唯一的 ExoPlayer 实例。UI 通过 `MediaController` 连接。**

```
┌─────────────── 同一进程 ───────────────┐
│                                        │
│  PlaybackService (MediaSessionService) │
│    └─ ExoPlayer  ← 唯一实例             │
│    └─ MediaSession                     │
│              ↑                         │
│         MediaController                │
│              ↑                         │
│    PlayerRepository (:app)             │
│              ↑                         │
│         各 feature ViewModel            │
└────────────────────────────────────────┘
```

**为什么不用 BiliPai 的单例方案**：它把所有权拆成两半——player 归 `MiniPlayerManager` 单例、session 归 Service，两者生命周期不一致。那 2752 行里的内存压力回收、闲置释放、surface 恢复补丁，相当一部分复杂度就源于「单例活得比 Service 久」。

**硬性约束：Service 不声明 `android:process`，必须与 UI 同进程。**  
理由：`MediaController` 实现了完整 `Player` 接口，`setVideoSurfaceView()` 与 `setTrackSelectionParameters()` 同进程下会转发到 session 的实际 player——而这两个调用正是「原地展开 + 无缝切轨」的全部依赖。跨进程时 surface 传递受限。

独立进程的唯一好处是 UI 崩溃不影响播放，代价是所有状态跨进程序列化，不值。

**前台服务**：`ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`。需准备 fallback 通知——拿不到主通知时先起一个占位的，否则 Android 14+ 会因前台服务启动超时崩溃。

> 选 `MediaSessionService` 而非 BBPlayer 用的 `MediaLibraryService`：后者多一层媒体库浏览（Android Auto 可进来翻歌单），要实现 `onGetLibraryRoot` / `onGetChildren` 一套回调，对「搜索驱动、无推荐」的 BiliSeek 价值不大。确有需求再升级，接口向下兼容。

### 5.2 播放模式状态机 【已定】

**设计为两个正交维度，而不是平铺的多状态。**

```
播放态  PlaybackMode     { IDLE, AUDIO, VIDEO }
呈现态  PresentationMode { NONE, INLINE, FULLSCREEN, PIP }
```

**核心不变式（整个播放内核的正确性基础）：**

> **`PlaybackMode == VIDEO` ⟺ `PresentationMode ∈ {INLINE, FULLSCREEN, PIP}`**
>
> 等价表述：**视频轨启用 ⟺ 存在可见且已 attach 的 Surface**

**为什么拆成两维**：`INLINE` 与 `FULLSCREEN` 从播放器角度**完全相同**——视频轨开着、surface 挂着，区别纯粹在 UI 布局。把它们做成两个播放状态，等于让播放内核去关心它不该关心的事。

拆开之后有三个直接收益：

1. **不变式从「需要维护的规则」变成结构上的定义**——两者互为充要条件，不可能违反。
2. **全屏与 PiP 退化为纯 UI 变化**，完全不触碰播放器，切换零风险。
3. **「PiP 不算进后台」这个特例消失了**——PiP 本来就是一种可见呈现态，不需要额外判定逻辑。

**迁移表（只剩播放态迁移，4 行）：**

| 从 | 到 | 触发 | 副作用 |
| --- | --- | --- | --- |
| `IDLE` | `AUDIO` | 用户播放条目 | 取流、prepare |
| `AUDIO` | `VIDEO` | 呈现态从 `NONE` 变为非 `NONE`（用户点「看」） | 见 §5.3 序列① |
| `VIDEO` | `AUDIO` | 呈现态变回 `NONE`（点「听」／进后台无 PiP／锁屏／切移动网络且开启仅 Wi-Fi） | 见 §5.3 序列② |
| 任意 | `IDLE` | 停止 | 释放 |

呈现态之间的迁移（`INLINE ⇄ FULLSCREEN`、`INLINE → PIP → INLINE`）**不产生播放器副作用**，只做 surface 重新 attach 与布局变更。

**进后台的判定**：`PresentationMode` 变为 `NONE` 才回落 `AUDIO`。进 PiP 时呈现态是 `PIP` 而非 `NONE`，因此不回落——这是不变式的自然结果，不需要专门写判定。

**音频焦点**与两个维度都正交：短暂丢失 → 暂停；永久丢失 → 暂停；duck → 降音量。不改变 `PlaybackMode`。

### 5.3 切轨时序 【已定】

顺序错了会出现黑屏、残留画面或音频跳秒。以下四个序列需严格遵守：

**① 进看模式（`AUDIO → VIDEO_*`）**
```
1. UI 创建 SurfaceView / attach
2. controller.setVideoSurfaceView(view)
3. setTrackSelectionParameters { setTrackTypeDisabled(VIDEO, false); clearVideoSizeConstraints() }
4. 等待首帧渲染回调 → UI 淡入视频层
```
不需要 seek，不需要 prepare。位置连续。

**② 退听模式（`VIDEO_* → AUDIO`）**
```
1. setTrackSelectionParameters { setTrackTypeDisabled(VIDEO, true); setMaxVideoSize(0, 0) }
2. controller.clearVideoSurface()
3. UI 移除 SurfaceView，淡回封面
```
**顺序不能反**：先关轨再清 surface。BiliPai 的注释写得明白——「关视频轨后一律清 surface」。反过来会短暂渲染到已销毁的 surface。

**③ 回前台恢复画面**
```
1. attach 新 surface
2. 恢复 TrackSelectionParameters
3. ⚠️ 不要 seek
```
**这是个真实的坑**：BiliPai 的注释记录了——后台音频连续播放时，即使 seek 到 `currentPosition`，Media3 也可能回退到前一个音频同步点，造成回前台后重复播放约 2 秒。恢复轨道和 surface 就足以重新产出画面，不要打断音频时间线。

**④ 进 PiP**
```
1. 保持视频轨启用（不回落）
2. surface 交给系统 PiP
3. 通知栏控制与 PiP 控制共用同一 MediaSession
```

### 5.4 后台与省电 【已定】

进后台且非 PiP 时，除关视频轨外还应：清 surface、暂停弹幕缓存（P2 阶段）。BiliPai 另有内存压力分级（`onTrimMemory` 触发重度优化、闲置释放），**MVP 不做**——先跑起来，等真出现内存问题再按需引入，它那套复杂度有相当部分是单例架构的自身负债，我们用 Service 持有后未必需要。

---

## 6. 数据层 【已定】

### 6.1 技术选型

**Room + KSP**。理由：一方库、Flow 原生支持、迁移机制成熟。

**从第一版就建立迁移机制。** BBPlayer 已积累 6 个迁移文件（含两次排序键重构），说明歌单/排序/播放历史的 schema 一定会演进。等需要时再补迁移会很痛。

### 6.2 落库边界

| 数据 | 是否落库 | 说明 |
| --- | --- | --- |
| 本地歌单及其条目 | ✅ | 用户资产 |
| 播放历史 / 进度 | ✅ | 续播、热力图 |
| 搜索历史 | ✅ | 首页空状态，上限 10 |
| 下载记录与文件索引 | ✅ | P1 |
| 收藏夹 / 合集 / UP 主投稿列表 | ❌ | 内存缓存 + 分页 |
| 搜索结果 | ❌ | 内存缓存 |

**远程列表不落库的理由**：一旦落库就要处理增量同步、删除检测、冲突合并，复杂度翻几倍；而收益只是离线能看到列表——可离线也播不了（URL 已失效），价值接近零。

### 6.3 「一切皆歌单」的落地

选型篇定的信息架构是：本地歌单 / 收藏夹 / 合集 / UP 主投稿 / **多 P 视频** / **搜索结果**，全部建模为同一种列表。

**统一抽象放在 UI 面向的状态层，而不是加载层。**

列表组件、批量选择、入队逻辑只依赖一个与来源无关的状态：

```kotlin
data class TrackListUiState(
    val id: TrackListId,      // Local(id) | Favorite(id) | Collection(id)
                              // | Uploader(mid) | MultiPage(bvid) | Search(query)
    val title: String,
    val tracks: List<Track>,
    val isMutable: Boolean,   // 只有 Local 可增删改
    val paging: PagingState,
)
```

**但加载机制不强求统一**：本地歌单走 Room 的 `Flow`（增删改自动刷新），远程列表走分页拉取。硬塞进同一个 `load(page)` 接口，会逼着本地实现把 Flow 包成假分页、或逼着远程实现伪造一个 Flow——两边都别扭。

各自实现、汇聚到同一个 `TrackListUiState` 即可。**新增一种列表 = 新增一个数据源实现**，不改 UI。

> 接口的具体形状留到实现时收敛，本文不锁死。过早统一加载层是这类「万物皆列表」抽象最常见的翻车点。

**分 P 处理**：搜索结果里一个 BV 折叠为一条 `Track`；点入若 `videos > 1`，跳到 `MultiPage(bvid)` 列表页展开各 P。

---

## 7. UI 骨架与导航 【已定】

### 7.1 结构

**单 Activity + Compose，导航用 Navigation Compose 稳定版。**

明确**不用** Navigation3 —— BiliPai 用的是 `1.2.0-alpha07`，还自行 vendored 了一个 NavigationEvent 兼容层（其架构文档注明「需随版本核对」）。alpha 依赖 + 自维护兼容层，对我们是纯负担。BiliPai 靠 Nav3 做的整卡转场服务于「卡片 → 视频详情页」的跳转，而我们已定**原地展开不跳页**，最出彩的那部分恰好用不上。

### 7.2 持久播放器容器

这是 UI 架构的核心，也是「原地展开」能否成立的关键：

```
Scaffold
 ├─ NavHost            ← 页面在这里切换
 ├─ MiniPlayer         ← 常驻底部，跨页面不销毁
 └─ BottomNavigation   ← 搜索 / 音乐库 / 设置
```

- MiniPlayer 挂在 NavHost **之外**，页面切换时不重建，播放不中断
- 上滑 MiniPlayer → 展开为播放页（同一 Composable 树，非导航跳转）
- 播放页内点「看」→ 封面区**原地**变为 SurfaceView
- 再上滑 → 进全屏（改变布局约束，仍非导航跳转）

**关键设计**：从听到看到全屏，全程没有导航栈操作。「退回听」始终是一步可逆的状态变更，不涉及返回栈——这是守住「视频不反客为主」的结构性保障。

**全屏的层级** ✅ **M8 已落地**：MVP 阶段用过「视频区上滑进全屏」，触发判定有缺陷被整个移除；
M8 重做时**入口改成显式按钮**，把那类误触从根上去掉，层级按下面的设计执行。

> 实现时的硬约束：全屏与内联**不能同时渲染** `PlayerSurface`——
> 同一个 SurfaceView 只能有一个父容器，两处并存会抛 `already has a parent`。

全屏时播放器容器**提升到 Scaffold 之上**成为顶层 overlay，Scaffold 本身原样不动。

```
Box
 ├─ Scaffold(NavHost + MiniPlayer + BottomNavigation)   ← 保持原状
 └─ FullscreenPlayerOverlay                             ← 仅全屏时存在
```

不要走「Scaffold 内部把底部导航和其他元素逐个隐掉」那条路：退出全屏时要逐个恢复，容易漏、容易污染底部导航的选中态与滚动位置。提升为 overlay 则退出即销毁，无需恢复任何东西。系统状态栏/导航栏的沉浸由 overlay 自己在进入/退出时开关。

### 7.3 三个入口

```
搜索（默认）  搜索框 + 搜索历史(10) + 最近播放 + 播放热力图
音乐库        本地歌单 / 收藏夹 / 合集 / 下载
设置
```

首页无算法推荐，但不空屏——全部是用户自己的行为数据。

---

## 8. 视觉体系 【已定】

**保持 Material 3 + 莫奈动态取色**，从 BiliPai `design-system` 移植视觉语言中立的部分。

### 8.1 直接搬（约 25 个生产文件 + 配套单测）

| 类别 | 内容 |
| --- | --- |
| Token | `AppSpacingTokens` / `AppSurfaceTokens` / `AppChromeSizeTokens` / `FeedContentTokens` |
| 动效 | `AppMotionTokens` / `AppEntranceMotion` / `BottomBarMotionSpec` / `VerticalContentRevealMotionPolicy` |
| 性能分级 | `MotionTier` + `MotionTierPolicy`、`BlurBudgetPolicy` / `BlurIntensity` / `BlurIntensityVisualPolicy` |
| 视觉细节 | `AppSquircleModifiers`、`OpticalContrastPalette`、`MediaContrastPalette`、`TopReadabilityChromePolicy` |
| 安全区 | `BottomSafeAreaPolicy`、`BottomBarContentPaddingPolicy` |
| 主题 | `AdaptiveAccentColorPolicy`（莫奈取色）、`ThemeContrastPolicy`、`Color` / `Shape` / `Type` |

`BlurBudgetPolicy`（按设备能力给模糊开预算）和 `MotionTier`（动效分级降级）是「精致感」的主要工程来源，且视觉语言中立——低端机也照顾到了。

### 8.2 不搬

`renderer/miuix/`、`core/ui/wallpaper/`、`effect/FullBarLiquidGlassModifier`（依赖 Haze）。**不引入 Miuix**（`0.9.4-…-SNAPSHOT`，快照版无稳定性承诺，是供应链风险）。

### 8.3 组件策略

约 30 个 `Adaptive*` 门面组件内部按 `UiPreset` 分支切换视觉语言。**不一次性剥离**——先只搬 token 层与策略层，组件用 Material 3 原生的；等确实觉得某个组件不够用，再去剥对应的 `Adaptive*`，同时把 `UiPreset` / `AppThemeConfig` / `PresetPrimitiveRenderer` 这套多语言切换机制简化掉（我们只有一套视觉语言）。

### 8.4 知识层白拿

BiliPai `docs/wiki/ui-design/` 的 15+ 份设计规范（方向、基础、排版、自适应布局、动效、无障碍、页面模板、验收标准）可直接阅读采用，这部分连搬代码都不算。

---

## 9. 移植路径：垂直切片 【已定】

**不 fork BiliPai。** 原因见选型篇 §6，另加一条：fork 会继承其单例播放器所有权，与本文 §5.1 的 Service 持有直接冲突，改造等于换地基。

做法是**按子系统整块搬**（而非逐文件挑拣），整块搬能保住隐式依赖和已踩过的设备坑。

### 里程碑

| # | 目标 | 内容 | 风险 |
| --- | --- | --- | --- |
| **M0** | **打通最高风险链路** | 空工程 → 起 `PlaybackService` → `MediaController` 连接 → attach surface → 切一次视频轨。**用任意公开测试视频，不接 B 站** | ⚠️ **最高**。整个架构押在「同进程 MediaController 能转发 surface 与轨道参数」上，必须最先验证 |
| **M1** | 出声 | 搬 `:core:network` 全套 → 风控 gate 跑通 → 硬编码一个 BV → 搬取流与 MPD 组装 → 播出音频 | 中。风控接入是外部依赖，可能遇到接口变动 |
| **M2** | 核心闭环 | Web 扫码登录 + Cookie 刷新 → 搜索 → 结果列表 → 播放 → **一键切看** | 中 |
| **M3** | 骨架成型 | 三入口导航 + MiniPlayer 持久容器 + 本地歌单 + 首页空状态 + design-system 移植 | 低 |
| **M4** | MVP 收尾 | 后台/锁屏回落、PiP 判定、播放队列、续播 | 低 |

**M0 必须第一个做。** 它不依赖 B 站任何接口，一两天能出结论，却验证了整个架构最不可逆的假设。如果 MediaController 转发 surface 有问题，越早知道越好——那意味着要退回 BiliPai 的单例方案，越晚发现改动越大。

---

## 10. 待决与风险

### 待决

1. ~~模块划分、拦截器链、状态机、数据层、UI 骨架~~ → **v0.2 已全部确认**，见对应章节。
2. **Haze 依赖**：`BlurBudgetPolicy` 是中立策略，但底层模糊实现若依赖 Haze，需决定是保留 Haze 还是换 Compose 原生 `blur()`。
3. **Lyricon 授权**（承接选型篇）：桌面歌词 P2 启动前必须查清。

### 已识别风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| MediaController 无法转发 surface / 轨道参数 | **架构级**，需退回单例方案 | M0 优先验证 |
| B 站风控策略变动（WBI/ticket 算法调整） | 全站不可用 | 三件套集中在 `:core:network`，改动面收敛；关注上游项目更新 |
| FLAC 解码在部分设备崩溃 | Hi-Res 不可用 | M8 已按已知解法预防（`HiResCompatibleRenderersFactory` 把输入缓冲抬到 1 MiB），**但需大会员 + 特定机型才验得到，目前未实测** |
| Cookie 刷新流程复杂度被低估 | 用户周期性掉登录 | 排期单列，复用 BiliPai 现成实现 |
| `design-system` 组件 Miuix 耦合比预期深 | 视觉移植受阻 | 渐进剥离，先只用 token 层 |

---

## 附录：与选型篇的对应关系

| 选型篇条目 | 本文落点 |
| --- | --- |
| 风控接入三件套 | §3.1 冷启动 gate |
| 登录态续期 / Cookie 刷新 | §3.3 |
| DASH 取流 + 本地 MPD | §4.1、§4.2 |
| 播放 URL 缓存 | §4.3 |
| 音质选择 / Hi-Res / 倍速互斥 | §4.4 |
| 音视频无缝切换 | §5.3 切轨时序 |
| 后台播放 / 系统媒体控制 | §5.1 |
| 锁屏自动回落 + PiP 不回落 | §5.2 状态机 |
| 一切皆歌单 | §6.3 |
| 首页空状态 | §7.3 |
| Material 3 / 莫奈取色 | §8 |
