# 合集第三轮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 合集弹层与内容页标出已听完 / 听到哪里并有「继续听」横条；内容页可切正序 / 倒序、可长按多选批量操作；返回后列表位置保留；弹层小节标题吸顶；听看开关点击区加大；DTO 样本换真实；文件改名与确认框共用。

**Architecture:** 纯函数 `EpisodeProgressPolicy`（`app/collection/`）根据本地 `play_history` 算每集状态与「继续听」目标；`CollectionController` 提供 `historyOf(bvids)` 流、每个内容页槽的排序状态（放进 `TrackListId.Collection` 的 `reversed`），订阅的合集改走 `spaceSeasonArchives`。`SeriesScreen` 加状态行、横条、排序图标与多选（`CollectionSelectionUi`）；`CollectionSheet` 加状态行、横条、吸顶小节标题。设计稿：`docs/superpowers/specs/2026-10-27-collections-round3-design.md`。

**Tech Stack:** Kotlin、Room、Jetpack Compose（Material3、Foundation `stickyHeader`）、JUnit4。

## Global Constraints

- **自动化代码绝不请求 B 站服务器**；测试只用本地数据。本轮新增的只有读本地库，B 站请求只多了「排序」参数。
- 读接口仍走 `wbiRequest`（签名 + FEED 限速 + 冷却）；不加重试。
- 不持久化远程数据（合集内容只在内存；播放记录本来就在本地）。
- `:core:network` 公开类型只改：`SeriesArchivesPage` 加 `total: Int = 0`；`spaceSeasonArchives` / `seriesArchives` 加参数 `reverse: Boolean = false`。
- 叫法：「已听完」「听到」「继续听」「从头开始」「正序」「倒序」；提示走 `notify` / `emitMessage`。
- 所有提交在分支 `feat/collections`，提交信息结尾带：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- 命令只用普通 `./gradlew …`（不套 `timeout`、不把内联脚本管道给解释器）；任何工具调用被拒，立刻报 BLOCKED 并附命令。不装机、不跑设备测试。

## 常用命令

```bash
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.*"
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionDtoTest"
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew test
```

---

### Task 0: 提交设计稿与计划

- [ ] **Step 1:** `git add docs/superpowers/specs/2026-10-27-collections-round3-design.md docs/superpowers/plans/2026-10-27-collections-round3.md && git commit -m "docs: 合集第三轮设计稿与计划"`（结尾带 Co-Authored-By）。

---

### Task 1: 网络层——排序参数与总集数（`:core:network`）

**Files:** Modify `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`

**Interfaces — Produces:**
- `data class SeriesArchivesPage(val items: List<SeriesArchive>, val page: Int, val hasMore: Boolean, val total: Int = 0)`
- `suspend fun spaceSeasonArchives(mid: Long, seasonId: Long, page: Int, pageSize: Int = 30, reverse: Boolean = false): Result<SeriesArchivesPage>`
- `suspend fun seriesArchives(mid: Long, seriesId: Long, page: Int, pageSize: Int = 30, reverse: Boolean = false): Result<SeriesArchivesPage>`

- [ ] **Step 1:** `SeriesArchivesPage` 加最后一个字段：

```kotlin
/** 合集内容的一页（M13-N）。[total] 为接口给的总条数（未知为 0）。 */
data class SeriesArchivesPage(
    val items: List<SeriesArchive>,
    val page: Int,
    val hasMore: Boolean,
    val total: Int = 0,
)
```

- [ ] **Step 2:** `spaceSeasonArchives` 加参数 `reverse: Boolean = false`（放在 `pageSize` 后面），`"sort_reverse" to "false"` 改成 `"sort_reverse" to reverse.toString()`，返回的 `SeriesArchivesPage(…)` 加 `total = d.page.total`。KDoc 首句改为「UP 空间合集的内容，[reverse] 为假时按 UP 主排的顺序，为真时倒过来。」。

- [ ] **Step 3:** `seriesArchives` 加参数 `reverse: Boolean = false`，`"sort" to "asc"` 改成 `"sort" to if (reverse) "desc" else "asc"`，返回加 `total = d.page.total`。KDoc 首句改为「旧版系列的内容，默认按发布时间从旧到新（往后播 = 往更新的播），[reverse] 为真时从新到旧。」。

- [ ] **Step 4:** `./gradlew :core:network:testDebugUnitTest :app:compileDebugKotlin` → BUILD SUCCESSFUL。

- [ ] **Step 5:** 提交 `feat(network): 合集 / 系列内容支持倒序，并带回总条数`。

---

### Task 2: 听到第几集的纯函数与本地查询

**Files:**
- Create: `app/src/main/java/com/biliseek/app/collection/EpisodeProgressPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/data/Daos.kt`（`HistoryDao`）、`app/src/main/java/com/biliseek/app/data/LibraryRepository.kt`
- Test: `app/src/test/java/com/biliseek/app/collection/EpisodeProgressPolicyTest.kt`（新建）

**Interfaces — Produces:**
- `data class EpisodeHistory(val positionMs: Long, val durationMs: Long, val playedAt: Long)`
- `sealed interface EpisodeProgress { data object None; data class Partial(val positionMs: Long, val durationMs: Long); data object Finished }`
- `object EpisodeProgressPolicy`：`latestByBvid(rows: List<PlayHistoryEntity>): Map<String, EpisodeHistory>`、`progressOf(h: EpisodeHistory?): EpisodeProgress`、`statusLine(p: EpisodeProgress, durationSec: Long): String?`、`resumeIndex(bvids: List<String>, history: Map<String, EpisodeHistory>): Int?`、`episodeNumber(index: Int, total: Int, reversed: Boolean): Int?`、`resumeTitle(number: Int?, title: String): String`、`resumeDetail(p: EpisodeProgress, durationSec: Long): String`
- `HistoryDao.observeByBvids(bvids: List<String>): Flow<List<PlayHistoryEntity>>`；`LibraryRepository.observeHistory(bvids: List<String>): Flow<List<PlayHistoryEntity>>`

- [ ] **Step 1: 写失败的测试**——新建 `EpisodeProgressPolicyTest.kt`：

