# 按视频、按天记收听时长 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让日播放记录页的列表、当日听歌时长、首页热力图深浅出自同一份「某天 × 某视频 × 听了多久」数据。

**Architecture:** 新表 `listen_entry`（DB v10）由 `ListenTimeTracker` 按当前视频写入；判断逻辑全在纯函数里（`ListenTimePolicy.kt` 的 `ListenClock`、新文件 `DayListenPolicy.kt`、`HeatmapPolicy.kt`）；10-07 以前的日子按 `listen_day` → `play_history` 回退。设计稿：`docs/superpowers/specs/2026-10-07-listen-entry-design.md`。

**Tech Stack:** Kotlin、Room 2.8（KSP、导出 schema）、Coroutines Flow、Jetpack Compose、JUnit4。

## Global Constraints

- 自动化测试一律离线，绝不访问 B 站服务器。
- minSdk 26：SQLite 3.18，不能用 `INSERT … ON CONFLICT DO UPDATE`；累加用 `INSERT OR IGNORE` + `UPDATE`，包在 `db.withTransaction`。
- `listen_entry.cid` 与 `play_history.cid` 同口径：`PlaybackState.cid`（请求里的 cid，起播第一 P 可能为 0）。
- `listen_day` 停止写入、保留不删；不改 `play_history` 语义。
- 热力图分档：0 分钟 → 0；>0 且 < 10 分钟 → 0.2；10–29 → 0.4；30–59 → 0.6；≥ 60 → 1.0。
- 日页每行副标题：「UP 主 · 听了 mm:ss」（`formatDuration`）；回退时仍显示完整时长。
- 不装机（用户开口才装）；提交由用户决定，计划里的 commit 步骤只在用户要求时执行。

---

### Task 1: `ListenClock` 每段带上视频键

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/ListenTimePolicy.kt`
- Test: `app/src/test/java/com/biliseek/app/data/ListenTimePolicyTest.kt`

**Interfaces:**
- Produces:
  - `data class ListenKey(val bvid: String, val cid: Long)`
  - `data class ListenSlice(val key: ListenKey, val epochDay: Long, val ms: Long, val endWallMs: Long)`
  - `ListenClock.start(key: ListenKey, elapsedMs: Long, wallMs: Long): List<ListenSlice>`（换视频时返回旧视频那一段）
  - `ListenClock.tick(elapsedMs: Long, wallMs: Long): List<ListenSlice>`、`ListenClock.stop(elapsedMs: Long): List<ListenSlice>`
  - `splitByLocalDay` / `DaySlice` 保持不变。

- [ ] **Step 1: 改写计时状态机的测试**（`splitByLocalDay` 三例不动；状态机各例改用 key，并新增换视频两例）

```kotlin
    private val a = ListenKey("BV1a", 10)
    private val b = ListenKey("BV1b", 20)

    @Test
    fun `播放 65 秒后停下记 65 秒`() {
        val clock = ListenClock(zone)
        clock.start(a, elapsedMs = 1_000, wallMs = wallAt(10, 0, 0))
        assertEquals(
            listOf(ListenSlice(a, day.toEpochDay(), 65_000, wallAt(10, 1, 5))),
            clock.stop(elapsedMs = 66_000),
        )
    }

    @Test
    fun `播放中定时结算只记上次结算之后的部分，合起来不多不少`() {
        val clock = ListenClock(zone)
        clock.start(a, 0, wallAt(10, 0, 0))
        val total = clock.tick(30_000, wallAt(10, 0, 30)) +
            clock.tick(60_000, wallAt(10, 1, 0)) +
            clock.stop(65_000)
        assertEquals(listOf(30_000L, 30_000L, 5_000L), total.map { it.ms })
    }

    @Test
    fun `暂停的时间不算`() {
        val clock = ListenClock(zone)
        clock.start(a, 0, wallAt(10, 0, 0))
        val first = clock.stop(10_000)
        clock.start(a, 20_000, wallAt(10, 0, 20))
        val second = clock.stop(30_000)
        assertEquals(listOf(10_000L, 10_000L), (first + second).map { it.ms })
    }

    @Test
    fun `没在播时结算或停下什么都不记`() {
        val clock = ListenClock(zone)
        assertTrue(clock.tick(30_000, wallAt(10, 0, 30)).isEmpty())
        assertTrue(clock.stop(30_000).isEmpty())
    }

    @Test
    fun `同一视频再开始不重置起点`() {
        val clock = ListenClock(zone)
        assertTrue(clock.start(a, 0, wallAt(10, 0, 0)).isEmpty())
        assertTrue(clock.start(a, 10_000, wallAt(10, 0, 10)).isEmpty())
        assertEquals(listOf(20_000L), clock.stop(20_000).map { it.ms })
    }

    @Test
    fun `播放中换视频先把前一段记给旧视频`() {
        val clock = ListenClock(zone)
        clock.start(a, 0, wallAt(10, 0, 0))
        assertEquals(
            listOf(ListenSlice(a, day.toEpochDay(), 40_000, wallAt(10, 0, 40))),
            clock.start(b, 40_000, wallAt(10, 0, 40)),
        )
        assertEquals(
            listOf(ListenSlice(b, day.toEpochDay(), 20_000, wallAt(10, 1, 0))),
            clock.stop(60_000),
        )
    }

    @Test
    fun `播放跨过零点时分到两天，各段的结束时间落在各自那天`() {
        val clock = ListenClock(zone)
        clock.start(a, 0, wallAt(23, 59, 50))
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(
            listOf(
                ListenSlice(a, day.toEpochDay(), 10_000, midnight),
                ListenSlice(a, day.plusDays(1).toEpochDay(), 10_000, midnight + 10_000),
            ),
            clock.stop(20_000),
        )
    }
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.ListenTimePolicyTest"`
Expected: FAIL（`ListenKey` / `ListenSlice` 未定义）

