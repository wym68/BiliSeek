# 首次使用演示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 13 处不易发现的手势（6 个列表右滑移除、迷你条上滑 / 拖进度 / 刻度尺、动态页点头像筛选、动态视频长按菜单、搜索 / UP 空间长按多选）各放一次首次演示：控件原地做一遍动作 + 画出来的手指 + 指着控件的小气泡；设置里能重置。

**Architecture:** 判定全在纯函数 `hint/HintPolicy.kt`（有单测）；`hint/HintController`（`AppViewModel.hints`）持有状态并经 `HintRecords` 落盘（SharedPreferences `biliseek_hints`）；界面经 `LocalHints`（CompositionLocal，`MainActivity` 提供）取用，`rememberHintDemo(key, ready)` 决定某个控件此刻是否在演示。演示只改画面（偏移量、显示用的拖动状态、画出来的手指 / 进度环），不触发任何业务回调、不发请求。

**Tech Stack:** Kotlin、Jetpack Compose（Material3、Popup、Animatable）、JUnit4。

设计稿：[2026-10-07-first-use-hints-design.md](../specs/2026-10-07-first-use-hints-design.md)（效果图在 `.superpowers/brainstorm/902492-1791365377/content/`）

## Global Constraints

以下抄自设计稿，每个任务都隐含遵守：

- 每项**只放一次**；**同一时间最多一个**；被别的演示占着、本来能放的，这次进入页面就放弃（不在前一个放完后接着连放）。
- 演示**全是画出来的**：不发网络请求、不 seek、不打开播放页、不弹菜单、不进多选、不改筛选。
- 演示动作开始时出现气泡，动作放完后气泡再停 **4 秒**（`HintTiming.BUBBLE_HOLD_MS = 4_000L`）后演示结束、记为看过；动作在出现后 **500ms**（`HintTiming.START_DELAY_MS = 500L`）才开始。
- **手指碰屏幕任何地方**：当前演示立刻结束、记为看过，这次触摸照常生效（只观察、不拦截）。
- **用户自己用过就当看过**：自己右滑删过、上滑开过 / 下滑收起过迷你条、左右拖过进度线、按住出过刻度尺、点过「最常访问」头像、长按出过视频菜单、长按进过多选。自己拖完进度线还会让 ③ 可以放（`zoomArmed`）。
- 中途离开页面（控件离开组合）不记为看过，下次再放。全屏、画中画不开始；本窗口没有焦点（App 在后台、弹层 / 对话框盖着）不开始。
- 右滑演示挪 **88dp**（原 72dp），停 400ms 后弹回，加手指。
- 气泡：白底（浅色 `surfaceContainerLowest`、深色 `surfaceContainerHigh`，同 `AppToast`），圆角 12dp，内边距左右 14dp、上下 10dp，`bodyMedium` 单行，阴影 6dp，尖角宽 16dp、高 8dp，离控件 8dp，不超出屏幕左右 16dp。
- 手指：28dp 圆，填充 `onSurface` 22%、1.5dp 描边 `onSurface` 35%；点按波纹为主色描边圆从 0.8 倍扩到 1.9 倍淡出（450ms）；长按为外圈半径 17dp、3dp 主色进度环在系统长按时间内转满，转满时出一次波纹，同时那一行盖一层 `onSurface` 10% 的按下态。
- 气泡文字（逐字使用）：
  - 右滑（6 个列表）：`右滑可以移除，能撤销`
  - ① 迷你条：`往上滑打开播放页，往下滑收起`
  - ② 迷你条：`左右拖进度线，每格 5 秒`
  - ③ 迷你条：`按住进度线半秒，出刻度尺细调`
  - ⑩ 动态头像：`点头像只看这个 UP 的动态，再点一次取消`
  - ⑪ 动态视频：`长按视频：加入列表、队列或下一首播放`
  - ⑫⑬ 搜索 / UP 空间：`长按一条进入多选，可以一次加好几条`
  - 设置行：标题 `重新显示操作提示`，说明 `右滑移除、拖进度条等操作的演示再放一次`，点后提示 `已重置，下次碰到时会再演示`
- `SWIPE_QUEUE` 沿用旧键 `queue_swipe_hint_shown`（看过旧版队列演示的老用户不重放）。

执行层面的约束：

- **不访问 Bilibili 服务器**（CLAUDE.md 安全规则）：不 `curl` B 站、不运行 App、不装机。测试全部离线。
- **不装机**：改完只做本地验证，用户开口才编译装机。
- 命令都在仓库根目录执行，不要 `cd`。
- 提交：每个任务最后一步提交，**只 `git add` 本任务列出的文件**（不要 `git add -A` / `git add .`）。工作区里已有的、不是本计划产生的未提交修改（如 `docs/01-开发环境搭建.md`、`docs/05-功能优化篇.md`、`docs/发版清单.md`、`NOTICE`、`README.md`）**不要动、不要纳入**。提交信息末尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- 新增类型都在 `:app` 内，不改 `core/*` 的公开 API。

---

## File Structure

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `app/src/main/java/com/biliseek/app/hint/HintPolicy.kt` | 新建 | `HintKey`、`PersistedHints`、`HintState`、全部判定纯函数 |
| `app/src/main/java/com/biliseek/app/hint/HintStore.kt` | 新建 | `HintRecords` 接口 + `SharedPrefsHintRecords` |
| `app/src/main/java/com/biliseek/app/hint/HintController.kt` | 新建 | 状态持有 + 落盘 |
| `app/src/test/java/com/biliseek/app/hint/HintPolicyTest.kt` | 新建 | 判定单测 |
| `app/src/test/java/com/biliseek/app/hint/HintControllerTest.kt` | 新建 | 控制器单测（内存假存储） |
| `app/src/main/java/com/biliseek/app/ui/components/HintDemo.kt` | 新建 | `LocalHints`、`HintTiming`、`HintDemo`、`rememberHintDemo`、`Modifier.hintTouchObserver` |
| `app/src/main/java/com/biliseek/app/ui/components/HintBubble.kt` | 新建 | 气泡（Popup + 带尖角的形状） |
| `app/src/main/java/com/biliseek/app/ui/components/DemoFinger.kt` | 新建 | 手指 + 状态（显示、点按、长按） |
| `app/src/main/java/com/biliseek/app/ui/components/LongPressHint.kt` | 新建 | 长按类演示包装（⑪⑫⑬） |
| `app/src/main/java/com/biliseek/app/AppViewModel.kt` | 改 | 加 `hints`；删旧的队列演示成员 |
| `app/src/main/java/com/biliseek/app/MainActivity.kt` | 改 | 冷启动计数、提供 `LocalHints`、全屏 / 画中画屏蔽；删旧的队列演示参数 |
| `app/src/main/java/com/biliseek/app/ui/AppShell.kt` | 改 | 根 Box 挂触摸观察 |
| `app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt` | 改 | 「重新显示操作提示」行 |
| `app/src/main/java/com/biliseek/app/data/SettingsStore.kt` | 改 | 删旧的队列演示记录 |
| `app/src/main/java/com/biliseek/app/ui/components/SwipeToRemoveRow.kt` | 改 | `hintKey` / `showHint`、88dp、手指、气泡、用户删过记看过 |
| `app/src/main/java/com/biliseek/app/ui/components/PlayerQueueSheet.kt` | 改 | 改用新参数、挂触摸观察 |
| `app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt`、`ui/WatchLaterScreen.kt`、`ui/DownloadsScreen.kt`、`ui/SummaryListScreen.kt` | 改 | 第一行传 `hintKey` / `showHint` |
| `app/src/main/java/com/biliseek/app/ui/MiniPlayer.kt` | 改 | ① |
| `app/src/main/java/com/biliseek/app/ui/components/DraggableProgressBar.kt` | 改 | ②③ |
| `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt` | 改 | 进入计数、⑩、⑪ |
| `app/src/main/java/com/biliseek/app/SearchScreen.kt`、`ui/UpSpaceScreen.kt` | 改 | ⑫⑬ |
| `docs/03-架构设计篇.md`、`docs/05-功能优化篇.md`、`docs/BiliSeek-手动测试指南.md` | 改 | 同步文档 |

---

### Task 1: 判定纯函数 `HintPolicy`

**Files:**
- Create: `app/src/main/java/com/biliseek/app/hint/HintPolicy.kt`
- Test: `app/src/test/java/com/biliseek/app/hint/HintPolicyTest.kt`

