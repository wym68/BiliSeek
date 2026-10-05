# 弹窗风格统一：加入列表 / 输入名字 / 删除下载 / 总结菜单 / 确认框

日期：2026-10-02
范围：`app` 模块的弹窗（`ui/AddToListPickerDialog.kt`、`ui/LibraryScreen.kt`、`ui/DownloadsScreen.kt`、`ui/MeteredVideoDialog.kt`、`ui/SummaryReaderScreen.kt`、`ui/ActionListSheet.kt`、`ui/components/SettingsComponents.kt`）、`:design-system` 的 `BsConfirmDialog`，以及加入列表里条目数量那条数据链的清理。
承接 `0c17374`（下载对话框改成与设置页同一风格的底部弹层）。

> **2026-10-04 更新**：加入列表和 7 处输入名字的弹窗已改回屏幕中间。外壳是 `CenterDialog`，`InputSheet` 改名为 `InputDialog`，里面的排法不变。
> 详见 [add-to-list-centered](2026-10-04-add-to-list-centered-design.md)。下文第 1、2 节写的是 10-02 当时的做法。

## 背景

用户反馈两张截图：「加入列表」弹窗里每行带着数量（「默认收藏夹 (3个内容)」），要求不显示；这个弹窗和「新建歌单」弹窗的外观与整个系统的 UI 不搭，并要求再排查还有哪些弹窗不适配。

## 排查结果

应用里选择类的弹窗已经统一成底部弹层（设置页的 `SettingsSheet` 系列、下载弹层、操作表 `ActionListSheet`、播放页各弹层）；确认类统一用 `BsConfirmDialog`。以下几处还是 Material 默认的样子：

| 弹窗 | 现状 | 处理（用户已确认全部做） |
| --- | --- | --- |
| 加入列表选择器 | `AlertDialog` 居中大卡片，行带数量 | 改成底部弹层，去掉数量 |
| 输入名字的弹窗，共 7 处 | `AlertDialog` + `OutlinedTextField`：列表页的新建 / 重命名歌单（`NameDialog`）、新建 / 编辑收藏夹，加入列表里的新建歌单并加入、新建收藏夹并加入、编辑收藏夹、重命名歌单 | 共用一个输入弹层 |
| 下载页「删除下载」选择框 | `AlertDialog` 里放两行纯文字 | 改成底部弹层，两项各带一句说明 |
| 总结阅读页的两个下拉菜单 | `DropdownMenu`：右上「更多」、版本切换 | 改成底部操作表（应用别处的「更多」都是操作表） |
| 四个确认框 | 直接写 `AlertDialog`，外观与 `BsConfirmDialog` 一样：移动网络询问、删歌单、删收藏夹（加入列表里一处、列表页一处） | 统一改用 `BsConfirmDialog`，外观不变 |

已经一致、不动：`BsConfirmDialog` 的现有 9 处、设置页与下载弹层、`ActionListSheet`、播放页各弹层、投币、AI 总结风格、回复、播放队列、评论弹层、`AppToast`。
小差异不动：`ActionListSheet` 的拖动条压缩过，其他弹层是默认高度。

## 方案

### 1. 加入列表：去掉数量，改成底部弹层

外壳用 `SettingsSheet`（与下载弹层同款），内容：

- 标题「加入列表」；
- 分区「本地歌单」「B站收藏夹」（主色小字，右侧一个「新建」文字按钮；收藏夹区只在登录后出现）；
- 每行：名字（单行省略）+ 右侧三点（歌单：重命名 / 删除；收藏夹：编辑 / 删除，仍是 `ActionListSheet`）。**只显示名字，不再显示数量**；
- 去掉：行间分割线、每区最多 3 行的限高、自制滚动条（`SimpleVerticalScrollbar`）和 `RowMoreIcon`（改用标准 `IconButton`）、底部「取消」按钮（下滑、点空白、返回都能关）。整个弹层可滚动。
- 行为不变：未登录只有本地歌单一区；没有歌单且未登录时仍直接进「新建歌单并加入」；点行 = 加入并关闭。

删除收藏夹确认语里的「N 个视频」是提醒删除影响的警告，不是列表显示，保留。列表页收藏夹行的「N 个 · 同步」也不动。

**数量那条数据链整条清掉**（只服务于这个显示，去掉后没人用）：弹窗参数 `playlistTrackCounts` → `MainActivity` 的收集与传参 → `AppViewModel.playlistTrackCounts` → `LibraryRepository.observePlaylistTrackCounts` → `PlaylistDao.observeTrackCounts` 与 `PlaylistTrackCount`。删 DAO 查询不影响 Room 表结构（schema 不变）。

### 2. 共用的输入弹层 `InputSheet`（`ui/components/InputSheet.kt`，新增）