```kotlin
package com.biliseek.app.collection

import com.biliseek.app.data.PlayHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeProgressPolicyTest {

    private fun h(positionMs: Long, durationMs: Long, playedAt: Long = 0) = EpisodeHistory(positionMs, durationMs, playedAt)

    private fun row(bvid: String, cid: Long, positionMs: Long, durationSec: Long, playedAt: Long) = PlayHistoryEntity(
        bvid = bvid, cid = cid, title = "", author = "", coverUrl = "",
        durationSec = durationSec, positionMs = positionMs, playedAt = playedAt,
    )

    @Test
    fun `三档判定与边界 同续播口径`() {
        assertEquals(EpisodeProgress.None, EpisodeProgressPolicy.progressOf(null))
        assertEquals(EpisodeProgress.None, EpisodeProgressPolicy.progressOf(h(5_000, 600_000)))
        assertEquals(EpisodeProgress.Partial(5_001, 600_000), EpisodeProgressPolicy.progressOf(h(5_001, 600_000)))
        assertEquals(EpisodeProgress.Partial(584_999, 600_000), EpisodeProgressPolicy.progressOf(h(584_999, 600_000)))
        assertEquals(EpisodeProgress.Finished, EpisodeProgressPolicy.progressOf(h(585_000, 600_000)))
        // 时长未知：只看是不是听过 5 秒
        assertEquals(EpisodeProgress.Partial(900_000, 0), EpisodeProgressPolicy.progressOf(h(900_000, 0)))
    }

    @Test
    fun `同一视频多条记录取最近一条`() {
        val map = EpisodeProgressPolicy.latestByBvid(
            listOf(
                row("BV1", cid = 1, positionMs = 100_000, durationSec = 600, playedAt = 10),
                row("BV1", cid = 2, positionMs = 30_000, durationSec = 300, playedAt = 20),
                row("BV2", cid = 3, positionMs = 1_000, durationSec = 60, playedAt = 5),
            ),
        )
        assertEquals(EpisodeHistory(30_000, 300_000, 20), map["BV1"])
        assertEquals(EpisodeHistory(1_000, 60_000, 5), map["BV2"])
    }

    @Test
    fun `状态文字`() {
        assertEquals("✓ 已听完 · 39:48", EpisodeProgressPolicy.statusLine(EpisodeProgress.Finished, 2388))
        assertEquals("听到 12:30 / 41:37", EpisodeProgressPolicy.statusLine(EpisodeProgress.Partial(750_000, 2_497_000), 2497))
        // 记录里没时长时用列表给的时长
        assertEquals("听到 12:30 / 41:37", EpisodeProgressPolicy.statusLine(EpisodeProgress.Partial(750_000, 0), 2497))
        assertNull(EpisodeProgressPolicy.statusLine(EpisodeProgress.None, 2497))
    }

    @Test
    fun `继续听 取最近听过的那一集 已听完就接下一集`() {
        val bvids = listOf("BV1", "BV2", "BV3")
        // 最近听的是 BV2、听到一半
        assertEquals(1, EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV1" to h(600_000, 600_000, 1), "BV2" to h(60_000, 600_000, 2))))
        // 最近听的是 BV2、已听完 → BV3
        assertEquals(2, EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV2" to h(600_000, 600_000, 2))))
        // 最后一集已听完 → 不显示
        assertNull(EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV3" to h(600_000, 600_000, 3))))
        // 没有记录 → 不显示
        assertNull(EpisodeProgressPolicy.resumeIndex(bvids, emptyMap()))
        // 不在这一页里的记录不算
        assertNull(EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV9" to h(60_000, 600_000, 9))))
    }

    @Test
    fun `第几集 正序加一 倒序用总数减 总数未知不写`() {
        assertEquals(5, EpisodeProgressPolicy.episodeNumber(index = 4, total = 36, reversed = false))
        assertEquals(32, EpisodeProgressPolicy.episodeNumber(index = 4, total = 36, reversed = true))
        assertNull(EpisodeProgressPolicy.episodeNumber(index = 4, total = 0, reversed = true))
    }

    @Test
    fun `横条文字`() {
        assertEquals("继续听 · 第 5 集 民国 05", EpisodeProgressPolicy.resumeTitle(5, "民国 05"))
        assertEquals("继续听 · 民国 05", EpisodeProgressPolicy.resumeTitle(null, "民国 05"))
        assertEquals("听到 12:30 / 41:37", EpisodeProgressPolicy.resumeDetail(EpisodeProgress.Partial(750_000, 2_497_000), 2497))
        assertEquals("从头开始 · 41:37", EpisodeProgressPolicy.resumeDetail(EpisodeProgress.None, 2497))
        assertEquals("从头开始 · 41:37", EpisodeProgressPolicy.resumeDetail(EpisodeProgress.Finished, 2497))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.EpisodeProgressPolicyTest"` → 编译失败（Unresolved reference）。

- [ ] **Step 2: 新建 `EpisodeProgressPolicy.kt`**

```kotlin
package com.biliseek.app.collection

import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.core.player.formatDuration

/** 一个视频最近一次的本地播放记录（`play_history`，多 P 取最近的那一条）。 */
data class EpisodeHistory(val positionMs: Long, val durationMs: Long, val playedAt: Long)

/** 一集的收听情况。 */
sealed interface EpisodeProgress {
    data object None : EpisodeProgress
    data class Partial(val positionMs: Long, val durationMs: Long) : EpisodeProgress
    data object Finished : EpisodeProgress
}

/**
 * 合集里「听到第几集」的纯函数（设计稿 docs/superpowers/specs/2026-10-27-collections-round3-design.md §1）。
 * 判定与续播（`:core:player` ResumePolicy）同口径：离结尾不到 15 秒算听完，不到 5 秒算没听。
 */
object EpisodeProgressPolicy {
    private const val MIN_MS = 5_000L
    private const val TAIL_MS = 15_000L

    /** 按 bvid 取最近一条记录。 */
    fun latestByBvid(rows: List<PlayHistoryEntity>): Map<String, EpisodeHistory> =
        rows.groupBy { it.bvid }.mapValues { (_, list) ->
            val latest = list.maxBy { it.playedAt }
            EpisodeHistory(latest.positionMs, latest.durationSec * 1000, latest.playedAt)
        }

    fun progressOf(h: EpisodeHistory?): EpisodeProgress = when {
        h == null -> EpisodeProgress.None
        h.durationMs > 0 && h.positionMs >= h.durationMs - TAIL_MS -> EpisodeProgress.Finished
        h.positionMs > MIN_MS -> EpisodeProgress.Partial(h.positionMs, h.durationMs)
        else -> EpisodeProgress.None
    }

    /** 行里的状态文字；没听过返回 null（照旧显示原来那一行）。[durationSec] 是列表给的时长。 */
    fun statusLine(p: EpisodeProgress, durationSec: Long): String? = when (p) {
        EpisodeProgress.Finished -> "✓ 已听完 · ${formatDuration(durationSec * 1000)}"
        is EpisodeProgress.Partial ->
            "听到 ${formatDuration(p.positionMs)} / ${formatDuration(if (p.durationMs > 0) p.durationMs else durationSec * 1000)}"
        EpisodeProgress.None -> null
    }

    /**
     * 「继续听」的目标在 [bvids] 里的下标：最近听过的那一集；它已听完就是下一集；
     * 最后一集已听完、或这些集都没有记录时为 null（不显示横条）。
     */
    fun resumeIndex(bvids: List<String>, history: Map<String, EpisodeHistory>): Int? {
        val latest = bvids.withIndex()
            .filter { history[it.value] != null }
            .maxByOrNull { history.getValue(it.value).playedAt } ?: return null
        return if (progressOf(history[latest.value]) == EpisodeProgress.Finished) {
            (latest.index + 1).takeIf { it < bvids.size }
        } else {
            latest.index
        }
    }

    /** 第几集：正序 = 下标 + 1；倒序 = 总数 − 下标；倒序且总数未知 = null。 */
    fun episodeNumber(index: Int, total: Int, reversed: Boolean): Int? = when {
        !reversed -> index + 1
        total > 0 -> total - index
        else -> null
    }

    fun resumeTitle(number: Int?, title: String): String =
        if (number != null) "继续听 · 第 $number 集 $title" else "继续听 · $title"

    /** 横条第二行：听到一半写进度，其他写「从头开始 · 时长」。 */
    fun resumeDetail(p: EpisodeProgress, durationSec: Long): String =
        if (p is EpisodeProgress.Partial) statusLine(p, durationSec)!! else "从头开始 · ${formatDuration(durationSec * 1000)}"
}
```