**Interfaces:**
- Consumes: 无
- Produces（后面任务都用这些名字）：
  - `enum class HintKey(val prefKey: String)`：`SWIPE_QUEUE, SWIPE_LOCAL_PLAYLIST, SWIPE_FAVORITE, SWIPE_WATCH_LATER, SWIPE_DOWNLOADS, SWIPE_SUMMARY, MINIBAR_SWIPE, MINIBAR_DRAG, MINIBAR_ZOOM, DYNAMIC_UP_FILTER, DYNAMIC_VIDEO_MENU, SEARCH_MULTI_SELECT, UPSPACE_MULTI_SELECT`
  - `const val HINT_NONE = -1`
  - `data class PersistedHints(shown: Set<HintKey> = emptySet(), launchSeq: Int = 0, minibarSwipeLaunch: Int = HINT_NONE)`
  - `data class HintState(persisted: PersistedHints = PersistedHints(), active: HintKey? = null, environmentBlocked: Boolean = false, dynamicVisit: Int = 0, upFilterVisit: Int = HINT_NONE, zoomArmed: Boolean = false)`
  - `fun canStartHint(key: HintKey, state: HintState): Boolean`
  - `fun shouldForfeitHint(key: HintKey, state: HintState): Boolean`
  - `fun startHint(state: HintState, key: HintKey): HintState`
  - `fun cancelHint(state: HintState, key: HintKey): HintState`
  - `fun markHintSeen(state: HintState, key: HintKey): HintState`
  - `fun applyUserAction(state: HintState, key: HintKey): HintState`
  - `fun resetHints(state: HintState): HintState`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/biliseek/app/hint/HintPolicyTest.kt`：

```kotlin
package com.biliseek.app.hint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintPolicyTest {

    private fun state(
        shown: Set<HintKey> = emptySet(),
        launchSeq: Int = 1,
        minibarSwipeLaunch: Int = HINT_NONE,
        active: HintKey? = null,
        blocked: Boolean = false,
        dynamicVisit: Int = 1,
        upFilterVisit: Int = HINT_NONE,
        zoomArmed: Boolean = false,
    ) = HintState(
        persisted = PersistedHints(shown, launchSeq, minibarSwipeLaunch),
        active = active,
        environmentBlocked = blocked,
        dynamicVisit = dynamicVisit,
        upFilterVisit = upFilterVisit,
        zoomArmed = zoomArmed,
    )

    @Test
    fun `没看过且没有别的在放就能放 看过就不放`() {
        assertTrue(canStartHint(HintKey.SWIPE_FAVORITE, state()))
        assertFalse(canStartHint(HintKey.SWIPE_FAVORITE, state(shown = setOf(HintKey.SWIPE_FAVORITE))))
    }

    @Test
    fun `同一时间只放一个 全屏或小窗时不放`() {
        assertFalse(canStartHint(HintKey.SWIPE_FAVORITE, state(active = HintKey.MINIBAR_SWIPE)))
        assertFalse(canStartHint(HintKey.SWIPE_FAVORITE, state(blocked = true)))
    }

    @Test
    fun `迷你条拖进度要等上滑看过 而且换了一次启动`() {
        assertFalse(canStartHint(HintKey.MINIBAR_DRAG, state()))
        val swipeThisLaunch = state(shown = setOf(HintKey.MINIBAR_SWIPE), launchSeq = 3, minibarSwipeLaunch = 3)
        assertFalse(canStartHint(HintKey.MINIBAR_DRAG, swipeThisLaunch))
        val nextLaunch = swipeThisLaunch.copy(persisted = swipeThisLaunch.persisted.copy(launchSeq = 4))
        assertTrue(canStartHint(HintKey.MINIBAR_DRAG, nextLaunch))
    }

    @Test
    fun `刻度尺演示只在用户自己拖完进度线之后`() {
        assertFalse(canStartHint(HintKey.MINIBAR_ZOOM, state()))
        assertTrue(canStartHint(HintKey.MINIBAR_ZOOM, state(zoomArmed = true)))
    }

    @Test
    fun `动态视频长按要等头像看过 而且不是同一次进入`() {
        assertFalse(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, state()))
        val sameVisit = state(shown = setOf(HintKey.DYNAMIC_UP_FILTER), dynamicVisit = 2, upFilterVisit = 2)
        assertFalse(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, sameVisit))
        assertTrue(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, sameVisit.copy(dynamicVisit = 3)))
        // 头像是以前打开 App 时看过的：本进程没有进入序号
        assertTrue(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, state(shown = setOf(HintKey.DYNAMIC_UP_FILTER))))
    }

    @Test
    fun `开始和取消只动正在放的那一个`() {
        assertEquals(HintKey.SWIPE_QUEUE, startHint(state(), HintKey.SWIPE_QUEUE).active)
        assertNull(startHint(state(shown = setOf(HintKey.SWIPE_QUEUE)), HintKey.SWIPE_QUEUE).active)
        assertNull(cancelHint(state(active = HintKey.SWIPE_QUEUE), HintKey.SWIPE_QUEUE).active)
        assertEquals(
            HintKey.SWIPE_QUEUE,
            cancelHint(state(active = HintKey.SWIPE_QUEUE), HintKey.SWIPE_FAVORITE).active,
        )
    }

    @Test
    fun `记为看过会停下正在放的 并记下上滑的启动序号和头像的进入序号`() {
        val s = markHintSeen(state(active = HintKey.MINIBAR_SWIPE, launchSeq = 5), HintKey.MINIBAR_SWIPE)
        assertNull(s.active)
        assertTrue(HintKey.MINIBAR_SWIPE in s.persisted.shown)
        assertEquals(5, s.persisted.minibarSwipeLaunch)
        assertEquals(4, markHintSeen(state(dynamicVisit = 4), HintKey.DYNAMIC_UP_FILTER).upFilterVisit)
    }

    @Test
    fun `已经看过的再记一次 不改序号`() {
        val s = state(shown = setOf(HintKey.MINIBAR_SWIPE), launchSeq = 7, minibarSwipeLaunch = 2)
        assertEquals(2, markHintSeen(s, HintKey.MINIBAR_SWIPE).persisted.minibarSwipeLaunch)
    }

    @Test
    fun `用户自己拖完进度线 记拖进度看过并且可以放刻度尺演示`() {
        val s = applyUserAction(state(), HintKey.MINIBAR_DRAG)
        assertTrue(HintKey.MINIBAR_DRAG in s.persisted.shown)
        assertTrue(s.zoomArmed)
        assertFalse(applyUserAction(state(shown = setOf(HintKey.MINIBAR_ZOOM)), HintKey.MINIBAR_DRAG).zoomArmed)
    }

    @Test
    fun `用户自己做过别的操作 只记那一项并停下它的演示`() {
        val s = applyUserAction(state(active = HintKey.SWIPE_FAVORITE), HintKey.SWIPE_FAVORITE)
        assertEquals(setOf(HintKey.SWIPE_FAVORITE), s.persisted.shown)
        assertNull(s.active)
        assertFalse(s.zoomArmed)
    }

    @Test
    fun `被别的演示占着而本来能放的 这次进入放弃`() {
        assertTrue(shouldForfeitHint(HintKey.SWIPE_FAVORITE, state(active = HintKey.MINIBAR_SWIPE)))
        assertFalse(shouldForfeitHint(HintKey.SWIPE_FAVORITE, state(active = HintKey.SWIPE_FAVORITE)))
        assertFalse(shouldForfeitHint(HintKey.SWIPE_FAVORITE, state()))
        // 本来就不能放（刻度尺还没解锁）：不放弃，等解锁
        assertFalse(shouldForfeitHint(HintKey.MINIBAR_ZOOM, state(active = HintKey.MINIBAR_SWIPE)))
    }

    @Test
    fun `重置清空记录 但保留启动序号`() {
        val s = resetHints(
            state(
                shown = HintKey.entries.toSet(),
                launchSeq = 9,
                minibarSwipeLaunch = 3,
                active = HintKey.SWIPE_QUEUE,
                upFilterVisit = 2,
                zoomArmed = true,
            ),
        )
        assertEquals(PersistedHints(launchSeq = 9), s.persisted)
        assertNull(s.active)
        assertEquals(HINT_NONE, s.upFilterVisit)
        assertFalse(s.zoomArmed)
    }

    @Test
    fun `队列沿用旧键 各项的键互不相同`() {
        assertEquals("queue_swipe_hint_shown", HintKey.SWIPE_QUEUE.prefKey)
        assertEquals(HintKey.entries.size, HintKey.entries.map { it.prefKey }.toSet().size)
    }
}
```

- [ ] **Step 2: 运行，确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.hint.HintPolicyTest"`
Expected: FAIL，`Unresolved reference: HintKey`（等）。

- [ ] **Step 3: 实现**

`app/src/main/java/com/biliseek/app/hint/HintPolicy.kt`：

```kotlin
package com.biliseek.app.hint

// 首次使用演示的判定（设计稿：docs/superpowers/specs/2026-10-07-first-use-hints-design.md）。
// 纯函数；HintController 只负责持有状态与落盘。

/** 13 项演示。[prefKey] 是 SharedPreferences（biliseek_hints）里的键。 */
enum class HintKey(val prefKey: String) {
    /** 沿用旧键：看过旧版队列演示的不再放。 */
    SWIPE_QUEUE("queue_swipe_hint_shown"),
    SWIPE_LOCAL_PLAYLIST("hint_swipe_local_playlist"),
    SWIPE_FAVORITE("hint_swipe_favorite"),
    SWIPE_WATCH_LATER("hint_swipe_watch_later"),
    SWIPE_DOWNLOADS("hint_swipe_downloads"),
    SWIPE_SUMMARY("hint_swipe_summary"),
    MINIBAR_SWIPE("hint_minibar_swipe"),
    MINIBAR_DRAG("hint_minibar_drag"),
    MINIBAR_ZOOM("hint_minibar_zoom"),
    DYNAMIC_UP_FILTER("hint_dynamic_up_filter"),
    DYNAMIC_VIDEO_MENU("hint_dynamic_video_menu"),
    SEARCH_MULTI_SELECT("hint_search_multi_select"),
    UPSPACE_MULTI_SELECT("hint_upspace_multi_select"),
}

/** 启动序号 / 进入序号的「没有」。 */
const val HINT_NONE = -1

/** 存在本机的部分。 */
data class PersistedHints(
    val shown: Set<HintKey> = emptySet(),
    /** 冷启动序号：每次冷启动加一。 */
    val launchSeq: Int = 0,
    /** [HintKey.MINIBAR_SWIPE] 记为看过时的启动序号：② 要等之后的另一次启动。 */
    val minibarSwipeLaunch: Int = HINT_NONE,
)

data class HintState(
    val persisted: PersistedHints = PersistedHints(),
    /** 正在放的演示（同一时间最多一个）。 */
    val active: HintKey? = null,
    /** 全屏 / 画中画：不开始新演示。 */
    val environmentBlocked: Boolean = false,
    /** 本进程第几次进入动态页（只在内存）。 */
    val dynamicVisit: Int = 0,
    /** [HintKey.DYNAMIC_UP_FILTER] 在本进程哪一次进入里记为看过：⑪ 要等之后的另一次进入。 */
    val upFilterVisit: Int = HINT_NONE,
    /** 用户自己拖完过进度线：③ 可以放（只在内存）。 */
    val zoomArmed: Boolean = false,
)

/** 现在能不能开始放 [key]。 */
fun canStartHint(key: HintKey, state: HintState): Boolean {
    val shown = state.persisted.shown
    if (key in shown || state.active != null || state.environmentBlocked) return false
    return when (key) {
        HintKey.MINIBAR_DRAG ->
            HintKey.MINIBAR_SWIPE in shown && state.persisted.launchSeq > state.persisted.minibarSwipeLaunch
        HintKey.MINIBAR_ZOOM -> state.zoomArmed
        HintKey.DYNAMIC_VIDEO_MENU ->
            HintKey.DYNAMIC_UP_FILTER in shown && state.upFilterVisit != state.dynamicVisit
        else -> true
    }
}

/**
 * 别的演示正在放、而 [key] 本来能放：这次进入页面就放弃，等下次进入（不在前一个放完后接着连放）。
 * 本来就不能放的（如刻度尺还没解锁）不放弃，条件满足时照常开始。
 */
fun shouldForfeitHint(key: HintKey, state: HintState): Boolean =
    state.active != null && state.active != key && canStartHint(key, state.copy(active = null))

fun startHint(state: HintState, key: HintKey): HintState =
    if (canStartHint(key, state)) state.copy(active = key) else state

/** 没放完就离开页面：不记为看过。 */
fun cancelHint(state: HintState, key: HintKey): HintState =
    if (state.active == key) state.copy(active = null) else state

/** 记为看过（演示放完 / 被碰掉 / 用户自己用过）；正在放的就是它则停下。 */
fun markHintSeen(state: HintState, key: HintKey): HintState {
    val persisted = state.persisted
    val first = key !in persisted.shown
    return state.copy(
        persisted = persisted.copy(
            shown = persisted.shown + key,
            minibarSwipeLaunch = if (first && key == HintKey.MINIBAR_SWIPE) {
                persisted.launchSeq
            } else {
                persisted.minibarSwipeLaunch
            },
        ),
        active = if (state.active == key) null else state.active,
        upFilterVisit = if (first && key == HintKey.DYNAMIC_UP_FILTER) state.dynamicVisit else state.upFilterVisit,
    )
}

/** 用户自己做了 [key] 对应的操作：记为看过；自己拖完进度线还会让刻度尺演示（③）可以放。 */
fun applyUserAction(state: HintState, key: HintKey): HintState {
    val seen = markHintSeen(state, key)
    val armZoom = key == HintKey.MINIBAR_DRAG && HintKey.MINIBAR_ZOOM !in seen.persisted.shown
    return if (armZoom) seen.copy(zoomArmed = true) else seen
}

/** 设置里「重新显示操作提示」：清空看过的记录，冷启动序号保留。 */
fun resetHints(state: HintState): HintState = state.copy(
    persisted = PersistedHints(launchSeq = state.persisted.launchSeq),
    active = null,
    upFilterVisit = HINT_NONE,
    zoomArmed = false,
)
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.hint.HintPolicyTest"`
Expected: PASS（13 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/hint/HintPolicy.kt app/src/test/java/com/biliseek/app/hint/HintPolicyTest.kt
git commit -m "feat(hint): 首次演示判定纯函数 HintPolicy

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 存储 `HintStore` 与控制器 `HintController`

**Files:**
- Create: `app/src/main/java/com/biliseek/app/hint/HintStore.kt`
- Create: `app/src/main/java/com/biliseek/app/hint/HintController.kt`
- Test: `app/src/test/java/com/biliseek/app/hint/HintControllerTest.kt`

