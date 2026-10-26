# 合集：播放页显示所属合集 + UP 空间合集

日期：2026-10-26
范围：`app` 新增 `collection/`（`CollectionController`、`CollectionPolicy`）、播放页合集入口行与合集弹层、UP 空间「合集」视图；改 `remote/RemoteLibraryController.kt`（订阅合集相关迁出）、`interaction/VideoInteractionController.kt`、`upspace/UpSpaceController.kt`、`AppViewModel.kt`、`MainActivity.kt`、`ui/PlayerScreen.kt`、`ui/UpSpaceScreen.kt`、`ui/SeriesScreen.kt`、`ui/LibraryScreen.kt`；`core:network` 加 DTO 字段、3 个只读接口、1 个写接口。

## 背景

1. 播放一个视频时，看不出它属于哪个合集，也没法顺着合集往下听。
2. UP 空间只有「投稿 / 动态」，看不到 UP 主的合集。

## 调研（只读代码与参考项目，未请求 B 站）

- **两种「合集」**：
  - 合集（`ugc_season`，新版）：UP 主创建，可分小节（sections），一个视频最多属于一个合集。视频详情里带。
  - 系列（`series`，旧版「视频列表」）：一个视频可在多个系列里；视频详情里不带，只能在 UP 空间里列出。
- **视频详情 `x/web-interface/view` 的 `data.ugc_season`**：`id`、`title`、`cover`、`mid`、`intro`、`ep_count`、`sections[]`（`id`、`title`、`episodes[]`：`aid`、`bvid`、`cid`、`title`、`arc{pic, duration, pubdate}`）。播放页展开时 `VideoInteractionController` 已经在请求这个接口，现有 `VideoViewInfo` 没解析这个字段。BiliPai（`ref/bilipai`）的合集面板直接用 `sections.flatMap { episodes }`，没有另外翻页。
- **`x/web-interface/archive/relation` 的 `season_fav`**：当前视频所在合集是否已订阅。播放页登录后已经在请求（`VideoInteractionController`），BiliPai 的订阅按钮就用这个字段。
- **UP 空间**（字段取自 BiliPai 的模型）：
  - `x/polymer/web-space/seasons_series_list?mid&page_num&page_size`：`data.items_lists.seasons_list[]`（`meta{season_id, name, cover, total, description, mid}`）+ `series_list[]`（`meta{series_id, name, cover, total, description, mid}`），以及分页信息。不需要登录。新版网页空间带 WBI 签名。
  - `x/polymer/web-space/seasons_archives_list?mid&season_id&sort_reverse&page_num&page_size`：合集内容（`archives[]`：`bvid`、`title`、`pic`、`duration`、`pubdate`），不需要登录。
  - `x/series/archives?mid&series_id&sort&pn&ps`：系列内容。
- **订阅**：`x/v3/fav/season/fav`（`season_id`、`platform=web`、`csrf`），与现有的 `x/v3/fav/season/unfav` 对称。
- **现有代码**：
  - 「列表 → 合集」tab 的订阅列表（`x/v3/fav/folder/collected/list`）和合集内容页 `SeriesScreen`（`x/space/fav/season/list`，`accountRead`），状态在 `RemoteLibraryController`（`collectedList`、`openedSeries`、`TrackListId.Series`、`playSeriesArchive` + `PagedQueueSource` 续页、`unsubscribeOpenedSeries`）。`TrackListId.Series` 只在 `RemoteLibraryController` 内部用。
  - `BiliRepository.seriesList`（`x/series/series`）、`seriesArchives`（`x/series/archives`，缺 `mid`，走 `accountRead`）已定义，但 app 里没有调用。

## 方案（用户已选）

### 1. 需求取舍

| 问题 | 选择 |
|---|---|
| 播放页怎么呈现 | 一行入口 + 底部弹层 |
| 点播合集里的视频时自动接后续集 | 不自动；想接着听，自己打开弹层点下一集 |
| UP 空间入口 | 第三个视图「合集」 |
| 旧版系列 | 和合集一起列 |
| 订阅合集 | 只在播放页弹层加；UP 空间合集内容页不加 |
| 实现方式 | 新建 `CollectionController`（方案 B） |

### 2. 结构与数据流

**`CollectionController`（新，`app/collection/`，由 `AppViewModel.collections` 持有）**，负责合集相关的页面和动作：