Run 同上 → 全部 PASS。

- [ ] **Step 3: 本地查询**——`HistoryDao` 里 `observeSince` 之后加：

```kotlin
    /** 合集弹层 / 内容页标「已听」用：这些视频的全部记录（多 P 每个分 P 一条）。 */
    @Query("SELECT * FROM play_history WHERE bvid IN (:bvids)")
    fun observeByBvids(bvids: List<String>): Flow<List<PlayHistoryEntity>>
```

`LibraryRepository` 里 `observeRecent` 附近加：

```kotlin
    /**
     * 这些视频的播放记录（合集标「已听」）。只取前 900 个不同的 bvid（SQLite 单条语句的参数上限是 999），
     * 更大的合集后面的集不标。
     */
    fun observeHistory(bvids: List<String>): Flow<List<PlayHistoryEntity>> =
        history.observeByBvids(bvids.distinct().take(900))
```

（`LibraryRepository` 里持有 `HistoryDao` 的属性名以文件里实际为准，例如 `history`。）

- [ ] **Step 4:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` → 通过。提交 `feat(collection): 听到第几集的判定纯函数与本地查询`。

---

### Task 3: `CollectionController`——排序、订阅合集换接口、播放记录流

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/RemoteListState.kt`、`app/src/main/java/com/biliseek/app/collection/CollectionModels.kt`、`app/src/main/java/com/biliseek/app/collection/CollectionPolicy.kt`、`app/src/main/java/com/biliseek/app/collection/CollectionController.kt`、`app/src/main/java/com/biliseek/app/AppViewModel.kt`、`app/src/main/java/com/biliseek/app/MainActivity.kt`
- Test: `app/src/test/java/com/biliseek/app/collection/CollectionPolicyTest.kt`

**Interfaces — Produces:**
- `RemoteListState.total: Int = 0`
- `TrackListId.Collection(val ref: CollectionRef, val reversed: Boolean = false)`
- `CollectionRef.Subscribed(val seasonId: Long, val mid: Long = 0, val upName: String = "")`
- `CollectionPolicy.sortSubtitle(total: Int, loaded: Int, reversed: Boolean): String`
- `CollectionController(… library: LibraryRepository …)`；`openSubscribed(seasonId: Long, title: String)`、`toggleSort(slot: CollectionSlot)`（当前顺序不另存，UI 用 `(state.id as? TrackListId.Collection)?.reversed` 读）、`historyOf(bvids: List<String>): Flow<Map<String, EpisodeHistory>>`

- [ ] **Step 1: 测试**——`CollectionPolicyTest` 加：

```kotlin
    @Test
    fun `内容页副标题写总集数和顺序 总数未知写已加载条数`() {
        assertEquals("共 36 集 · 正序", CollectionPolicy.sortSubtitle(total = 36, loaded = 30, reversed = false))
        assertEquals("共 36 集 · 倒序", CollectionPolicy.sortSubtitle(total = 36, loaded = 30, reversed = true))
        assertEquals("30 首 · 正序", CollectionPolicy.sortSubtitle(total = 0, loaded = 30, reversed = false))
    }

    @Test
    fun `订阅的合集找不到 UP 主时不能排序 其他都能`() {
        assertTrue(CollectionPolicy.canSort(CollectionRef.Subscribed(seasonId = 1, mid = 9, upName = "UP甲")))
        assertFalse(CollectionPolicy.canSort(CollectionRef.Subscribed(seasonId = 1)))
        assertTrue(CollectionPolicy.canSort(CollectionRef.Season(mid = 9, seasonId = 1, upName = "UP甲")))
        assertTrue(CollectionPolicy.canSort(CollectionRef.Series(mid = 9, seriesId = 2, upName = "UP甲")))
    }
```

- [ ] **Step 2: 模型**
  - `RemoteListState` 加字段（放在 `errorMessage` 后面）：`/** 接口给的总条数，未知为 0。 */ val total: Int = 0,`
  - `TrackListId.Collection` 改为 `data class Collection(val ref: CollectionRef, val reversed: Boolean = false) : TrackListId`（KDoc 补「[reversed] 为当前是否倒序」）。
  - `CollectionRef.Subscribed` 改为 `data class Subscribed(val seasonId: Long, val mid: Long = 0, val upName: String = "") : CollectionRef`，KDoc 改为「「列表 → 合集」里我订阅的合集。有 [mid] 时走 `seasons_archives_list`（可倒序）；没有时退回 `x/space/fav/season/list`（要登录、不能排序）。」
  - `CollectionPolicy` 加：

```kotlin
    /** 合集内容页副标题：「共 36 集 · 正序」；总数未知时「30 首 · 正序」。 */
    fun sortSubtitle(total: Int, loaded: Int, reversed: Boolean): String =
        (if (total > 0) "共 $total 集" else "$loaded 首") + " · " + (if (reversed) "倒序" else "正序")

    /** 这个来源能不能切排序：订阅的合集没有 mid 时不能（旧接口不支持）。 */
    fun canSort(ref: CollectionRef): Boolean = ref !is CollectionRef.Subscribed || ref.mid > 0
```

- [ ] **Step 3: `CollectionController`**
  - 构造参数加 `private val library: LibraryRepository,`（放在 `repository` 后面；import `com.biliseek.app.data.LibraryRepository`）。
  - 新增：