**Interfaces:**
- Consumes: Task 1 的全部类型与函数。
- Produces：
  - `interface HintRecords { fun load(): PersistedHints; fun save(hints: PersistedHints) }`
  - `class SharedPrefsHintRecords(context: android.content.Context) : HintRecords`
  - `class HintController(records: HintRecords)`：`val state: StateFlow<HintState>`；`fun onColdStart()`、`fun setEnvironmentBlocked(blocked: Boolean)`、`fun enterDynamic()`、`fun tryStart(key: HintKey): Boolean`、`fun finish(key: HintKey)`、`fun cancel(key: HintKey)`、`fun onUserAction(key: HintKey)`、`fun onTouch()`、`fun reset()`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/biliseek/app/hint/HintControllerTest.kt`：

```kotlin
package com.biliseek.app.hint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintControllerTest {

    private class FakeRecords(var saved: PersistedHints = PersistedHints()) : HintRecords {
        var saves = 0
        override fun load(): PersistedHints = saved
        override fun save(hints: PersistedHints) {
            saved = hints
            saves++
        }
    }

    @Test
    fun `同一时间只能开始一个`() {
        val c = HintController(FakeRecords())
        assertTrue(c.tryStart(HintKey.SWIPE_QUEUE))
        assertFalse(c.tryStart(HintKey.SWIPE_FAVORITE))
        // 已经在放，不算新开始
        assertFalse(c.tryStart(HintKey.SWIPE_QUEUE))
    }

    @Test
    fun `放完记为看过并落盘 离开页面不记`() {
        val records = FakeRecords()
        val c = HintController(records)
        c.tryStart(HintKey.SWIPE_QUEUE)
        c.cancel(HintKey.SWIPE_QUEUE)
        assertTrue(records.saved.shown.isEmpty())
        assertNull(c.state.value.active)
        c.tryStart(HintKey.SWIPE_QUEUE)
        c.finish(HintKey.SWIPE_QUEUE)
        assertEquals(setOf(HintKey.SWIPE_QUEUE), records.saved.shown)
        assertNull(c.state.value.active)
    }

    @Test
    fun `碰屏幕结束当前演示并记为看过 没在放时什么也不做`() {
        val records = FakeRecords()
        val c = HintController(records)
        c.onTouch()
        assertEquals(0, records.saves)
        c.tryStart(HintKey.SEARCH_MULTI_SELECT)
        c.onTouch()
        assertNull(c.state.value.active)
        assertEquals(setOf(HintKey.SEARCH_MULTI_SELECT), records.saved.shown)
    }

    @Test
    fun `迷你条上滑看过后 下一次冷启动才放拖进度`() {
        val records = FakeRecords()
        val first = HintController(records)
        first.onColdStart()
        first.tryStart(HintKey.MINIBAR_SWIPE)
        first.finish(HintKey.MINIBAR_SWIPE)
        assertFalse(first.tryStart(HintKey.MINIBAR_DRAG))
        val second = HintController(records)
        second.onColdStart()
        assertTrue(second.tryStart(HintKey.MINIBAR_DRAG))
    }

    @Test
    fun `用户自己拖完进度线后 刻度尺演示可以开始`() {
        val c = HintController(FakeRecords())
        assertFalse(c.tryStart(HintKey.MINIBAR_ZOOM))
        c.onUserAction(HintKey.MINIBAR_DRAG)
        assertTrue(c.tryStart(HintKey.MINIBAR_ZOOM))
    }

    @Test
    fun `动态页 头像看过的那次进入不放视频长按 下次进入才放`() {
        val c = HintController(FakeRecords())
        c.enterDynamic()
        c.tryStart(HintKey.DYNAMIC_UP_FILTER)
        c.finish(HintKey.DYNAMIC_UP_FILTER)
        assertFalse(c.tryStart(HintKey.DYNAMIC_VIDEO_MENU))
        c.enterDynamic()
        assertTrue(c.tryStart(HintKey.DYNAMIC_VIDEO_MENU))
    }

    @Test
    fun `全屏或小窗时不开始`() {
        val c = HintController(FakeRecords())
        c.setEnvironmentBlocked(true)
        assertFalse(c.tryStart(HintKey.SWIPE_QUEUE))
        c.setEnvironmentBlocked(false)
        assertTrue(c.tryStart(HintKey.SWIPE_QUEUE))
    }

    @Test
    fun `重置清空看过的记录并落盘 启动序号保留`() {
        val records = FakeRecords(
            PersistedHints(shown = setOf(HintKey.SWIPE_QUEUE), launchSeq = 4, minibarSwipeLaunch = 2),
        )
        val c = HintController(records)
        c.reset()
        assertEquals(PersistedHints(launchSeq = 4), records.saved)
        assertTrue(c.tryStart(HintKey.SWIPE_QUEUE))
    }

    @Test
    fun `只改内存状态时不写盘`() {
        val records = FakeRecords()
        val c = HintController(records)
        c.enterDynamic()
        c.setEnvironmentBlocked(true)
        c.setEnvironmentBlocked(false)
        c.tryStart(HintKey.SWIPE_QUEUE)
        c.cancel(HintKey.SWIPE_QUEUE)
        assertEquals(0, records.saves)
    }
}
```

- [ ] **Step 2: 运行，确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.hint.HintControllerTest"`
Expected: FAIL，`Unresolved reference: HintRecords` / `HintController`。

- [ ] **Step 3: 实现存储**

`app/src/main/java/com/biliseek/app/hint/HintStore.kt`：

```kotlin
package com.biliseek.app.hint

import android.content.Context

/** 首次演示记录的存取（[HintController] 用；测试里换成内存实现）。 */
interface HintRecords {
    fun load(): PersistedHints
    fun save(hints: PersistedHints)
}

/** 存在 SharedPreferences「biliseek_hints」：同步读，界面首帧就要知道放不放演示。 */
class SharedPrefsHintRecords(context: Context) : HintRecords {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PersistedHints = PersistedHints(
        shown = HintKey.entries.filterTo(mutableSetOf()) { prefs.getBoolean(it.prefKey, false) },
        launchSeq = prefs.getInt(KEY_LAUNCH_SEQ, 0),
        minibarSwipeLaunch = prefs.getInt(KEY_MINIBAR_SWIPE_LAUNCH, HINT_NONE),
    )

    override fun save(hints: PersistedHints) {
        val editor = prefs.edit()
        HintKey.entries.forEach { editor.putBoolean(it.prefKey, it in hints.shown) }
        editor.putInt(KEY_LAUNCH_SEQ, hints.launchSeq)
        editor.putInt(KEY_MINIBAR_SWIPE_LAUNCH, hints.minibarSwipeLaunch)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "biliseek_hints"
        const val KEY_LAUNCH_SEQ = "hint_launch_seq"
        const val KEY_MINIBAR_SWIPE_LAUNCH = "hint_minibar_swipe_launch"
    }
}
```

- [ ] **Step 4: 实现控制器**

`app/src/main/java/com/biliseek/app/hint/HintController.kt`：

```kotlin
package com.biliseek.app.hint

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首次使用演示的状态持有者（功能控制器，`AppViewModel.hints`；界面经 `LocalHints` 取用）。
 * 判定全在 HintPolicy.kt，这里只管状态与落盘：持久部分一变就写。只在主线程调用。
 */
class HintController(private val records: HintRecords) {

    private val _state = MutableStateFlow(HintState(persisted = records.load()))
    val state: StateFlow<HintState> = _state.asStateFlow()

    /** 冷启动一次（`MainActivity` 没有 savedInstanceState 时）。 */
    fun onColdStart() = update {
        it.copy(persisted = it.persisted.copy(launchSeq = it.persisted.launchSeq + 1))
    }

    /** 全屏 / 画中画时不开始新演示。 */
    fun setEnvironmentBlocked(blocked: Boolean) = update { it.copy(environmentBlocked = blocked) }

    /** 进入动态页一次（⑪ 要等之后的另一次进入）。 */
    fun enterDynamic() = update { it.copy(dynamicVisit = it.dynamicVisit + 1) }

    /** 能放就开始放 [key]；返回这次是否开始了。 */
    fun tryStart(key: HintKey): Boolean {
        val before = _state.value.active
        update { startHint(it, key) }
        return before != key && _state.value.active == key
    }

    /** 演示放完：记为看过。 */
    fun finish(key: HintKey) = update { markHintSeen(it, key) }

    /** 没放完就离开页面：不记。 */
    fun cancel(key: HintKey) = update { cancelHint(it, key) }

    /** 用户自己做了 [key] 对应的操作。 */
    fun onUserAction(key: HintKey) = update { applyUserAction(it, key) }

    /** 演示中手指碰了屏幕：当前演示结束、记为看过。 */
    fun onTouch() {
        val active = _state.value.active ?: return
        finish(active)
    }

    /** 设置里「重新显示操作提示」。 */
    fun reset() = update { resetHints(it) }

    private fun update(transform: (HintState) -> HintState) {
        val before = _state.value
        val after = transform(before)
        if (after == before) return
        _state.value = after
        if (after.persisted != before.persisted) records.save(after.persisted)
    }
}
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.hint.*"`
Expected: PASS（`HintPolicyTest` 13 个、`HintControllerTest` 9 个）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/biliseek/app/hint/HintStore.kt app/src/main/java/com/biliseek/app/hint/HintController.kt app/src/test/java/com/biliseek/app/hint/HintControllerTest.kt
git commit -m "feat(hint): 首次演示记录存取与 HintController

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 界面基础件（气泡、手指、`rememberHintDemo`）+ 接入 + 设置重置

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/components/HintDemo.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/components/HintBubble.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/components/DemoFinger.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（`queueSwipeHintShown` 上方，约 404 行）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`onCreate` 约 164 行；`CompositionLocalProvider` 约 193 行；`LaunchedEffect(expansion, isInPip)` 约 351 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/AppShell.kt`（根 `Box`，约 426 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt`（「界面」分组，约 223–237 行）

**Interfaces:**
- Consumes: `HintController`、`HintKey`、`HintState`、`shouldForfeitHint`（Task 1–2）。
- Produces（包 `com.biliseek.app.ui.components`）：
  - `val LocalHints: ProvidableCompositionLocal<HintController?>`
  - `object HintTiming { const val START_DELAY_MS = 500L; const val BUBBLE_HOLD_MS = 4_000L }`
  - `class HintDemo(val running: Boolean, onFinish: () -> Unit) { fun finish() }`
  - `@Composable fun rememberHintDemo(key: HintKey?, ready: Boolean): HintDemo`
  - `fun Modifier.hintTouchObserver(hints: HintController?): Modifier`
  - `@Composable fun HintBubble(text: String, pointX: Dp? = null, preferAbove: Boolean = false, extraAbove: Dp = 0.dp)`
  - `class DemoFingerState`：`visible`、`pressed`（只读）；`fun show()`、`fun hide()`、`suspend fun tap()`、`suspend fun longPress(pressMs: Long)`、`suspend fun reset()`
  - `@Composable fun rememberDemoFingerState(): DemoFingerState`
  - `@Composable fun DemoFinger(state: DemoFingerState, modifier: Modifier = Modifier)`（28dp；调用方把它的中心对到手指位置：偏移 `-DemoFingerDefaults.Size / 2`）
  - `object DemoFingerDefaults { val Size = 28.dp; ... }`
  - `AppViewModel.hints: HintController`

本任务不加单元测试（纯 Compose 外观与接线，无判定逻辑）；验证 = 编译通过 + 既有测试仍过。

- [ ] **Step 1: `HintDemo.kt`**

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import com.biliseek.app.hint.HintController
import com.biliseek.app.hint.HintKey
import com.biliseek.app.hint.HintState
import com.biliseek.app.hint.shouldForfeitHint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 首次使用演示（设计稿：docs/superpowers/specs/2026-10-07-first-use-hints-design.md）的控制器。
 * 由 `MainActivity` 提供，同 [LocalShowMessage]；没提供时（预览、测试）为 null，不放任何演示。
 */
val LocalHints = staticCompositionLocalOf<HintController?> { null }

/** 演示的节奏。 */
object HintTiming {
    /** 出现后多久才开始动（页面进场 / 面板展开动画走完，用户才看得到）。 */
    const val START_DELAY_MS = 500L

