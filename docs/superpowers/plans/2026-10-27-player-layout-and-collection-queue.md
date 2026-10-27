# 合集第二轮：播放页新布局 + 点当前集把合集放进列表 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 播放页改成「顶栏中间听 | 看、控制块沉底、倍速 / 定时回三点菜单（设了值进度条上方出标签）、中段 合集卡片 → 简介行 → 简介预览填满」；合集弹层里点正在播的那一集，不重新起播、把整个合集放进播放列表。

**Architecture:** `:core:player` 加纯函数 `resolveQueueOnAdopt`（`QueuePolicy.kt`）和公开方法 `PlayerConnection.adoptContext`；`:app` 的 `CollectionController` 通过宿主回调调用它，弹层点当前集走这条路。播放页：`PlayerSwitchRow.kt` 里的听看开关改成顶栏用的紧凑版、新增 `PlayerStatusChips`、删掉 `PlayerSwitchRow`；`PlayerMoreMenuSheet` 加倍速 / 定时两项；`VideoDescription.kt` 加 `VideoDescriptionPreview`，行数由 `VideoDescriptionPolicy.previewLines` 算。设计稿：`docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md`。

**Tech Stack:** Kotlin、Jetpack Compose（Material3）、Media3、JUnit4、Compose UI Test。

## Global Constraints

- **自动化代码绝不请求 B 站服务器**：所有测试只用本地构造数据；真机效果由用户验证。本轮不新增任何 B 站请求。
- **点击播放永远不删带图钉的条目**（docs/10）；「换进合集」同样：固定条目原样保留，当前视频带图钉时保留图钉。
- `:core:player` 公开接口只新增 `PlayerConnection.adoptContext(items: List<PlaybackRequest>, index: Int): Int?`；`resolveQueueOnAdopt` / `AdoptPlan` 是 `internal`。`QueueEntry` 仍是 `private`。
- 叫法统一：「倍速」「定时」「放进播放列表」；提示走 `notify` / `emitMessage`；底部弹层用 `CompactDragHandle`。
- 决策逻辑写成纯函数并配单测（`*Policy.kt`）。
- 所有提交在分支 `feat/collections` 上，提交信息结尾带：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- 命令只用普通 `./gradlew …`（不套 `timeout`、不把内联脚本管道给解释器）；任何工具调用被拒，立刻报 BLOCKED 并附命令。不装机、不跑设备测试（装机在最后由主控做）。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `core/player/src/main/java/com/biliseek/core/player/internal/QueuePolicy.kt` | 改 | `AdoptPlan`、`resolveQueueOnAdopt` |
| `core/player/src/main/java/com/biliseek/core/player/PlayerConnection.kt` | 改 | `adoptContext` |
| `core/player/src/test/java/com/biliseek/core/player/internal/QueuePolicyTest.kt` | 改 | `resolveQueueOnAdopt` 单测 |
| `app/src/main/java/com/biliseek/app/collection/CollectionPolicy.kt` | 改 | `adoptMessage` |
| `app/src/main/java/com/biliseek/app/collection/CollectionController.kt` | 改 | `adoptList` 回调、`queueEpisodesAround` |
| `app/src/main/java/com/biliseek/app/ui/CollectionSheet.kt` | 改 | `onQueueCollection` |
| `app/src/main/java/com/biliseek/app/ui/PlayerSheets.kt` | 改 | 菜单加倍速 / 定时（右侧显示当前值） |
| `app/src/main/java/com/biliseek/app/ui/PlayerSwitchRow.kt` | 改 | 紧凑听看开关（`internal`）、`PlayerStatusChips`；删 `PlayerSwitchRow` |
| `app/src/main/java/com/biliseek/app/ui/VideoDescription.kt` / `VideoDescriptionPolicy.kt` | 改 | `VideoDescriptionPreview` / `previewLines` |
| `app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt` | 改 | 顶栏、中段顺序与预览、状态标签、控制块沉底、菜单回调、弹层回调 |
| `app/src/main/java/com/biliseek/app/AppViewModel.kt` / `MainActivity.kt` | 改 | 接线 |
| `app/src/test/…/collection/CollectionPolicyTest.kt`、`…/ui/VideoDescriptionPolicyTest.kt` | 改 | 单测 |
| `app/src/androidTest/java/com/biliseek/app/PlayerUiTest.kt` | 改 | 顶栏、倍速 / 定时入口用例 |
| `docs/05-功能优化篇.md`、`docs/10-播放队列工单.md`、`docs/BiliSeek-手动测试指南.md`、`CLAUDE.md` | 改 | 记录、队列规则、手动测试、公开接口 |

## 常用命令

```bash
./gradlew :core:player:testDebugUnitTest --tests "com.biliseek.core.player.internal.QueuePolicyTest"
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.*"
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.ui.VideoDescriptionPolicyTest"
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew test
```

---

### Task 0: 提交设计稿与计划

- [ ] **Step 1:** 在 `feat/collections` 上提交两份文档