```kotlin
    /** 打开「列表 → 合集」里的一项：从订阅列表找出 UP 主（mid、名字），有就走可排序的接口。 */
    fun openSubscribed(seasonId: Long, title: String) {
        val item = _collectedList.value.firstOrNull { it.id == seasonId }
        open(
            CollectionSlot.LIBRARY,
            CollectionRef.Subscribed(seasonId, mid = item?.upper?.mid ?: 0, upName = item?.upper?.name.orEmpty()),
            title,
        )
    }

    /** 切换正序 / 倒序：换掉状态里的顺序标记、从第 1 页重取（不支持排序的来源什么都不做）。 */
    fun toggleSort(slot: CollectionSlot) {
        val target = slot(slot)
        val state = target.value ?: return
        val id = state.id as? TrackListId.Collection ?: return
        if (!CollectionPolicy.canSort(id.ref)) return
        target.value = RemoteListState(id = id.copy(reversed = !id.reversed), title = state.title)
        loadPage(slot, 1)
    }

    /** 这些视频的本地播放记录（按 bvid 取最近一条），合集标「已听」与「继续听」用。 */
    fun historyOf(bvids: List<String>): Flow<Map<String, EpisodeHistory>> =
        library.observeHistory(bvids).map(EpisodeProgressPolicy::latestByBvid)
```

  （import `kotlinx.coroutines.flow.Flow`、`kotlinx.coroutines.flow.map`。）
  - `loadPage`：开头取 `val id = state.id as? TrackListId.Collection ?: return`，后面的 `fetchPage(ref, page)` 改为 `fetchPage(id.ref, page, id.reversed)`；两处丢弃旧结果的判断从 `base.ref() != ref` 改为 `base.id != id`；成功时 `base.copy(…)` 里加 `total = result.total,`。
  - `play`：`PagedQueueSource` 里的 `fetchPage(ref, page)` 改为按状态 id 的顺序取：`fetchPage(id.ref, page, id.reversed)`（`id` 同上从 `state.id` 取）。
  - `fetchPage` 加参数 `reversed: Boolean`：

```kotlin
    private suspend fun fetchPage(ref: CollectionRef, page: Int, reversed: Boolean): Result<SeriesArchivesPage> = when (ref) {
        is CollectionRef.Subscribed ->
            if (ref.mid > 0) {
                repository.spaceSeasonArchives(ref.mid, ref.seasonId, page, reverse = reversed)
                    .map { it.withAuthor(ref.upName, ref.mid) }
            } else {
                repository.collectionSeasonList(ref.seasonId, page).map { result ->
                    SeriesArchivesPage(result.items.map { it.toArchive() }, result.page, result.hasMore)
                }
            }
        is CollectionRef.Season -> repository.spaceSeasonArchives(ref.mid, ref.seasonId, page, reverse = reversed)
            .map { it.withAuthor(ref.upName, ref.mid) }
        is CollectionRef.Series -> repository.seriesArchives(ref.mid, ref.seriesId, page, reverse = reversed)
            .map { it.withAuthor(ref.upName, ref.mid) }
    }
```

  - `unsubscribeOpenedInLibrary` / `removeFromCollected` 里对 `CollectionRef.Subscribed` 的用法（只取 `seasonId`）不用改。
- [ ] **Step 4: 接线**
  - `AppViewModel`：`CollectionController(` 里 `repository = network.repository,` 后加 `library = library,`。
  - `MainActivity`：`LibraryScreen(` 的 `onOpenSeries = { id, title -> viewModel.collections.open(CollectionSlot.LIBRARY, CollectionRef.Subscribed(id), title) },` 改为 `onOpenSeries = viewModel.collections::openSubscribed,`；删掉因此不用的 `CollectionRef` import（若别处还用就保留）。
- [ ] **Step 5:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` → 通过。提交 `feat(collection): 合集内容页支持倒序，订阅合集改走 UP 空间合集接口；播放记录流`。

---

### Task 4: 合集内容页——状态行、继续听横条、排序图标、多选

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/SeriesScreen.kt`、`app/src/main/java/com/biliseek/app/ui/CollectionSheet.kt`（新增共用横条与共用确认框）、`app/src/main/java/com/biliseek/app/AppViewModel.kt`、`app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt`、`app/src/main/java/com/biliseek/app/MainActivity.kt`

**Interfaces — Produces:**
- `data class CollectionSelectionUi(active, selected, isLoggedIn, onEnter: (String) -> Unit, onToggle: (String) -> Unit, onSelectAll, onClear, onExit, onAction: (PendingAction) -> Unit)`（放 `SeriesScreen.kt`，默认值全空 = 不支持多选）
- `internal fun CollectionResumeBanner(title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier)`（放 `CollectionSheet.kt`）
- `internal fun UnsubscribeCollectionDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit)`（放 `CollectionSheet.kt`，第 12 条）
- `SeriesScreen` 新参数：`onToggleSort: (() -> Unit)? = null`、`history: Map<String, EpisodeHistory> = emptyMap()`、`playingBvid: String = ""`、`selection: CollectionSelectionUi = CollectionSelectionUi()`（正序 / 倒序从 `state.id` 读，不另传）
- `AppViewModel`：`val collectionSelection = SelectionScope()`、`enterCollectionSelection(bvid)`、`selectAllCollection(slot)`、`runCollectionBatch(slot, action)`

- [ ] **Step 0: 共用确认框（第 12 条）**——`CollectionSheet.kt` 文件末尾加下面的组件，并把 `CollectionSheet` 里现有的 `BsConfirmDialog(title = "取消订阅合集", …)` 换成 `UnsubscribeCollectionDialog(title = collection.title, onConfirm = { confirmingUnsubscribe = false; onSetSubscribed(false) }, onDismiss = { confirmingUnsubscribe = false })`（照原来分行写），上面那行「与「列表 → 合集」内容页同一个确认框…」注释删掉（说明挪进组件 KDoc）。`SeriesScreen` 在 Step 2 里改用它。

```kotlin
/** 「取消订阅合集」确认框：合集内容页与合集弹层共用。两个键是「保留 / 取消订阅」，不用「取消」免得和「取消订阅」看混。 */
@Composable
internal fun UnsubscribeCollectionDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BsConfirmDialog(
        title = "取消订阅合集",
        text = "确定不再订阅「$title」？它会从合集列表里移除。",
        confirmLabel = "取消订阅",
        dismissLabel = "保留",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
```

- [ ] **Step 1: 共用横条**——`CollectionSheet.kt` 文件末尾加：

