# 首次使用演示：不易发现的手势放一次演示

日期：2026-10-07
范围：`app`（新增 `hint/HintPolicy.kt`、`hint/HintStore.kt`、`hint/HintController.kt`、`ui/components/HintDemo.kt`、`ui/components/HintBubble.kt`、`ui/components/DemoFinger.kt`、`ui/components/LongPressHint.kt`；改 `data/SettingsStore.kt`（去掉旧的队列演示记录）、`ui/components/SwipeToRemoveRow.kt`、`ui/MiniPlayer.kt`、`ui/components/DraggableProgressBar.kt`、`ui/DynamicScreen.kt`、`SearchScreen.kt`、`ui/UpSpaceScreen.kt`、各列表页、`ui/SettingsScreen.kt`）。`core/*` 不改。

## 背景（用户原话）

> 现在对于初次使用该软件的用户，一些功能可能不知道。如在多处列表中右滑移除，minibar 播放进度条的两种拖动方法，动态页点击 up 主头像仅显示该 up 主信息或取消；等不易察觉到功能。

## 现状

- 只有播放队列有演示：第一次打开时一行自动右挪 72dp 再弹回（`SwipeToRemoveRow(demo = …)`），无文字；记录在 SharedPreferences `biliseek_hints` 的 `KEY_QUEUE_SWIPE_HINT`（`SettingsStore.queueSwipeHintShown`）。
- 其余不易发现的操作都没有任何提示。

## 方案（用户在效果图里选定：气泡 B、迷你条循序渐进 C、点按画手指 B）

效果图：`.superpowers/brainstorm/902492-1791365377/content/`（`hint-form-v2.html`、`minibar-sequence.html`、`tap-longpress.html`；脚本 `/tmp/bsmock/gen_*.py`）。

### 1. 演示清单（13 项，每项只放一次）

| # | 键 | 位置 | 演示 | 气泡文字 | 什么时候放 |
|---|---|---|---|---|---|
| 1 | `SWIPE_QUEUE` | 播放队列 | 屏上第一条非当前曲目的行右滑再弹回 | 右滑可以移除，能撤销（已下载、AI 总结为「右滑可以删除，能撤销」，露出的字也是「删除」） | 第一次打开有可删行的队列（已有；沿用旧键，老用户不重放） |
| 2 | `SWIPE_LOCAL_PLAYLIST` | 本地列表内页 | 第一行右滑再弹回 | 同上 | 第一次打开、有内容 |
| 3 | `SWIPE_FAVORITE` | 收藏夹内页（所有收藏夹共用一条记录） | 同上 | 同上 | 同上 |
| 4 | `SWIPE_WATCH_LATER` | 稍后再看 | 同上 | 同上 | 同上 |
| 5 | `SWIPE_DOWNLOADS` | 已下载（已完成列表、分 P 页共用一条记录） | 同上 | 同上 | 先碰到哪个就在哪个放 |
| 6 | `SWIPE_SUMMARY` | AI 总结列表 | 同上 | 同上 | 第一次打开、有内容 |
| 7 | `MINIBAR_SWIPE` | 迷你条 ① | 整条上移 18dp 弹回，再下移 12dp 弹回 | 往上滑打开播放页，往下滑收起 | 第一次出现迷你条 |
| 8 | `MINIBAR_DRAG` | 迷你条 ② | 进度线加粗、圆点按 5 秒一格走 4 格再退回，时间变蓝 | 左右拖进度线，每格 5 秒 | 7 放过之后的**另一次启动**里出现迷你条 |
| 9 | `MINIBAR_ZOOM` | 迷你条 ③ | 圆点跳到线上约 70% 处，刻度尺浮出，指针走 3 格，收起 | 按住进度线半秒，出刻度尺细调 | 用户**自己**第一次左右拖进度线、松手之后 |
| 10 | `DYNAMIC_UP_FILTER` | 动态页「最常访问」 | 手指点第 1 个头像两下：第一下出蓝圈，第二下蓝圈消失 | 点头像只看这个 UP 的动态，再点一次取消 | 第一次进动态页、头像行有内容 |
| 11 | `DYNAMIC_VIDEO_MENU` | 动态页第一张视频卡片的视频行 | 手指长按：进度环半秒转满，那一行变深 | 长按视频：加入列表、队列或下一首播放 | 10 放过之后的**另一次进入**动态页，且屏上有视频卡片 |
| 12 | `SEARCH_MULTI_SELECT` | 搜索结果第一条 | 同 11 | 长按一条进入多选，可以一次加好几条 | 第一次出现视频搜索结果 |
| 13 | `UPSPACE_MULTI_SELECT` | UP 空间视频列表第一条 | 同 11 | 同 12 | 第一次打开 UP 空间、有视频 |

