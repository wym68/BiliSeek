# 榜单、收藏夹、稍后再看、合集：视频条目加三点菜单

日期：2026-10-02
范围：`:core:network`（榜单条目带出 UP 编号）、`:app`（共用的三点菜单组件、四个页面接线）。
承接 [dynamic-video-long-press-menu](2026-09-29-dynamic-video-long-press-menu-design.md)：同一个思路——把条目映射成搜索结果的模型，复用 `startSingleAction`。

## 背景

搜索结果、UP 空间投稿的视频行右侧有三点按钮，点开是一份动作菜单；榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客）、收藏夹内容页、稍后再看、合集内容页的行现在只有「点一下播放」，没有任何菜单。
用户要求这些页面的视频行也加上三点按钮，菜单与搜索结果的相同。

## 现状

- 搜索结果的三点菜单（`SearchScreen.kt`）是 `ActionListSheet`，七项依次：加入列表、加入队列、下一首播放、查看UP主、复制链接、下载、AI 总结。
- 动作经 `AppViewModel.startSingleAction(SearchVideo, PendingAction)`：单 P 直接执行，多 P 进挑分 P 页。加入列表的歌单选择框、下载对话框、挑分 P 页、AI 总结都挂在 `MainActivity` 上，任何页面触发都能弹出。
- UP 空间投稿和视频动态的菜单也是这么做的：条目先映射成 `SearchVideo`（`SpaceVideo.toSearchVideo()`、`DynamicEntry.toSearchVideo()`）再复用。

## 方案

### 范围

- 榜单页 `ChartScreen`：每周必看、入站必刷、热歌榜、热门播客；搜索框里打开的活动页是同一个页面，一并有。
- 收藏夹**内容页**（`FavoriteDetail`）、稍后再看（`WatchLaterScreen`）、合集**内容页**（`SeriesScreen`）。
- 不含：合集**列表**页（每行是合集，不是视频）、收藏夹目录。

### 共用组件（`ui/VideoMenu.kt`，新增）

- `VideoMenuActions`：七个动作的回调集合（照 `DynamicVideoActions` 的写法），默认全是空操作；`MainActivity` 组装一份，经 `ChartScreen` 与 `LibraryScreen` 透传给四个页面。
- `VideoMenuButton`：行右侧的三点图标按钮（`MoreVert`，contentDescription「更多操作」）。
- `VideoMenuSheet(video, actions, onDismiss)`：`ActionListSheet`，标题是视频标题（剥掉高亮标签），项目由 `videoMenuActions(video.mid)` 决定；点一项先收起菜单再执行动作（与搜索页一致）。
- 页面自己持有「当前打开菜单的那条」（`remember { mutableStateOf<SearchVideo?>(null) }`），行里点三点按钮置位；点行本身仍是播放。
- 搜索页自己那份内联的菜单不动（文案与顺序保持一致）；UP 空间、动态各自的菜单也不动。

### 纯函数（`ui/VideoMenuPolicy.kt`，新增，离线单测）

- `VideoMenuAction`：七个动作与它们的菜单文案（文案要与 `ActionListSheet.iconFor` 按文字对应图标的写法一致）。
- `videoMenuActions(authorMid)`：七项按搜索页的顺序；没有 UP 编号（≤ 0）时去掉「查看UP主」。
- 四个条目 → `SearchVideo` 的映射：`ChartItem`、`FavoriteTrack`、`WatchLaterItem`、`SeriesArchive`。时长用秒转成 `mm:ss` / `h:mm:ss` 文本，`parseDurationText` 能还原（`startSingleAction` 靠它得到秒数）。

### 数据：UP 编号

「查看UP主」要用 UP 的编号（`SearchUser(mid, uname)`）：

| 页面 | 来源 | 现状 |
| --- | --- | --- |
| 每周必看 / 入站必刷 / 热门播客 | 响应里的 `owner.mid` | DTO 的 `PopularOwner` 只留了 `name`，补 `mid`；`ChartItem` 加 `authorMid` |
| 收藏夹 | `FavoriteMedia.upper.mid` | `FavoriteTrack` 加 `authorMid`，`toTrack()` 填 |
| 合集 | `CollectionMediaItem.upper.mid` | `SeriesArchive` 加 `authorMid`，`toArchive()` 填 |
| 稍后再看 | `WatchLaterItem.owner.mid` | 本来就有 |
| 热歌榜 | 没有 | 歌曲只有歌手，`authorMid = 0` |

热歌榜（以及任何拿不到 UP 编号的条目）的菜单里**不显示「查看UP主」**，其余六项照旧。用户选了这个方案，否决了「点开时再现查 MV 的 UP 主」（多一次请求、多一条失败路径）。

### 接线（`MainActivity`）

`VideoMenuActions` 的七个回调与搜索页的接线完全一致：

- 加入列表 / 加入队列 / 下一首播放 / 下载 → `startSingleAction(video, AddToPlaylist / AppendToQueue / EnqueueNext / Download)`；
- 查看UP主 → `openUpSpace(SearchUser(mid, uname = author))` + `openUpSpaceRequests.tryEmit(Unit)`；
- 复制链接 → `copyToClipboard(videoUrl(video))`；
- AI 总结 → `summarizeVideo(video)`。

## 不做

- 不改搜索页、UP 空间、动态已有的菜单，不让它们改用共用组件（后续想统一再说）。
- 不加长按复选、批量操作；不加页面专属操作（稍后再看的「移除」、收藏夹的「取消收藏」等）。
- 不为热歌榜现查 UP 主。

## 风险

- 每个动作的第一步都会读一次分 P 列表（`startSingleAction` 的老行为，走仓库的限速读请求），连点几个条目的菜单会各发一次；与搜索结果一致。
- 热歌榜里不少歌的视频是 MV，榜单接口不给时长（0），加入队列 / 下载时时长为 0，播放后才回填——与现在点播一致。
- 界面接线没有单测，靠真机验证。

## 测试与验证

- `:core:network`：`ChartDtoTest` 钉住 `owner.mid` 的解析；`ChartPolicyTest` 钉住 `authorMid` 随条目带出、热歌榜为 0。
- `:app`：`VideoMenuPolicyTest`——菜单项与顺序、没有 UP 编号时少「查看UP主」、四个映射的字段与时长文本往返。
- `./gradlew test assembleDebug` 通过。
- 真机（由用户）：见手动测试指南。

## 文档

- `docs/05-功能优化篇.md`：新增 §13.34，总览表一行。
- `docs/BiliSeek-手动测试指南.md`：榜单（步骤 14）、收藏夹 / 稍后再看 / 合集各加三点菜单检查点。
- `docs/09-UI规范与重构.md`：共用组件表加 `VideoMenuSheet` / `VideoMenuButton`。
