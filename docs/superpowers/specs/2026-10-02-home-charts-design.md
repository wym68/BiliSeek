# 首页快捷入口改为榜单：每周必看 / 入站必刷 / 热歌榜

日期：2026-10-02
范围：`:core:network`（接口、DTO、仓库方法、纯函数）、`:app`（首页卡片、`ChartController` / `ChartScreen`、`MainActivity` 接线）。
前置：首页标题栏小改见 [home-header-polish](2026-10-02-home-header-polish-design.md)，与本文互不依赖。

## 背景

首页「快捷入口」现在是「稍后再看」（登录后才显示）和「近期歌单」。两者在底部「列表」页里本来就有，是重复入口。
用户要求换成**不需要账号**的榜单入口：每周必看、入站必刷、热歌榜。

热门播客也在需求里，但它是 App 热门页的「分品类热门」入口：入口列表（`top_items`）和入口 ID（`entrance_id`）都来自
App 端 gRPC `bilibili.app.show.popular.v1.Popular/Index`，社区文档的分区表（含 v2）与各开源客户端里都没有网页端对应物。
模拟 App 的设备头和签名与项目现有的网页端凭据体系不是一回事，风险高、无法离线验证，**本文不含播客**，见末尾「待定」。

## 方案

### 1. 首页

- `HomeEmptyState`：去掉 `onOpenWatchLater`、`onOpenRecentPlaylists`、`isLoggedIn` 三个参数，新增 `onOpenChart: (ChartKind) -> Unit`。
  `QuickEntryCard` 的 `visible` 参数只服务「登录后才显示」，一并去掉。
- 「快捷入口」一行三张卡片：每周必看（`CalendarMonth`）、入站必刷（`Whatshot`）、热歌榜（`Leaderboard`），均不需要登录。
  卡片样式沿用现有的 `QuickEntryCard`，分区标题仍是「快捷入口」。
- 「稍后再看」「近期歌单」从首页去掉；底部「列表」页里的稍后再看入口和列表 tab 本身不动。
- `tabSwitchRequests` 仍被「去设置」等流程使用，机制保留；只更新 `MainActivity` / `AppShell` 里两处提到「快捷入口『近期歌单』」的过时注释。

### 2. 榜单页

- **挂载方式**：覆盖在「搜索」tab 里，与「稍后再看」覆盖在列表 tab 里同一做法，不新增导航路由。
  `ChartController.opened` 非空时，`searchContent` 渲染 `ChartScreen` 代替 `SearchScreen`，`BackHandler` 关闭榜单页回首页。
  切到别的 tab 再回来、从播放页返回，榜单页都还在。
  搜索关键词变为非空时关闭榜单页（外部分享链接会触发搜索，不能让结果页被榜单页盖住）。
- **样式**：照「稍后再看」页——`BsTopBar` + `PullRefreshContainer` + 加载中 / 失败可重试 / 空列表 + 列表。
- **每行**：封面（`CoverThumbnail`）+ 标题（最多 2 行）+ 副行「UP 或歌手 · 时长 · 播放量」，没有的项省略；热歌榜行左侧带名次。
- **页头一行说明**：
  - 每周必看：`config.name` 与 `config.subject` 用「 · 」连起来，如「2024第279期 07.19 - 07.25 · 身残志坚钻石大盗」
  - 入站必刷：官方 `explain`（如「我不允许还有人没看过这98个宝藏视频！」）
  - 热歌榜：「第 N 期」
- **点条目**：整张榜单作为临时队列，从这条开始播（`ListPlayer.play(items, index, loadMore = null)`，与稍后再看一致），
  不覆盖用户加入的队列（`docs/10`：整张列表来源属于「临时」档）。
- **热歌榜取哪个视频**：每首歌有 `mv_bvid`（官方 MV，可能为空）和 `creation_bvid`（关联稿件）。优先播 MV，没有 MV 播关联稿件，
  两者都为空的歌曲不显示。显示标题用歌名、作者用歌手。播 MV 时时长未知，填 0，由播放器取到真实时长后回填；
  播关联稿件时用 `creation_duration`。名次取 `rank`，按名次升序排。
- **只做读**：不做长按菜单、下载、往期切换（本文写成时；后来用户要求了，见 [chart-issues](2026-10-02-chart-issues-design.md)）、原创榜（接口支持 `list_type=2`，但未要求）。

### 3. 接口与风控（`:core:network`）

`BiliApi` 新增 5 个公开接口（均无需登录、无需 WBI）：

| 榜单 | 接口 | 用法 |
| --- | --- | --- |
| 每周必看 | `GET x/web-interface/popular/series/list` | 取所有期数，取最大的 `number` |
| 每周必看 | `GET x/web-interface/popular/series/one?number=N` | 取该期视频 |
| 入站必刷 | `GET x/web-interface/popular/precious` | 一次取全部 |
| 热歌榜 | `GET x/copyright-music-publicity/toplist/all_period?list_type=1` | `data.list` 是「年份 → 期数组」的对象，取 `publish_time` 最大的一期 |
| 热歌榜 | `GET x/copyright-music-publicity/toplist/music_list?list_id=ID` | 取该期歌曲 |