- [ ] **Step 3: 实现**（替换 `ListenClock`，文件头注释补一句「每段带视频键」）

```kotlin
/** 收听计时归到哪个视频：与 `play_history` 同口径的 (bvid, cid)。 */
data class ListenKey(val bvid: String, val cid: Long)

/** [key] 这个视频在 [epochDay] 这天听了 [ms] 毫秒，这一段在墙钟 [endWallMs] 结束。 */
data class ListenSlice(val key: ListenKey, val epochDay: Long, val ms: Long, val endWallMs: Long)

class ListenClock(private val zone: ZoneId) {
    private var key: ListenKey? = null
    private var startElapsedMs = 0L
    private var startWallMs = 0L

    /** 开始给 [key] 计时；同一视频已在计时不重置起点；正在给别的视频计时就先结算那一段并返回。 */
    fun start(key: ListenKey, elapsedMs: Long, wallMs: Long): List<ListenSlice> {
        if (this.key == key) return emptyList()
        val settled = settle(elapsedMs)
        this.key = key
        startElapsedMs = elapsedMs
        startWallMs = wallMs
        return settled
    }

    /** 播放中结算到现在，并从现在重新起一段。没在计时返回空。 */
    fun tick(elapsedMs: Long, wallMs: Long): List<ListenSlice> {
        val slices = settle(elapsedMs)
        if (key != null) {
            startElapsedMs = elapsedMs
            startWallMs = wallMs
        }
        return slices
    }

    /** 停下：结算这一段，停止计时。没在计时返回空。 */
    fun stop(elapsedMs: Long): List<ListenSlice> = settle(elapsedMs).also { key = null }

    private fun settle(elapsedMs: Long): List<ListenSlice> {
        val current = key ?: return emptyList()
        var end = startWallMs
        return splitByLocalDay(startWallMs, elapsedMs - startElapsedMs, zone).map {
            end += it.ms
            ListenSlice(current, it.epochDay, it.ms, end)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令，Expected: PASS）。`ListenTimeTracker` 此时编译不过，Task 4 一起改；本步只跑这个测试类会先编译整个 `:app`，若因 tracker 编译失败，先按 Task 4 Step 3 改 tracker 的签名再跑。

---

### Task 2: 日页口径、日合计合并、热力图分档（纯函数）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/data/DayListenPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/HeatmapPolicy.kt`
- Test: `app/src/test/java/com/biliseek/app/data/DayListenPolicyTest.kt`、`app/src/test/java/com/biliseek/app/ui/HeatmapLevelTest.kt`