```bash
git status --short   # 期望只有这两份新文档
git add docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md docs/superpowers/plans/2026-10-27-player-layout-and-collection-queue.md
git commit -m "docs: 合集第二轮（播放页新布局、点当前集放进列表）设计稿与计划

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: 队列——把合集换进列表、不重新起播（`:core:player`）

**Files:**
- Modify: `core/player/src/main/java/com/biliseek/core/player/internal/QueuePolicy.kt`
- Modify: `core/player/src/main/java/com/biliseek/core/player/PlayerConnection.kt`
- Test: `core/player/src/test/java/com/biliseek/core/player/internal/QueuePolicyTest.kt`

**Interfaces:**
- Produces:
  - `internal data class AdoptPlan(kept: List<Int>, insertAt: Int, runStart: Int, runEnd: Int, newIndex: Int)`
  - `internal fun resolveQueueOnAdopt(pinned: List<Boolean>, keys: List<String>, current: Int, beforeCount: Int): AdoptPlan?`
  - `fun PlayerConnection.adoptContext(items: List<PlaybackRequest>, index: Int): Int?`（公开；返回被替换掉的临时条目数，没换返回 null）

- [ ] **Step 1: 写失败的测试**——`QueuePolicyTest.kt` 类末尾加：

```kotlin
    // ── 「点正在播的那一集」把合集换进列表 ─────────────────

    @Test
    fun `换进合集 只有当前一条临时 当前落在合集里它的位置`() {
        val plan = resolveQueueOnAdopt(pinned = listOf(false), keys = listOf("A"), current = 0, beforeCount = 2)!!
        assertEquals(emptyList<Int>(), plan.kept)
        assertEquals(0, plan.insertAt)
        assertEquals(0, plan.runStart)
        assertEquals(1, plan.runEnd)
        assertEquals(2, plan.newIndex)
    }

    @Test
    fun `换进合集 当前视频已展开多个分P 整段保留 在播的分P不变`() {
        // 当前视频 A 展开成 3 个分 P，正在播第 2 个
        val plan = resolveQueueOnAdopt(
            pinned = listOf(false, false, false),
            keys = listOf("A", "A", "A"),
            current = 1,
            beforeCount = 4,
        )!!
        assertEquals(0, plan.runStart)
        assertEquals(3, plan.runEnd)
        assertEquals(4 + 1, plan.newIndex)
    }

    @Test
    fun `换进合集 前后的固定条目原样保留 新块插在它们中间`() {
        // X(固定) A(当前，临时) Y(固定)
        val plan = resolveQueueOnAdopt(
            pinned = listOf(true, false, true),
            keys = listOf("X", "A", "Y"),
            current = 1,
            beforeCount = 1,
        )!!
        assertEquals(listOf(0, 2), plan.kept)
        assertEquals(1, plan.insertAt)
        // 结果：X、合集第 1 集、A、合集其余… 、Y → A 在下标 2
        assertEquals(2, plan.newIndex)
    }

    @Test
    fun `换进合集 当前条目本身带图钉 不算进保留的固定条目`() {
        // B(临时) A(当前，固定) C(临时)
        val plan = resolveQueueOnAdopt(
            pinned = listOf(false, true, false),
            keys = listOf("B", "A", "C"),
            current = 1,
            beforeCount = 0,
        )!!
        assertEquals(emptyList<Int>(), plan.kept)
        assertEquals(1, plan.runStart)
        assertEquals(2, plan.runEnd)
        assertEquals(0, plan.newIndex)
    }

    @Test
    fun `换进合集 当前下标越界返回 null`() {
        assertNull(resolveQueueOnAdopt(pinned = emptyList(), keys = emptyList(), current = -1, beforeCount = 0))
        assertNull(resolveQueueOnAdopt(pinned = listOf(false), keys = listOf("A"), current = 3, beforeCount = 0))
    }
```

（文件里若还没有 `import org.junit.Assert.assertNull`，加上。）

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :core:player:testDebugUnitTest --tests "com.biliseek.core.player.internal.QueuePolicyTest"`
Expected: 编译失败，`Unresolved reference: resolveQueueOnAdopt`。

- [ ] **Step 3: `QueuePolicy.kt` 加纯函数**——紧接在 `resolveQueueOnTap` 函数后面加：