- **我订阅的合集列表**：`collectedList`、`collectedListState`、`loadCollectedList` 从 `RemoteLibraryController` 整体迁入。
- **合集内容页，分两个槽**：`LIBRARY`（「列表」tab）和 `UP_SPACE`（UP 空间页）。每个槽负责打开、关闭、翻页、点一项从这里开始播（`ListPlayer.play(items, index, loadMore)` + `PagedQueueSource` 续页），`LIBRARY` 槽还负责取消订阅。
  - 分两个槽的原因：订阅合集页是「列表」tab 里的一层，UP 空间是单独的导航页。只用一个「当前打开的合集」，从 UP 空间打开的合集也会盖在「列表」tab 上。分开以后，订阅合集页的行为不变。
  - 打开时传入合集引用：`Subscribed(seasonId)`（现有 `x/space/fav/season/list`，行为不变）、`Season(mid, seasonId)`、`Series(mid, seriesId)`。用哪个内容接口由 `CollectionPolicy` 决定。
  - `TrackListId.Series` 随迁移改成能区分这三种的标识。
- **播放页弹层的动作**：`playEpisodes(collection, index)`（整个合集作为临时块，从这一集开始播）、`subscribe(seasonId)` / `unsubscribe(seasonId)`。
  - 写请求同一时间只发一个。
  - 订阅成功：重新取订阅列表第 1 页；取消订阅成功：列表里有这一项就去掉（同现有逻辑），「列表」tab 开着的正是它就关掉内容页。
  - 两种情况都调 `videoInteraction.markSeasonSubscribed(seasonId, value)` 同步播放页按钮（同现有 `markFavored` 的模式）。

**不放进 `CollectionController` 的部分：**

- **当前视频属于哪个合集**：`VideoInteractionController` 解析 `view` 的 `ugc_season`，`VideoInteractionState` 加 `collection: VideoCollection?`（id、标题、封面、全部集数、当前是第几集），再从 `relation` 的 `season_fav` 加 `seasonSubscribed`。按 bvid 缓存，与现有字段一致。不用多发请求。
- **UP 空间「合集」视图的列表**：`UpSpaceController` 管，`UpSpaceView` 加 `COLLECTIONS`，`UpSpaceUiState` 加 `collections: RemoteListState<SpaceCollection>`（`SpaceCollection`：类型（合集 / 系列）、id、mid、名称、封面、视频数，由 `seasons_series_list` 的两个列表映射而来），跟着 UP 空间打开关闭、各自翻页（同投稿、动态）。点某一项调 `collections.open(ref, UP_SPACE)`；关闭 UP 空间或换成另一个 UP 主时，`UP_SPACE` 槽一起关。

**`CollectionPolicy.kt`（纯函数，单测）**：

- 各小节的集数拼成一个列表；按 bvid 找当前是第几集（找不到时返回 null，入口行只显示合集名）。
- 生成「第 n / N 集」。
- 合集在前、系列在后的排列顺序。
- 合集弹层的行（小节标题 + 各集）、打开时滚到哪一行、点的是不是正在播的那集。

根据合集引用选内容接口是 `CollectionController` 里对密封类型的 `when`，漏写一种编译不过，不另写纯函数。

### 3. 网络层（`core:network`）

| 改动 | 内容 | 走哪条路径 |
|---|---|---|
| DTO | `VideoViewInfo` 加 `ugc_season`；`ArchiveRelation` 加 `season_fav` | 沿用现有请求 |
| UP 主的合集和系列列表 | `x/polymer/web-space/seasons_series_list` | `wbiRequest`（WBI 签名 + FEED 限速桶 + 冷却期检查 + `refreshingOn101`），与 UP 投稿相同 |
| 合集内容 | `x/polymer/web-space/seasons_archives_list`，`sort_reverse=false` | 同上 |
| 系列内容 | `x/series/archives`，`sort=asc` | 同上；改现有没人用的 `seriesArchives`（补 `mid`、`sort`，换成 `wbiRequest`），不另加一个 |
| 订阅 | `x/v3/fav/season/fav` | 现有写请求路径（WRITE 桶；`-111` / `-101` 只重试一次），与 `unfavSeason` 对称 |

