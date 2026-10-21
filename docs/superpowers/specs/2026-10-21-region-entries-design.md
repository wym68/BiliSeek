# 首页快捷入口第二期：分区入口（时光姬式）

日期：2026-10-21
前置：第一期 `docs/superpowers/specs/2026-10-21-home-quick-entries-design.md`（同分支 `feat/home-quick-entries`，尚未合并）。
范围：`app` 新增 `region/`（分区清单、纯函数、控制器）、分区总览页 / 分区详情页 / 时间设置弹层；改 `data/QuickEntryPolicy.kt`（入口默认值）、`data/SettingsStore.kt`（时间范围、排序、短视频开关）、`HomeScreen.kt`、`AppViewModel.kt`、`MainActivity.kt`；`core:network` 加 `newlist_rank` 接口、DTO、解析。

## 背景

用户参考 bilimiao「时光姬」：首页显示很多分区入口，点进去看某时间段内按播放等排序的投稿。第一期已把快捷入口做成可配置；第二期加分区。

## 调研与实测（10-21）

- 网页 v2 分区页 `/c/xxx` 的内容来自 `region/feed/rcmd`，是推荐流，不用。不带推荐地按分区列视频只能用旧版（v1）分区号的接口。
- **10-21 19:29 用户授权请求一次** `x/web-interface/newlist_rank?main_ver=v3&search_type=video&view_type=hot_rank&copy_right=-1&new_web_tag=1&order=click&cate_id=28&page=1&pagesize=20&time_from=20261015&time_to=20261021`：不签名、不带 Cookie，`code 0`，`numResults 6736`、`numPages 337`，第一页 20 条均为 10-15～10-20 的投稿。结论：**旧分区号能取到新投稿**。
- 实测问题：分区不精确（原创音乐里混进科普、电竞、超跑讲解）；20 条里 7 条短于 30 秒。
- 返回 `data.rqt_type = "search"`：后台是搜索服务。
- 字段：`bvid`、`title`、`pic`（`//` 开头）、`author`、`mid`、`duration`（秒，数字）、`play`（**字符串**）、`favorites`、`video_review`（弹幕）、`review`（评论）、`pubdate`（`yyyy-MM-dd HH:mm:ss`）、`senddate`。分页：`page`、`pagesize`、`numPages`、`numResults`。

## 方案（用户已选）

### 1. 分区清单（写死，5 大类 22 个子分区，全部为 v1 tid，取自 bilibili-API-collect 镜像 `docs/video/video_zone.md`，已下线的排除）

| 大类（图标） | 子分区（tid） |
|---|---|
| 音乐 | 原创音乐 28、翻唱 31、演奏 59、VOCALOID·UTAU 30、音乐现场 29、乐评盘点 243、电台 267、音乐综合 130 |
| 知识 | 人文历史 228、社科·法律·心理 124、科学科普 201、财经商业 207、职业职场 209、校园学习 208 |
| 纪录片 | 人文·历史 37、科学·探索·自然 178、社会·美食·旅行 180 |
| 杂谈 | 影视杂谈 182、娱乐杂谈 241、动漫杂谈 253 |
| 配音与广播剧 | 动态漫·广播剧 195、配音 257 |

- 图标（Material Outlined）：音乐 `LibraryMusic`、知识 `School`、纪录片 `Movie`、杂谈 `Forum`、配音与广播剧 `RecordVoiceOver`；总入口「分区」用 `Category`。
- 「杂谈」「配音与广播剧」是自拟分组（原分属影视 / 娱乐 / 动画 / 国创，每类只取一两个）。
- 不做：ASMR（v1 的 175 已下线，只有 v2 有）、资讯（文档注明「无排名功能」）。

### 2. 页面

- **分区总览页**（快捷入口「分区」，默认打开）：显示在首页这一栏（同榜单页），返回收起回首页。
  - 顶部「时间范围」卡片：显示当前范围（如「10-15 至 10-21」）与排序，点开时间设置弹层。
  - 5 张大类卡片：标题行（图标 + 大类名），下面 `FlowRow` 排子分区标签。点标题进该大类第一个子分区，点标签进对应子分区。
- **分区详情页**：顶栏标题「大类名」，下面 `ScrollableTabRow` 列子分区，`HorizontalPager` 左右滑切换；每页一个子分区的列表。
  - 顶栏右侧排序菜单：播放（`click`）、收藏（`stow`）、硬币（`coin`）、弹幕（`dm`）、评论（`scores`）。
  - 列表上方一行：当前时间范围（点开时间设置弹层）+「隐藏 1 分钟以下」开关。
  - 列表行同榜单行样式（封面、标题、「UP 主 · 时长 · 播放量」）；三点菜单同搜索结果。
  - 滚到底自动加载下一页（每页 20）；失败显示「重试」，不自动重试；空结果显示空状态。
  - 点条目：该子分区已加载的列表作为临时队列从这一条开始播，`loadMore` 续页（同搜索结果，`ListPlayer.play(items, index, loadMore)`）。
  - 时间范围 / 排序改了：所有已加载的子分区列表清空，当前页重新取第 1 页。
  - 短视频开关只影响显示（列表里存原始结果，显示时过滤），切换立即生效、不重新请求；一页全被过滤掉时照常自动续页。点条目时临时队列与续页也按当前开关过滤。