- **风控**：全部走 `accountRead`（冷却期内本地快速失败；`-352` 记入全局冷却、**绝不重试**；`-412` 重置设备凭据），
  请求套 `ThrottleBucket.FEED`（最小间隔 600ms + 抖动）。不加任何重试循环。不学 `pages` / `videoView` / `userCard`
  只 `require(code == 0)` 的写法（欠账表里已记）。
- **请求量**：每周必看、热歌榜各 2 次（串行：先取最新一期，再取内容），入站必刷 1 次。每次打开榜单页或下拉刷新重新取，不落库。
- **仓库方法**：`weeklyMustWatch()`、`preciousVideos()`、`hotSongs()`，都返回 `Result<ChartPage>`。
- **对外新增的类型**（`:core:network` 公开面）：`ChartPage(subtitle, items)`、`ChartItem(bvid, title, coverUrl, author, durationSec, playCount, rank)`，
  以及 `BiliApi` 方法签名里要用的响应 DTO（沿用现有 DTO 公开的惯例）。映射成 `ChartItem` 后 `:app` 只依赖这两个类型。
- **纯函数**（`core/network/internal/ChartPolicy.kt`，有单测）：
  - `resolveLatestWeeklyNumber(entries)`：取最大期数，空列表返回 null
  - `resolveLatestMusicPeriod(byYear)`：取 `publish_time` 最大的一期，空返回 null
  - `resolveSongVideo(song)`：MV 优先、关联稿件兜底、都为空返回 null，同时给出封面与时长
  - 三个响应到 `ChartPage` 的映射（跳过没有 `bvid` 的条目，封面 URL 经 `normalizeCoverUrl`）

### 4. `:app` 结构

- 新增 `ChartKind`（每周必看 / 入站必刷 / 热歌榜）与 `ChartController`（`app/chart/`），照 `RemoteLibraryController` 的写法，
  挂在 `AppViewModel.charts`，不往 `AppViewModel` 里堆逻辑。状态：`opened: StateFlow<ChartState?>`，
  `ChartState(kind, subtitle, items, isLoading, errorMessage)`；方法 `open(kind)` / `close()` / `retry()` / `play(item)`。
  加载结果回来时，若榜单页已被关闭或换成了别的榜，丢弃结果。
- 新增 `ChartScreen`（`app/ui/ChartScreen.kt`）和纯函数 `chartRowSubtitle`（`app/chart/ChartPolicy.kt`，榜单行的副行文字「作者 · 时长 · 播放量」，没有的项省略）。
- `MainActivity`：`HomeEmptyState` 传 `onOpenChart = viewModel.charts::open`；`searchContent` 里按 `opened` 切换。

## 不做

- 播客、往期切换、原创榜、长按菜单、下载、榜单缓存。
- 不动「稍后再看」页与列表 tab。

## 待定：热门播客

用户从 App 里取到了热门播客页的分享短链 `https://b23.tv/1smoWpr`。短链只能靠请求 b23.tv 解析，
按项目安全规则（CLAUDE.md：自动化代码不得访问 B 站服务器）由用户在浏览器里打开，回传跳转后的最终网址。
若是网页链接，再另起一份小设计补第四张卡片；若是 App 专属页面，继续搁置。

## 测试

- 单测（离线，`:core:network`）：
  - `ChartDtoTest`：五个响应的解析，示例 JSON 取自社区文档（字段按文档手写，**没对照过真实响应**）；
    年份对象的解析；`owner` / `stat` 缺失等脏数据回退。
  - `ChartPolicyTest`：上面三个纯函数与映射（期数乱序、空列表、MV 与关联稿件取舍、`bvid` 全空跳过、按名次排序、页头说明文字）。
- `:app`：`ChartPolicyTest` 覆盖 `chartRowSubtitle`；`ChartController` 是薄胶水，现有控制器也没有仓库级测试，不另测。
- `./gradlew test assembleDebug` 通过。
- 真机（由用户）：三张卡片和三个榜单页都能打开、下拉刷新；点条目起播、MiniPlayer 出现、返回回到首页；
  热歌榜的榜单与 App「音乐榜」对得上，MV 能播；断网时显示失败并可重试。

## 文档

- `docs/05-功能优化篇.md`：§13.28 + 总览表一行。
- `docs/BiliSeek-手动测试指南.md`：步骤 14（首页空状态）里「快捷入口」那行改写，加榜单相关检查点。
- `docs/03-架构设计篇.md`：控制器表加 `ChartController`；首页描述（第 368 行附近）与「不落库」清单（第 304 行附近）补上榜单。