```kotlin
/**
 * 「继续听」横条（样式 D）：列表第一项，secondaryContainer、12dp 圆角。
 * 合集弹层与合集内容页共用；第一行「继续听 · 第 N 集 标题」，第二行进度或「从头开始」。
 */
@Composable
internal fun CollectionResumeBanner(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}
```

（import `androidx.compose.material.icons.filled.PlayArrow`。）

- [ ] **Step 2: `SeriesScreen.kt` 改写**——在文件里加 `CollectionSelectionUi`，并把 `SeriesScreen` 改成下面这样（保留原有 import，缺的补上：`combinedClickable`、`ExperimentalFoundationApi`、`BackHandler`、`Checkbox`、`IconButton`、`Icon`、`Icons.Filled.SwapVert`、`EpisodeHistory`、`EpisodeProgress`、`EpisodeProgressPolicy`、`CollectionPolicy`、`TrackListId`、`com.biliseek.app.PendingAction`）：

```kotlin
/** 合集内容页多选的状态与动作（宿主实现；默认 = 不支持多选）。 */
data class CollectionSelectionUi(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
    val isLoggedIn: Boolean = false,
    val onEnter: (String) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onAction: (PendingAction) -> Unit = {},
)

/**
 * 合集内容页（M13-N）。分页拉取，滚动到底自动加载下一页。
 *
 * - 「列表」tab 里打开时标题右侧有「取消订阅」（确认后取消订阅，成功后宿主关掉本页）；UP 空间里打开的合集 / 系列不显示。
 * - 顶栏排序图标切正序 / 倒序，副标题写总集数与顺序（合集第三轮 §2）。
 * - 每行按本地播放记录写「已听完 / 听到哪里」，列表顶部有「继续听」横条（§1，样式 B + D）。
 * - 长按进多选，顶栏换成 [SelectionToolbar]，「全选」只选已加载的（§3）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(
    state: RemoteListState<SeriesArchive>,
    onClose: () -> Unit,
    onItemClick: (SeriesArchive) -> Unit,
    onLoadMore: (Int) -> Unit,
    /** 取消订阅这个合集；null = 不显示「取消订阅」（UP 空间里打开的合集 / 系列）。 */
    onUnsubscribe: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** 视频行右侧三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
    /** 切换正序 / 倒序；null = 不显示排序图标。 */
    onToggleSort: (() -> Unit)? = null,
    /** 本地播放记录（按 bvid）。 */
    history: Map<String, EpisodeHistory> = emptyMap(),
    /** 正在播的视频：「继续听」目标是它时不显示横条。 */
    playingBvid: String = "",
    selection: CollectionSelectionUi = CollectionSelectionUi(),
) {
    val reversed = (state.id as? TrackListId.Collection)?.reversed == true
    BackHandler(enabled = selection.active) { selection.onExit() }

    var menuFor by remember { mutableStateOf<SearchVideo?>(null) }
    menuFor?.let { VideoMenuSheet(it, videoActions) { menuFor = null } }

    var confirmingUnsubscribe by remember { mutableStateOf(false) }
    if (confirmingUnsubscribe) {
        UnsubscribeCollectionDialog(
            title = state.title,
            onConfirm = {
                confirmingUnsubscribe = false
                onUnsubscribe?.invoke()
            },
            onDismiss = { confirmingUnsubscribe = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionToolbar(
                selectedCount = selection.selected.size,
                visibleCount = state.items.size,
                allSelected = state.items.isNotEmpty() && state.items.all { it.bvid in selection.selected },
                canAddToPlaylist = selection.isLoggedIn,
                canDownload = selection.isLoggedIn,
                onSelectAll = selection.onSelectAll,
                onClearSelection = selection.onClear,
                onAddToPlaylist = { selection.onAction(PendingAction.AddToPlaylist) },
                onAppendToQueue = { selection.onAction(PendingAction.AppendToQueue) },
                onEnqueueNext = { selection.onAction(PendingAction.EnqueueNext) },
                onDownload = { selection.onAction(PendingAction.Download) },
                onExit = selection.onExit,
            )
        } else {
            BsTopBar(
                title = state.title,
                subtitle = CollectionPolicy.sortSubtitle(state.total, state.items.size, reversed),
                onNavigate = onClose,
                actions = {
                    if (onToggleSort != null) {
                        IconButton(onClick = onToggleSort) {
                            Icon(Icons.Filled.SwapVert, contentDescription = if (reversed) "排序：倒序" else "排序：正序")
                        }
                    }
                    if (onUnsubscribe != null) {
                        TextButton(onClick = { confirmingUnsubscribe = true }) { Text("取消订阅") }
                    }
                },
            )
        }

        when {
            state.isLoading && state.items.isEmpty() -> BsLoadingState()
            state.errorMessage != null && state.items.isEmpty() ->
                BsErrorState(state.errorMessage, onRetry = { onLoadMore(1) })
            state.items.isEmpty() -> BsEmptyState("这个合集是空的", icon = Icons.Outlined.VideoLibrary)
            else -> {
                val bvids = state.items.map { it.bvid }
                val resumeIndex = remember(bvids, history) { EpisodeProgressPolicy.resumeIndex(bvids, history) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                    val target = resumeIndex?.let { state.items[it] }
                    // 横条位固定是第一项，没横条时是空项：播放记录比列表晚到，晚插进来会被挤到屏幕外
                    item(key = "resume") {
                        if (!selection.active && target != null && target.bvid != playingBvid) {
                            CollectionResumeBanner(
                                title = EpisodeProgressPolicy.resumeTitle(
                                    EpisodeProgressPolicy.episodeNumber(resumeIndex, state.total, reversed),
                                    target.title,
                                ),
                                detail = EpisodeProgressPolicy.resumeDetail(
                                    EpisodeProgressPolicy.progressOf(history[target.bvid]),
                                    target.duration,
                                ),
                                onClick = { onItemClick(target) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(state.items, key = { it.bvid }) { archive ->
                        val progress = EpisodeProgressPolicy.progressOf(history[archive.bvid])
                        val checked = archive.bvid in selection.selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selection.active) selection.onToggle(archive.bvid) else onItemClick(archive)
                                    },
                                    onLongClick = { if (!selection.active) selection.onEnter(archive.bvid) },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverThumbnail(normalizeCoverUrl(archive.cover), width = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    archive.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (progress == EpisodeProgress.Finished) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    EpisodeProgressPolicy.statusLine(progress, archive.duration)
                                        ?: (archive.author + "  " + formatDuration(archive.duration * 1000)),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (selection.active) {
                                Checkbox(checked = checked, onCheckedChange = { selection.onToggle(archive.bvid) })
                            } else {
                                VideoMenuButton { menuFor = archive.toSearchVideo() }
                            }
                        }
                        HorizontalDivider()
                    }
                    // （下面的 footer 原样保留）
                }
            }
        }
    }
}
```

