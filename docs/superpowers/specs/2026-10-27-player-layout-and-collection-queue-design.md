# 合集第二轮：播放页新布局 + 点当前集把合集放进列表

日期：2026-10-27
前置：`docs/superpowers/specs/2026-10-26-collections-design.md`（分支 `feat/collections`，第一轮已实现、10-27 10:24 装机）。
范围：`:core:player` 队列（`QueuePolicy`、`PlayerConnection`）；`:app` 播放页（`PlayerScreen`、`PlayerSwitchRow.kt`、`PlayerSheets.kt`、`VideoDescription.kt` / `VideoDescriptionPolicy.kt`）、合集（`CollectionSheet.kt`、`CollectionController`、`CollectionPolicy`）、`AppViewModel`、`MainActivity`、`AppShell`。

## 背景（用户真机反馈，10-27）

1. 播放页加了合集卡片后中段放不下，要滚动才看得全。
2. 从搜索点播合集里的视频，合集不进播放列表；在合集弹层里点正在播的那一集也不会（第一轮的规则是「只关弹层」）。

## 方案（用户已选，效果图 `.superpowers/brainstorm/2060778-1793098365/content/player-layout.html`、`desc-preview.html`）

### 1. 播放页布局（顶栏 B + 状态标签 Y + 简介 P2）

- **顶栏**：左「收起」，正中是「听 | 看」切换（取代「正在播放」几个字），右边（看模式时）小窗 + 三点。切换用现有分段胶囊的紧凑版：高 32dp（原 40dp），图标与文字不变，读屏仍是单选。
- **底部块**：进度条 + 控制行整体沉到页面底部（中段占满封面与底部块之间的空间）。原来控制行下面那一排「倍速 · 听看 · 定时」（`PlayerSwitchRow`）去掉。
- **倍速 / 定时**：回到三点菜单，放在列表项最前面（「倍速」「定时」），设了值时右边写当前值（`1.5×`、`28:40` / `播完当前`，主色）。页面上：**只在设了值时**，进度条上方出现对应的小标签（现有 `PlayerChip` 样式：`1.5×`、`⏱ 28:40`），点标签直接打开对应的倍速 / 定时弹层；都没设时这一行不出现。
- **中段顺序（P2）**：标题 → 互动栏 → 合集卡片 → 简介行（「简介 展开 ⌄ · 播放量 · 时间」）→ **简介正文预览**。预览占中段剩下的全部高度，能放几行放几行，最后一行末尾省略号；放不下一行就不显示。预览是纯文字（时间点、链接不可点），点预览或「展开」都打开现有的全文弹层。简介为空（`VideoDescriptionPolicy.shouldShow` 为假）时没有预览，同现在。
- 正常屏幕中段不滚动：标题、互动栏、合集卡片、简介行按自然高度排，剩下的高度都给预览。屏幕特别矮、这几行放不下时，预览缩到 0 行，这几行改成可上下滑。

### 2. 合集弹层里点正在播的那一集

- 不重新起播、不打断：把整个合集换成列表里的临时块，当前在播的视频落在合集里它的位置；它已展开的分 P 原样保留（在播 P2 仍是 P2）；合集其余各集轮到时再取分 P。带图钉的条目不动；当前视频本身带图钉时，它保留图钉，合集其余各集排在它前后。
- 替换掉的临时条目 ≥ 2 项时，同点击播放一样弹「已替换队列中的 N 项 · 撤销」，撤销后队列恢复、播放不受影响；否则提示「已把「合集名」放进播放列表」。
- 点别的集：同第一轮（整个合集作为临时块，从那一集起播）。从搜索等处点播合集里的视频仍只播这一个（用户本轮没有要求改）。

### 3. 接口改动

- `:core:player` 新增公开方法 `PlayerConnection.adoptContext(items: List<PlaybackRequest>, index: Int): Int?`：`items[index]` 不是当前在播的视频时什么都不做、返回 null；否则返回被替换掉的临时条目数。决策写成纯函数 `resolveQueueOnAdopt`（`QueuePolicy.kt`，`internal`）。CLAUDE.md 与 `docs/10-播放队列工单.md` 里「队列公开新增只有…」那句同步加上它。
- `:app`：`CollectionController` 构造参数加一个「换进列表」的宿主回调；`CollectionSheetHandlers` 加 `onQueueCollection`；`PlayerMoreMenuSheet` 加 `onOpenSpeed` / `onOpenTimer`。

## 测试（全部离线）

- `QueuePolicyTest`：`resolveQueueOnAdopt`——只有当前一条临时、当前视频已展开多个分 P、前后有固定条目、当前条目本身固定、越界。
- `CollectionPolicyTest`：放进列表后的提示（替换 ≥ 2 项时不另提示）。
- `VideoDescriptionPolicyTest`（新建或补）：预览行数 = 可用高度 ÷ 行高，向下取整、不小于 0；行高为 0 时 0 行。
- `PlayerUiTest`（需设备，至少编译）：顶栏没有「正在播放」、有听看切换；没设倍速 / 定时时页面上没有对应标签、从三点菜单能打开倍速 / 定时弹层；设了值后进度条上方出现 `1.5×` / `28:40` / `播完当前`。

## 不做

- 从搜索等处点播合集视频时自动把合集放进列表。
- 简介预览里的时间点 / 链接可点。
- 全屏、小窗、迷你条的布局。
