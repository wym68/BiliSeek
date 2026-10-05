# 收藏夹 / 合集 / 稍后再看：页内管理按钮与右滑移除

日期：2026-10-04
范围：
- `:core:network`：新增取消订阅合集的写接口
- `app`：
  - `remote/RemoteLibraryController.kt`，新增 `remote/RemoteListPolicy.kt`
  - `ui/LibraryScreen.kt`、`ui/WatchLaterScreen.kt`、`ui/SeriesScreen.kt`、`ui/VideoMenu.kt`、`ui/VideoMenuPolicy.kt`
  - 右滑组件从 `ui/components/PlayerQueueSheet.kt` 抽出来共用
  - `data/`：收藏夹目录快照的条数就地减一
  - `MainActivity` / `AppViewModel` 接线

## 背景（用户原话）

> 收藏夹中的文件夹长按会弹出编辑和删除的功能选项，这个功能改到在进入文件夹后标题右侧进行按钮布置。合集中也是进入一个合集后，有取消合集的按钮。稍后再看中的条目，收藏夹中具体文件夹中的条目可以右滑从稍后再看中去掉，以及在三点按钮中添加删除功能。

用户已选：
- 右滑或点「删除」后，条目**立刻移除，可撤销**，和播放队列的右滑删除一样。
- 收藏夹列表上的长按菜单**去掉**（「改到」）。

## 方案

### 1. 收藏夹：编辑 / 删除挪到内容页标题右侧

- 收藏夹内容页（`FavoriteDetail`）顶栏右侧放两个图标按钮：
  - 「编辑收藏夹」（铅笔）：弹 `InputDialog`，标题「编辑收藏夹」，预填当前名字，确认键「保存」。
    - 保存成功后，页面标题跟着改：`RemoteLibraryController.renameOpenedFavorite`。
  - 「删除收藏夹」（垃圾桶）：弹 `BsConfirmDialog`，文案同原来长按里的「确定删除「X」？收藏夹内的视频不会被删除。」，红色「删除」。
    - 确认后先关掉内容页回到列表，删除在后台进行，做法不变（宿主 `AppViewModel.deleteFavoriteFolder`），完成后提示。
- 收藏夹列表：去掉长按（`combinedClickable` 改成 `clickable`），连同长按菜单、编辑 / 删除弹窗和对应状态。「新建收藏夹」不动。

### 2. 合集：内容页标题右侧「取消订阅」

- 合集内容页（`SeriesScreen`）顶栏右侧放文字按钮「取消订阅」。用文字是因为没有一看就懂的图标。用「取消订阅」而不是「取消合集」，和合集页「还没有订阅合集」的说法一致。
- 点了先弹 `BsConfirmDialog`：
  - 标题「取消订阅合集」，正文「确定不再订阅「X」？它会从合集列表里移除。」
  - 两个键是「保留」和红色「取消订阅」。不用「取消」，免得和「取消订阅」看混。
- 网络：新增 `BiliApi.unfavSeason`（`POST x/v3/fav/season/unfav`，字段 `platform=web`、`season_id`、`csrf`）和 `BiliRepository.unfavSeason(seasonId)`。
  - 走现有的 `writeRequest`：写节流、csrf、-101 / -111 只重试一次、-352 进冷却。
  - 接口与字段对照了参考项目 BiliPai 的实现。
- 控制器 `unsubscribeOpenedSeries()`：
  - 成功：从合集列表里去掉这一项（`totalCount` 减一），关掉内容页，提示「已取消订阅「X」」。
  - 失败：提示原因，页面不动。
  - 请求进行中再点不重复发。

### 3. 稍后再看 / 收藏夹条目：右滑移除 + 三点菜单「删除」

- **右滑**：把播放队列里私有的 `SwipeToRemoveRow` 抽到 `ui/components/SwipeToRemoveRow.kt` 共用，手感不变：
  - 向右滑过行宽 40% 或快速甩动就删，否则弹回
  - 红底，左侧垃圾桶和「删除」
  - 前景色作为参数：队列面板用 `surfaceContainerLow`，列表页用页面底色 `background`
  - 首次演示只有队列在用
- **三点菜单**：`VideoMenuAction` 末尾加 `Remove("删除")`。
  - 只有宿主给了移除动作才显示：`videoMenuActions(authorMid, removable)`，`VideoMenuSheet(…, onRemove)`
  - 显示的页面只有稍后再看和收藏夹内容页；榜单、合集、搜索结果不显示
  - 文字以「删除」开头，`ActionListSheet` 会自动标红、配图标
- **移除流程**（控制器）：
  1. 条目立刻从列表里拿掉，发出提示「已从稍后再看移除」/「已从「收藏夹名」移除」，带「撤销」。
  2. 提示停留 4 秒（同队列）。到 5 秒才真正提交：
     - 稍后再看：`delToView(aid)`
     - 收藏夹：`dealFavorite(aid, del = [mediaId])`
     
     比提示多 1 秒，免得最后一刻点了撤销却已经提交。点「撤销」就取消提交、条目放回原位，不用再发请求。
  3. 只有最近一次移除能撤销，和队列一致；之前的照常到点提交。
  4. 提交失败：条目放回原位，提示「移除失败：原因」。撤销窗口内刷新或退出再进，正等着提交的条目按 bvid 去掉，不会又冒出来。
  5. 提交成功：收藏夹在本地目录快照里把这个收藏夹的条数减一（`FavoriteFolderDao.decrementCount`），不多发请求。
