# 首页快捷入口：多加入口，设置里选显示哪些、调顺序（第一期）

日期：2026-10-21
范围：`app` 的 `ui/HomeScreen.kt`、`chart/ChartController.kt`、`remote/RemoteLibraryController.kt`、`ui/LibraryScreen.kt`（收藏夹内容页加只读）、`ui/SettingsScreen.kt` + 新的入口设置页、`data/SettingsStore.kt`、`hint/HintPolicy.kt`、`MainActivity.kt`；新纯函数 `QuickEntryPolicy.kt`；`core:network` 的 `BiliApi.kt` / `BiliRepository.kt` / 榜单解析（加排行榜、原创榜）。

## 背景（用户原话）

> 现在搜索页面的快捷入口有四个。参考 bilimiao 项目首页的时光姬页面，它可以显示很多分区入口。我想在快捷入口处多添加一些入口，可以在设置中设置展示哪些入口。

## 调研结论（10-21）

- **bilimiao 时光姬**：内置旧版分区表（v1 tid），点分区进子分区标签页，用 `x/web-interface/newlist_rank`（时间段 + 播放/收藏等排序，不要 WBI）列视频；没有隐藏/排序分区的设置；结果为空时每 2 秒重试最多 5 次（违反本项目「不加重试」，不能照搬）。
- **网页 B 站（10-21 用户授权访问一次首页 HTML）**：首页导航 `channel_list` 是 30 个 v2 主分区（tid 1001–1031，`enableSub` 全为 0，子分区未启用），另有非分区入口：视频播客、超高清（活动页）、新歌热榜、番剧/国创/电影/电视剧/纪录片/综艺、直播、课堂、专栏、活动、社区中心、公益。
- **v2 分区页 `/c/xxx` 的内容来自 `region/feed/rcmd`，是推荐流**，不用。不带推荐地按分区列视频只有旧号（v1）接口：`newlist_rank`、`ranking/v2`、`dynamic/region`。新旧分区号没有官方对照表，旧号还能不能查到新投稿要真机确认。
- **决定分两期**：第一期只做榜单 / 现成数据类入口 + 设置；第二期再做分区入口（`newlist_rank`，时间段与排序的交互届时再定）。

## 方案（用户已选）

### 1. 入口清单：7 个，出厂全部打开

| 入口 | 稳定 id | 数据来源 | 新增工作 |
|---|---|---|---|
| 每周必看 | `weekly` | 不变 | 无 |
| 入站必刷 | `precious` | 不变 | 无 |
| 热歌榜 | `hot_songs` | 不变 | 无 |
| 热门播客 | `podcast` | 不变（活动页 `fuwAmTrtqFLexuSx`） | 无 |
| **原创音乐榜** | `original_songs` | 热歌榜同一组接口，`all_period?list_type=2` + `music_list` | 期数请求的 `list_type` 提成参数；页面、期数切换全复用 |
| **视频播客** | `video_podcast` | 官方公开收藏夹 media_id `3628308062`（网页「视频播客专区」页里唯一的播单组件），`x/v3/fav/resource/list` 分页 | 见第 3 节 |
| **全站排行榜** | `ranking` | 新接口 `x/web-interface/ranking/v2?rid=0&type=all`，一次 100 条，没有期数 | 新接口 + DTO + 解析 |

- `ChartKind` 加 `ORIGINAL_SONGS("原创音乐榜")`、`RANKING("全站排行榜")`；原创榜走 `fetchIssued`（与热歌榜同一套期数），排行榜走单页（同入站必刷），页头用接口返回的 `note`（「根据稿件内容质量、近期的数据综合展示，动态更新」）。
- 新请求都走 `chartRead`（FEED 限速桶 + 冷却期 + -352/-412 分流，**不重试**）。
- **排行榜不加 WBI 签名**：10-21 实测不签名返回 `code 0`、100 条；文档也标「非必要」。
- 「热门播客」与「视频播客」是两个不同的页面，两个都保留；不想要的在设置里关掉。

### 2. 设置的存储与规则

- `SettingsStore` 加一个字符串键 `home_quick_entries`：按显示顺序存各入口的稳定 id 与开关，形如 `weekly,precious,-podcast,ranking`（`-` 前缀 = 关）。用稳定 id 不用枚举名，第二期分区入口可写成 `region:<tid>`。
- 纯函数 `app/data/QuickEntryPolicy.kt`（`AppSettings` 要持有它，放 `data` 包，与 `ReorderPolicy`、`DownloadPolicy` 同处；入口 → 榜单 / 收藏夹的映射在 `AppViewModel.openQuickEntry`）：
  - `resolveQuickEntries(saved: String?): List<QuickEntrySetting>`：从没设置过 → 出厂顺序、全开；不认识的 id 丢掉；重复 id 只留第一个；代码里有、存的没有的入口（以后版本新加的）**追加到末尾、按出厂默认打开**。
  - `encodeQuickEntries(list): String`，与上面互逆。
  - `visibleQuickEntries(list)`：只留打开的，保持顺序。