**Interfaces:**
- Produces:
  - `data class DayListenRow(@Embedded val history: PlayHistoryEntity, val listenedMs: Long, val lastListenedAt: Long)`（Room JOIN 结果，Task 3 的 DAO 返回它）
  - `data class DayMs(val epochDay: Long, val ms: Long)`
  - `data class DayRow(val history: PlayHistoryEntity, val listenedMs: Long?)`（null = 回退，显示完整时长）
  - `data class DayView(val rows: List<DayRow>, val totalMs: Long?)`（null = 未统计）
  - `fun resolveDayView(listened: List<DayListenRow>, legacyMs: Long?, legacyHistory: List<PlayHistoryEntity>): DayView`
  - `fun mergeDayTotals(entries: List<DayMs>, legacy: List<DayMs>): Map<Long, Long>`
  - `internal fun heatmapListenAlpha(listenedMs: Long): Float`
  - `internal fun heatmapCellAlpha(listenedMs: Long?, playCount: Int): Float`

- [ ] **Step 1: 写测试 `DayListenPolicyTest.kt`**

```kotlin
package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayListenPolicyTest {

    private fun h(bvid: String, durationSec: Long = 300) =
        PlayHistoryEntity(bvid = bvid, cid = 0, title = bvid, author = "", coverUrl = "",
            durationSec = durationSec, positionMs = 0, playedAt = 0)

    @Test
    fun `有明细时列表就是明细，时长是各行之和`() {
        val view = resolveDayView(
            listened = listOf(DayListenRow(h("BV1a"), 60_000, 2), DayListenRow(h("BV1b"), 30_000, 1)),
            legacyMs = 999_999,
            legacyHistory = listOf(h("BV1z")),
        )
        assertEquals(listOf("BV1a" to 60_000L, "BV1b" to 30_000L), view.rows.map { it.history.bvid to it.listenedMs })
        assertEquals(90_000L, view.totalMs)
    }

    @Test
    fun `没有明细时回到播放历史口径，时长取旧的按天合计`() {
        val view = resolveDayView(listened = emptyList(), legacyMs = 120_000, legacyHistory = listOf(h("BV1z")))
        assertEquals(listOf(DayRow(h("BV1z"), null)), view.rows)
        assertEquals(120_000L, view.totalMs)
    }

    @Test
    fun `两样都没有时显示未统计`() {
        val view = resolveDayView(listened = emptyList(), legacyMs = null, legacyHistory = listOf(h("BV1z")))
        assertNull(view.totalMs)
    }

    @Test
    fun `日合计以明细为准，没有明细的日子取旧合计`() {
        assertEquals(
            mapOf(1L to 50L, 2L to 70L, 3L to 9L),
            mergeDayTotals(
                entries = listOf(DayMs(1, 50), DayMs(2, 70)),
                legacy = listOf(DayMs(2, 999), DayMs(3, 9)),
            ),
        )
    }
}
```

- [ ] **Step 2: 在 `HeatmapLevelTest.kt` 末尾加分钟分档测试**