（原文件 `if (state.hasMore || state.isLoadingMore) { item(key = "footer") { … } }` 整段保留在 `items(...)` 之后；原来的 `BsConfirmDialog` 换成 Step 0 的 `UnsubscribeCollectionDialog`，`BsConfirmDialog` 的 import 随之删掉。切换顺序时状态被换成空列表、先显示加载中，`LazyColumn` 重新进入组合，自然回到顶部，不需要另存滚动位置。）

- [ ] **Step 3: `AppViewModel` 多选**——`val upSelection = SelectionScope()` 后面加 `val collectionSelection = SelectionScope()`。互斥：`enterSelectionMode` 里 `upSelection.exit()` 后加一行 `collectionSelection.exit()`；`enterPageSelection` 和 `enterUpSelection` 里同样在已有的 `…exit()` 后各加一行 `collectionSelection.exit()`（`enterSelectionMode` 上面那行注释「…复位另两套」改成「…复位另三套」，并补上合集）。再加：

```kotlin
    /** 合集内容页长按进多选（与其他复选互斥）。 */
    fun enterCollectionSelection(bvid: String) {
        exitSelectionMode()
        pageSelection.exit()
        upSelection.exit()
        collectionSelection.enter(bvid)
    }

    private fun collectionItems(slot: CollectionSlot) = when (slot) {
        CollectionSlot.LIBRARY -> collections.openedInLibrary.value
        CollectionSlot.UP_SPACE -> collections.openedInUpSpace.value
    }?.items.orEmpty()

    /** 全选只选已加载的（合集第三轮 §3）。 */
    fun selectAllCollection(slot: CollectionSlot) = collectionSelection.selectAll(collectionItems(slot).map { it.bvid })

    /** 批量动作：同 UP 投稿的多选（逐个取分 P 后执行）。 */
    fun runCollectionBatch(slot: CollectionSlot, action: PendingAction) = viewModelScope.launch {
        val sel = collectionSelection.selected.value
        val videos = collectionItems(slot).filter { it.bvid in sel }.map { it.toSearchVideo() }
        collectionSelection.exit()
        execute(action, videos.flatMap { expandToRequests(it) })
    }
```

（`toSearchVideo` 是 `ui/VideoMenuPolicy.kt` 里的 `SeriesArchive.toSearchVideo()`，按需 import。）

- [ ] **Step 4: 接线**
  - `MainActivity` 收集：`val collectionSelectionActive by viewModel.collectionSelection.active.collectAsState()`、`val collectionSelected by viewModel.collectionSelection.selected.collectAsState()`；`val libraryHistory by remember(openedSeries?.items?.map { it.bvid }) { viewModel.collections.historyOf(openedSeries?.items?.map { it.bvid }.orEmpty()) }.collectAsState(emptyMap())`，UP 空间那份同理用 `upSpaceCollection`（变量名 `upSpaceHistory`）。
  - 一个构造多选 UI 的小函数（放 `MainActivity` 里 `setContent` 内的局部 lambda 即可）：

```kotlin
                fun collectionSelectionUi(slot: CollectionSlot) = com.biliseek.app.ui.CollectionSelectionUi(
                    active = collectionSelectionActive,
                    selected = collectionSelected,
                    isLoggedIn = session.isLoggedIn,
                    onEnter = viewModel::enterCollectionSelection,
                    onToggle = viewModel.collectionSelection::toggle,
                    onSelectAll = { viewModel.selectAllCollection(slot) },
                    onClear = viewModel.collectionSelection::clear,
                    onExit = viewModel.collectionSelection::exit,
                    onAction = { viewModel.runCollectionBatch(slot, it) },
                )
```

  - UP 空间的 `SeriesScreen(…)` 调用加：`onToggleSort = (openedUpCollection.id as? com.biliseek.app.data.TrackListId.Collection)?.takeIf { com.biliseek.app.collection.CollectionPolicy.canSort(it.ref) }?.let { { viewModel.collections.toggleSort(CollectionSlot.UP_SPACE) } }`、`history = upSpaceHistory`、`playingBvid = playback.bvid`、`selection = collectionSelectionUi(CollectionSlot.UP_SPACE)`。
  - `LibraryScreen` 加参数并透传给它内部的 `SeriesScreen`：`onToggleSeriesSort: (() -> Unit)? = null`、`seriesHistory: Map<String, com.biliseek.app.collection.EpisodeHistory> = emptyMap()`、`playingBvid: String = ""`、`seriesSelection: CollectionSelectionUi = CollectionSelectionUi()`；`MainActivity` 的 `LibraryScreen(…)` 传入 LIBRARY 槽对应的值（排序用同样的 `canSort` 判断）。
  - 退出多选：两个槽里任一个关掉、换了合集或换了顺序（含取消订阅后控制器自己关掉「列表」那页）都要退出多选。在 `MainActivity` 收集 `openedSeries` 的地方后面加一处，不在各个关闭回调里分别写：

```kotlin
                // 合集内容页关掉、换合集或换顺序时退出多选（取消订阅成功后页面是控制器关的，回调里拦不到）
                LaunchedEffect(openedSeries?.id, upSpaceCollection?.id) { viewModel.collectionSelection.exit() }
```

  （内容页上按返回时，`SeriesScreen` 里 `BackHandler(enabled = selection.active)` 后注册、先拿到返回，先退多选、第二下才关页。）
- [ ] **Step 5:** `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest` → 通过。提交 `feat(collection): 合集内容页标出已听 / 继续听、可切顺序、可长按多选`。

---

### Task 5: 合集弹层——状态行、继续听横条、小节标题吸顶

**Files:** Modify `app/src/main/java/com/biliseek/app/collection/CollectionPolicy.kt`、`app/src/main/java/com/biliseek/app/ui/CollectionSheet.kt`、`app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt`、`app/src/main/java/com/biliseek/app/MainActivity.kt`；Test `app/src/test/java/com/biliseek/app/collection/CollectionPolicyTest.kt`

**Interfaces — Produces:** `CollectionPolicy.sheetInitialItem(rows: List<CollectionSheetRow>, currentIndex: Int?): Int`；`CollectionSheetHandlers.historyOf: (List<String>) -> Flow<Map<String, EpisodeHistory>> = { flowOf(emptyMap()) }`；`CollectionSheet(…, history: Map<String, EpisodeHistory> = emptyMap())`。

**两条布局约定（弹层与 Task 4 的内容页一样）：**
- 「继续听」横条的位置**固定是列表第一项**（key `"resume"`），没有横条时这一项是空的（高 0）。播放记录是异步读的，会比列表晚到；如果那时才在最前面插一项，列表按 key 停在原来那一行，横条会被挤到屏幕外。固定留位就没有这个问题。
- 小节标题吸顶后会盖住第一个看得见的行，所以有小节标题时，打开弹层要往上多退一行，当前这一集才不会被盖住。