- 「另一次启动」= `MainActivity` 冷启动（`savedInstanceState == null`）计一次，存一个启动序号；7 放完时记下当时的序号，8 要求当前序号更大。
- 「另一次进入」动态页 = 离开动态页（切 tab / 压栈）再回来；只在内存里记，进程重启也算新一次进入。

### 2. 共同规则

- **同一时间最多一个**：有演示在放时，其他满足条件的等下次（下次进入该页面 / 下次出现迷你条）。
- **演示全是画出来的**：不发网络请求、不 seek、不打开播放页、不弹菜单、不进多选、不改筛选。点头像的蓝圈只是画面。
- **用户自己用过就当看过**：用户自己做了这个操作（右滑删过、上滑开过播放页、左右拖过、长按出过菜单 / 进过多选、点头像筛过），就记为已看过，不再演示。拖进度线时如果 8 还没放，同时记 8 已看过；9 照常在这次松手后放。
- **记为已看过的时机**：演示完整放完。中途离开页面、页面被盖住，下次再放。
- **不放的场合**：全屏、画中画、App 不在前台、有弹层 / 对话框盖着主界面时（后两种都表现为本窗口没有焦点）。
- **列表有内容才放**；加载中、空列表、出错时等着，列表出来了再放（同一次进入内有效）。

### 3. 演示动作

- **气泡（`HintBubble`）**：白底（浅色 `surfaceContainerLowest`、深色 `surfaceContainerHigh`，同 `AppToast`），圆角 12dp，内边距 10/14dp，`bodyMedium` 单行，阴影；12dp 尖角指向控件，控件在上方时气泡在下、反之在上；横向尽量对齐控件、不超出屏幕左右 16dp。演示动作开始时出现，放完后再停 4 秒消失；**手指碰屏幕任何地方立刻消失，这次触摸照常生效**（不拦截）。
- **手指（`DemoFinger`）**：28dp 灰色半透明圆（填充 `rgba(60,60,70,.22)`、1.5dp 描边 `.35`）。
  - 滑动：手指跟着被拖动的东西一起走（列表行、迷你条、进度圆点 / 刻度尺指针）。
  - 点按：手指出现，主色描边圆从 0.8 倍扩到 1.9 倍淡出。
  - 长按：手指按住，外圈 40dp 主色进度环在系统长按时间（约半秒）内转满，转满时出一次点按波纹；那一行同时显示按下态（`onSurface` 10%）。
- **右滑演示**：挪动距离从 72dp 改为 **88dp**（「移除」/「删除」两个字能露全），停 400ms 后弹回；加手指。
- **迷你条 ① / ② / ③**：节奏照效果图（① 约 5 秒、② 约 6 秒、③ 约 7 秒）；② ③ 时间文字同真实拖动时一样变主色。③ 的刻度尺复用现有刻度尺绘制，只是指针位置由演示驱动。
- 演示期间不震动。

### 4. 结构