```kotlin
    private fun min(m: Long) = m * 60_000

    @Test
    fun `按收听分钟分档`() {
        assertEquals(0f, heatmapListenAlpha(0))
        assertEquals(0.2f, heatmapListenAlpha(5_000))
        assertEquals(0.2f, heatmapListenAlpha(min(9) + 59_999))
        assertEquals(0.4f, heatmapListenAlpha(min(10)))
        assertEquals(0.4f, heatmapListenAlpha(min(29)))
        assertEquals(0.6f, heatmapListenAlpha(min(30)))
        assertEquals(0.6f, heatmapListenAlpha(min(59)))
        assertEquals(1.0f, heatmapListenAlpha(min(60)))
    }

    @Test
    fun `有收听时长按分钟，没有才按播放个数`() {
        assertEquals(0.4f, heatmapCellAlpha(listenedMs = min(15), playCount = 9))
        assertEquals(0f, heatmapCellAlpha(listenedMs = 0, playCount = 9))
        assertEquals(1.0f, heatmapCellAlpha(listenedMs = null, playCount = 9))
        assertEquals(0f, heatmapCellAlpha(listenedMs = null, playCount = 0))
    }
```

- [ ] **Step 3: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.DayListenPolicyTest" --tests "com.biliseek.app.ui.HeatmapLevelTest"`
Expected: FAIL（符号未定义）

- [ ] **Step 4: 实现 `DayListenPolicy.kt`**

```kotlin
package com.biliseek.app.data

import androidx.room.Embedded

// 日播放记录页与热力图的口径（设计稿 2026-10-07-listen-entry）：有按视频的收听明细（listen_entry）就用明细，
// 列表、当日时长、热力图深浅出自同一份数据；10-07 以前的日子没有明细，回退到 listen_day / play_history。

/** listen_entry JOIN play_history 的一行：这个视频这天听了 [listenedMs]，最后一次在 [lastListenedAt]。 */
data class DayListenRow(
    @Embedded val history: PlayHistoryEntity,
    val listenedMs: Long,
    val lastListenedAt: Long,
)

/** 某天（`LocalDate.toEpochDay()`）的毫秒合计。 */
data class DayMs(val epochDay: Long, val ms: Long)

/** 日页的一行；[listenedMs] 为 null 表示回退口径，显示视频完整时长。 */
data class DayRow(val history: PlayHistoryEntity, val listenedMs: Long?)

/** 日页内容；[totalMs] 为 null 显示「未统计」。 */
data class DayView(val rows: List<DayRow>, val totalMs: Long?)

/** [listened] 已按最后收听时间倒序。有明细用明细；否则列表用 [legacyHistory]、时长用 [legacyMs]。 */
fun resolveDayView(
    listened: List<DayListenRow>,
    legacyMs: Long?,
    legacyHistory: List<PlayHistoryEntity>,
): DayView =
    if (listened.isNotEmpty()) {
        DayView(listened.map { DayRow(it.history, it.listenedMs) }, listened.sumOf { it.listenedMs })
    } else {
        DayView(legacyHistory.map { DayRow(it, null) }, legacyMs)
    }

/** 每天的收听合计：有明细的日子用明细之和，其余用 listen_day 的旧合计。 */
fun mergeDayTotals(entries: List<DayMs>, legacy: List<DayMs>): Map<Long, Long> =
    legacy.associate { it.epochDay to it.ms } + entries.associate { it.epochDay to it.ms }
```

- [ ] **Step 5: 在 `HeatmapPolicy.kt` 末尾加分档函数**，并把文件头注释「色阶见 HomeScreen 的 heatmapLevelAlpha」改成「色阶见下方 [heatmapCellAlpha]」

```kotlin
/** 按当天收听时长分档：0 不涂；不足 10 分钟 0.2；10–29 分钟 0.4；30–59 分钟 0.6；60 分钟及以上 1.0。 */
internal fun heatmapListenAlpha(listenedMs: Long): Float {
    val minutes = listenedMs / 60_000
    return when {
        listenedMs <= 0 -> 0f
        minutes < 10 -> 0.2f
        minutes < 30 -> 0.4f
        minutes < 60 -> 0.6f
        else -> 1.0f
    }
}

/** 有收听时长（[listenedMs] 非 null）按分钟分档；10-04 以前没有时长的日子按播放个数（[heatmapLevelAlpha]）。 */
internal fun heatmapCellAlpha(listenedMs: Long?, playCount: Int): Float =
    if (listenedMs != null) heatmapListenAlpha(listenedMs) else heatmapLevelAlpha(playCount)
```

