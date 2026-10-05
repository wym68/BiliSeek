# 本地列表做成和收藏夹一样：内页管理、三点与右滑删除、自定义图标

日期：2026-10-04
范围：
- `app`：
  - `ui/LibraryScreen.kt`（列表页行、`PlaylistDetail`）、`ui/AddToListPickerDialog.kt`
  - 新增 `ui/PlaylistIcons.kt`（预设图标 + 选择器 + 新建 / 编辑弹窗）
  - `ui/VideoMenuPolicy.kt`、`AppViewModel`、`MainActivity`
  - `data/`：`PlaylistEntity.icon`，DB v8 → v9
- `:design-system`：`BsTopBar` 加可选的标题图标

## 背景（用户原话，附真机截图：本地列表行上的「改名 / 删除」被圈出）

> 现在的本地列表应该做成和默认收藏夹一样的功能。改名和删除应该放在打开收藏夹后的按钮。里面的条目也是添加三点功能按钮和右滑删除功能。保持现在的长按条目可拖动顺序的功能。本地列表名字之前也可以加一个图标，而且这个图标可以自定义。

用户选定：
- 图标在新建、编辑弹窗里和名字一起选。
- 图标用一组预设图标，不选就用默认的播放列表图标。
- 图标除了列表页名字前，还显示在列表内页标题前和「加入列表」弹窗里。

## 现状

- 列表页每行是名字加「改名」「删除」两个文字按钮。点「删除」**没有确认，直接删**。
- 内页（`PlaylistDetail`）每条有个「移除」文字按钮，长按可以拖动排序，没有三点菜单。
- `PlaylistEntity` 只有名字和时间。打开列表时，内页用的是那一刻的快照。

## 方案

### 1. 图标

- `PlaylistEntity` 加 `icon: String = ""`，存预设图标的 key，空串表示默认。
  - 迁移 `MIGRATION_8_9`：`ALTER TABLE playlist ADD COLUMN icon TEXT NOT NULL DEFAULT ''`，写法同以前的 `MIGRATION_1_2`。
  - DB 升到 v9，导出 `9.json`，`MigrationTest` 补 8→9。
- `ui/PlaylistIcons.kt`：
  - 20 个预设图标，key 存库、顺序固定：播放列表（默认）、音符、耳机、心形、星星、月亮（睡前）、咖啡、健身、通勤、学习、工作、游戏、电影、话筒、播客、钢琴、冥想、旅行、灯泡、宠物。
  - `playlistIcon(key)`：空串或不认识的 key（以后删掉的图标）都返回默认图标。
- 选择器 `PlaylistIconPicker`：放在弹窗里名字输入框下面，一排一排的圆角图标，点一个选中（主色底），默认选中当前图标。
- 新建 / 编辑弹窗 `PlaylistEditDialog`：外壳是现有的 `InputDialog`，内容是名字 + 图标。下面几处都用它：
  - 列表页「新建」
  - 内页「编辑」
  - 「加入列表」里的「新建歌单并加入」和三点里的编辑（原来叫「重命名歌单」，现在也能改图标，改叫「编辑歌单」）
- 显示：
  - 列表页每行名字前（原为主色；2026-10-04 按用户要求三处都改为灰黑 `onSurfaceVariant`，与「稍后再看」同色，见功能优化篇 13.50）
  - 列表内页标题前：`BsTopBar` 新增可选参数 `titleIcon`，其他调用不受影响
  - 「加入列表」弹窗里每个本地列表名字前

### 2. 列表页

- 每行改成「图标 + 名字 + 右侧箭头」，整行点开内页。去掉「改名 / 删除」，和收藏夹列表一样。

### 3. 列表内页

- 标题前显示图标，标题右侧放「编辑」（铅笔）和「删除」（垃圾桶）：
  - 编辑：`PlaylistEditDialog`，改名字和图标。保存后内页标题立刻更新（`_openedPlaylist` 跟着换成新值）。
  - 删除：`BsConfirmDialog`「确定删除「X」？列表里的条目会一起移除，视频本身不受影响。」，红色「删除」。确认后回到列表页再删。
- 条目：
  - 右滑（共用 `SwipeToRemoveRow`）或三点菜单最后的「删除」（`VideoMenuSheet` 传 `onRemove`）：立刻从列表移除，底部提示「已从「X」移除」带「撤销」。
    - 本地数据不用等：直接移除，撤销时按原来的 `sortKey` 放回原位（`restoreToPlaylist`）。
    - 只有最近一次能撤销，和其他列表一样。
  - 三点菜单项同搜索结果。本地条目没有 UP 编号，所以没有「查看UP主」（`PlaylistTrack.toSearchVideo`）。
  - 「移除」文字按钮去掉。
- **长按拖动排序保留**，和右滑不冲突：
  - 直接横滑：长按还没触发就超出触摸阈值，长按取消，横滑交给右滑删除。
  - 按住不动直到长按触发再拖：拖动事件由排序消费，右滑删除看到已消费的事件不会启动。
  - 拖动时的上下位移和层级加在整行外层，不被右滑行的裁剪截掉。
  - 提示文字改为「长按拖动排序，右滑删除」。

### 4. 撤销提示

`AppViewModel.playlistRemovalNotice` 和 `undoPlaylistRemoval()`。`MainActivity` 用与队列、远程列表同一个提示位置，三处共用一个「弹撤销提示」的小函数，新提示出来先收起旧的。

## 不做

- 不显示每个列表的条数。
- 不把弹窗文案里的「歌单」统一改成「列表」。
- 不改收藏夹、稍后再看。

## 测试与验证

- 先写测试，确认失败再实现：
  - `PlaylistIconsTest`：key 不重复、默认图标、空串和不认识的 key 回到默认
  - `VideoMenuPolicyTest`：本地条目映射
- androidTest（只编译核对）：
  - `MigrationTest` 补 8→9
  - `DialogsUiTest` 随回调签名调整
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`。不装机：用户要求攒一批再装。