    /** 动作放完后气泡再停多久，然后演示结束、记为看过。 */
    const val BUBBLE_HOLD_MS = 4_000L
}

/** 某个控件此刻的演示：[running] 时自己跑动作、显示气泡；放完调 [finish]。 */
class HintDemo(val running: Boolean, private val onFinish: () -> Unit) {
    fun finish() = onFinish()
}

/**
 * 这个控件要不要演示 [key]：[ready]（列表有内容、轮到这一行……）且本窗口有焦点时尝试开始。
 * 别的演示占着、而它本来能放 → 这次进入就放弃（控件重新进入组合才再试）。
 * 控件离开组合时没放完 → 取消，不记为看过。[key] 为 null 时永远不演示。
 */
@Composable
fun rememberHintDemo(key: HintKey?, ready: Boolean): HintDemo {
    val hints = LocalHints.current
    val fallback = remember { MutableStateFlow(HintState()) }
    val state by (hints?.state ?: fallback).collectAsState()
    // 后台、弹层 / 对话框盖着：本窗口没有焦点（队列面板自己是一个窗口，里面照放）
    val focused = LocalWindowInfo.current.isWindowFocused
    var started by remember(key) { mutableStateOf(false) }
    var forfeited by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, ready, focused, state) {
        if (hints == null || key == null) return@LaunchedEffect
        if (started) {
            // 放完 / 被碰掉 / 被重置：交还
            if (state.active != key) started = false
            return@LaunchedEffect
        }
        if (!ready || !focused || forfeited) return@LaunchedEffect
        if (shouldForfeitHint(key, state)) {
            forfeited = true
        } else if (hints.tryStart(key)) {
            started = true
        }
    }
    val currentStarted by rememberUpdatedState(started)
    DisposableEffect(hints, key) {
        onDispose { if (currentStarted && key != null) hints?.cancel(key) }
    }
    val running = key != null && started && state.active == key
    return HintDemo(running) { if (key != null) hints?.finish(key) }
}

/**
 * 演示中手指碰到屏幕任何地方：当前演示结束、记为看过。只观察（Initial 阶段、不消费），这次触摸照常生效。
 * 挂在 `AppShell` 根和队列面板根（面板是另一个窗口）。
 */
fun Modifier.hintTouchObserver(hints: HintController?): Modifier =
    if (hints == null) {
        this
    } else {
        pointerInput(hints) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                hints.onTouch()
            }
        }
    }
```

- [ ] **Step 2: `DemoFinger.kt`**

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 首次演示里画出来的手指（设计稿 2026-10-07-first-use-hints，效果图选 B）。
 * 演示脚本在协程里调 [show] / [tap] / [longPress] / [hide]；被打断时调 [reset]。
 */
@Stable
class DemoFingerState {
    var visible by mutableStateOf(false)
        private set

    /** 长按演示中：调用方给那一行盖上按下态。 */
    var pressed by mutableStateOf(false)
        private set

    internal val ring = Animatable(0f)
    internal val ripple = Animatable(0f)

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
    }

    /** 点一下：主色描边圆从 0.8 倍扩到 1.9 倍淡出。 */
    suspend fun tap() {
        visible = true
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(DemoFingerDefaults.RIPPLE_MS))
        ripple.snapTo(0f)
    }

    /** 按住：外圈进度环在 [pressMs] 内转满，转满时出一次波纹，再停一下松手消失。 */
    suspend fun longPress(pressMs: Long) {
        visible = true
        pressed = true
        ring.snapTo(0f)
        ring.animateTo(1f, tween(pressMs.toInt(), easing = LinearEasing))
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(DemoFingerDefaults.RIPPLE_MS))
        delay(DemoFingerDefaults.RELEASE_DELAY_MS)
        pressed = false
        ring.snapTo(0f)
        ripple.snapTo(0f)
        visible = false
    }

    suspend fun reset() {
        visible = false
        pressed = false
        ring.snapTo(0f)
        ripple.snapTo(0f)
    }
}

@Composable
fun rememberDemoFingerState(): DemoFingerState = remember { DemoFingerState() }

/** 手指：28dp 灰色半透明圆。调用方把它的中心对到手指位置（偏移 `-DemoFingerDefaults.Size / 2`）。 */
@Composable
fun DemoFinger(state: DemoFingerState, modifier: Modifier = Modifier) {
    if (!state.visible) return
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    // 进度环、波纹画在 28dp 外面：Canvas 不裁剪
    Canvas(modifier.size(DemoFingerDefaults.Size)) {
        val radius = size.minDimension / 2
        drawCircle(ink.copy(alpha = 0.22f), radius)
        drawCircle(ink.copy(alpha = 0.35f), radius - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        val ring = state.ring.value
        if (ring > 0f) {
            val r = DemoFingerDefaults.RingRadius.toPx()
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 360f * ring,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        val ripple = state.ripple.value
        if (ripple > 0f && ripple < 1f) {
            drawCircle(
                primary.copy(alpha = 0.55f * (1f - ripple)),
                radius * (0.8f + 1.1f * ripple),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

object DemoFingerDefaults {
    val Size = 28.dp
    val RingRadius = 17.dp
    const val RIPPLE_MS = 450
    const val RELEASE_DELAY_MS = 500L
}
```

- [ ] **Step 3: `HintBubble.kt`**

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 首次演示的说明气泡（设计稿 2026-10-07-first-use-hints，效果图选 B）：白底圆角，尖角指着控件。
 * 放在要指的控件里（它的父布局就是锚点）；锚点下方放得下就放下方，否则放上方（[preferAbove] 反过来）。
 * 不抢焦点、不拦截别处的触摸；碰到气泡本身也算碰了屏幕（当前演示结束）。
 *
 * @param pointX 尖角指向锚点里的哪个横坐标（从锚点左缘算起）；null = 锚点正中。
 * @param extraAbove 放上方时再额外抬高多少（迷你条刻度尺演示：让出刻度尺的位置）。
 */
@Composable
fun HintBubble(
    text: String,
    pointX: Dp? = null,
    preferAbove: Boolean = false,
    extraAbove: Dp = 0.dp,
) {
    val hints = LocalHints.current
    val density = LocalDensity.current
    var arrowX by remember { mutableFloatStateOf(0f) }
    var below by remember { mutableStateOf(!preferAbove) }
    val provider = remember(pointX, preferAbove, extraAbove, density) {
        with(density) {
            BubblePositionProvider(
                pointXPx = pointX?.roundToPx(),
                gapPx = BUBBLE_GAP.roundToPx(),
                extraAbovePx = extraAbove.roundToPx(),
                marginPx = BUBBLE_MARGIN.roundToPx(),
                preferAbove = preferAbove,
            ) { x, isBelow ->
                arrowX = x
                below = isBelow
            }
        }
    }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        val scheme = MaterialTheme.colorScheme
        val isDark = scheme.surface.luminance() < 0.5f
        val shape = remember(arrowX, below, density) {
            with(density) {
                BubbleShape(
                    arrowCenterPx = arrowX,
                    arrowOnTop = below,
                    cornerPx = BUBBLE_CORNER.toPx(),
                    arrowWidthPx = ARROW_WIDTH.toPx(),
                    arrowHeightPx = ARROW_HEIGHT.toPx(),
                )
            }
        }
        Surface(
            modifier = Modifier.pointerInput(hints) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hints?.onTouch()
                }
            },
            shape = shape,
            color = if (isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
            contentColor = scheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp + if (below) ARROW_HEIGHT else 0.dp,
                    bottom = 10.dp + if (below) 0.dp else ARROW_HEIGHT,
                ),
            )
        }
    }
}

/** 定位：横向让尖角对准 [pointXPx]、整体不超出屏幕左右 [marginPx]；纵向放锚点下方或上方。 */
private class BubblePositionProvider(
    private val pointXPx: Int?,
    private val gapPx: Int,
    private val extraAbovePx: Int,
    private val marginPx: Int,
    private val preferAbove: Boolean,
    private val onPlaced: (arrowX: Float, below: Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val pointX = anchorBounds.left + (pointXPx ?: (anchorBounds.width / 2))
        val maxX = (windowSize.width - marginPx - popupContentSize.width).coerceAtLeast(marginPx)
        val x = (pointX - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val belowY = anchorBounds.bottom + gapPx
        val aboveY = anchorBounds.top - gapPx - extraAbovePx - popupContentSize.height
        val below = if (preferAbove) aboveY < 0 else belowY + popupContentSize.height <= windowSize.height
        onPlaced((pointX - x).toFloat(), below)
        return IntOffset(x, if (below) belowY else aboveY)
    }
}

/** 圆角矩形 + 一个尖角（尖角在上沿或下沿，横向中心 [arrowCenterPx]，离圆角至少半个尖角宽）。 */
private class BubbleShape(
    private val arrowCenterPx: Float,
    private val arrowOnTop: Boolean,
    private val cornerPx: Float,
    private val arrowWidthPx: Float,
    private val arrowHeightPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bodyTop = if (arrowOnTop) arrowHeightPx else 0f
        val bodyBottom = if (arrowOnTop) size.height else size.height - arrowHeightPx
        val body = Path().apply {
            addRoundRect(RoundRect(0f, bodyTop, size.width, bodyBottom, CornerRadius(cornerPx)))
        }
        val half = arrowWidthPx / 2
        val minX = cornerPx + half
        val maxX = size.width - cornerPx - half
        val cx = if (minX <= maxX) arrowCenterPx.coerceIn(minX, maxX) else size.width / 2
        // 尖角底边伸进主体 1px，合并后不留缝
        val arrow = Path().apply {
            if (arrowOnTop) {
                moveTo(cx - half, bodyTop + 1f)
                lineTo(cx, 0f)
                lineTo(cx + half, bodyTop + 1f)
            } else {
                moveTo(cx - half, bodyBottom - 1f)
                lineTo(cx, size.height)
                lineTo(cx + half, bodyBottom - 1f)
            }
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, arrow))
    }
}

private val BUBBLE_GAP = 8.dp
private val BUBBLE_MARGIN = 16.dp
private val BUBBLE_CORNER = 12.dp
private val ARROW_WIDTH = 16.dp
private val ARROW_HEIGHT = 8.dp
```

- [ ] **Step 4: `AppViewModel` 加 `hints`**

在 `app/src/main/java/com/biliseek/app/AppViewModel.kt` 的
```kotlin
    /** 队列面板的右滑演示是否已经放过（只在第一次打开时演示一次）。 */
    val queueSwipeHintShown: StateFlow<Boolean> = settingsStore.queueSwipeHintShown
```
**上方**插入（旧成员留到 Task 4 再删）：
```kotlin
    /** 首次使用演示（设计稿 2026-10-07-first-use-hints）。界面经 LocalHints 取用。 */
    val hints = com.biliseek.app.hint.HintController(com.biliseek.app.hint.SharedPrefsHintRecords(app))

```

- [ ] **Step 5: `MainActivity` 接入**

(a) `onCreate` 里 `super.onCreate(savedInstanceState)` 的下一行插入：
```kotlin
        // 首次演示的「另一次启动」按冷启动计（设计稿 2026-10-07-first-use-hints）
        if (savedInstanceState == null) viewModel.hints.onColdStart()