- [ ] **Step 6: 跑测试确认通过**（同 Step 3 命令，Expected: PASS）

---

### Task 3: `listen_entry` 表、DAO、迁移 9→10、仓库方法

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/Entities.kt`（`ListenDayEntity` 之后）
- Modify: `app/src/main/java/com/biliseek/app/data/Daos.kt`（`ListenDayDao` 加旧合计查询；新增 `ListenEntryDao`）
- Modify: `app/src/main/java/com/biliseek/app/data/BiliSeekDatabase.kt`（entities、version 10、`listenEntryDao()`、`MIGRATION_9_10`、`addMigrations`）
- Modify: `app/src/main/java/com/biliseek/app/data/LibraryRepository.kt`
- Create（构建生成）: `app/schemas/com.biliseek.app.data.BiliSeekDatabase/10.json`
- Test: `app/src/androidTest/java/com/biliseek/app/data/MigrationTest.kt`

**Interfaces:**
- Consumes: `ListenSlice`（Task 1）、`DayListenRow` / `DayMs` / `mergeDayTotals`（Task 2）
- Produces:
  - `LibraryRepository.addListenedTime(slice: ListenSlice)`（替换旧的 `addListenedTime(epochDay, ms)`）
  - `LibraryRepository.observeDayListen(epochDay: Long): Flow<List<DayListenRow>>`
  - `LibraryRepository.observeListenTotalsSince(fromEpochDay: Long): Flow<Map<Long, Long>>`
  - `observeListenedMs(epochDay)` 保留（回退用）

- [ ] **Step 1: 写迁移测试**（`MigrationTest.kt`，`migrate8To9` 之后）

```kotlin
    @Test
    fun migrate9To10_新增按视频的收听表且不动旧合计() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL("INSERT INTO listen_day (epochDay, listenedMs) VALUES (20365, 70000)")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, BiliSeekDatabase.MIGRATION_9_10)

        db.query("SELECT listenedMs FROM listen_day WHERE epochDay = 20365").use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移后旧的按天合计丢失", 70000L, cursor.getLong(0))
        }
        // 与 ListenEntryDao 同样的两步累加
        repeat(2) {
            db.execSQL("INSERT OR IGNORE INTO listen_entry (epochDay, bvid, cid, listenedMs, lastListenedAt) VALUES (20370, 'BV1x', 0, 0, 0)")
            db.execSQL("UPDATE listen_entry SET listenedMs = listenedMs + 30000, lastListenedAt = ${it + 1} WHERE epochDay = 20370 AND bvid = 'BV1x' AND cid = 0")
        }
        db.query("SELECT listenedMs, lastListenedAt FROM listen_entry").use { cursor ->
            cursor.moveToFirst()
            assertEquals("同一天同一视频没累加上", 60000L, cursor.getLong(0))
            assertEquals(2L, cursor.getLong(1))
        }
    }
```

- [ ] **Step 2: 跑编译确认失败**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: FAIL（`MIGRATION_9_10` 未定义）

- [ ] **Step 3: 实体**（`Entities.kt`，`ListenDayEntity` 之后；并把 `ListenDayEntity` 的 KDoc 补一句「v10 起停止写入，只给 10-07 以前的日子回退用，见 [ListenEntryEntity]」）

```kotlin
/**
 * 某个视频某天的收听时长（v10）：日播放记录页的列表、当日听歌时长、热力图深浅都出自它。
 * (bvid, cid) 与 `play_history` 同口径，展示用的标题、封面等 JOIN `play_history` 取。
 */
