# 「加入列表」里加「稍后再看」

日期：2026-10-04
范围：`app` 的 `ui/AddToListPickerDialog.kt`、`remote/RemoteLibraryController.kt`、`remote/RemoteListPolicy.kt`、`AppViewModel`（`confirmAddToWatchLater`）、`MainActivity`。`core:network` 不改。

## 背景（用户原话）

> 加入列表没有加入稍后再看的选择

## 现状

- 所有「加入列表」入口（搜索结果、榜单、UP 空间、动态、收藏夹 / 稍后再看内容页、本地列表、已下载、播放页三点菜单、多选批量、多 P 面板）都汇到同一个弹窗 `AddToListPickerDialog`：本地歌单 + B 站收藏夹（登录后）。
- 网络层早有「加入稍后再看」：`BiliRepository.addToView(aid)`（写操作工单 06 Phase A 做的，走统一写请求：csrf、`-111` / `-101` 刷新重试一次、`-352` 冷却、写请求限速），但一直没接到界面上。

## 方案（用户已选 A：放在最上面，单独一行）

- **位置**：弹窗标题下第一行「稍后再看」，在「本地歌单」分区之前，和「列表」页顺序一致（稍后再看 → 本地列表 → 收藏夹）。
  - 图标同列表页那一行（`Icons.Outlined.Schedule`，灰黑 `onSurfaceVariant`）；不属于任何分区，没有三点菜单（稍后再看不能改名、不能删），右侧留白同左侧。
  - 只在登录后显示（同收藏夹分区）。
  - 效果图：`.superpowers/brainstorm/1852443-1791142286/content/watch-later-placement.html`，脚本 `/tmp/biliseek-fonts/build_watch_later_picker_mock.py`。
- **点了以后**：弹窗关闭，把待加的视频加进 B 站稍后再看。
  - 稍后再看按视频记、不分 P：选了多 P 里的某一 P，加进去的是整个视频；同一个视频只发一次（按 bvid 去重）。
  - aid 用 bvid 本地换算（`resolveAid`，同移除），换算不了按失败算，不额外请求。
  - 多个视频一个一个发，靠写请求限速拉开间隔，不加重试。
- **提示**（纯函数 `watchLaterAddMessage`）：
  - 全部成功：「已加入 N 项到稍后再看」（与收藏夹「已加入 N 项到收藏夹」同一说法）；
  - 全部失败：「加入稍后再看失败：原因」；
  - 部分失败：「已加入 N 项，M 项失败：原因」。
  - 原因说人话（`watchLaterFailureReason`）：`90001` →「稍后再看已满」，`90003` →「视频已失效」；其他用异常自带的说明。
- **稍后再看页**每次打开都重新拉取，加完再进去就能看到，不用另外刷新。
- 逻辑放在 `RemoteLibraryController.addToWatchLater`（稍后再看归它管）；`AppViewModel.confirmAddToWatchLater` 只负责取出待加条目、关弹窗、转交。

## 不做

- 不查「是否已在稍后再看」（要多一次请求）；已在里面的再加一次，B 站怎么处理（预期成功、挪到最前）由用户真机确认。
- 不在三点菜单里另加「稍后再看」（用户要的是「加入列表」里能选）。

## 测试与验证

- 先写 `RemoteListPolicyTest` 的提示 / 原因用例并确认编译失败再实现。
- `DialogsUiTest`：登录后最上面有「稍后再看」、点了回调；未登录不显示（只编译，不在用户手机上跑）。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；真实加入由用户在真机验证（自动化不能请求 B 站）；不装机，等用户开口。