```

(b) `CompositionLocalProvider(` 的参数里，在 `com.biliseek.app.ui.components.LocalShowMessage provides showMessage,` 下一行插入：
```kotlin
                    com.biliseek.app.ui.components.LocalHints provides viewModel.hints,
```

(c) 在
```kotlin
                // 弹幕只在全屏（且不在 PiP）时拉取与显示
                LaunchedEffect(expansion, isInPip) {
```
**上方**插入：
```kotlin
                // 全屏 / 画中画时不开始新的首次演示
                LaunchedEffect(expansion, isInPip) {
                    viewModel.hints.setEnvironmentBlocked(expansion == PlayerExpansion.FULLSCREEN || isInPip)
                }

```

- [ ] **Step 6: `AppShell` 根挂触摸观察**

`app/src/main/java/com/biliseek/app/ui/AppShell.kt` 约 426 行：
```kotlin
    Box(Modifier.fillMaxSize()) {
```
改为：
```kotlin
    // 首次演示中碰屏幕任何地方：演示结束（只观察，不拦截）
    Box(Modifier.fillMaxSize().hintTouchObserver(LocalHints.current)) {
```
并在 import 区加：
```kotlin
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.hintTouchObserver
```

- [ ] **Step 7: 设置页「重新显示操作提示」**

`app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt` 的「界面」分组：
```kotlin
                SettingsRow(
                    title = "迷你播放器样式",
                    icon = Icons.Outlined.PictureInPictureAlt,
                    value = miniBarChoices.first { it.value == settings.miniBarStyle }.label,
                    onClick = { sheet = SettingsSheetKind.MINI_BAR },
                )
            }
```
改为：
```kotlin
                SettingsRow(
                    title = "迷你播放器样式",
                    icon = Icons.Outlined.PictureInPictureAlt,
                    value = miniBarChoices.first { it.value == settings.miniBarStyle }.label,
                    onClick = { sheet = SettingsSheetKind.MINI_BAR },
                )
                // 首次演示全部清掉重来（设计稿 2026-10-07-first-use-hints）
                val hints = LocalHints.current
                val showMessage = LocalShowMessage.current
                SettingsRow(
                    title = "重新显示操作提示",
                    subtitle = "右滑移除、拖进度条等操作的演示再放一次",
                    icon = Icons.Outlined.TouchApp,
                    onClick = {
                        hints?.reset()
                        showMessage?.invoke("已重置，下次碰到时会再演示")
                    },
                )
            }
```
import 区加：
```kotlin
import androidx.compose.material.icons.outlined.TouchApp
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.LocalShowMessage
```
（若 `LocalShowMessage` 已 import 则不重复。）

- [ ] **Step 8: 编译 + 既有测试**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/HintDemo.kt app/src/main/java/com/biliseek/app/ui/components/HintBubble.kt app/src/main/java/com/biliseek/app/ui/components/DemoFinger.kt app/src/main/java/com/biliseek/app/AppViewModel.kt app/src/main/java/com/biliseek/app/MainActivity.kt app/src/main/java/com/biliseek/app/ui/AppShell.kt app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt
git commit -m "feat(hint): 演示气泡、手指与接入；设置里可重新显示操作提示

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 右滑移除演示（6 个列表，含队列迁移）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/SwipeToRemoveRow.kt`（整个文件替换）
- Modify: `app/src/main/java/com/biliseek/app/ui/components/PlayerQueueSheet.kt`（约 71–86、93、150–156 行）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（约 414、1187–1188 行）
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（约 404–407 行）
- Modify: `app/src/main/java/com/biliseek/app/data/SettingsStore.kt`（约 240、285–302 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt`（约 823、978 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/WatchLaterScreen.kt`（约 101 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/DownloadsScreen.kt`（约 192、198、345 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/SummaryListScreen.kt`（约 82 行）

**Interfaces:**
- Consumes: `rememberHintDemo`、`HintTiming`、`HintBubble`、`DemoFinger`、`DemoFingerDefaults`、`rememberDemoFingerState`、`LocalHints`、`hintTouchObserver`（Task 3）；`HintKey`（Task 1）。
- Produces: `SwipeToRemoveRow(background, onRemove, enabled = true, hintKey: HintKey? = null, showHint: Boolean = false, content)`（删去 `demo` / `onDemoDone`）；`PlayerQueueSheet` 删去 `showSwipeHint` / `onSwipeHintShown`。

- [ ] **Step 1: 替换 `SwipeToRemoveRow.kt`**

整个文件内容：

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.biliseek.app.hint.HintKey
import com.biliseek.designsystem.theme.BsSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 可向右滑动删除的一行（播放队列、稍后再看、收藏夹内容页、本地列表、已下载、AI 总结列表共用）。
 *
 * 不用 Material3 的 SwipeToDismissBox：自己控制偏移量，首次演示和手势共用同一个 [Animatable]。
 * 滑过行宽 40%（或快速甩动）松手即删除，否则弹回。
 * [background] 是行的底色：前景要不透明才能盖住后面的红色删除底，所以要和所在页面的底色一致。
 *
 * 首次演示（设计稿 2026-10-07-first-use-hints）：[hintKey] 是这个列表的演示，[showHint] 为真的那一行
 * 轮到时自己往右挪 88dp（露出「删除」）再弹回，画一个手指、下方气泡说明；用户在任一行自己删过就记为看过。
 */
@Composable
fun SwipeToRemoveRow(
    background: Color,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    hintKey: HintKey? = null,
    showHint: Boolean = false,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    val nudgePx = with(LocalDensity.current) { SWIPE_HINT_NUDGE.toPx() }
    val hints = LocalHints.current
    val demo = rememberHintDemo(hintKey, ready = showHint && enabled)
    val finger = rememberDemoFingerState()

    LaunchedEffect(demo.running) {
        if (!demo.running) {
            finger.reset()
            // 演示被碰掉时行可能还挪着：弹回（用户接着拖会接管偏移量）
            if (offset.value != 0f) offset.animateTo(0f)
            return@LaunchedEffect
        }
        // 面板展开 / 页面进场走完再演示，否则用户看不到
        delay(HintTiming.START_DELAY_MS)
        finger.show()
        offset.animateTo(nudgePx, tween(SWIPE_HINT_MOVE_MS))
        delay(SWIPE_HINT_HOLD_MS)
        offset.animateTo(0f, tween(SWIPE_HINT_MOVE_MS))
        finger.hide()
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .clipToBounds(),
    ) {
        if (offset.value > 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(BsSize.iconSm),
                )
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(background)
                .draggable(
                    enabled = enabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            val passed = widthPx > 0 && offset.value > widthPx * SWIPE_REMOVE_FRACTION
                            if (passed || velocity > SWIPE_FLING_VELOCITY) {
                                offset.animateTo(widthPx.toFloat(), tween(SWIPE_HINT_MOVE_MS))
                                onRemove()
                                if (hintKey != null) hints?.onUserAction(hintKey)
                                // 删除后同一个 key 的行可能被复用（例如撤销），偏移要归零
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f)
                            }
                        }
                    },
                ),
        ) {
            content()
            // 手指跟着行一起挪
            DemoFinger(
                finger,
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = SWIPE_HINT_FINGER_X - DemoFingerDefaults.Size / 2),
            )
        }
        if (demo.running) HintBubble(SWIPE_HINT_TEXT, pointX = SWIPE_HINT_FINGER_X)
    }
}

/** 演示挪多远：88dp 刚好露全「删除」两个字（72dp 只露出一半）。 */
private val SWIPE_HINT_NUDGE = 88.dp

/** 演示手指在行里的横坐标（封面上）。 */
private val SWIPE_HINT_FINGER_X = 48.dp
private const val SWIPE_HINT_TEXT = "右滑可以移除，能撤销"
private const val SWIPE_HINT_MOVE_MS = 250
private const val SWIPE_HINT_HOLD_MS = 400L
private const val SWIPE_REMOVE_FRACTION = 0.4f
private const val SWIPE_FLING_VELOCITY = 1500f
```

- [ ] **Step 2: 队列面板改用新参数**

`app/src/main/java/com/biliseek/app/ui/components/PlayerQueueSheet.kt`：

(a) 签名里删掉这两行：
```kotlin
    showSwipeHint: Boolean = false,
    onSwipeHintShown: () -> Unit = {},
```
(b) 把
```kotlin
    // 演示放在第一条看得见、能删的行上（不是当前播放那一行）
    val hintIndex = if (showSwipeHint) {
        queue.indices.firstOrNull { it >= firstVisible && it != currentIndex }
    } else {
        null
    }
```
改为：
```kotlin
    // 首次演示放在第一条看得见、能删的行上（不是当前播放那一行）
    val hintIndex = queue.indices.firstOrNull { it >= firstVisible && it != currentIndex }