- 外壳也是 `SettingsSheet`：标题 + 输入框（槽位）+ 底部一对按钮「取消」（`OutlinedButton`）/ 确认（`Button`，`confirmEnabled` 控制可点），与下载弹层同一排法。
- 槽位把 `FocusRequester` 交给调用方挂到第一个输入框上；弹层出现时自动聚焦并弹出键盘（原来的对话框也是一出来就聚焦）。聚焦失败不崩（`runCatching`）。
- 键盘弹出时弹层顶起来：`SettingsSheet` 的内容列在 `navigationBarsPadding()` 之后加 `imePadding()`；没有键盘时 IME 内边距为 0，对其他用 `SettingsSheet` 的弹层没有影响。
- 输入框用 `OutlinedTextField`（与 AI 设置页一致），左右 `BsSpacing.xl`，多个输入框之间留 `BsSpacing.md`。
- 7 处调用点保持原来的标题、字段标签和确认按钮文案（`DialogsUiTest` 依赖「新建歌单并加入」「歌单名」）；`NameDialog` 原来没有字段标签，补上「歌单名」，确认键在名字为空时不可点（与其他几处一致）。

### 3. 下载页「删除下载」→ 底部弹层

`SettingsSheet`：标题「删除下载」，说明行是任务标题；一句话「文件在X，删除时怎么处理源文件？」；两个整行可点的选项，各带一句说明：

- 「仅清记录」：只删下载记录，文件留在原处；
- 「记录和源文件一起删」（标题用 error 色）：同时删除X里的文件。

没有「取消」按钮，下滑 / 点空白关闭。

### 4. 总结阅读页的两个下拉菜单 → 操作表

- 右上「更多」：`ActionListSheet`，标题是总结标题（没有就「AI 总结」），项目与现在一致（另存为… / 查看原文或查看总结 / 取消或生成新版本 / 删除此版本或删除 / 删除全部版本）；
- 版本条上的「版本 2/3 ▾」：点开 `ActionListSheet`，标题「切换版本」，每个版本一行，文字沿用现在的写法（当前版本前带「✓ 」、失败 / 生成中带后缀）。
- `ActionListSheet` 只对文字恰好是「删除」的项标红、配图标；总结菜单里有「删除此版本」「删除全部版本」，所以改成以「删除」开头的项都标红、配删除图标（现有各处菜单的「删除」不受影响）。

### 5. 确认框统一用 `BsConfirmDialog`

- 移动网络询问：`BsConfirmDialog(confirmLabel = "这次就看", dismissLabel = "继续只听")`，行为不变（「这次就看」只放行一次，不写回设置）。
- 删歌单（加入列表里）、删收藏夹（列表页一处）：`destructive = true`。
- 删收藏夹（加入列表里）带忙碌态（清空视频 + 删文件夹的整段过程里转圈、两个键不可点、点空白也关不掉）：`BsConfirmDialog` 加一个 `busy: Boolean = false` 参数，忙碌时确认键显示小转圈和「处理中…」、两个键不可点、`onDismissRequest` 不响应——与现有行为一致。

## 不做

- 不动已经一致的弹层，不动列表页收藏夹行的「N 个」显示。
- 不给确认类弹窗改成底部弹层（确认类用 `BsConfirmDialog` 是系统既有的约定）。
- 不为输入弹层加「回车确认」等新交互。
- 不统一两套弹层拖动条的高度。（2026-10-04 已改：用户第三次嫌默认横杠留白大，全部底部弹层统一用 `CompactDragHandle`，见 [player-menu-and-sheet-handle](2026-10-04-player-menu-and-sheet-handle-design.md)。）

## 风险

- 键盘弹出时输入弹层能不能被顶起来、输入框能不能自动聚焦，没有在真机上验证（`SummaryStyleSheet` 里已有弹层放输入框的先例，但那里没处理键盘）。万一不对，调 `InputSheet` / `SettingsSheet` 的内边距即可。
- 加入列表不再限高：收藏夹很多时弹层会很高，靠整体滚动；B 站收藏夹数量有上限，本地歌单一般很少。
- 输入弹层叠在加入列表弹层上、三点菜单（操作表）也叠在加入列表弹层上：都是独立窗口，后开的在上，返回键先关最上面的（与原来 `AlertDialog` 上叠操作表一致）。
- 总结阅读页的菜单从贴着图标的小菜单变成底部操作表，交互有变化（用户已确认）。

## 测试与验证

- 纯界面改动，没有可抽出的判断逻辑，不新增 JVM 单测；现有单测不受影响。
- `DialogsUiTest`（androidTest）：加入列表现有两例保持（歌单列表与回调、未登录无歌单直接进新建，「新建歌单并加入」「歌单名」仍在）；
  新增：已登录时收藏夹行只显示名字、不出现「个内容」；`InputSheet` 名字为空时确认键不可点、填了之后点确认带出文字；`BsConfirmDialog` 忙碌态不响应。
  **只做编译核对，没有在设备上跑**（会卸载重装 debug 包，不在用户的真机上跑）。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin` 通过。
- 真机（由用户）：见手动测试指南。

## 文档

- `docs/09-UI规范与重构.md`：共用组件表加 `InputSheet`，`BsConfirmDialog` 一行补 `busy`，新增「弹窗选型」小节（选择类、输入类用 `SettingsSheet` 系；确认类用 `BsConfirmDialog`；更多操作用 `ActionListSheet`；不再直接写 `AlertDialog` / `DropdownMenu`）。
- `docs/05-功能优化篇.md`：新增 §13.36，总览表一行。
- `docs/BiliSeek-手动测试指南.md`：加入列表、新建 / 重命名、删除下载、总结阅读页菜单各补检查点。