- [ ] **Step 0: 打开时先露哪一项（纯函数 + 测试）**——`CollectionPolicyTest` 里在「多个小节显示」那条测试后加：

```kotlin
    @Test
    fun `弹层打开时先露的项 第一项留给横条 有小节标题时多退一行免得被吸顶标题盖住`() {
        val single = CollectionPolicy.fromView(
            view("BV2", UgcSection(title = "正片", episodes = listOf(ep("BV1"), ep("BV2"), ep("BV3")))),
        )!!
        // 没小节标题：行号 1 + 横条位 1 = 2，当前这集在最上面
        assertEquals(2, CollectionPolicy.sheetInitialItem(CollectionPolicy.sheetRows(single), single.currentIndex))

        val multi = CollectionPolicy.fromView(
            view(
                "BV3",
                UgcSection(title = "上篇", episodes = listOf(ep("BV1"), ep("BV2"))),
                UgcSection(title = "下篇", episodes = listOf(ep("BV3"))),
            ),
        )!!
        // 当前在行号 4（「下篇」标题在行号 3）：+1 横条位、-1 退一行 = 4，即先露「下篇」标题
        assertEquals(4, CollectionPolicy.sheetInitialItem(CollectionPolicy.sheetRows(multi), multi.currentIndex))
        // 没有当前集：从头（横条位）
        assertEquals(0, CollectionPolicy.sheetInitialItem(CollectionPolicy.sheetRows(multi), null))
    }
```

`CollectionPolicy` 里 `currentRowIndex` 后加：

```kotlin
    /**
     * 弹层打开时 LazyColumn 先露哪一项。第一项固定是「继续听」横条的位置（没横条时是空项），所以行号加 1；
     * 有小节标题时再往上退一行：吸顶的小节标题会盖住第一个看得见的行，退一行（退到小节标题本身或上一集）当前这集才露得出来。
     */
    fun sheetInitialItem(rows: List<CollectionSheetRow>, currentIndex: Int?): Int {
        if (currentIndex == null) return 0
        val row = currentRowIndex(rows, currentIndex)
        return if (rows.any { it is CollectionSheetRow.Header }) row else row + 1
    }
```

Run `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.CollectionPolicyTest"` → PASS。

- [ ] **Step 1:** `CollectionSheetHandlers` 加：

```kotlin
    /** 这些视频的本地播放记录（宿主转给 CollectionController.historyOf）。 */
    val historyOf: (List<String>) -> Flow<Map<String, EpisodeHistory>> = { flowOf(emptyMap()) },
```

- [ ] **Step 2: `CollectionSheet`**
  - 加参数 `history: Map<String, EpisodeHistory> = emptyMap(),`。
  - `rememberLazyListState(initialFirstVisibleItemIndex = …)` 改用 Step 0 的函数，并算出横条目标：

```kotlin
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = CollectionPolicy.sheetInitialItem(rows, collection.currentIndex),
    )
    val bvids = remember(collection) { collection.episodes.map { it.bvid } }
    val resumeIndex = EpisodeProgressPolicy.resumeIndex(bvids, history)
        ?.takeIf { collection.episodes[it].bvid != playingBvid }
```

  - `LazyColumn` 内容改为（`@OptIn(ExperimentalFoundationApi::class)` 加到函数上；`stickyHeader` 来自 `androidx.compose.foundation.lazy`）：

```kotlin
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            // 横条位固定是第一项，没横条时是空项（见上面的布局约定）
            item(key = "resume") {
                if (resumeIndex != null) {
                    val target = collection.episodes[resumeIndex]
                    CollectionResumeBanner(
                        title = EpisodeProgressPolicy.resumeTitle(resumeIndex + 1, target.title),
                        detail = EpisodeProgressPolicy.resumeDetail(
                            EpisodeProgressPolicy.progressOf(history[target.bvid]),
                            target.durationSec,
                        ),
                        onClick = {
                            onPlayEpisode(resumeIndex)
                            onDismiss()
                        },
                        modifier = Modifier.padding(horizontal = BsSpacing.xl, vertical = BsSpacing.sm),
                    )
                }
            }
            rows.forEachIndexed { i, row ->
                when (row) {
                    is CollectionSheetRow.Header -> stickyHeader(key = "h$i") {
                        // 吸顶：背景同弹层底色，盖住下面滚上来的行
                        Box(Modifier.fillMaxWidth().background(BottomSheetDefaults.ContainerColor)) {
                            SettingsSheetSection(row.title)
                        }
                    }
                    is CollectionSheetRow.Episode -> item(key = "e${row.index}") {
                        CollectionEpisodeRow(
                            episode = row.episode,
                            isCurrent = row.episode.bvid == playingBvid,
                            progress = EpisodeProgressPolicy.progressOf(history[row.episode.bvid]),
                            accent = accent,
                            onClick = {
                                if (CollectionPolicy.shouldPlayEpisode(collection, row.index, playingBvid)) {
                                    onPlayEpisode(row.index)
                                } else {
                                    onQueueCollection(row.index)
                                }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
```

  - `CollectionEpisodeRow` 加参数 `progress: EpisodeProgress`：非当前集时，标题颜色 `if (progress == EpisodeProgress.Finished) onSurfaceVariant else onSurface`，第二行 `EpisodeProgressPolicy.statusLine(progress, episode.durationSec) ?: duration`；当前集照旧（主色、「正在播放 · 时长」）。
- [ ] **Step 3: `PlayerScreen`**——`CollectionSheet(` 调用前：

```kotlin
            val sheetHistory by remember(collection) {
                collectionHandlers.historyOf(collection.episodes.map { it.bvid })
            }.collectAsState(initial = emptyMap())
```

  调用里加 `history = sheetHistory,`。（`collectAsState`、`getValue` 的 import 按需补。）
- [ ] **Step 4: `MainActivity`**——`CollectionSheetHandlers(` 里加 `historyOf = viewModel.collections::historyOf,`。
- [ ] **Step 5:** 编译 + `:app` 单测 + androidTest 编译通过。提交 `feat(collection): 合集弹层标出已听 / 继续听，小节标题吸顶`。

---

### Task 6: 打磨——滚动位置、开关点击区、改名

**Files:** `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt`、`LibraryScreen.kt`、`MainActivity.kt`、`PlayerSwitchRow.kt`（改名为 `ListenWatchSwitch.kt`）