```
(c) 把
```kotlin
        Box(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT_FRACTION)) {
```
改为（面板是另一个窗口，主界面的触摸观察管不到这里）：
```kotlin
        Box(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT_FRACTION).hintTouchObserver(LocalHints.current)) {
```
(d) 把
```kotlin
                                demo = idx == hintIndex,
                                onDemoDone = onSwipeHintShown,
```
改为：
```kotlin
                                hintKey = HintKey.SWIPE_QUEUE,
                                showHint = idx == hintIndex,
```
(e) import 区加 `import com.biliseek.app.hint.HintKey`（`LocalHints`、`hintTouchObserver` 同包，不用 import）。

- [ ] **Step 3: 删旧的队列演示记录**

(a) `MainActivity.kt` 删掉这一行：
```kotlin
                val queueSwipeHintShown by viewModel.queueSwipeHintShown.collectAsState()
```
并在 `PlayerQueueSheet(` 调用里删掉：
```kotlin
                        showSwipeHint = !queueSwipeHintShown,
                        onSwipeHintShown = viewModel::markQueueSwipeHintShown,
```
(b) `AppViewModel.kt` 删掉：
```kotlin
    /** 队列面板的右滑演示是否已经放过（只在第一次打开时演示一次）。 */
    val queueSwipeHintShown: StateFlow<Boolean> = settingsStore.queueSwipeHintShown

    fun markQueueSwipeHintShown() = settingsStore.setQueueSwipeHintShown()

```
(c) `SettingsStore.kt` 删掉 companion 里的
```kotlin
        const val KEY_QUEUE_SWIPE_HINT = "queue_swipe_hint_shown"
```
以及文件末尾整段（从 `// ── 一次性操作引导` 到 `setQueueSwipeHintShown` 的结束 `}`）：
```kotlin
    // ── 一次性操作引导（SharedPreferences：同步读，界面首帧就要知道放不放演示） ──

    private val hintPrefs by lazy {
        context.getSharedPreferences("biliseek_hints", Context.MODE_PRIVATE)
    }

    private val _queueSwipeHintShown by lazy {
        MutableStateFlow(hintPrefs.getBoolean(KEY_QUEUE_SWIPE_HINT, false))
    }

    /** 队列面板的右滑删除演示是否已经放过（docs/10：只在第一次打开时演示一次）。 */
    val queueSwipeHintShown: StateFlow<Boolean> by lazy { _queueSwipeHintShown.asStateFlow() }

    fun setQueueSwipeHintShown() {
        if (_queueSwipeHintShown.value) return
        _queueSwipeHintShown.value = true
        hintPrefs.edit().putBoolean(KEY_QUEUE_SWIPE_HINT, true).apply()
    }
```
（这个键现在由 `HintKey.SWIPE_QUEUE.prefKey` 读写，同一个 SharedPreferences 文件，老用户的记录照旧有效。删完后检查 `AppViewModel` / `SettingsStore` 有没有因此变成没用的 import，有就删掉。）

- [ ] **Step 4: 其余 5 个列表的第一行**

每处在 `SwipeToRemoveRow(` 的参数里加 `hintKey` / `showHint`（都加在 `onRemove = …` 之后）：

(a) `ui/LibraryScreen.kt` 本地列表内页（约 823 行，`items(reorder.order, key = { it.track.id }) { item ->` 里）：
```kotlin
                                hintKey = HintKey.SWIPE_LOCAL_PLAYLIST,
                                showHint = id == reorder.order.firstOrNull()?.track?.id,
```
(b) `ui/LibraryScreen.kt` 收藏夹内页（约 978 行，`items(state.items, key = { it.bvid }) { track ->` 里）：
```kotlin
                            hintKey = HintKey.SWIPE_FAVORITE,
                            showHint = track.bvid == state.items.firstOrNull()?.bvid,
```
`LibraryScreen.kt` import 区加 `import com.biliseek.app.hint.HintKey`。

(c) `ui/WatchLaterScreen.kt`（约 101 行）：
```kotlin
                                hintKey = HintKey.SWIPE_WATCH_LATER,
                                showHint = item.bvid == state.items.firstOrNull()?.bvid,
```
import 区加 `import com.biliseek.app.hint.HintKey`。

(d) `ui/DownloadsScreen.kt`：已完成列表的两处（约 192、198 行，`DoneEntry.Single` 与 `DoneEntry.Group` 分支）都加：
```kotlin
                                hintKey = HintKey.SWIPE_DOWNLOADS,
                                showHint = entry === doneEntries.firstOrNull(),
```
分 P 页（约 345 行，`itemsIndexed(group.parts, …) { index, part ->` 里）加：
```kotlin
                    hintKey = HintKey.SWIPE_DOWNLOADS,
                    showHint = index == 0,
```
import 区加 `import com.biliseek.app.hint.HintKey`。

(e) `ui/SummaryListScreen.kt`（约 82 行，`items(state.groups, key = SummaryLibraryPolicy::key) { group ->` 里）：
```kotlin
                    hintKey = HintKey.SWIPE_SUMMARY,
                    showHint = group === state.groups.firstOrNull(),
```
import 区加 `import com.biliseek.app.hint.HintKey`。

- [ ] **Step 5: 确认没有遗留旧参数**

Run: `grep -rn "onDemoDone\|demo = \|showSwipeHint\|queueSwipeHint\|KEY_QUEUE_SWIPE_HINT" app/src`
Expected: 无输出。

- [ ] **Step 6: 编译 + 测试**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/SwipeToRemoveRow.kt app/src/main/java/com/biliseek/app/ui/components/PlayerQueueSheet.kt app/src/main/java/com/biliseek/app/MainActivity.kt app/src/main/java/com/biliseek/app/AppViewModel.kt app/src/main/java/com/biliseek/app/data/SettingsStore.kt app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt app/src/main/java/com/biliseek/app/ui/WatchLaterScreen.kt app/src/main/java/com/biliseek/app/ui/DownloadsScreen.kt app/src/main/java/com/biliseek/app/ui/SummaryListScreen.kt
git commit -m "feat(hint): 六个列表的右滑移除首次演示（88dp、手指、气泡）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 迷你条 ①②③

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/MiniPlayer.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DraggableProgressBar.kt`

**Interfaces:**
- Consumes: Task 3 的界面基础件；`HintKey`；`MiniBarPolicy.kt` 里已有的 `scrubTarget`、`snapToStep`、`SCRUB_STEP_MS`、`zoomWindowMs`、`zoomPressMs`、`zoomStartMs`。
- Produces: 无新的对外接口（两个组件签名不变）。

- [ ] **Step 1: `MiniPlayer` ① 的状态与脚本**

在 `MiniPlayer.kt` 的
```kotlin
        var revealed by remember { mutableStateOf(true) }
```
下面插入：
```kotlin

        // 首次演示 ①：整条上移再弹回、下移再弹回（设计稿 2026-10-07-first-use-hints）
        val hints = LocalHints.current
        val swipeDemo = rememberHintDemo(HintKey.MINIBAR_SWIPE, ready = revealed)
        val swipeFinger = rememberDemoFingerState()
        val lift = remember { Animatable(0f) }
        LaunchedEffect(swipeDemo.running) {
            if (!swipeDemo.running) {
                swipeFinger.reset()
                lift.animateTo(0f)
                return@LaunchedEffect
            }
            delay(HintTiming.START_DELAY_MS)
            swipeFinger.show()
            lift.animateTo(-MINIBAR_DEMO_UP_DP, tween(MINIBAR_DEMO_MOVE_MS))
            lift.animateTo(0f, tween(MINIBAR_DEMO_MOVE_MS))
            delay(MINIBAR_DEMO_PAUSE_MS)
            lift.animateTo(MINIBAR_DEMO_DOWN_DP, tween(MINIBAR_DEMO_MOVE_MS))
            lift.animateTo(0f, tween(MINIBAR_DEMO_MOVE_MS))
            swipeFinger.hide()
            delay(HintTiming.BUBBLE_HOLD_MS)
            swipeDemo.finish()
        }
```

- [ ] **Step 2: `MiniPlayer` ① 的画面**

(a) 把
```kotlin
        Surface(
            tonalElevation = 3.dp,
            modifier = surfaceModifier.fillMaxWidth(),
        ) {
            AnimatedContent(
```
改为：
```kotlin
        Box {
        Surface(
            tonalElevation = 3.dp,
            // 演示 ① 时整条上下挪：offset 放在 clip 前面，挪的是整条，不是被裁掉的内容
            modifier = Modifier
                .offset { IntOffset(0, lift.value.dp.roundToPx()) }
                .then(surfaceModifier)
                .fillMaxWidth(),
        ) {
            Box {
            AnimatedContent(
```
(b) 把函数末尾
```kotlin
                } else {
                    MiniBarHandle(
                        onDrag = { dragAmount ->
                            if (dragAmount < -HANDLE_DRAG_THRESHOLD) revealed = true
                        },
                    )
                }
            }
        }
    }
}
```
改为：
```kotlin
                } else {
                    MiniBarHandle(
                        onDrag = { dragAmount ->
                            if (dragAmount < -HANDLE_DRAG_THRESHOLD) revealed = true
                        },
                    )
                }
            }
            // 手指在迷你条正中，跟着整条一起挪
            DemoFinger(swipeFinger, Modifier.align(Alignment.Center))
            }
        }
        if (swipeDemo.running) HintBubble(MINIBAR_SWIPE_HINT_TEXT, preferAbove = true)
        }
    }
}
```
然后把新包进去的两层（`Box {` 到对应的 `}`）里的内容各多缩进 4 格，保持文件缩进整齐。

(c) 用户自己竖滑也算看过——把
```kotlin
                                        MiniBarSwipe.EXPAND -> { fired = true; currentOnExpand() }
                                        MiniBarSwipe.COLLAPSE -> { fired = true; revealed = false }
```
改为：
```kotlin
                                        MiniBarSwipe.EXPAND -> {
                                            fired = true
                                            hints?.onUserAction(HintKey.MINIBAR_SWIPE)
                                            currentOnExpand()
                                        }
                                        MiniBarSwipe.COLLAPSE -> {
                                            fired = true
                                            hints?.onUserAction(HintKey.MINIBAR_SWIPE)
                                            revealed = false
                                        }
```
(d) 文件末尾（`HANDLE_DRAG_THRESHOLD` 下面）加常量：
```kotlin

// 首次演示 ① 的节奏（设计稿 2026-10-07-first-use-hints；效果图 minibar-sequence.html）
private const val MINIBAR_DEMO_UP_DP = 18f
private const val MINIBAR_DEMO_DOWN_DP = 12f
private const val MINIBAR_DEMO_MOVE_MS = 250
private const val MINIBAR_DEMO_PAUSE_MS = 400L
private const val MINIBAR_SWIPE_HINT_TEXT = "往上滑打开播放页，往下滑收起"
```
(e) import 区加：
```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntOffset
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.DemoFinger
import com.biliseek.app.ui.components.HintBubble
import com.biliseek.app.ui.components.HintTiming
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.rememberDemoFingerState
import com.biliseek.app.ui.components.rememberHintDemo
import kotlinx.coroutines.delay
```

- [ ] **Step 3: 编译 ①**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: `DraggableProgressBar` ②③**

`app/src/main/java/com/biliseek/app/ui/components/DraggableProgressBar.kt`：

(a) 把函数开头
```kotlin
    val haptic = LocalHapticFeedback.current
    val currentPosition by rememberUpdatedState(positionMs)
```
改为
```kotlin
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentPosition by rememberUpdatedState(positionMs)
```
(b) 在
```kotlin
    var widthPx by remember { mutableIntStateOf(0) }
```
下面插入：
```kotlin

    // 首次演示 ②③（设计稿 2026-10-07-first-use-hints）：只改显示用的 scrubMs / ruler，不调 onSeek
    val hints = LocalHints.current
    val dragDemo = rememberHintDemo(HintKey.MINIBAR_DRAG, ready = durationMs > 0)
    val zoomDemo = rememberHintDemo(HintKey.MINIBAR_ZOOM, ready = durationMs > 0)
    val finger = rememberDemoFingerState()
    // scrubMs / ruler 此刻是不是演示设的：用户一按下就清掉，交还给手势
    var demoScrubbing by remember { mutableStateOf(false) }
    // 气泡尖角指的位置：演示开始时手指所在处，之后不跟着走
    var demoAnchorX by remember { mutableStateOf(0.dp) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis

    // 进度线上 [fraction] 处的横坐标（px）
    fun barX(fraction: Float): Float {
        val inset = with(density) { BAR_INSET.toPx() }
        return inset + (widthPx - 2 * inset) * fraction.coerceIn(0f, 1f)
    }

    fun clearDemoScrub() {
        if (!demoScrubbing) return
        demoScrubbing = false
        ruler = null
        scrubMs = null
        currentOnScrub(null)
    }

    // ②：圆点按 5 秒一格走 4 格再退回，时间跟着变色
    LaunchedEffect(dragDemo.running) {
        if (!dragDemo.running) {
            clearDemoScrub()
            finger.reset()
            return@LaunchedEffect
        }
        delay(HintTiming.START_DELAY_MS)
        val start = currentPosition
        val duration = currentDuration
        val startFraction = if (duration > 0) start.toFloat() / duration else 0f
        demoAnchorX = with(density) { barX(startFraction).toDp() }
        demoScrubbing = true
        finger.show()
        scrubMs = start
        currentOnScrub(start)
        for (step in 1..DRAG_DEMO_STEPS) {
            delay(DRAG_DEMO_STEP_MS)
            val target = scrubTarget(start, step, duration)
            scrubMs = target
            currentOnScrub(target)
        }
        delay(DEMO_END_PAUSE_MS)
        clearDemoScrub()
        finger.hide()
        delay(HintTiming.BUBBLE_HOLD_MS)
        dragDemo.finish()
    }

    // ③：在进度线约 70% 处按住，半秒后浮出刻度尺，指针走 3 格，收起
    LaunchedEffect(zoomDemo.running) {
        if (!zoomDemo.running) {
            clearDemoScrub()
            finger.reset()
            return@LaunchedEffect
        }
        delay(HintTiming.START_DELAY_MS)
        val duration = currentDuration
        val window = zoomWindowMs(duration)
        val pressMs = zoomPressMs(ZOOM_DEMO_FRACTION, duration)
        demoAnchorX = with(density) { barX(ZOOM_DEMO_FRACTION).toDp() }
        demoScrubbing = true
        finger.show()
        scrubMs = pressMs
        currentOnScrub(pressMs)
        delay(longPressMs)
        ruler = ZoomRuler(zoomStartMs(pressMs, ZOOM_DEMO_FRACTION, window), window)
        for (step in 1..ZOOM_DEMO_STEPS) {
            delay(ZOOM_DEMO_STEP_MS)
            val target = snapToStep(pressMs + step * SCRUB_STEP_MS, duration)
            scrubMs = target
            currentOnScrub(target)
        }
        delay(DEMO_END_PAUSE_MS)
        clearDemoScrub()
        finger.hide()
        delay(HintTiming.BUBBLE_HOLD_MS)
        zoomDemo.finish()
    }
```
(c) 手势里交还状态、记「用户自己用过」。把
```kotlin
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentDuration <= 0) return@awaitEachGesture
```
改为
```kotlin
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 演示中按下：演示设的拖动状态先清掉，交还给手势
                        clearDemoScrub()
                        if (currentDuration <= 0) return@awaitEachGesture