@Entity(tableName = "listen_entry", primaryKeys = ["epochDay", "bvid", "cid"])
data class ListenEntryEntity(
    /** 本地日期（`LocalDate.toEpochDay()`）。 */
    val epochDay: Long,
    val bvid: String,
    val cid: Long,
    /** 这天累计的收听毫秒数。 */
    val listenedMs: Long,
    /** 这天最后一次计入的墙钟毫秒（列表排序用）。 */
    val lastListenedAt: Long,
)
```

- [ ] **Step 4: DAO**（`Daos.kt`：`ListenDayDao` 里加 `observeSince`；文件末尾新增 `ListenEntryDao`）

```kotlin
    /** [fromEpochDay] 起每天的旧合计（热力图回退用）。 */
    @Query("SELECT epochDay, listenedMs AS ms FROM listen_day WHERE epochDay >= :fromEpochDay")
    fun observeSince(fromEpochDay: Long): Flow<List<DayMs>>
```

```kotlin
/** 按视频、按天的收听时长。累加同 [ListenDayDao]：两步，由仓库包在事务里。 */
@Dao
interface ListenEntryDao {

    @Query(
        "INSERT OR IGNORE INTO listen_entry (epochDay, bvid, cid, listenedMs, lastListenedAt) " +
            "VALUES (:epochDay, :bvid, :cid, 0, 0)",
    )
    suspend fun ensure(epochDay: Long, bvid: String, cid: Long)

    @Query(
        "UPDATE listen_entry SET listenedMs = listenedMs + :ms, lastListenedAt = :at " +
            "WHERE epochDay = :epochDay AND bvid = :bvid AND cid = :cid",
    )
    suspend fun add(epochDay: Long, bvid: String, cid: Long, ms: Long, at: Long)

    /** 某天听过的视频，最后听的在前。 */
    @Query(
        """
        SELECT h.*, e.listenedMs AS listenedMs, e.lastListenedAt AS lastListenedAt
        FROM listen_entry e JOIN play_history h ON h.bvid = e.bvid AND h.cid = e.cid
        WHERE e.epochDay = :epochDay
        ORDER BY e.lastListenedAt DESC
        """,
    )
    fun observeDay(epochDay: Long): Flow<List<DayListenRow>>

    /** [fromEpochDay] 起每天的合计。 */
    @Query("SELECT epochDay, SUM(listenedMs) AS ms FROM listen_entry WHERE epochDay >= :fromEpochDay GROUP BY epochDay")
    fun observeTotalsSince(fromEpochDay: Long): Flow<List<DayMs>>
}
```

- [ ] **Step 5: 数据库**（`BiliSeekDatabase.kt`）：entities 末尾加 `ListenEntryEntity::class`；`version = 10`；加 `abstract fun listenEntryDao(): ListenEntryDao`；`addMigrations(…, MIGRATION_8_9, MIGRATION_9_10)`；`MIGRATION_8_9` 之后加：

```kotlin
        /**
         * v9 → v10：按视频、按天记收听时长（listen_entry）。只建表；listen_day 保留给 10-07 以前的日子回退。
         * 建表语句与导出的 `10.json` 一致。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listen_entry` (`epochDay` INTEGER NOT NULL, `bvid` TEXT NOT NULL, " +
                        "`cid` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, `lastListenedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`epochDay`, `bvid`, `cid`))",
                )
            }
        }
```

- [ ] **Step 6: 仓库**（`LibraryRepository.kt`：替换 `addListenedTime`，加两个 observe；需要 `import kotlinx.coroutines.flow.combine`）

```kotlin
    private val listenEntries = db.listenEntryDao()

    /** 累计一段真实收听时长到这个视频这一天（[ListenTimeTracker] 调）。 */
    suspend fun addListenedTime(slice: ListenSlice) {
        if (slice.ms <= 0) return
        db.withTransaction {
            listenEntries.ensure(slice.epochDay, slice.key.bvid, slice.key.cid)
            listenEntries.add(slice.epochDay, slice.key.bvid, slice.key.cid, slice.ms, slice.endWallMs)
        }
    }

    /** 某天听过的视频（JOIN play_history），最后听的在前。 */
    fun observeDayListen(epochDay: Long): Flow<List<DayListenRow>> = listenEntries.observeDay(epochDay)

    /** [fromEpochDay] 起每天的收听合计：有明细用明细，否则用 listen_day 的旧合计。 */
    fun observeListenTotalsSince(fromEpochDay: Long): Flow<Map<Long, Long>> =
        combine(listenEntries.observeTotalsSince(fromEpochDay), listenDays.observeSince(fromEpochDay), ::mergeDayTotals)