```kotlin
/** 「把合集换进列表、不重新起播」的改写计划（见 [resolveQueueOnAdopt]）。 */
internal data class AdoptPlan(
    /** 保留的固定条目（原下标、原顺序；不含当前视频那一段）。 */
    val kept: List<Int>,
    /** 新块插在 [kept] 的第几项之前。 */
    val insertAt: Int,
    /** 当前视频那一段在原队列里的范围 [runStart, runEnd)：当前条目前后连着的同一视频条目（含已展开的分 P）。 */
    val runStart: Int,
    val runEnd: Int,
    /** 改写后的当前下标。 */
    val newIndex: Int,
)

/**
 * 合集弹层里点正在播的那一集：临时条目整块换成合集，当前视频（连同已展开的分 P）原样落在合集里它的位置，
 * 不重新起播（docs/10）。
 *
 * - 当前视频那一段 = 当前条目前后连着、[keys] 相同的条目；
 * - 不在这一段里的固定条目原样保留，新块插在这一段原位置之前的固定条目之后；
 * - 新块 = 合集前 [beforeCount] 项 + 这一段 + 合集其余项，所以新下标 = insertAt + beforeCount + 当前条目在段内的偏移。
 *
 * [current] 越界返回 null。
 */
internal fun resolveQueueOnAdopt(
    pinned: List<Boolean>,
    keys: List<String>,
    current: Int,
    beforeCount: Int,
): AdoptPlan? {
    if (current !in keys.indices) return null
    val key = keys[current]
    var start = current
    while (start > 0 && keys[start - 1] == key) start--
    var end = current + 1
    while (end < keys.size && keys[end] == key) end++
    val kept = pinned.indices.filter { pinned[it] && it !in start until end }
    val insertAt = kept.count { it < start }
    return AdoptPlan(
        kept = kept,
        insertAt = insertAt,
        runStart = start,
        runEnd = end,
        newIndex = insertAt + beforeCount + (current - start),
    )
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `./gradlew :core:player:testDebugUnitTest --tests "com.biliseek.core.player.internal.QueuePolicyTest"`
Expected: 全部 PASS。

- [ ] **Step 5: `PlayerConnection.kt` 加 `adoptContext`**——紧接在 `playFromContext` 方法后面加（`resolveQueueOnAdopt` 按文件里其它 `internal` 队列函数的方式 import，如 `import com.biliseek.core.player.internal.resolveQueueOnAdopt`；若这些函数是同文件顶部统一 import 的，照样加一行）：

```kotlin
    /**
     * 把一串内容换成临时块、**不重新起播**：当前在播的视频落在第 [index] 项（合集弹层里点正在播的那一集，docs/10）。
     *
     * [items] 的第 [index] 项必须就是当前在播的视频（按视频比对，同一视频的分 P 算一个），否则什么都不做、返回 null。
     * 当前视频已展开的分 P 原样留在块里；其余条目轮到时再取分 P。固定条目原样保留（见 [resolveQueueOnAdopt]）。
     * 替换掉的临时条目够多时，同点击播放一样留撤销快照、回调 [onQueueReplaced]；撤销时当前条目没变，播放不受影响。
     *
     * @return 被替换掉的临时条目数（见 [countLostOnTap]）；没换返回 null
     */
    fun adoptContext(items: List<PlaybackRequest>, index: Int): Int? {
        val current = entries.getOrNull(queueIndex) ?: return null
        if (items.getOrNull(index)?.identityKey() != current.request.identityKey()) return null
        val plan = resolveQueueOnAdopt(
            pinned = entries.map { it.pinned },
            keys = entries.map { it.request.identityKey() },
            current = queueIndex,
            beforeCount = index,
        ) ?: return null
        val run = entries.subList(plan.runStart, plan.runEnd).toList()
        val block = items.subList(0, index).map { QueueEntry(it, pinned = false, expandParts = true) } +
            run +
            items.subList(index + 1, items.size).map { QueueEntry(it, pinned = false, expandParts = true) }
        val lost = countLostOnTap(
            removed = entries.filterIndexed { i, e -> !e.pinned && i !in plan.runStart until plan.runEnd }
                .map { it.request.identityKey() },
            incoming = block.map { it.request.identityKey() },
        )
        val offerUndo = shouldOfferUndo(lost)
        undoSnapshot = if (offerUndo) snapshot() else null
        val kept = plan.kept.map { entries[it] }
        entries = kept.subList(0, plan.insertAt) + block + kept.subList(plan.insertAt, kept.size)
        queueIndex = plan.newIndex
        queueSource = null
        pushState()
        if (offerUndo) onQueueReplaced?.invoke(lost)
        return lost
    }
```

- [ ] **Step 6: 跑 `:core:player` 全部单测**

Run: `./gradlew :core:player:testDebugUnitTest`
Expected: 全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add core/player
git commit -m "feat(player): 队列可把一串内容换成临时块而不重新起播（adoptContext）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 合集弹层里点正在播的那一集 → 放进播放列表（`:app`）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/collection/CollectionPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/collection/CollectionController.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/CollectionSheet.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`
- Test: `app/src/test/java/com/biliseek/app/collection/CollectionPolicyTest.kt`

**Interfaces:**
- Consumes: `PlayerConnection.adoptContext(items, index): Int?`（Task 1）。
- Produces:
  - `CollectionPolicy.adoptMessage(title: String, lost: Int): String?`
  - `CollectionController(… adoptList: (items: List<PlaybackRequest>, index: Int) -> Int?, …)`、`CollectionController.queueEpisodesAround(collection: VideoCollection, index: Int)`
  - `CollectionSheetHandlers.onQueueCollection: (VideoCollection, Int) -> Unit`；`CollectionSheet(…, onQueueCollection: (Int) -> Unit, …)`

- [ ] **Step 1: 写失败的测试**——`CollectionPolicyTest.kt` 类里加：

```kotlin
    @Test
    fun `放进列表的提示 替换掉两项以上时由撤销提示代替`() {
        assertEquals("已把「合集甲」放进播放列表", CollectionPolicy.adoptMessage("合集甲", lost = 0))
        assertEquals("已把「合集甲」放进播放列表", CollectionPolicy.adoptMessage("合集甲", lost = 1))
        assertNull(CollectionPolicy.adoptMessage("合集甲", lost = 2))
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.CollectionPolicyTest"`
Expected: 编译失败，`Unresolved reference: adoptMessage`。

- [ ] **Step 2: `CollectionPolicy` 加函数**（放在 `shouldPlayEpisode` 后面）：

```kotlin
    /**
     * 「点正在播的那一集」把合集放进列表之后的提示。替换掉 2 项以上时播放器自己会弹
     * 「已替换队列中的 N 项 · 撤销」（与 `:core:player` 的 shouldOfferUndo 同一门槛），这里不再重复。
     */
    fun adoptMessage(title: String, lost: Int): String? =
        if (lost >= 2) null else "已把「$title」放进播放列表"
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.CollectionPolicyTest"`
Expected: 全部 PASS。