```
把左右拖分支 `finally` 里的
```kotlin
                                if (completed && target != null && target != startMs) currentOnSeek(target)
```
改为
```kotlin
                                if (completed && target != null && target != startMs) currentOnSeek(target)
                                // 自己拖完一次：② 记为看过，③ 可以放了
                                if (completed) hints?.onUserAction(HintKey.MINIBAR_DRAG)
```
把放大分支 `finally` 里的
```kotlin
                                if (completed && target != null && target != startPosition) currentOnSeek(target)
```
改为
```kotlin
                                if (completed && target != null && target != startPosition) currentOnSeek(target)
                                if (completed) hints?.onUserAction(HintKey.MINIBAR_ZOOM)
```
(d) Canvas 里画线的竖直位置改用常量：把
```kotlin
            val y = 11.dp.toPx()
```
改为
```kotlin
            val y = BAR_LINE_Y.toPx()
```
(e) 在 Canvas 结束（`}` 之后）、`val zoom = ruler` 之前插入：
```kotlin

        // 演示的手指：放大时在刻度尺选中处对应的进度线位置（手指按在进度线上），否则在圆点上
        val zoomNow = ruler
        val fingerFraction = if (zoomNow != null && zoomNow.windowMs > 0) {
            (shownMs - zoomNow.startMs).toFloat() / zoomNow.windowMs
        } else {
            fraction
        }
        DemoFinger(
            finger,
            Modifier.offset {
                IntOffset(
                    (barX(fingerFraction) - DemoFingerDefaults.Size.toPx() / 2).roundToInt(),
                    (BAR_LINE_Y - DemoFingerDefaults.Size / 2).roundToPx(),
                )
            },
        )
        if (dragDemo.running) HintBubble(DRAG_HINT_TEXT, pointX = demoAnchorX, preferAbove = true)
        if (zoomDemo.running) {
            HintBubble(ZOOM_HINT_TEXT, pointX = demoAnchorX, preferAbove = true, extraAbove = RULER_HEIGHT + RULER_GAP)
        }
```
(f) 文件末尾加常量：
```kotlin

/** 进度线的竖直位置（Canvas 里画线的 y）。 */
private val BAR_LINE_Y = 11.dp

// 首次演示 ②③ 的节奏（设计稿 2026-10-07-first-use-hints；效果图 minibar-sequence.html）
private const val DRAG_DEMO_STEPS = 4
private const val DRAG_DEMO_STEP_MS = 350L
private const val ZOOM_DEMO_STEPS = 3
private const val ZOOM_DEMO_STEP_MS = 450L
private const val DEMO_END_PAUSE_MS = 600L

/** ③ 演示按住的位置：进度线约 70% 处。 */
private const val ZOOM_DEMO_FRACTION = 0.7f
private const val DRAG_HINT_TEXT = "左右拖进度线，每格 5 秒"
private const val ZOOM_HINT_TEXT = "按住进度线半秒，出刻度尺细调"
```
(g) import 区加：
```kotlin
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalViewConfiguration
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.SCRUB_STEP_MS
import com.biliseek.app.ui.snapToStep
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
```

- [ ] **Step 5: 编译 + 测试**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL（`MiniBarPolicyTest`、`PlayerUiTest` 编译不受影响：组件签名没变）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/MiniPlayer.kt app/src/main/java/com/biliseek/app/ui/components/DraggableProgressBar.kt
git commit -m "feat(hint): 迷你条上滑、拖进度、刻度尺三段首次演示

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 动态页（⑩⑪）、搜索结果（⑫）、UP 空间（⑬）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/components/LongPressHint.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/SearchScreen.kt`（约 540–566 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt`（约 302–313 行）

**Interfaces:**
- Consumes: Task 3 的界面基础件；`HintKey`。
- Produces:
  - `@Composable fun LongPressHint(key: HintKey, text: String, showHint: Boolean, fingerX: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit)`
  - `const val MULTI_SELECT_HINT_TEXT = "长按一条进入多选，可以一次加好几条"`
  - `DynamicCard(..., videoHint: Boolean = false)`（新参数放在 `videoActions` 之后）

- [ ] **Step 1: `LongPressHint.kt`**

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import com.biliseek.app.hint.HintKey
import kotlinx.coroutines.delay

/** 搜索结果、UP 空间投稿长按多选的气泡文字（⑫⑬）。 */
const val MULTI_SELECT_HINT_TEXT = "长按一条进入多选，可以一次加好几条"

/**
 * 长按类操作的首次演示（设计稿 2026-10-07-first-use-hints，效果图选 B）：包住一行，
 * [showHint] 为真且轮到 [key] 时，画一个手指按住、外圈进度环在系统长按时间内转满、这一行变深，下方气泡说明。
 * 只是画面：不触发长按回调。用户自己长按过，由调用方 `LocalHints.current?.onUserAction(key)`。
 *
 * @param fingerX 手指在行内的横坐标（从行左缘算起），一般是封面中心。
 */
@Composable
fun LongPressHint(
    key: HintKey,
    text: String,
    showHint: Boolean,
    fingerX: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val demo = rememberHintDemo(key, ready = showHint)
    val finger = rememberDemoFingerState()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    LaunchedEffect(demo.running) {
        if (!demo.running) {
            finger.reset()
            return@LaunchedEffect
        }
        delay(HintTiming.START_DELAY_MS)
        finger.longPress(longPressMs)
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
    }
    Box(modifier) {
        content()
        if (finger.pressed) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = PRESSED_ALPHA)),
            )
        }
        DemoFinger(
            finger,
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = fingerX - DemoFingerDefaults.Size / 2),
        )
        if (demo.running) HintBubble(text, pointX = fingerX)
    }
}

/** 按下态：同 Material 的 pressed 层。 */
private const val PRESSED_ALPHA = 0.10f
```

- [ ] **Step 2: 动态页进入计数（DynamicScreen）**

把
```kotlin
    // 登录后首次进入自动初始化（拉关注列表 + 第一页）
    LaunchedEffect(Unit) { onInit() }
```
改为：
```kotlin
    // 登录后首次进入自动初始化（拉关注列表 + 第一页）
    LaunchedEffect(Unit) { onInit() }
    // 首次演示 ⑪ 要等「另一次进入」动态页（设计稿 2026-10-07-first-use-hints）
    val hints = LocalHints.current
    LaunchedEffect(Unit) { hints?.enterDynamic() }
```

- [ ] **Step 3: ⑩ 最常访问第一个头像**

把 `MostVisitedUpRow` 里的
```kotlin
    // UP 头像横滑列表
    LazyRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(upChips.take(10), key = { it.mid }) { up ->
            UpAvatarChip(
                up = up,
                selected = selectedUpMid == up.mid,
                onClick = { onSelectUp(up) },
            )
        }
    }
}
```
改为：
```kotlin
    // 首次演示 ⑩：手指点第一个头像两下，第一下出蓝圈、第二下消失（只是画面，不筛选）
    val hints = LocalHints.current
    val demo = rememberHintDemo(HintKey.DYNAMIC_UP_FILTER, ready = upChips.isNotEmpty() && selectedUpMid == null)
    val finger = rememberDemoFingerState()
    var demoSelected by remember { mutableStateOf(false) }
    LaunchedEffect(demo.running) {
        if (!demo.running) {
            demoSelected = false
            finger.reset()
            return@LaunchedEffect
        }
        delay(HintTiming.START_DELAY_MS)
        finger.show()
        delay(UP_DEMO_PAUSE_MS)
        finger.tap()
        demoSelected = true
        delay(UP_DEMO_SELECTED_MS)
        finger.tap()
        demoSelected = false
        delay(UP_DEMO_PAUSE_MS)
        finger.hide()
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
    }

    // UP 头像横滑列表
    LazyRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(upChips.take(10), key = { _, up -> up.mid }) { index, up ->
            val chip = @Composable {
                UpAvatarChip(
                    up = up,
                    selected = selectedUpMid == up.mid || (index == 0 && demoSelected),
                    onClick = {
                        hints?.onUserAction(HintKey.DYNAMIC_UP_FILTER)
                        onSelectUp(up)
                    },
                )
            }
            if (index == 0) {
                Box {
                    chip()
                    DemoFinger(
                        finger,
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = UP_AVATAR_CENTER_Y - DemoFingerDefaults.Size / 2),
                    )
                    if (demo.running) HintBubble(UP_FILTER_HINT_TEXT)
                }
            } else {
                chip()
            }
        }
    }
}

// 首次演示 ⑩ 的节奏与位置
private const val UP_DEMO_PAUSE_MS = 200L
private const val UP_DEMO_SELECTED_MS = 900L

/** 头像（40dp）中心离头像格顶边的距离。 */
private val UP_AVATAR_CENTER_Y = 20.dp
private const val UP_FILTER_HINT_TEXT = "点头像只看这个 UP 的动态，再点一次取消"
```

- [ ] **Step 4: ⑪ 第一张视频卡片**

(a) `DynamicScreen` 主列表：把
```kotlin
                        items(state.feed.items, key = { it.idStr }) { entry ->
                            DynamicCard(
```
改为：
```kotlin
                        // 首次演示 ⑪ 放在第一条带视频的动态上
                        val firstVideoId = state.feed.items.firstOrNull { it.video != null }?.idStr
                        items(state.feed.items, key = { it.idStr }) { entry ->
                            DynamicCard(
```
并在这个 `DynamicCard(` 调用的 `videoActions = videoActions,` 下一行加：
```kotlin
                                videoHint = entry.idStr == firstVideoId,
```
(b) `DynamicCard` 签名：在
```kotlin
    /** 视频行长按弹出的动作菜单。null = 不挂长按。 */
    videoActions: DynamicVideoActions? = null,
```
下面加：
```kotlin
    /** 首次演示 ⑪：这一张的视频行演示长按（只有动态 tab 的第一张视频卡片为真）。 */
    videoHint: Boolean = false,
```
(c) `DynamicCard` 视频行：把
```kotlin
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().combinedClickable(
                    onClick = onClick,
                    onLongClick = videoActions?.let { { showActions = true } },
                ),
```
改为：
```kotlin
            val hints = LocalHints.current
            LongPressHint(
                key = HintKey.DYNAMIC_VIDEO_MENU,
                text = VIDEO_MENU_HINT_TEXT,
                showHint = videoHint && videoActions != null,
                fingerX = VIDEO_COVER_CENTER_X,
                modifier = Modifier.padding(top = 8.dp),
            ) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = onClick,
                    onLongClick = videoActions?.let {
                        {
                            hints?.onUserAction(HintKey.DYNAMIC_VIDEO_MENU)
                            showActions = true
                        }
                    },
                ),
```
并在这个 `Row` 的结束处——也就是
```kotlin
                    if (entry.rawType == "DYNAMIC_TYPE_FORWARD") {
                        Text("转发动态", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else if (bodyImageUrls(body).isNotEmpty()) {
```
改为：
```kotlin
                    if (entry.rawType == "DYNAMIC_TYPE_FORWARD") {
                        Text("转发动态", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            }
        } else if (bodyImageUrls(body).isNotEmpty()) {
```
然后把 `LongPressHint { … }` 里包住的整个 `Row` 多缩进 4 格。
(d) 文件末尾加：
```kotlin

// 首次演示 ⑪
private const val VIDEO_MENU_HINT_TEXT = "长按视频：加入列表、队列或下一首播放"

/** 视频行里封面（96dp 宽，从行左缘起）的中心。 */
private val VIDEO_COVER_CENTER_X = 48.dp
```
(e) `DynamicScreen.kt` import 区加（已有的不重复）：
```kotlin
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.DemoFinger
import com.biliseek.app.ui.components.DemoFingerDefaults
import com.biliseek.app.ui.components.HintBubble
import com.biliseek.app.ui.components.HintTiming
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.LongPressHint
import com.biliseek.app.ui.components.rememberDemoFingerState
import com.biliseek.app.ui.components.rememberHintDemo
import kotlinx.coroutines.delay
```