```

- [ ] **Step 7: 编译（生成 10.json）并核对**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL；`app/schemas/com.biliseek.app.data.BiliSeekDatabase/10.json` 生成，其中 `listen_entry` 的 `createSql` 与 `MIGRATION_9_10` 的列、主键一致（`grep -A3 '"tableName": "listen_entry"' …/10.json`）。

---

### Task 4: 接线——计时器、日页、热力图

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/ListenTimeTracker.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（`heatmap`、`dayHistory`、`dayListenedMs`、`listenTimeTracker`）
- Modify: `app/src/main/java/com/biliseek/app/ui/HomeScreen.kt`（热力图入参改为 `Map<LocalDate, Float>` 的深浅）
- Modify: `app/src/main/java/com/biliseek/app/ui/DayHistoryScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`DayHistoryScreen` 调用处）

**Interfaces:**
- Consumes: Task 1–3 的全部产物。
- Produces: `AppViewModel.dayView: StateFlow<DayView>`（替换 `dayHistory` + `dayListenedMs`）；`AppViewModel.heatmap: StateFlow<Map<LocalDate, Float>>`（值为深浅 alpha）。

- [ ] **Step 1: 计时器**（`ListenTimeTracker.kt`）：入参改为 `playing: Flow<ListenKey?>`（null = 没在播或没有 bvid），`record: suspend (ListenSlice) -> Unit`；`collect` 体改为：

```kotlin
            playing.distinctUntilChanged().collect { key ->
                if (key != null) {
                    save(clock.start(key, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
                    if (ticker == null) {
                        ticker = scope.launch {
                            while (true) {
                                delay(FLUSH_INTERVAL_MS)
                                save(clock.tick(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
                            }
                        }
                    }
                } else {
                    ticker?.cancel()
                    ticker = null
                    save(clock.stop(SystemClock.elapsedRealtime()))
                }
            }
```

`private suspend fun save(slices: List<ListenSlice>)`；类 KDoc 补「换视频时把前一段记给旧视频」。

- [ ] **Step 2: ViewModel**（`AppViewModel.kt`）

```kotlin
    private val listenTimeTracker = ListenTimeTracker(
        scope = viewModelScope,
        playing = connection.state.map { s ->
            if (s.isPlaying && s.bvid.isNotBlank()) ListenKey(s.bvid, s.cid) else null
        },
        record = { library.addListenedTime(it) },
    )
```

`dayHistory` 与 `dayListenedMs` 合并为：

```kotlin
    /** 日历史页内容：有按视频的收听明细用明细，否则回退到播放历史 + 旧的按天合计（[resolveDayView]）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayView: StateFlow<DayView> = _dayHistoryDate
        .flatMapLatest { date ->
            if (date == null) flowOf(DayView(emptyList(), null))
            else {
                val zone = ZoneId.systemDefault()
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                combine(
                    library.observeDayListen(date.toEpochDay()),
                    library.observeListenedMs(date.toEpochDay()),
                    library.observeDayHistory(start, start + 24 * 3600 * 1000L),
                    ::resolveDayView,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayView(emptyList(), null))
```

`heatmap` 改为（KDoc 补「深浅：有收听时长按分钟，否则按播放个数」）：