- **单个子分区作为快捷入口**：22 个子分区进入「设置 → 首页快捷入口」清单，**默认关**；打开后首页出现该子分区卡片（名字 = 子分区名，图标 = 所属大类图标），点进详情页并停在该子分区。

### 3. 时间设置弹层（ModalBottomSheet，`CompactDragHandle`）

- 三种：**最近 7 天**（含今天，往前 6 天，每次打开按当天重算）；**按月份**（年、月两个选择，范围 = 当月 1 日～月末，本月则到今天）；**自定义**（Material3 `DateRangePicker`，最长 30 天，最早 2009-01-01，最晚今天；超出时确认按钮不可用并提示）。
- 排序也放在弹层里一份（与详情页菜单是同一个值）。

### 4. 存储

- `SettingsStore` 加：`region_time_mode`（`LAST_7_DAYS` / `MONTH` / `CUSTOM`）、`region_time_from` / `region_time_to`（`yyyyMMdd`，`MONTH`、`CUSTOM` 用）、`region_order`（默认 `click`）、`region_hide_short`（默认 true）。全部分区共用，重启保留。
- 分区列表内容只在内存，不落库（CLAUDE.md「不持久化远程数据」）。

### 5. 入口清单改造（`QuickEntryPolicy`）

- `QuickEntry` 由 enum 改为能表达分区的形式：固定入口（第一期 7 个 + 新增「分区」`regions`）与分区入口 `region:<tid>`（由分区清单生成）。每个入口带**出厂默认开关**：第一期 7 个与「分区」开，22 个子分区关。
- 规则调整：「代码里有、存的里没有的入口追加到末尾」改为**按该入口的出厂默认值**（不再一律打开）；其余规则不变。出厂顺序：第一期 7 个 →「分区」→ 22 个子分区（按清单顺序）。
- 设置页清单照常显示全部（30 个），开关 + 拖动排序。

### 6. 网络与风控

- `BiliApi.newlistRank(cateId, order, page, pageSize = 20, timeFrom, timeTo)`：固定参数 `main_ver=v3`、`search_type=video`、`view_type=hot_rank`、`copy_right=-1`、`new_web_tag=1`。不签名。
- `BiliRepository.regionVideos(tid, order, page, from, to): Result<RegionPage>`：走 **SEARCH 限速桶**（800ms + 抖动）+ 冷却 + `resolveRetryAction` 分流（-352 进冷却、绝不重试）。`result` 为 null 或空 → 空页，不重试。
- DTO 字段 `play` 是字符串：解析成 `Long`（非数字 → null）。封面 `normalizeCoverUrl`。

### 7. 代码结构

- `core/network`：`dto/RegionDto.kt`（`NewlistRankData`、`NewlistRankVideo`）、`internal/RegionParsePolicy.kt`（DTO → `RegionVideo` / `RegionPage`，纯函数）、`RegionModels.kt`（公开 `RegionVideo`、`RegionPage`）。
- `app/region/`：
  - `RegionCatalog.kt`：大类与子分区清单（数据）。
  - `RegionPolicy.kt`：纯函数——时间范围计算（7 天 / 月份 / 自定义校验）、`yyyyMMdd` 格式化、短视频过滤（`< 60` 秒）、翻页追加按 bvid 去重、「还有下一页」判断。
  - `RegionController.kt`：每个子分区一份 `RegionListState`（内存）、当前打开的大类与子分区、加载 / 续页 / 播放。
- `app/ui/`：`RegionOverviewScreen.kt`、`RegionDetailScreen.kt`、`RegionTimeSheet.kt`。
- 列表需 `miniBarContentPadding()`；提示用 `AppToast`。

## 不做

- ASMR、资讯、v2 分区号、网页分区页推荐流。
- 每个分区各自记时间 / 排序。
- 分区清单联网刷新（bilimiao 用 `app.bilibili.com/x/v2/region/index`，需要 appkey 签名）。
- 自动重试（bilimiao 的「空结果每 2 秒重试 5 次」不照搬）。

## 测试与验证

- 离线样例 `core/network/src/test/resources/newlist_rank_sample.json`（10-21 真实返回，cate_id=28，裁剪成 3 条：一条 64 秒、一条 16 秒、一条 288 秒）。
- 单测：
  - 解析：字段映射、`play` 字符串转数字、非数字 → null、`result` 为 null → 空页、页数。
  - `RegionPolicy`：最近 7 天（含跨月）、月份（本月到今天、往月到月末、闰年 2 月）、自定义（> 30 天无效、早于 2009-01-01 无效、晚于今天无效）、短视频过滤、追加去重、是否还有下一页。
  - `QuickEntryPolicy`：分区入口默认关、「分区」默认开、存的里没有的入口按各自默认值追加、`region:<tid>` 往返、未知 tid 丢弃。
- 测试不碰 B 站服务器。`./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
- 真机（用户）：22 个子分区各点一次，能取到数据；时间三种模式；排序切换；短视频开关；翻页；播放续页；子分区快捷入口；设置项重启保留；连点不触发 -352。
- 文档：`05` §13、§10（第二期从待办移出）、`03`（新控制器、SEARCH 桶用途）、`09`（分区页）、手动测试指南。