- [ ] **Step 5: ⑫ 搜索结果第一条**

`SearchScreen.kt` 把
```kotlin
                    items(state.items, key = { it.bvid }) { video ->
                        val selected = video.bvid in selectedBvids
                        ResultRow(
                            video = video,
                            onClick = {
                                if (isSelectionMode) onToggleSelection(video.bvid)
                                else onResultClick(video)
                            },
                            onLongClick = {
                                // 复选态下长按不做事；正常态长按进入复选模式并勾选该项
                                if (!isSelectionMode) onEnterSelection(video)
                            },
```
改为：
```kotlin
                    items(state.items, key = { it.bvid }) { video ->
                        val selected = video.bvid in selectedBvids
                        val hints = LocalHints.current
                        // 首次演示 ⑫：第一条演示长按进多选（设计稿 2026-10-07-first-use-hints）
                        LongPressHint(
                            key = HintKey.SEARCH_MULTI_SELECT,
                            text = MULTI_SELECT_HINT_TEXT,
                            showHint = !isSelectionMode && video.bvid == state.items.firstOrNull()?.bvid,
                            fingerX = RESULT_COVER_CENTER_X,
                        ) {
                        ResultRow(
                            video = video,
                            onClick = {
                                if (isSelectionMode) onToggleSelection(video.bvid)
                                else onResultClick(video)
                            },
                            onLongClick = {
                                // 复选态下长按不做事；正常态长按进入复选模式并勾选该项
                                if (!isSelectionMode) {
                                    hints?.onUserAction(HintKey.SEARCH_MULTI_SELECT)
                                    onEnterSelection(video)
                                }
                            },
```
并在这个 `ResultRow(…)` 调用结束的 `)` 之后、`HorizontalDivider()` 之前加一行 `}`（关闭 `LongPressHint`），把包住的 `ResultRow(…)` 多缩进 4 格。文件末尾加：
```kotlin

/** 搜索结果行里封面（64dp 宽，左边距 16dp）的中心。 */
private val RESULT_COVER_CENTER_X = 48.dp
```
import 区加：
```kotlin
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.LongPressHint
import com.biliseek.app.ui.components.MULTI_SELECT_HINT_TEXT
```

- [ ] **Step 6: ⑬ UP 空间投稿第一条**

`ui/UpSpaceScreen.kt` 把
```kotlin
                items(posts.items, key = { it.bvid }) { video ->
                    PostRow(
                        video = video,
                        selected = video.bvid in selectedBvids,
                        selectionMode = isSelection,
                        onClick = { if (isSelection) onToggleSelection(video.bvid) else onPostClick(video) },
                        onLongClick = { if (!isSelection) onEnterSelection(video) },
                        onToggle = { onToggleSelection(video.bvid) },
                        onOpenActions = { onOpenActions(video) },
                    )
                    HorizontalDivider()
                }
```
改为：
```kotlin
                items(posts.items, key = { it.bvid }) { video ->
                    val hints = LocalHints.current
                    // 首次演示 ⑬：第一条演示长按进多选（设计稿 2026-10-07-first-use-hints）
                    LongPressHint(
                        key = HintKey.UPSPACE_MULTI_SELECT,
                        text = MULTI_SELECT_HINT_TEXT,
                        showHint = !isSelection && video.bvid == posts.items.firstOrNull()?.bvid,
                        fingerX = POST_COVER_CENTER_X,
                    ) {
                        PostRow(
                            video = video,
                            selected = video.bvid in selectedBvids,
                            selectionMode = isSelection,
                            onClick = { if (isSelection) onToggleSelection(video.bvid) else onPostClick(video) },
                            onLongClick = {
                                if (!isSelection) {
                                    hints?.onUserAction(HintKey.UPSPACE_MULTI_SELECT)
                                    onEnterSelection(video)
                                }
                            },
                            onToggle = { onToggleSelection(video.bvid) },
                            onOpenActions = { onOpenActions(video) },
                        )
                    }
                    HorizontalDivider()
                }
```
文件末尾加：
```kotlin

/** 投稿行里封面（64dp 宽，左边距 16dp）的中心。 */
private val POST_COVER_CENTER_X = 48.dp
```
import 区加：
```kotlin
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.LongPressHint
import com.biliseek.app.ui.components.MULTI_SELECT_HINT_TEXT
```

- [ ] **Step 7: 编译 + 测试**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/LongPressHint.kt app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt app/src/main/java/com/biliseek/app/SearchScreen.kt app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt
git commit -m "feat(hint): 动态头像筛选、视频长按、搜索与 UP 空间长按多选的首次演示

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 文档同步与总验证

**Files:**
- Modify: `docs/03-架构设计篇.md`（§7.1 Controller 表，约 369 行之后）
- Modify: `docs/05-功能优化篇.md`（§13 末尾，13.66 之后）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 33 之后，约 1000 行附近、步骤 33 一节的末尾）

**Interfaces:**
- Consumes: 前面所有任务的实际实现（以代码为准）。
- Produces: 无。

- [ ] **Step 1: 先看这三份文档有没有别人的未提交修改**

Run: `git status --short docs/`
Expected: 如果 `docs/05-功能优化篇.md`、`docs/03-架构设计篇.md`、`docs/BiliSeek-手动测试指南.md` 任一显示为已修改（` M`）且不是本计划改的，**停下来问用户**：这些未提交的修改要不要一起提交 / 先提交；得到答复前不要改这份文件。

- [ ] **Step 2: 架构设计篇 §7.1 Controller 表加一行**

在
```markdown
| `SummaryController`、`SummaryLibrary`、`AiSettingsController`、`AsrModelController` | `app/summary/` | AI 总结、总结列表、AI 设置、识别模型 |
```
下面加：
```markdown
| `HintController` | `app/hint/` | 首次使用演示：13 处不易发现的手势各放一次（判定在 `HintPolicy`，记录存 SharedPreferences `biliseek_hints`）；界面经 `LocalHints` 取用、`rememberHintDemo` 决定某个控件此刻是否演示；碰屏幕即结束、用户自己用过即算看过；设置里可重置 |
```

- [ ] **Step 3: 功能优化篇 §13 加一条**

在 `### 13.66 充电专属动态显示锁定卡片（2026-10-05）` 一节（含其「> 后续」段）之后加：
```markdown

### 13.67 首次使用演示（2026-10-07）

- **起因**（用户原话）：「现在对于初次使用该软件的用户，一些功能可能不知道。如在多处列表中右滑移除，minibar 播放进度条的两种拖动方法，动态页点击 up 主头像仅显示该 up 主信息或取消；等不易察觉到功能。」
- **范围**（用户审定 A 类 + B1）：6 个列表右滑移除（队列、本地列表、收藏夹、稍后再看、已下载、AI 总结，每个列表各一次）、迷你条上滑 / 下滑、左右拖进度、按住出刻度尺、动态页点「最常访问」头像筛选 / 再点取消、动态视频长按菜单、搜索结果与 UP 空间长按多选，共 13 项。
- **样子**（浏览器效果图里选定）：控件原地做一遍动作 + 画出来的手指 + 指着控件的白色小气泡（不用聚光灯、不用开机引导）；迷你条三段循序渐进：第一次出现放上滑，下一次冷启动放拖进度，用户自己拖完进度线后放刻度尺；点 / 长按类画手指点两下 / 按住（进度环），只是画面。右滑演示挪动距离 72dp → 88dp（「删除」露全）。
- **规则**：每项只放一次、同一时间一个（被占着的这次进入放弃，不连放）；碰屏幕任何地方即结束并记为看过；用户自己用过即算看过；全屏 / 画中画 / 本窗口无焦点不放；设置 → 界面 → 「重新显示操作提示」全部重来。
- **做法**：`hint/HintPolicy.kt`（纯函数）+ `HintController`（`AppViewModel.hints`）+ `SharedPrefsHintRecords`（`biliseek_hints`；队列沿用旧键，老用户不重放）；界面 `LocalHints` / `rememberHintDemo` / `HintBubble` / `DemoFinger` / `LongPressHint`；`SwipeToRemoveRow(hintKey, showHint)` 取代 `demo` / `onDemoDone`；旧的 `SettingsStore.queueSwipeHintShown` 删除。
- **已知局限**：没关注任何 UP（「最常访问」不出现）时头像演示放不了，视频长按演示也就一直不放。
- **验证**：`HintPolicyTest`（13 例）、`HintControllerTest`（9 例）；`./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin` 通过；动画手感与出现时机待用户真机验证。
- 设计稿：`docs/superpowers/specs/2026-10-07-first-use-hints-design.md`。
```

- [ ] **Step 4: 手动测试指南加一步**

在 `### 步骤 33 — 播放页：…` 一节的末尾（下一个 `##` / `###` 标题之前）加：
```markdown

### 步骤 33.1 — 首次使用演示（2026-10-07 新增，规则见 `docs/superpowers/specs/2026-10-07-first-use-hints-design.md`）

先到 设置 → 界面 → 「重新显示操作提示」点一下（提示「已重置，下次碰到时会再演示」），然后：

| 操作 | 预期 |
| --- | --- |
| 打开有内容的收藏夹 / 本地列表 / 稍后再看 / 已下载 / AI 总结 / 播放队列 | 每处第一次：第一行（队列是第一条非当前曲目）自己右滑 88dp 露出「删除」再弹回，有灰色手指，下方气泡「右滑可以移除，能撤销」，约 4 秒后消失；再进同一列表不再放 |
| 演示中碰屏幕任何地方 | 气泡和动作立即结束，这次触摸照常生效；之后不再放这一项 |
| 第一次出现迷你条 | 整条上移再弹回、下移再弹回，上方气泡「往上滑打开播放页，往下滑收起」 |
| 划掉 App 重新打开、出现迷你条 | 进度线加粗、圆点走 4 格再退回，时间变蓝，气泡「左右拖进度线，每格 5 秒」；**不会真跳进度** |
| 自己左右拖一次进度线、松手 | 紧接着放刻度尺演示：圆点到约 70% 处，半秒后浮出刻度尺、指针走 3 格后收起，气泡在刻度尺上方；**不会真跳进度** |
| 进动态页（已登录、有关注） | 「最常访问」第一个头像上手指点两下：出蓝圈又消失，气泡「点头像只看这个 UP 的动态，再点一次取消」；**动态列表不刷新** |
| 离开动态页再回来 | 第一张视频卡片的视频行：手指按住、进度环转满、那一行变深，气泡「长按视频：加入列表、队列或下一首播放」；**不弹菜单** |
| 第一次出现搜索结果 / 第一次打开有投稿的 UP 空间 | 第一条上同样的长按演示，气泡「长按一条进入多选，可以一次加好几条」；**不进多选** |
| 演示开始前自己先用过这个操作（如先自己右滑删一条） | 这一项不再演示 |
| 全屏、小窗时 | 不开始任何演示 |
```

- [ ] **Step 5: 总验证**

Run: `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL，全部单测通过。

Run: `grep -rn "onDemoDone\|showSwipeHint\|queueSwipeHint" app/src docs/03-架构设计篇.md`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add docs/03-架构设计篇.md docs/05-功能优化篇.md docs/BiliSeek-手动测试指南.md
git commit -m "docs: 首次使用演示同步到架构设计篇、功能优化篇 13.67、手动测试指南步骤 33.1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
（若 Step 1 里用户决定不纳入某份文档的既有修改，就从这条 `git add` 里去掉那份文件，改为只提交本次新增的段落——无法单独提交时问用户。）

- [ ] **Step 7: 告知用户**

不装机。告诉用户：改完、测试通过；真机要看的是步骤 33.1 那张表；装机等用户开口。