- `hint/HintPolicy.kt`（纯函数，单测）：`enum class HintKey(prefKey)`（上表 13 项；`SWIPE_QUEUE` 沿用旧键 `queue_swipe_hint_shown`）、`PersistedHints`（看过哪些、冷启动序号、①记为看过时的启动序号）、`HintState`（加上正在放的、全屏 / 画中画、本进程第几次进入动态页、⑩在哪次进入记为看过、③能不能放）；`canStartHint`、`shouldForfeitHint`（被别的演示占着、而它本来能放 → 这次进入放弃）、`startHint` / `cancelHint` / `markHintSeen` / `applyUserAction` / `resetHints`。
- `hint/HintStore.kt`：`HintRecords` 接口 + `SharedPrefsHintRecords`（SharedPreferences `biliseek_hints`，同步读）。旧的 `SettingsStore.queueSwipeHintShown` 删除。
- `hint/HintController.kt`（功能控制器，`AppViewModel.hints`）：`state: StateFlow<HintState>`、`onColdStart()`、`setEnvironmentBlocked()`、`enterDynamic()`、`tryStart(key)`、`finish(key)`、`cancel(key)`、`onUserAction(key)`、`onTouch()`、`reset()`。只在主线程调用；持久部分一变就写盘。
- 界面取用：`ui/components/HintDemo.kt` 的 `LocalHints`（`MainActivity` 提供，同 `LocalShowMessage`，不经各页面参数传递）、`rememberHintDemo(key, ready)`（本窗口有焦点、`ready` 为真时尝试开始；返回 `running` 和 `finish()`；离开组合时没放完就 `cancel`）、`Modifier.hintTouchObserver()`（挂在 `AppShell` 根和队列面板根，只看不拦截）。「有弹层盖着」= 当前窗口没有焦点（队列面板自己是独立窗口，里面的演示照放）。
- 组件：`HintBubble`（`Popup`、不抢焦点、尖角跟着锚点）、`DemoFinger` + `DemoFingerState`（显示、点按波纹、长按进度环）、`LongPressHint`（包住一行：长按演示 + 气泡，⑪⑫⑬共用）。
- 各控件接入：`SwipeToRemoveRow(hintKey, showHint)`（距离 88dp、加手指、用户删过记为看过）、`MiniPlayer`（①）、`DraggableProgressBar`（②③，演示只改显示用的拖动状态，不调 `onSeek`）、`MostVisitedUpRow` 第一个头像（⑩）、`DynamicCard(videoHint)`（⑪）、搜索 / UP 空间结果行（⑫⑬）。
- 设置页「界面」分组最后加一行「重新显示操作提示」：`hints.reset()`，提示「已重置，下次碰到时会再演示」（走 `LocalShowMessage`）。
- `AppViewModel` 删去 `queueSwipeHintShown` / `markQueueSwipeHintShown`；`PlayerQueueSheet` 删去 `showSwipeHint` / `onSwipeHintShown` 参数。
- 已知局限：没关注任何 UP（「最常访问」不出现）时⑩放不了，⑪也就一直不放。

## 不做

- 开机引导轮播、聚光灯遮罩、「知道了」按钮。
- B 类其余项（长按点赞三连、看模式长按 2 倍速、横屏自动全屏）和 C 类（全屏手势、图片长按保存、长按选字、拖动排序、消息长按回复）。
- 分 P 面板的长按多选（与 12 同一手势，用户在搜索结果里已学过）。

## 风险

- 气泡用 `Popup` 不拦截触摸：需要确认按下事件能同时让气泡消失、又不被吃掉；做不到时退回「4 秒自动消失 + 下一次触摸透传」。
- 「用户自己用过就当看过」要在 7 个控件的真实手势回调里各加一次 `onUserAction`，漏加只会多放一次演示，不影响功能。
- 演示期间用户恰好去操作同一个控件（如演示右滑时去拖那一行）：演示立刻停止并复位，记为已看过。

## 测试与验证

- 先写 `HintPolicyTest`（各项触发条件、同一时间一个、另一次启动 / 另一次进入、用户操作记为已看过、重置）并确认编译失败，再实现。
- `SettingsStore` 的 SharedPreferences 读写（含旧键兼容）：项目没有 Robolectric，不写 JVM 测试；键名映射放进 `HintPolicy` 的纯函数里测，实际存取由真机验证（装过旧版、看过队列演示的手机升级后不应重放 #1）。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；动画手感、出现时机由用户真机验证；不装机，等用户开口。
- 同步文档：`docs/05-功能优化篇` §13、`docs/BiliSeek-手动测试指南` 新增步骤、`docs/03-架构设计篇`（HintController / HintPolicy）。