- **aid**：
  - 稍后再看条目自带 `aid`，没有（≤ 0）时用 bvid 本地换算。换算失败按「移除失败」处理。
  - **绝不传 `null`**：`delToView(null)` 的意思是「清空所有已看过的」。
  - 收藏夹条目只有 bvid，同样本地换算（`bvToAv`，和宿主现有做法一致）。
- **撤销提示**：`RemoteLibraryController.removalNotice` 发文字，`MainActivity` 用队列撤销同一个提示位置（迷你播放条上方）弹带「撤销」的提示，点了调 `undoRemoval()`。
  - 两类撤销提示共用一个位置。新提示出来时先收起旧的，免得新提示排队等旧的、撤销时间被吃掉。
  - 队列那边的收集也加同样一行。
- **收藏夹翻页（这次顺带要改）**：
  - 问题：删过条目后，服务器上的序号整体前移，按「页码 + 1」往下翻会漏掉几条。
  - 改为：按「已加载、且服务器上还在的条数」（列表里的条数 + 正等着提交的条数）算该取哪一页（`favoriteFetchPage`）。新一页里已在列表、或正等着提交移除的条目按 bvid 去掉（`appendUnseen`），这样也不会出现重复 key。
  - 界面上「滚到底自动加载」的触发改为跟条数走，不再跟页码走：重取同一页时页码不变，按页码就不会再触发。
  - 万一一页取回来没有新条目、接口还说有更多（别处同时改了收藏夹），就停止自动加载，不在底部一直转圈；重新进入文件夹即可。
- **加载失败后不再自动重来（实现时发现的原有问题）**：收藏夹、合集内容页底部的自动加载在失败后会重新进入组合，立刻再打一次，等于对 B 站不停自动重试。改为失败后只等用户点「重试」。合集列表页底部有同样的问题，当时不在范围内；用户确认后已修（docs/05 §13.41）。

### 纯函数（`remote/RemoteListPolicy.kt`，有单测）

- `favoriteFetchPage(loadedOnServer, pageSize)`：下一次取第几页（从 1 起）。
- `appendUnseen(existing, incoming, excludedKeys, key)`：接上新一页，去掉已有的和正等着提交移除的。
- `restoreAt(items, item, index, key)`：撤销或提交失败时放回原位。原位超出长度就放末尾；已在列表里（比如刚刷新过）就不重复放。
- `resolveAid(aid, bvid)`：有 aid 用 aid，否则用 bvid 换算，都不行返回 null。

## 不做

- 不改收藏夹删除本身的做法（先逐个移出视频再删文件夹）。
- 不给合集列表、收藏夹目录加右滑。
- 合集页的「取消订阅」只按合集（season）处理。B 站「收藏和订阅」里如果混有别人的收藏夹，现有代码本来就当合集打开；对它取消订阅会收到接口报错，按失败提示处理。
- 不做批量移除。

## 风险

- 右滑和系统返回手势：从屏幕左边缘开始右滑会被系统当成「返回」，要从行中间开始滑（和队列面板一样）。
- 提交是延后的：撤销窗口内退出页面，照常到点提交（控制器挂在宿主上，不跟页面走）；撤销窗口内被系统杀掉进程，这次移除不会提交，下次打开还在。
- 撤销提示和队列的撤销提示共用一个位置，后来的会顶掉先前的，被顶掉那条就不能再撤销了（照常到点提交）。
- 默认收藏夹 B 站不让删。「删除」按钮照常显示，删了会收到接口报错提示，和原来长按一样。

## 测试与验证

- 先写测试：
  - `RemoteListPolicyTest`：翻页、接页去重、放回原位、aid 解析
  - `VideoMenuPolicyTest`：可移除时末尾多「删除」，不可移除时不变
  
  先确认编译失败（RED），再实现。
- 写接口照现有惯例：薄包装，不单独测。写请求编排已有 `WriteRequestTest` 覆盖。
- androidTest 只做编译核对，不在设备上跑。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`。不装机：用户要求攒一批修改再装。
- 真机（由用户）：
  - 右滑手感、撤销、提交后在 B 站上是否真的移除
  - 取消订阅后合集列表
  - 收藏夹删过几条后往下翻是否漏条

## 文档

- `docs/05-功能优化篇.md`：§13.39 和总览表。
- `docs/09-UI规范与重构.md`：共用组件表加 `SwipeToRemoveRow`；`VideoMenuSheet` 一行补「删除」。
- `docs/BiliSeek-手动测试指南.md`：收藏夹、合集、稍后再看的检查点。
- `docs/03-架构设计篇.md`：`RemoteLibraryController` 一行补「移除 / 取消订阅」。