- [ ] **Step 3: `CollectionController`**

3a. 构造参数 `private val playList: ListPlayer,` 后面加：

```kotlin
    /** 把一串内容换成列表里的临时块、不重新起播（宿主转给 PlayerConnection.adoptContext）；没换返回 null。 */
    private val adoptList: (items: List<PlaybackRequest>, index: Int) -> Int?,
```

3b. `playEpisodes` 方法后面加：

```kotlin
    /**
     * 合集弹层里点正在播的那一集：整个合集换成列表里的临时块，当前这集不重新起播（docs/10）。
     * 替换掉 2 项以上时播放器自己弹「已替换… · 撤销」，否则这里提示已放进列表。
     */
    fun queueEpisodesAround(collection: VideoCollection, index: Int) {
        if (index !in collection.episodes.indices) return
        val lost = adoptList(collection.episodes.map { it.toRequest() }, index) ?: return
        CollectionPolicy.adoptMessage(collection.title, lost)?.let(notify)
    }
```

- [ ] **Step 4: `AppViewModel.kt`**——`val collections: CollectionController = CollectionController(` 里 `playList = { … },` 后面加：

```kotlin
        adoptList = { items, index -> connection.adoptContext(items, index) },
```

- [ ] **Step 5: `CollectionSheet.kt`**

5a. `CollectionSheetHandlers` 加一项：

```kotlin
data class CollectionSheetHandlers(
    val onPlayEpisode: (VideoCollection, Int) -> Unit = { _, _ -> },
    val onSetSubscribed: (VideoCollection, Boolean) -> Unit = { _, _ -> },
    /** 点正在播的那一集：不重新播，把整个合集放进播放列表。 */
    val onQueueCollection: (VideoCollection, Int) -> Unit = { _, _ -> },
)
```

5b. `CollectionSheet` 参数 `onSetSubscribed: (Boolean) -> Unit,` 后面加 `onQueueCollection: (Int) -> Unit,`；KDoc 第二句改成「点某一集：是正在播的就不重新播、把整个合集放进播放列表，否则从这一集起播整个合集；两种都关弹层。点「已订阅」先确认。」；集数行的 `onClick` 改成：

```kotlin
                        onClick = {
                            if (CollectionPolicy.shouldPlayEpisode(collection, row.index, playingBvid)) {
                                onPlayEpisode(row.index)
                            } else {
                                onQueueCollection(row.index)
                            }
                            onDismiss()
                        },
```

- [ ] **Step 6: `PlayerScreen.kt`**——`CollectionSheet(` 调用里 `onSetSubscribed = …,` 后面加：

```kotlin
                onQueueCollection = { index -> collectionHandlers.onQueueCollection(collection, index) },
```

- [ ] **Step 7: `MainActivity.kt`**——`com.biliseek.app.ui.CollectionSheetHandlers(` 里 `onSetSubscribed = { … },` 后面加：

```kotlin
                            onQueueCollection = viewModel.collections::queueEpisodesAround,
```