- 出厂顺序：每周必看、入站必刷、热歌榜、原创音乐榜、热门播客、视频播客、全站排行榜。

### 3. 视频播客：只读打开收藏夹内容页

- 10-21 实测：不登录就能读，`code 0`，标题「视频播客」，486 条，`has_more` 为真，属主「闪闪发光的生活官」；内容多为 30–100 分钟的对谈类长视频。
- **复用收藏夹内容页（`FavoriteDetail`）的列表、分页、滚到底自动加载、失败重试、三点菜单**，加只读开关：不显示编辑、删除收藏夹，不能右滑移除，三点菜单里没有「从收藏夹移除」。
- **状态单独一份**：现有 `openedFavorite` 由「我的」页使用（它不为空时「我的」页就显示内容页），视频播客不能共用，否则切到「我的」页会看到它。`RemoteLibraryController` 加 `openedPublicFavorite` + `openPublicFavorite(mediaId, title)` / `closePublicFavorite()`，翻页逻辑与 `loadFavoritePage` 共用（按目标状态与取数函数参数化），只读的没有待提交移除。
- `BiliRepository.favoriteResources` 开头要求登录（`requireLoggedInMid()`），不登录会直接失败；加 `publicFavoriteResources`（同一请求、同样的过滤，不要求登录），两者共用一段私有实现。
- 显示位置同榜单页：首页这一栏里（`MainActivity` 的 `searchContent` 分支），返回手势收起回首页；搜索框有关键词（如外部分享触发搜索）时同样关掉。
- 点条目：与收藏夹一样单个起播（`playFavoriteTrack`）。
- media_id 写成常量（同 `PODCAST_ERA_URL` 的做法）；运营换了收藏夹就改常量。

### 4. 首页

- 每行 4 张卡片，自动换行；卡片样式、高度与现在一致。最后一行不足 4 张时靠左，空位用等宽占位撑住，宽度不变。
- 全部关掉时「快捷入口」整节不显示。

### 5. 设置页

- 「界面」分组加一行「首页快捷入口」，值显示「显示 N 个」，点进去是新的一页（顶栏返回）：
  - 每行：图标、名字、开关、右侧拖动手柄。
  - 长按拖动排序，交互复用本地列表的拖动排序（浮起卡片、别的行让位、每格轻震、边缘自动滚动；`ReorderPolicy`）。为此把 `PlaylistReorderState` 改成按任意 key 的通用 `DragReorderState`（松手时只报「从第几格到第几格」，本地列表那边自己算 sortKey），本地列表行为不变。
  - 关掉的入口留在原位，开关显示关；再打开回到原位。
- 每次改动立刻写入 `SettingsStore`，首页跟着变。

### 6. 首次使用提示

- 新 `HintKey.HOME_QUICK_ENTRIES`（键 `hint_home_quick_entries`）：首页快捷入口第一次显示、且没有别的演示在放时，在卡片区上方弹 `HintBubble`：「入口可以在 设置 → 首页快捷入口 里关闭和排序」。
- 没有手势动画；被点掉或约 4 秒后自动消失都记为看过。「重新显示操作提示」一起重置。

## 不做

- 分区入口（第二期）。
- 「热门」（`x/web-interface/popular`，带 Cookie 时是个性化排序，等于推荐）、网页分区页的推荐流、番剧/影视/直播/课堂/专栏等。
- 排行榜按分区切换（第二期和分区一起考虑）。
- 首页长按进编辑；入口设置页的「恢复默认」。

## 测试与验证

- 先写测试并确认失败再实现：
  - `QuickEntryPolicyTest`：未设置 → 出厂全开；顺序与开关往返编码；未知 id 丢弃；重复 id；新入口追加到末尾并打开；全关。
  - 排行榜解析：`core/network/src/test/resources/ranking_v2_sample.json`（10-21 真实返回，裁剪到 3 条）→ `ChartPage`（标题、封面、UP 主、时长、播放量、名次、页头 note）。
  - 原创榜：只是期数请求的 `list_type` 换成 2，解析与热歌榜完全相同；仓库层没有 HTTP 单测设施，由真机验证。
  - 收藏夹只读：`fav_video_podcast_sample.json`（10-21 真实返回，裁剪）走现有收藏夹解析能出条目、`has_more` 为真。
  - `HintPolicy` 新键可开始、可记为看过。
- 测试只用离线样例，不碰 B 站服务器。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
- 真机（用户）：三个新入口能打开、能播；视频播客滚到底能接着加载、没有移除/编辑入口、切到「我的」页不受影响；设置里关、开、拖动后首页即时变化，重启后保持；提示只出现一次。
- 文档：`05` §13 改动记录、`09` 首页快捷入口与设置页、手动测试指南加步骤。