- 新增的公开类型只有 DTO：合集、小节、集数，以及 UP 空间合集列表和内容的分页结果。
- 不签名也能用的接口一律签名：签名多带的参数没有坏处，还能避免 `-352`。
- `BiliRepository.seriesList`（`x/series/series`）没有地方调用，这次不动。

### 4. 界面

**播放页**

- 简介那一行下面加一行入口：「合集 · 名称 · 第 3/24 集 ›」。视频不属于合集，或详情没拉到，就不显示这一行。
- 点开是底部弹层（`ModalBottomSheet` + `CompactDragHandle`）：
  - 标题区：合集名、「共 N 集」。登录后右边有「订阅 / 已订阅」，没登录不显示这个按钮。
  - 列表：超过一个小节时显示小节标题；当前这集高亮，打开时自动滚到它。
  - 点某一集：整个合集作为临时块，从这一集开始播，带图钉的条目不动。点正在播的那集：只关闭弹层，不重新播。
  - 点「已订阅」：弹出与「列表 → 合集」相同的确认框「取消订阅合集 · 保留 / 取消订阅（红）」。点「订阅」不需要确认。
  - 提示：「已订阅「X」」「已取消订阅「X」」「订阅失败：…」「取消订阅失败：…」。

**UP 空间**

- 视图从「投稿 / 动态」变成「投稿 / 动态 / 合集」。第一次切到「合集」才发请求。
- 每行显示封面、名称、「合集 · 24 个视频」或「系列 · 24 个视频」。合集在前，系列在后。
- 点开后，在 UP 空间页上盖一层合集内容页：用 `SeriesScreen`，不显示「取消订阅」（`onUnsubscribe` 改成可空，空时不显示按钮），三点菜单照旧。
- 排序：合集按 UP 主自己排的顺序；系列按发布时间从旧到新（B 站网页默认从新到旧，这里有意改成和合集一样「往后播 = 往更新的播」）。

**样式**：入口行和弹层的具体样子，实现前先出浏览器效果图给用户选。

### 5. 出错时怎么处理

- 播放页：合集信息来自已有的请求，没有额外的出错情况；拿不到就不显示入口行。
- UP 空间合集列表：加载失败时底部显示错误和「重试」，不自动重试（`shouldAutoLoadMore`）；列表为空时显示「这位 UP 主还没有合集」。
- 合集内容页：沿用 `SeriesScreen` 现有的加载、失败、重试。
- `-352`：`wbiRequest` 进入冷却期，不重试，提示与 UP 投稿一致。
- 订阅 / 取消订阅：失败只提示，按钮状态不变；请求进行中按钮不能点。

## 测试（全部离线）

- `CollectionPolicyTest`：多个小节拼成一个列表、按 bvid 找当前集（含找不到）、「第 n / N 集」、弹层的行与初始滚动位置、点正在播的那集不重新播、合集与系列的排序。
- `CollectionPagePolicyTest`（`:core:network`）：有总数按总数算、没总数看这一页满不满、空页就到底。
- `VideoActionPolicyTest`：订阅状态只改同一合集的视频。
- DTO 解析测试：`ugc_season`、`season_fav`、`seasons_series_list`、`seasons_archives_list`、`x/series/archives`，用本地手写的 JSON 样本。样本是照着 BiliPai 的模型和接口文档写的，不是真实响应；真机跑过后，用日志里的真实响应替换。
- 写请求：订阅走已有单测覆盖的 `writeRequest` / `runWriteRequest`，参数与 `unfavSeason` 对称，不另写单测。
- `PlayerUiTest`（需设备，至少编译通过）：属于合集时显示入口行、点开列出各集；不属于合集时没有入口行。
- 迁移回归（手动测试指南）：订阅合集列表、翻页、点播续页、取消订阅，行为与迁移前完全一致。

## 待真机确认

- `seasons_series_list` 不签名会不会返回 `-352`（统一签名，按理没事）。
- 几百集的大合集，`view` 的 `ugc_season` 会不会把所有集都返回。
- 播放页订阅后，「列表 → 合集」里能看到；在弹层里取消订阅后，列表里消失。
- 旧版系列的内容接口在不登录时能不能用。

## 不做

- 点播合集里的视频时自动接后续集。
- UP 空间合集内容页的订阅按钮。
- 清理 `BiliRepository.seriesList` 这类没人用的代码。