- [ ] **Step 1: 第 6 条滚动位置**
  - `UpSpaceScreen` 加参数 `collectionsListState: LazyListState = rememberLazyListState()`，传给 `UpCollectionsList`（加同名参数），其中 `LazyColumn(state = collectionsListState, …)`。`MainActivity` 的 `upSpaceContent` 里，在 `if (openedUpCollection != null)` 之前：`val upCollectionsListState = remember(upSpace.up?.mid) { LazyListState() }`，传给 `UpSpaceScreen(collectionsListState = upCollectionsListState)`。
  - `LibraryScreen` 函数顶部（`var showCreate by remember …` 旁边，提前 `return` 之前）加 `val seriesListState = rememberLazyListState()`，「合集」tab 的 `LazyColumn(` 加 `state = seriesListState,`。
- [ ] **Step 2: 第 8 条开关点击区**——`ListenWatchSwitch` 外层改成 48dp 高，看得见的胶囊仍画在中间 32dp；每段的点击区是整段 48dp 高，按下的水波纹只画在看得见的那一段里（不然会是一大块方形）。`PlayerChip` 还在用 `border` / `BorderStroke`，那两个 import 保留。

```kotlin
@Composable
internal fun ListenWatchSwitch(isAudioMode: Boolean, onSwitchMode: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    fun switchTo(audio: Boolean) {
        if (audio == isAudioMode) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onSwitchMode()
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    // 点击区 48dp 高；看得见的胶囊仍是中间 32dp（上下各留 8dp，那里也能点到）。描边画在胶囊里面，同原来的 border
    Row(
        modifier
            .height(48.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val top = 8.dp.toPx() + stroke / 2
                val h = size.height - top * 2
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, top),
                    size = Size(size.width - stroke, h),
                    cornerRadius = CornerRadius(h / 2),
                    style = Stroke(width = stroke),
                )
            }
            .padding(horizontal = 3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchSegment("听", Icons.Filled.Headphones, selected = isAudioMode) { switchTo(audio = true) }
        SwitchSegment("看", WatchTvIcon, selected = !isAudioMode) { switchTo(audio = false) }
    }
}

@Composable
private fun SwitchSegment(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    // 整段 48dp 高都能点；看得见的一段是 26dp 高（32dp 胶囊内缩 3dp），水波纹也只画在这一段里
    Box(
        Modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(26.dp)
                .widthIn(min = 52.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.primary else Color.Transparent)
                .indication(interaction, ripple())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
```

  （新 import：`androidx.compose.foundation.indication`、`androidx.compose.foundation.interaction.MutableInteractionSource`、`androidx.compose.foundation.layout.Box`、`androidx.compose.material3.ripple`、`androidx.compose.runtime.remember`、`androidx.compose.ui.draw.drawBehind`、`androidx.compose.ui.geometry.CornerRadius`、`androidx.compose.ui.geometry.Offset`、`androidx.compose.ui.geometry.Size`、`androidx.compose.ui.graphics.drawscope.Stroke`。KDoc 里「顶栏用紧凑尺寸：高 32dp」改成「看得见的胶囊高 32dp，点击区 48dp 高」。）
- [ ] **Step 3: 第 12 条改名**——`git mv app/src/main/java/com/biliseek/app/ui/PlayerSwitchRow.kt app/src/main/java/com/biliseek/app/ui/ListenWatchSwitch.kt`（与 Step 2 同一个提交即可）；`grep -rn "PlayerSwitchRow" app/src docs CLAUDE.md` 找到的引用一并改成新文件名（`docs/05` 里的历史记录条目不改）。确认框共用已在 Task 4 Step 0 做了。
- [ ] **Step 4:** 编译 + `:app` 单测 + androidTest 编译通过；`grep -rn "PlayerSwitchRow" app/src` 只剩历史注释或为空。提交 `refactor(player): 听看开关点击区加大、文件改名；返回后合集列表位置保留`。

---

### Task 7: DTO 样本换成按真实响应整理的（第 10 条）

**Files:** `core/network/src/test/java/com/biliseek/core/network/CollectionDtoTest.kt`

- [ ] **Step 1:** 从 `/tmp/coll-screen/live-biliseek.log`（10-27 真机日志，`grep -a` 读，行首是 logcat 前缀 `… D/BiliSeekHttp(pid): `）找到这些接口的响应体（每条响应体在 `<-- 200 <url>` 之后、以 `{"code"` 开头的那一行）：`x/web-interface/wbi/view`（带 `ugc_season`）、`x/web-interface/archive/relation`、`x/polymer/web-space/seasons_series_list`、`x/polymer/web-space/seasons_archives_list`；日志里没有 `x/series/archives` 的就保留现有手写样本。日志单行超过约 4KB 会被截断：完整的直接用（把长列表截到 2–3 项、去掉与测试无关的大段字段，保持合法 JSON）；被截断的，按能看到的真实前缀照抄字段名、类型、嵌套，再补齐成合法 JSON。不得包含账号 cookie、csrf 等敏感值（这些接口的响应体里没有；若出现就删）。
- [ ] **Step 2:** 用整理好的样本替换测试里的手写 JSON，**断言不减少**，必要时按真实值调整期望值（比如合集名、集数、`season_fav` 的真实取值）。测试类注释改为「样本按 2026-10-27 真机日志里的真实响应整理（列表截短）」。
- [ ] **Step 3:** `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionDtoTest"` → PASS。提交 `test(network): 合集 DTO 样本换成按真实响应整理的`。

---

### Task 8: 文档与全量验证

- [ ] **Step 1:** `docs/05-功能优化篇.md` 末尾加 `### 13.90 合集第三轮：听到第几集、排序、多选与打磨（2026-10-27）`，写清：用户选的样式（B + D + F）、数据来源（本地 play_history、同续播口径）、订阅合集改走 `seasons_archives_list`（找不到 mid 退回旧接口）、多选逐个取分 P 的已知风险、滚动位置、吸顶、开关点击区、改名与确认框共用、DTO 样本换真实；验证与真机待确认项（同设计稿）。
- [ ] **Step 2:** `docs/BiliSeek-手动测试指南.md` 步骤 33.2 表格末尾加行：已听 / 听到一半的显示（弹层与内容页）、继续听横条（目标与续播位置）、排序图标与副标题、长按多选与批量动作、返回后列表位置、多小节吸顶、「列表 → 合集」换接口后的回归（列表、翻页、点播续页、取消订阅）。步骤 33 表里加一行「听 \| 看 开关胶囊上下边缘也能点到」。
- [ ] **Step 3:** `docs/09-UI规范与重构.md` 播放页一节里如有写听看开关尺寸的地方，补「点击区 48dp 高」。
- [ ] **Step 4:** `./gradlew test :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL。提交 `docs: 合集第三轮记录与手动测试项`。