- [ ] **Step 8: 编译并跑 `:app` 单测**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add app/src
git commit -m "feat(collection): 合集弹层里点正在播的那一集，把整个合集放进播放列表

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 三点菜单加回倍速 / 定时

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/PlayerSheets.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt`

**Interfaces:**
- Produces: `PlayerMoreMenuSheet(… onOpenSpeed: () -> Unit = {}, onOpenTimer: () -> Unit = {})`；`MenuItemRow(icon, label, value: String? = null, onClick)`。

- [ ] **Step 1: `MenuItemRow` 加右侧值**——整个函数换成：

```kotlin
@Composable
private fun MenuItemRow(
    icon: ImageVector,
    label: String,
    /** 右侧的当前值（主色）；null 不显示。 */
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
```

- [ ] **Step 2: `PlayerMoreMenuSheet` 加两项**

2a. 参数 `onSetDefaultAudioQuality: … = {},` 后面加：

```kotlin
    /** 打开倍速弹层（2026-10-27 起倍速入口回到菜单）。 */
    onOpenSpeed: () -> Unit = {},
    /** 打开定时弹层。 */
    onOpenTimer: () -> Unit = {},
```

2b. 在 `HorizontalDivider(Modifier.padding(vertical = 8.dp))` 之后、`if (state.videoQualities.isNotEmpty())` 之前加：

```kotlin
            MenuItemRow(
                Icons.Filled.Speed,
                "倍速",
                value = speedButtonLabel(state.speed).takeIf { !isSameSpeed(state.speed, 1.0f) },
            ) { closeThen(onOpenSpeed) }
            MenuItemRow(
                Icons.Filled.Timer,
                "定时",
                value = sleepTimerChipLabel(state.sleepTimerRemainingMs, state.sleepTimerEndOfTrack)
                    .takeIf { state.sleepTimerActive },
            ) { closeThen(onOpenTimer) }
```

（文件里若没有 `import androidx.compose.material.icons.filled.Speed` / `...filled.Timer`，加上；`speedButtonLabel`、`isSameSpeed`、`sleepTimerChipLabel` 是同包顶层函数。）

2c. KDoc 调研要点里「倍速、定时只从播放页上的胶囊进，菜单里不重复」改成「倍速、定时在菜单里（2026-10-27 起），设了值右侧写当前值；页面上只在设了值时于进度条上方出小标签」；顶部说明「列表项（画质 / 音质 / 复制链接）」改成「列表项（倍速 / 定时 / 画质 / 音质 / 复制链接）」。

- [ ] **Step 3: `PlayerScreen.kt`**——`PlayerMoreMenuSheet(` 调用里 `onSetDefaultAudioQuality = onSetDefaultAudioQuality,` 后面加：

```kotlin
                onOpenSpeed = { showSpeedSheet = true },
                onOpenTimer = { showTimerSheet = true },
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src
git commit -m "feat(player): 三点菜单加回倍速 / 定时，右侧显示当前值

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 播放页新布局（顶栏听看、控制块沉底、状态标签、简介预览）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/PlayerSwitchRow.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/VideoDescriptionPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/VideoDescription.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt`
- Test: `app/src/test/java/com/biliseek/app/ui/VideoDescriptionPolicyTest.kt`
- Test: `app/src/androidTest/java/com/biliseek/app/PlayerUiTest.kt`

**Interfaces:**
- Consumes: Task 3 的菜单入口（页面上的胶囊去掉后，倍速 / 定时只能从菜单或状态标签进）。
- Produces: `internal fun ListenWatchSwitch(isAudioMode: Boolean, onSwitchMode: () -> Unit, modifier: Modifier = Modifier)`；`internal fun PlayerStatusChips(state: PlaybackState, onOpenSpeed: () -> Unit, onOpenTimer: () -> Unit, modifier: Modifier = Modifier)`；`VideoDescriptionPolicy.previewLines(availableHeightPx: Int, lineHeightPx: Int): Int`；`internal fun VideoDescriptionPreview(desc: String, onOpen: () -> Unit, modifier: Modifier = Modifier)`。删除 `PlayerSwitchRow`。

- [ ] **Step 1: 写失败的测试**——`VideoDescriptionPolicyTest.kt` 类里加：

```kotlin
    @Test
    fun `简介预览行数 可用高度除以行高向下取整 不小于0`() {
        assertEquals(5, VideoDescriptionPolicy.previewLines(availableHeightPx = 340, lineHeightPx = 64))
        assertEquals(1, VideoDescriptionPolicy.previewLines(availableHeightPx = 64, lineHeightPx = 64))
        assertEquals(0, VideoDescriptionPolicy.previewLines(availableHeightPx = 63, lineHeightPx = 64))
        assertEquals(0, VideoDescriptionPolicy.previewLines(availableHeightPx = 0, lineHeightPx = 64))
        assertEquals(0, VideoDescriptionPolicy.previewLines(availableHeightPx = 300, lineHeightPx = 0))
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.ui.VideoDescriptionPolicyTest"`
Expected: 编译失败，`Unresolved reference: previewLines`。

- [ ] **Step 2: `VideoDescriptionPolicy` 加函数**（放在 `statsLine` 后面）：

```kotlin
    /** 播放页简介预览能放几行：可用高度 ÷ 行高，向下取整、不小于 0；行高不正时 0 行。 */
    fun previewLines(availableHeightPx: Int, lineHeightPx: Int): Int =
        if (lineHeightPx <= 0) 0 else (availableHeightPx / lineHeightPx).coerceAtLeast(0)
```

Run: 同上。Expected: PASS。

- [ ] **Step 3: `VideoDescription.kt` 加预览**——放在 `VideoDescriptionRow` 函数后面（缺的 import 补上：`androidx.compose.foundation.layout.BoxWithConstraints`、`androidx.compose.ui.platform.LocalDensity`，以及文件里还没有的 `clickable` / `Role` / `fillMaxWidth` / `TextOverflow`）：

```kotlin
/**
 * 简介正文预览：放在播放页中段最下面，占满剩下的高度——能放几行放几行，最后一行末尾省略号；一行都放不下就不画。
 * 纯文字（时间点、链接不可点），点一下打开全文弹层（同「简介 展开」）。
 * 设计稿 docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md（方案 P2）。
 */
@Composable
internal fun VideoDescriptionPreview(
    desc: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodySmall
    val lineHeightPx = with(LocalDensity.current) { style.lineHeight.roundToPx() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val lines = VideoDescriptionPolicy.previewLines(constraints.maxHeight, lineHeightPx)
        if (lines > 0) {
            Text(
                desc.trim(),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen),
            )
        }
    }
}
```

同时把 `VideoDescriptionRow` 的 KDoc 第一句「简介正文不在页面上占地方，点「简介 展开」在底部弹层里看」改成「简介正文的前几行由 [VideoDescriptionPreview] 显示在下面，全文点「简介 展开」在底部弹层里看」。

- [ ] **Step 4: `PlayerSwitchRow.kt`**

4a. 删除整个 `PlayerSwitchRow` 函数（连同它的 KDoc）。

4b. `ListenWatchSwitch` 改成顶栏用的紧凑版、`internal`：

```kotlin
/**
 * 听 | 看开关：分段胶囊，选中的一段填主色。放在播放页顶栏正中（2026-10-27 起，取代「正在播放」几个字）。
 *
 * 一眼看得出现在是听还是看；点已选中的一段不做事。顶栏用紧凑尺寸：高 32dp。
 */
@Composable
internal fun ListenWatchSwitch(isAudioMode: Boolean, onSwitchMode: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    fun switchTo(audio: Boolean) {
        if (audio == isAudioMode) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onSwitchMode()
    }

    Row(
        modifier
            .height(32.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SwitchSegment("听", Icons.Filled.Headphones, selected = isAudioMode) { switchTo(audio = true) }
        SwitchSegment("看", WatchTvIcon, selected = !isAudioMode) { switchTo(audio = false) }
    }
}
```

4c. `SwitchSegment` 里的尺寸改成紧凑版：`.widthIn(min = 52.dp)`、`.padding(horizontal = 8.dp)`、图标 `Modifier.size(16.dp)`、`Spacer(Modifier.width(4.dp))`、文字 `style = MaterialTheme.typography.labelLarge`（其余不变）。

4d. 文件末尾加：

```kotlin
/**
 * 进度条上方的倍速 / 定时小标签：只有调过倍速、设了定时才出现（都没设时什么都不画），点了打开对应的弹层。
 * 入口本身在三点菜单里（2026-10-27 起，设计稿 docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md）。
 */
@Composable
internal fun PlayerStatusChips(
    state: PlaybackState,
    onOpenSpeed: () -> Unit,
    onOpenTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedActive = !isSameSpeed(state.speed, 1.0f)
    val timerActive = state.sleepTimerActive
    if (!speedActive && !timerActive) return
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speedActive) {
            val label = speedButtonLabel(state.speed)
            PlayerChip(
                icon = Icons.Filled.Speed,
                label = label,
                active = true,
                onClick = onOpenSpeed,
                description = "倍速 $label",
            )
        }
        if (timerActive) {
            val label = sleepTimerChipLabel(state.sleepTimerRemainingMs, state.sleepTimerEndOfTrack)
            PlayerChip(
                icon = Icons.Filled.Timer,
                label = label,
                active = true,
                onClick = onOpenTimer,
                description = "定时关闭 $label",
            )
        }
    }
}
```

（删掉 `PlayerSwitchRow` 后若有 import 变成未使用就删掉，比如 `Box`；`PlayerChip` 保持 `private`。）

- [ ] **Step 5: `PlayerScreen.kt`**

5a. `PlayerTopBar` 整个函数换成：

```kotlin
/**
 * 播放页顶部栏：左收起，正中听 | 看开关（2026-10-27 起取代「正在播放」），右边（看模式）小窗 + 三点菜单。
 */
@Composable
private fun PlayerTopBar(
    isAudioMode: Boolean,
    onSwitchMode: () -> Unit,
    onCollapse: () -> Unit,
    onOpenMenu: () -> Unit,
    onEnterPip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
        }
        ListenWatchSwitch(isAudioMode, onSwitchMode)
        Row(Modifier.align(Alignment.CenterEnd)) {
            if (onEnterPip != null) {
                IconButton(onClick = onEnterPip) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "小窗播放")
                }
            }
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
            }
        }
    }
}
```

5b. 在 `PlayerScreen` 里 `val activeColor = …` 后面加一个切换动作（顶栏要用），并把 `PlayerTopBar(` 调用换成：

```kotlin
    val switchMode = {
        if (isAudio) {
            onPresentationChange(PresentationMode.INLINE, surfaceView)
        } else {
            onPresentationChange(PresentationMode.NONE, null)
        }
    }
```

```kotlin
            PlayerTopBar(
                isAudioMode = isAudio,
                onSwitchMode = switchMode,
                onCollapse = onCollapse,
                onOpenMenu = { showMenu = true },
                // 小窗只对看模式有意义：纯听本来就能在后台放
                onEnterPip = if (isAudio) null else onEnterPip,
            )
```

5c. 中段整块（从 `// 换视频时滚动位置回到顶部` 到 `key(state.bvid) { … }` 结束）换成：

```kotlin
            // 中段占满封面与底部控制块之间的空间；顺序：标题 → 互动栏 → 合集卡片 → 简介行 → 简介预览（填满剩下的高度）。
            // 不再整体滚动：多出来的高度都给简介预览，屏幕特别矮时预览先缩到 0 行。换视频时整块重建。
            key(state.bvid) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        state.title.ifBlank { "BiliSeek" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    VideoActionBar(
                        state = boundInteraction,
                        handlers = interactionHandlers,
                        onOpenFavorite = onOpenAddToList,
                        onOpenComments = onOpenComments,
                        onShare = onShare,
                        activeColor = activeColor,
                        onSummary = onSummary,
                        hasSummary = hasSummary,
                    )

                    boundInteraction?.collection?.let { collection ->
                        CollectionEntryRow(
                            collection = collection,
                            onOpen = { showCollectionSheet = true },
                        )
                    }

                    boundInteraction?.let { info ->
                        VideoDescriptionRow(
                            desc = info.desc,
                            viewCount = info.viewCount,
                            pubDate = info.pubDate,
                            linkColor = activeColor,
                            onOpen = { showDescriptionSheet = true },
                        )
                        if (VideoDescriptionPolicy.shouldShow(info.desc)) {
                            VideoDescriptionPreview(
                                desc = info.desc,
                                onOpen = { showDescriptionSheet = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
```

5d. `ProgressBar(state, onSeek)` 前面加状态标签；删掉整个 `PlayerSwitchRow( … )` 调用：

```kotlin
            PlayerStatusChips(
                state = state,
                onOpenSpeed = { showSpeedSheet = true },
                onOpenTimer = { showTimerSheet = true },
            )

            ProgressBar(state, onSeek)
```

5e. 外层大 `Column` 上方那段注释（「顶部栏 → CoverArea → 标题/互动栏/简介行 → 进度条/控制行/听看开关…」到「…封面与控制行始终在。」）换成：

```kotlin
        // 顶部栏（收起 · 听 | 看 · 小窗 / 菜单）→ CoverArea → 中段（占满剩余高度）→ 倍速 / 定时标签（设了值才有）→ 进度条 → 控制行，
        // 统一放进同一条纵向流里——音频/视频模式布局完全一致，只有 CoverArea 内容不同。
        // 不再各自作为 Box 的平级子项——否则它们会叠在一起画（曾经的重叠 bug）。
        // 进度条与控制行沉在页面底部；倍速 / 定时入口在三点菜单（设计稿 2026-10-27-player-layout-and-collection-queue-design.md）。
```

5f. 删掉因此不再使用的 import（如 `androidx.compose.foundation.rememberScrollState`、`androidx.compose.foundation.verticalScroll`）。

- [ ] **Step 6: `PlayerUiTest.kt` 改用例**

6a. `播放页_听模式显示顶部栏与听看开关_停在听` 里 `compose.onNodeWithText("正在播放").assertIsDisplayed()` 换成两行：

```kotlin
        compose.onNodeWithContentDescription("收起").assertIsDisplayed()
        compose.onNodeWithText("正在播放").assertDoesNotExist()
```

6b. 「播放页：倍速与定时胶囊」那一节的五个用例整体换成：

```kotlin
    // ── 播放页：倍速与定时（入口在三点菜单，设了值进度条上方出标签）──

    @Test
    fun 播放页_正常速度且没设定时_页面上没有倍速定时标签() {
        setPlayerScreen(playingState)

        compose.onNodeWithText("倍速").assertDoesNotExist()
        compose.onNodeWithText("定时").assertDoesNotExist()
    }

    @Test
    fun 播放页_三点菜单里点倍速是倍速档位() {
        setPlayerScreen(playingState)

        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("倍速").assertIsDisplayed().performClick()
        compose.onNodeWithText("倍速播放").assertIsDisplayed()
    }

    @Test
    fun 播放页_三点菜单里点定时是定时选项() {
        setPlayerScreen(playingState)

        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("定时").assertIsDisplayed().performClick()
        compose.onNodeWithText("定时关闭").assertIsDisplayed()
        compose.onNodeWithText("播完当前").assertIsDisplayed()
    }

    @Test
    fun 播放页_调过倍速后进度条上方出现当前档位标签() {
        setPlayerScreen(playingState.copy(speed = 1.5f))

        compose.onNodeWithText("1.5×").assertIsDisplayed()
    }

    @Test
    fun 播放页_设了定时后出现剩余时间标签() {
        setPlayerScreen(playingState.copy(sleepTimerRemainingMs = 1_720_000))

        compose.onNodeWithText("28:40").assertIsDisplayed()
    }

    @Test
    fun 播放页_选了播完当前出现播完当前标签() {
        setPlayerScreen(playingState.copy(sleepTimerEndOfTrack = true))

        compose.onNodeWithText("播完当前").assertIsDisplayed()
    }
```

- [ ] **Step 7: 编译（含 androidTest）并跑 `:app` 单测**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全部 PASS。

Run: `grep -rn "PlayerSwitchRow(" app/src`
Expected: 无输出。

- [ ] **Step 8: 提交**

```bash
git add app/src
git commit -m "feat(player): 播放页新布局：顶栏听看、控制块沉底、倍速定时标签、简介预览填满中段

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 文档与全量验证

**Files:**
- Modify: `docs/05-功能优化篇.md`、`docs/10-播放队列工单.md`、`docs/BiliSeek-手动测试指南.md`、`CLAUDE.md`

- [ ] **Step 1: `CLAUDE.md`**——「Tapping play never wipes what the user queued」那一条末尾的 ``the only public additions are `PlaybackState.queuePinned` and `PlayerConnection.playFromContext / removeFromQueue / undoQueueChange`.`` 改成 ``the only public additions are `PlaybackState.queuePinned` and `PlayerConnection.playFromContext / adoptContext / removeFromQueue / undoQueueChange` (`adoptContext` swaps in a list as the temporary block around the item already playing, without restarting it — the collection sheet's "tap the current episode").``

- [ ] **Step 2: `docs/10-播放队列工单.md`**——在「固定标志」那一条（`PlaybackState.queuePinned` 与 `queue` 一一对应…）后面加一条：

```markdown
- **换进列表、不重新起播**：`PlayerConnection.adoptContext(items, index)`（合集弹层里点正在播的那一集）。`items[index]` 必须是当前在播的视频，否则不做事。临时条目整块换成 `items`，当前视频那一段（含已展开的分 P）原样落在第 `index` 项的位置、继续播；不在这一段里的固定条目原样保留；当前视频本身带图钉时保留图钉。替换掉 ≥ 2 项时同点击播放一样给撤销。规则在 `resolveQueueOnAdopt`。
```

- [ ] **Step 3: `docs/05-功能优化篇.md`**——文件末尾加：

```markdown
### 13.85 合集第二轮：播放页新布局、点当前集把合集放进列表（2026-10-27）

- **起因**（用户真机反馈）：播放页加了合集卡片后中段放不下、要滚动；合集弹层里点正在播的那一集，合集不进播放列表。设计稿 `docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md`，效果图用户选了顶栏 B + 标签 Y + 简介 P2。
- **播放页**：顶栏正中改成听 | 看开关（紧凑版，取代「正在播放」）；进度条 + 控制行沉到页面底部；原控制行下面的「倍速 · 听看 · 定时」一排去掉。倍速、定时回到三点菜单（列表项最前面，设了值右侧写当前值）；页面上只在设了值时，进度条上方出现 `1.5×` / `28:40` / `播完当前` 小标签，点了打开对应弹层。中段顺序改为 标题 → 互动栏 → 合集卡片 → 简介行 → 简介预览：预览占满剩下的高度，能放几行放几行（`VideoDescriptionPolicy.previewLines`），纯文字，点了打开全文弹层；中段不再整体滚动。
- **点当前集**：合集弹层里点正在播的那一集，不重新起播，把整个合集换成列表里的临时块、当前视频落在它的位置（已展开的分 P 原样保留，固定条目不动）。新增公开方法 `PlayerConnection.adoptContext`，规则 `resolveQueueOnAdopt`（docs/10）。替换掉 ≥ 2 项时弹「已替换… · 撤销」，否则提示「已把「合集名」放进播放列表」。
- **验证**：`QueuePolicyTest`、`CollectionPolicyTest`、`VideoDescriptionPolicyTest` 离线单测；`PlayerUiTest` 顶栏与倍速 / 定时用例只编译、未在设备上跑。**真机待确认**：各种标题长度、有无合集、有无简介时中段是否放得下；看模式顶栏是否挤；点当前集后队列面板内容与撤销。
```

- [ ] **Step 4: `docs/BiliSeek-手动测试指南.md`**——按新行为改这些行（原文按内容找）：
  - 「点迷你播放条 | 打开播放页，顶栏中间是「正在播放」」→「点迷你播放条 | 打开播放页，顶栏中间是「听 \| 看」开关」。
  - 步骤 6 第一句「点播放页「听 | 看」开关左边的「倍速」胶囊，…」→「点播放页右上角三点菜单里的「倍速」，…」；选中态那一行改成「弹层里当前倍速的档位高亮，只高亮一个；菜单里「倍速」右侧写当前档位，页面进度条上方出现「1.5×」标签，回 `1×` 标签消失」。
  - 步骤 7 第一句「点播放页「听 | 看」开关右边的「定时」胶囊，…」→「点播放页右上角三点菜单里的「定时」，…」；「点「取消」 | 剩余时间消失，胶囊回到灰色「定时」」→「点「取消」 | 进度条上方的定时标签消失，菜单里「定时」右侧不再写时间」。
  - 「点播放页下方「听 | 看」开关里的「**看**」」→「点播放页顶栏「听 | 看」开关里的「**看**」」。
  - 步骤 33 标题改成「步骤 33 — 播放页：顶栏听 | 看开关、控制行、倍速与定时、简介（0.4.1 新增，2026-10-27 改版，规则见 `docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md`）」；表里「控制行下面一排」那一行改成「顶栏 | 左收起、正中「听 \| 看」开关、右边（看模式）小窗 + 三点；没有「正在播放」几个字」；「倍速胶囊」「定时胶囊」两行改成「倍速 / 定时 | 都在三点菜单里（列表项最前面），设了值右侧写当前值；页面上只在设了值时于进度条上方出现小标签（`1.5×` / 剩余时间 / `播完当前`），点标签打开对应弹层；都没设时没有这一行」；「右上角三点菜单」那一行里「下方「画质 / 音质 / 复制链接」…**没有倍速、定时关闭**」改成「下方「倍速 / 定时 / 画质 / 音质 / 复制链接」（没有可选画质时没有「画质」）」；再加两行「控制块位置 | 进度条和播放控制行沉在页面底部，不随中段内容上下跳」「简介预览 | 中段最下面显示简介前几行（空间有多少显示多少，末尾省略号）；点预览或「展开」弹出全文；简介为空时没有预览」。
  - 附录速查表里「6 | **切倍速** | 「倍速」胶囊 → …」和「7 | **睡眠定时** | 「定时」胶囊 → …」里的「胶囊」改成「三点菜单」，「胶囊写当前档位」改成「进度条上方标签写当前档位」，「胶囊显示剩余」改成「标签显示剩余」。
  - 步骤 33.2 表里「弹层里点正在播的那集 | 弹层关掉，**不重新播**」改成「弹层里点正在播的那集 | 弹层关掉，**不重新播**；打开队列：整个合集都在列表里，当前这集在它的位置上继续播，带图钉的还在；之前列表里有别的（≥2 项）被换掉时弹「已替换… · 撤销」，点撤销恢复，播放不断；否则提示「已把「合集名」放进播放列表」」。

- [ ] **Step 5: 全量验证**

Run: `./gradlew test :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL，全部单测 PASS。

- [ ] **Step 6: 提交**

```bash
git add docs CLAUDE.md
git commit -m "docs: 合集第二轮记录、队列规则、手动测试与公开接口

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

（装机由主控在用户准备好之后做：`./gradlew installDebug`，覆盖安装通常几秒返回。）