```kotlin
    val heatmap: StateFlow<Map<LocalDate, Float>> = run {
        val zone = ZoneId.systemDefault()
        val since = System.currentTimeMillis() - HEATMAP_DAYS * 24 * 3600 * 1000L
        val fromDay = Instant.ofEpochMilli(since).atZone(zone).toLocalDate().toEpochDay()
        combine(
            library.observeHistorySince(since),
            library.observeListenTotalsSince(fromDay),
        ) { list, totals ->
            val counts = list.groupingBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }.eachCount()
            (counts.keys + totals.keys.map(LocalDate::ofEpochDay)).associateWith { date ->
                heatmapCellAlpha(totals[date.toEpochDay()], counts[date] ?: 0)
            }.filterValues { it > 0f }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
```

`HEATMAP_DAYS` 在 `heatmap` 之前定义（若在之后，`run` 里引用会在初始化前读到 0；检查后按需挪动）。`heatmapCellAlpha` 是 `ui` 包的 `internal` 函数，同模块可直接 import `com.biliseek.app.ui.heatmapCellAlpha`。

- [ ] **Step 3: 首页热力图**（`HomeScreen.kt`）：`heatmap: Map<LocalDate, Int>` → `heatmap: Map<LocalDate, Float>`；`WeeklyHeatGrid` 的 `counts: Map<LocalDate, Int>` → `levels: Map<LocalDate, Float>`；格子里 `val alpha = levels[date] ?: 0f`（去掉 `heatmapLevelAlpha(count)` 调用；`heatmapLevelAlpha` 本身保留，`heatmapCellAlpha` 回退时用）。`heatmapLevelAlpha` 的 KDoc 改为「播放次数分档：10-04 以前没有收听时长的日子用」。

- [ ] **Step 4: 日页**（`DayHistoryScreen.kt`）：参数 `entries` + `listenedMs` → `view: DayView`，`onEntryClick: (PlayHistoryEntity) -> Unit` 不变；空判断用 `view.rows.isEmpty()`；时长 `view.totalMs?.let { formatDuration(it) } ?: "未统计"`；「共 ${view.rows.size} 首」；`items(view.rows, key = { it.history.id })`，行内用 `row.history`，副标题：

```kotlin
                            (entry.author.ifBlank { "" }) +
                                (if (entry.author.isNotBlank()) " · " else "") +
                                (row.listenedMs?.let { "听了 ${formatDuration(it)}" }
                                    ?: formatDuration(entry.durationSec * 1000)),
```

KDoc 改写：列表与时长出自同一份按视频的收听明细；10-07 以前回退。

- [ ] **Step 5: MainActivity**：`dayHistory` / `dayListenedMs` 的 `collectAsState` 换成 `val dayView by viewModel.dayView.collectAsState()`，调用改 `DayHistoryScreen(date = dayDate, view = dayView, onClose = …, onEntryClick = …)`。

- [ ] **Step 6: 全量验证**

Run: `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL，全部单测通过。再 `grep -rn "dayListenedMs\|dayHistory\b\|addListenedTime(it.epochDay" app/src/main` 确认没有残留引用。

---

### Task 5: 文档与记忆

**Files:**
- Modify: `docs/05-功能优化篇.md`（新增 §13.68 + 总览行；§10.0 删掉「待用户决定」那条；§13.40 末尾后续说明改为「§13.68 解决」；测试数按新的 `@Test` 计数刷新）
- Modify: `docs/03-架构设计篇.md` §6.2（落库表加 `listen_entry`，`listen_day` 注明停止写入；DB v10）
- Modify: `docs/01-开发环境搭建.md`（schema `1.json`–`10.json`、version 10、测试数）
- Modify: `docs/BiliSeek-手动测试指南.md` 步骤 14（同一视频隔天重播两天都在；各行「听了」之和 = 顶部时长；热力图深浅随收听分钟变化；附录 3 删掉「日播放记录列表和当日时长可能对不上」）
- Modify: memory `listen-time-heatmap-pending.md`

- [ ] **Step 1**：按上面逐份修改（§13.68 写起因、做法、验证、未验证，格式照 §13.67）。
- [ ] **Step 2**：`grep -rn "version 9\|9.json\|对不上" docs/01-开发环境搭建.md docs/03-架构设计篇.md docs/BiliSeek-手动测试指南.md` 确认无遗漏。
