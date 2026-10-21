# 首页快捷入口第二期（分区入口）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 时光姬式分区入口：首页「分区」总入口 → 分区总览（时间范围 + 5 大类 22 子分区）→ 分区详情（子分区标签左右滑、排序、隐藏短视频、无限滚动、续页播放）；22 个子分区也可单独作为快捷入口（默认关）。

**Architecture:** 网络层加 `newlist_rank`（SEARCH 限速桶，不签名），结果复用 `ChartItem`，包成公开的 `RegionPage`。`app/region/` 放分区清单（数据）、`RegionPolicy`（时间范围、设置解析、过滤、翻页的纯函数）与 `RegionController`（内存状态 + 加载 + 播放）；设置（时间、排序、短视频开关）存 DataStore。`QuickEntry` 从 enum 改为 sealed interface（`FixedQuickEntry` enum + `RegionQuickEntry`），每个入口带出厂默认开关。页面挂在首页这一栏（同榜单页）。

**Tech Stack:** Kotlin、Jetpack Compose Material3（`HorizontalPager`、`ScrollableTabRow`、`DateRangePicker`、`ModalBottomSheet`）、Retrofit + kotlinx.serialization、DataStore、JUnit4。

**Spec:** `docs/superpowers/specs/2026-10-21-region-entries-design.md`（前置第一期：`docs/superpowers/specs/2026-10-21-home-quick-entries-design.md`）

## Global Constraints

- **测试绝不请求 B 站服务器**；只用离线样例 `core/network/src/test/resources/newlist_rank_sample.json`（已提交，cate_id=28 真实返回裁剪成 3 条：BV18rn36iEBq 64 秒 play "2134234"、BV1PaJB6eEeM 16 秒、BV15GJQ6REdA 288 秒；numPages 337，page 1）。
- `newlist_rank` 不签名；走 **SEARCH 限速桶**（`SEARCH_MIN_INTERVAL_MS` 800ms + 抖动）+ 冷却 + -352/-412 分流；**不重试**；`result` 为 null/空 → 空页。
- 固定参数：`main_ver=v3`、`search_type=video`、`view_type=hot_rank`、`copy_right=-1`、`new_web_tag=1`、`pagesize=20`；时间 `yyyyMMdd`。
- 排序：播放 `click`（默认）、收藏 `stow`、硬币 `coin`、弹幕 `dm`、评论 `scores`。
- 时间：最近 7 天 = 今天往前 6 天到今天（每次按当天重算）；按月份 = 当月 1 日～月末（本月到今天）；自定义最长 30 天（含首尾）、最早 2009-01-01、最晚今天。
- 短视频阈值：时长 < 60 秒；只在显示时过滤，开关切换不重新请求。
- 存储键：`region_time_mode`（`LAST_7_DAYS`/`MONTH`/`CUSTOM`）、`region_time_from`、`region_time_to`（`yyyyMMdd`）、`region_order`（`click` 等）、`region_hide_short`（默认 true）。
- 分区清单（v1 tid，顺序即显示顺序）：音乐：原创音乐 28、翻唱 31、演奏 59、VOCALOID·UTAU 30、音乐现场 29、乐评盘点 243、电台 267、音乐综合 130；知识：人文历史 228、社科·法律·心理 124、科学科普 201、财经商业 207、职业职场 209、校园学习 208；纪录片：人文·历史 37、科学·探索·自然 178、社会·美食·旅行 180；杂谈：影视杂谈 182、娱乐杂谈 241、动漫杂谈 253；配音与广播剧：动态漫·广播剧 195、配音 257。
- 图标（Outlined）：音乐 `LibraryMusic`、知识 `School`、纪录片 `Movie`、杂谈 `Forum`、配音与广播剧 `RecordVoiceOver`；总入口「分区」`Category`。
- 快捷入口：总入口「分区」id `regions`，默认开；子分区 id `region:<tid>`，默认关；出厂顺序 = 第一期 7 个 → `regions` → 22 个子分区（清单顺序）；存的里没有的入口按**各自默认值**追加到末尾。
- 分区列表只在内存，不落库。列表底部 `miniBarContentPadding()`；提示用 `AppToast`/`transientMessage`；底部弹层用 `CompactDragHandle`。
- 中文注释，风格与周围代码一致。worktree `/home/minifive/own_work/BiliSeek-home-quick-entries`（分支 `feat/home-quick-entries`）上开发，每个任务完成后直接提交；只 `git add` 本任务改动的文件，不用 `git add -p`。提交信息末尾加 Co-Authored-By 行。
- Android 库模块单测命令是 `:<module>:testDebugUnitTest`（不是 `:test --tests`）。全量验证：`./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`。不装机。

---

### Task 1: 网络层——`newlist_rank` 接口、DTO、解析、仓库读

**Files:**
- Create: `core/network/src/main/java/com/biliseek/core/network/dto/RegionDto.kt`
- Create: `core/network/src/main/java/com/biliseek/core/network/internal/RegionParsePolicy.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt`（加 `RegionPage`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`（加 `newlistRank`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（加 `regionVideos`、`regionRead`）
- Test: `core/network/src/test/java/com/biliseek/core/network/RegionParsePolicyTest.kt`

**Interfaces:**
- Produces:
  - `data class RegionPage(val items: List<ChartItem>, val page: Int, val totalPages: Int)`（`com.biliseek.core.network`，public）
  - `suspend fun BiliRepository.regionVideos(tid: Int, order: String, page: Int, timeFrom: String, timeTo: String): Result<RegionPage>`

- [ ] **Step 1: 写失败的测试**

`core/network/src/test/java/com/biliseek/core/network/RegionParsePolicyTest.kt`：

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.NewlistRankData
import com.biliseek.core.network.internal.toRegionPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `newlist_rank`（时光机式分区列表）解析。真实样例取自 10-21 用户授权的一次请求（cate_id=28），裁剪成 3 条。 */
class RegionParsePolicyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("newlist_rank_sample.json")) { "缺少测试资源" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `真实样例_字段映射与页数`() {
        val r = json.decodeFromString<BiliResponse<NewlistRankData>>(sample())
        val page = r.data.toRegionPage(requestedPage = 1)
        assertEquals(1, page.page)
        assertEquals(337, page.totalPages)
        assertEquals(listOf("BV18rn36iEBq", "BV1PaJB6eEeM", "BV15GJQ6REdA"), page.items.map { it.bvid })
        val first = page.items[0]
        assertEquals("重阳节，以此摇滚，缅怀逝去的姐妹。", first.title)
        assertEquals("桂香GuiXiang", first.author)
        assertEquals(1920927277L, first.authorMid)
        assertEquals(64L, first.durationSec)
        assertEquals(2134234L, first.playCount)
        assertNull(first.rank)
        assertEquals("https://i0.hdslb.com/bfs/archive/d4c65f30bac48928fd966eb75cd94ca712db77ed.jpg", first.coverUrl)
    }

    @Test
    fun `play不是数字_播放量为null_没有bvid的跳过`() {
        val r = json.decodeFromString<BiliResponse<NewlistRankData>>(
            """
            {"code":0,"data":{"numPages":3,"page":2,"result":[
              {"bvid":"","title":"失效"},
              {"bvid":"BV1xxxxxxxxx","title":"t","play":"--","duration":120}
            ]}}
            """.trimIndent(),
        )
        val page = r.data.toRegionPage(requestedPage = 2)
        assertEquals(listOf("BV1xxxxxxxxx"), page.items.map { it.bvid })
        assertNull(page.items[0].playCount)
        assertEquals(2, page.page)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun `result为null或data为null_空页_页码用请求的`() {
        val r = json.decodeFromString<BiliResponse<NewlistRankData>>("""{"code":0,"data":{"result":null,"numPages":0}}""")
        val page = r.data.toRegionPage(requestedPage = 4)
        assertEquals(emptyList<ChartItem>(), page.items)
        assertEquals(4, page.page)
        assertEquals(0, page.totalPages)
        assertEquals(RegionPage(emptyList(), 1, 0), null.toRegionPage(requestedPage = 1))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.RegionParsePolicyTest"`
Expected: 编译失败，`Unresolved reference: NewlistRankData`。

- [ ] **Step 3: 实现 DTO、模型、解析**

`dto/RegionDto.kt`：

```kotlin
package com.biliseek.core.network.dto

import kotlinx.serialization.Serializable

// ── 分区列表（时光机式，`x/web-interface/newlist_rank`）──────────────
// 后台是搜索服务（返回 `rqt_type: "search"`）。字段按 10-21 真实返回整理，只取用得到的，全部带默认值。

/** `newlist_rank` 的 data。`result` 可能为 null（没有结果）。 */
@Serializable
data class NewlistRankData(
    val result: List<NewlistRankVideo>? = null,
    val page: Int = 0,
    val numPages: Int = 0,
    val numResults: Int = 0,
)

@Serializable
data class NewlistRankVideo(
    val bvid: String = "",
    val title: String = "",
    /** `//` 开头的封面地址。 */
    val pic: String = "",
    val author: String = "",
    val mid: Long = 0,
    /** 秒。 */
    val duration: Long = 0,
    /** 播放量，接口给的是**字符串**（如 "2134234"）。 */
    val play: String = "",
)
```

`ChartModels.kt` 末尾加：

```kotlin
/** 分区列表的一页（`newlist_rank`）：条目复用榜单行模型，没有名次。 */
data class RegionPage(
    val items: List<ChartItem>,
    /** 这一页的页码（第 1 页起）。 */
    val page: Int,
    /** 总页数；没有结果为 0。 */
    val totalPages: Int,
)
```

`internal/RegionParsePolicy.kt`：

```kotlin
package com.biliseek.core.network.internal

import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.RegionPage
import com.biliseek.core.network.dto.NewlistRankData
import com.biliseek.core.network.dto.NewlistRankVideo
import com.biliseek.core.network.normalizeCoverUrl

/** `newlist_rank` 的 data → 一页。data / result 为 null 是空页；接口没给页码时用请求的页码。 */
internal fun NewlistRankData?.toRegionPage(requestedPage: Int): RegionPage = RegionPage(
    items = this?.result.orEmpty().mapNotNull { it.toChartItem() },
    page = this?.page?.takeIf { it > 0 } ?: requestedPage,
    totalPages = this?.numPages ?: 0,
)

/** 没有 bvid（已失效）的跳过；播放量字符串不是数字时为 null。 */
internal fun NewlistRankVideo.toChartItem(): ChartItem? =
    if (bvid.isBlank()) {
        null
    } else {
        ChartItem(
            bvid = bvid,
            title = title,
            coverUrl = normalizeCoverUrl(pic),
            author = author,
            durationSec = duration,
            playCount = play.toLongOrNull()?.takeIf { it > 0 },
            rank = null,
            authorMid = mid,
        )
    }
```

- [ ] **Step 4: 接口与仓库**

`BiliApi.kt`（`rankingV2` 之后；dto import 加 `NewlistRankData`）：

```kotlin
    /**
     * 分区列表（时光机式）：某个**旧版（v1）分区号**在 [timeFrom]–[timeTo]（`yyyyMMdd`）之间的投稿，按 [order] 排序。
     * 后台是搜索服务；10-21 实测不签名、不带 Cookie 返回正常。`pagesize` 必填。
     */
    @GET("x/web-interface/newlist_rank")
    suspend fun newlistRank(
        @Query("cate_id") cateId: Int,
        @Query("order") order: String,
        @Query("page") page: Int,
        @Query("pagesize") pageSize: Int,
        @Query("time_from") timeFrom: String,
        @Query("time_to") timeTo: String,
        @Query("main_ver") mainVer: String = "v3",
        @Query("search_type") searchType: String = "video",
        @Query("view_type") viewType: String = "hot_rank",
        @Query("copy_right") copyRight: Int = -1,
        @Query("new_web_tag") newWebTag: Int = 1,
    ): BiliResponse<NewlistRankData>
```

`BiliRepository.kt`，在 `chartRead` 之后加（import `com.biliseek.core.network.internal.toRegionPage`）：

```kotlin
    // ── 分区列表（时光机式）──────────────────────────────

    /**
     * 分区列表的一页：旧版分区号 [tid]、排序 [order]（`click` / `stow` / `coin` / `dm` / `scores`）、
     * 时间 [timeFrom]–[timeTo]（`yyyyMMdd`）。每页 [REGION_PAGE_SIZE] 条。走 [regionRead]；没有结果是空页，**不重试**。
     */
    suspend fun regionVideos(
        tid: Int,
        order: String,
        page: Int,
        timeFrom: String,
        timeTo: String,
    ): Result<RegionPage> = runCatching {
        regionRead("分区列表") {
            api.newlistRank(
                cateId = tid,
                order = order,
                page = page,
                pageSize = REGION_PAGE_SIZE,
                timeFrom = timeFrom,
                timeTo = timeTo,
            )
        }.toRegionPage(requestedPage = page)
    }

    /**
     * 分区列表的读出口：后台是搜索服务，套 SEARCH 限速桶（与搜索共用间隔）；风控分流同 [accountRead]
     * （冷却期内本地快速失败，-352 记入冷却且绝不重试，-412 重置设备凭据）。**不加重试循环。**
     */
    private suspend fun <T> regionRead(what: String, request: suspend () -> BiliResponse<T>): T? =
        accountRead(what) {
            requestThrottle.gate(ThrottleBucket.SEARCH, SEARCH_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) { request() }
        }
```

`companion object` 里（`MAX_CARD_IDS` 之后）加：

```kotlin
        /** 分区列表每页条数（`newlist_rank` 的 `pagesize`）。 */
        const val REGION_PAGE_SIZE = 20
```

（`BiliRepository` 的 companion 是 `private companion object` 还是 `companion object`：看实际声明，常量放在 `PODCAST_ERA_URL` 所在的那个 companion。）

- [ ] **Step 5: 跑测试**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部通过。

- [ ] **Step 6: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/RegionDto.kt \
  core/network/src/main/java/com/biliseek/core/network/internal/RegionParsePolicy.kt \
  core/network/src/main/java/com/biliseek/core/network/ChartModels.kt \
  core/network/src/main/java/com/biliseek/core/network/BiliApi.kt \
  core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt \
  core/network/src/test/java/com/biliseek/core/network/RegionParsePolicyTest.kt
git commit -m "feat(network): 分区列表 newlist_rank（SEARCH 限速桶，不签名）"
```

---

### Task 2: 分区清单与 RegionPolicy（纯函数）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/region/RegionCatalog.kt`
- Create: `app/src/main/java/com/biliseek/app/region/RegionPolicy.kt`
- Create: `app/src/test/java/com/biliseek/app/region/RegionPolicyTest.kt`

**Interfaces:**
- Produces（`com.biliseek.app.region`）：
  - `enum class RegionGroup(val title: String)`：`MUSIC("音乐")`、`KNOWLEDGE("知识")`、`DOCUMENTARY("纪录片")`、`TALK("杂谈")`、`VOICE("配音与广播剧")`
  - `data class SubRegion(val tid: Int, val name: String, val group: RegionGroup)`
  - `object RegionCatalog { val all: List<SubRegion>; fun inGroup(group: RegionGroup): List<SubRegion>; fun byTid(tid: Int): SubRegion? }`
  - `enum class RegionOrder(val param: String, val label: String)`：`CLICK("click","播放")`、`STOW("stow","收藏")`、`COIN("coin","硬币")`、`DM("dm","弹幕")`、`SCORES("scores","评论")`
  - `enum class RegionTimeMode { LAST_7_DAYS, MONTH, CUSTOM }`
  - `data class DateSpan(val from: LocalDate, val to: LocalDate)`
  - `data class RegionSettings(val timeMode: RegionTimeMode = LAST_7_DAYS, val savedSpan: DateSpan? = null, val order: RegionOrder = CLICK, val hideShort: Boolean = true)`
  - `fun RegionSettings.span(today: LocalDate): DateSpan`
  - `fun lastSevenDays(today: LocalDate): DateSpan`
  - `fun monthSpan(year: Int, month: Int, today: LocalDate): DateSpan`
  - `enum class CustomSpanError { REVERSED, TOO_LONG, TOO_EARLY, AFTER_TODAY }` + `fun validateCustomSpan(span: DateSpan, today: LocalDate): CustomSpanError?`
  - `fun formatApiDate(date: LocalDate): String`、`fun parseApiDate(text: String?): LocalDate?`
  - `fun formatSpanLabel(span: DateSpan, today: LocalDate): String`
  - `fun resolveRegionSettings(mode: String?, from: String?, to: String?, order: String?, hideShort: Boolean?): RegionSettings`
  - `fun visibleRegionItems(items: List<ChartItem>, hideShort: Boolean): List<ChartItem>`
  - `fun appendUnseenRegionItems(existing: List<ChartItem>, incoming: List<ChartItem>): List<ChartItem>`
  - `fun regionHasMore(page: Int, totalPages: Int, received: Int): Boolean`
  - `const val SHORT_VIDEO_SECONDS = 60L`、`val REGION_EARLIEST: LocalDate`（2009-01-01）、`const val CUSTOM_SPAN_MAX_DAYS = 30L`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/biliseek/app/region/RegionPolicyTest.kt`：

```kotlin
package com.biliseek.app.region

import com.biliseek.core.network.ChartItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RegionPolicyTest {

    private val today = LocalDate.of(2026, 10, 21)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun item(bvid: String, sec: Long) = ChartItem(
        bvid = bvid, title = bvid, coverUrl = "", author = "", durationSec = sec, playCount = null, rank = null,
    )

    @Test
    fun `清单_5大类22个子分区_顺序与tid`() {
        assertEquals(22, RegionCatalog.all.size)
        assertEquals(
            listOf(28, 31, 59, 30, 29, 243, 267, 130),
            RegionCatalog.inGroup(RegionGroup.MUSIC).map { it.tid },
        )
        assertEquals(listOf(228, 124, 201, 207, 209, 208), RegionCatalog.inGroup(RegionGroup.KNOWLEDGE).map { it.tid })
        assertEquals(listOf(37, 178, 180), RegionCatalog.inGroup(RegionGroup.DOCUMENTARY).map { it.tid })
        assertEquals(listOf(182, 241, 253), RegionCatalog.inGroup(RegionGroup.TALK).map { it.tid })
        assertEquals(listOf(195, 257), RegionCatalog.inGroup(RegionGroup.VOICE).map { it.tid })
        assertEquals("电台", RegionCatalog.byTid(267)?.name)
        assertNull(RegionCatalog.byTid(175))
        assertEquals(RegionCatalog.all.size, RegionCatalog.all.map { it.tid }.toSet().size)
    }

    @Test
    fun `最近7天_含今天往前6天_跨月`() {
        assertEquals(DateSpan(d(2026, 10, 15), today), lastSevenDays(today))
        assertEquals(DateSpan(d(2026, 2, 26), d(2026, 3, 4)), lastSevenDays(d(2026, 3, 4)))
    }

    @Test
    fun `按月份_往月到月末_本月到今天_闰年2月`() {
        assertEquals(DateSpan(d(2026, 9, 1), d(2026, 9, 30)), monthSpan(2026, 9, today))
        assertEquals(DateSpan(d(2026, 10, 1), today), monthSpan(2026, 10, today))
        assertEquals(DateSpan(d(2024, 2, 1), d(2024, 2, 29)), monthSpan(2024, 2, today))
    }

    @Test
    fun `自定义_校验`() {
        assertNull(validateCustomSpan(DateSpan(d(2026, 9, 22), today), today)) // 30 天（含首尾）
        assertEquals(CustomSpanError.TOO_LONG, validateCustomSpan(DateSpan(d(2026, 9, 21), today), today))
        assertEquals(CustomSpanError.REVERSED, validateCustomSpan(DateSpan(today, d(2026, 10, 1)), today))
        assertEquals(CustomSpanError.TOO_EARLY, validateCustomSpan(DateSpan(d(2008, 12, 31), d(2009, 1, 2)), today))
        assertEquals(CustomSpanError.AFTER_TODAY, validateCustomSpan(DateSpan(today, d(2026, 10, 22)), today))
    }

    @Test
    fun `设置的时间范围_最近7天按当天重算_月份与自定义用存的`() {
        assertEquals(lastSevenDays(today), RegionSettings().span(today))
        val saved = DateSpan(d(2026, 9, 1), d(2026, 9, 30))
        assertEquals(saved, RegionSettings(timeMode = RegionTimeMode.MONTH, savedSpan = saved).span(today))
        assertEquals(saved, RegionSettings(timeMode = RegionTimeMode.CUSTOM, savedSpan = saved).span(today))
        // 存的范围丢了：退回最近 7 天
        assertEquals(lastSevenDays(today), RegionSettings(timeMode = RegionTimeMode.CUSTOM, savedSpan = null).span(today))
    }

    @Test
    fun `日期格式`() {
        assertEquals("20261015", formatApiDate(d(2026, 10, 15)))
        assertEquals(d(2026, 10, 15), parseApiDate("20261015"))
        assertNull(parseApiDate("2026-10-15"))
        assertNull(parseApiDate(null))
        assertEquals("10-15 至 10-21", formatSpanLabel(DateSpan(d(2026, 10, 15), today), today))
        assertEquals("2025-12-01 至 2025-12-31", formatSpanLabel(DateSpan(d(2025, 12, 1), d(2025, 12, 31)), today))
    }

    @Test
    fun `读设置_缺失或非法时用默认`() {
        assertEquals(RegionSettings(), resolveRegionSettings(null, null, null, null, null))
        val month = resolveRegionSettings("MONTH", "20260901", "20260930", "stow", false)
        assertEquals(RegionTimeMode.MONTH, month.timeMode)
        assertEquals(DateSpan(d(2026, 9, 1), d(2026, 9, 30)), month.savedSpan)
        assertEquals(RegionOrder.STOW, month.order)
        assertFalse(month.hideShort)
        // 模式要范围、范围却坏了：退回最近 7 天
        assertEquals(RegionTimeMode.LAST_7_DAYS, resolveRegionSettings("CUSTOM", "bad", "20260930", null, null).timeMode)
        assertEquals(RegionOrder.CLICK, resolveRegionSettings(null, null, null, "unknown", null).order)
        assertEquals(RegionTimeMode.LAST_7_DAYS, resolveRegionSettings("WHATEVER", null, null, null, null).timeMode)
    }

    @Test
    fun `短视频过滤_不足60秒去掉_开关关时原样`() {
        val items = listOf(item("a", 59), item("b", 60), item("c", 0), item("d", 300))
        assertEquals(listOf("b", "d"), visibleRegionItems(items, hideShort = true).map { it.bvid })
        assertEquals(items, visibleRegionItems(items, hideShort = false))
    }

    @Test
    fun `翻页追加按bvid去重_还有没有下一页`() {
        val merged = appendUnseenRegionItems(listOf(item("a", 99), item("b", 99)), listOf(item("b", 99), item("c", 99)))
        assertEquals(listOf("a", "b", "c"), merged.map { it.bvid })
        assertTrue(regionHasMore(page = 1, totalPages = 337, received = 20))
        assertFalse(regionHasMore(page = 337, totalPages = 337, received = 20))
        assertFalse(regionHasMore(page = 2, totalPages = 337, received = 0))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.region.RegionPolicyTest"`
Expected: 编译失败，`Unresolved reference: RegionCatalog`。

- [ ] **Step 3: 实现**

`app/src/main/java/com/biliseek/app/region/RegionCatalog.kt`：

```kotlin
package com.biliseek.app.region

// 分区入口的清单（设计稿：docs/superpowers/specs/2026-10-21-region-entries-design.md）。
// 全部是**旧版（v1）分区号**：newlist_rank 只认 v1；取自 bilibili-API-collect 镜像 docs/video/video_zone.md，已下线的排除。
// 只收适合「听」的；「杂谈」「配音与广播剧」是自拟分组（原分属影视 / 娱乐 / 动画 / 国创）。

/** 分区大类（总览页的一张卡片、详情页的一组标签）。声明顺序 = 显示顺序。 */
enum class RegionGroup(val title: String) {
    MUSIC("音乐"),
    KNOWLEDGE("知识"),
    DOCUMENTARY("纪录片"),
    TALK("杂谈"),
    VOICE("配音与广播剧"),
}

/** 一个子分区：[tid] 是 v1 分区号。 */
data class SubRegion(val tid: Int, val name: String, val group: RegionGroup)

object RegionCatalog {
    val all: List<SubRegion> = listOf(
        SubRegion(28, "原创音乐", RegionGroup.MUSIC),
        SubRegion(31, "翻唱", RegionGroup.MUSIC),
        SubRegion(59, "演奏", RegionGroup.MUSIC),
        SubRegion(30, "VOCALOID·UTAU", RegionGroup.MUSIC),
        SubRegion(29, "音乐现场", RegionGroup.MUSIC),
        SubRegion(243, "乐评盘点", RegionGroup.MUSIC),
        SubRegion(267, "电台", RegionGroup.MUSIC),
        SubRegion(130, "音乐综合", RegionGroup.MUSIC),
        SubRegion(228, "人文历史", RegionGroup.KNOWLEDGE),
        SubRegion(124, "社科·法律·心理", RegionGroup.KNOWLEDGE),
        SubRegion(201, "科学科普", RegionGroup.KNOWLEDGE),
        SubRegion(207, "财经商业", RegionGroup.KNOWLEDGE),
        SubRegion(209, "职业职场", RegionGroup.KNOWLEDGE),
        SubRegion(208, "校园学习", RegionGroup.KNOWLEDGE),
        SubRegion(37, "人文·历史", RegionGroup.DOCUMENTARY),
        SubRegion(178, "科学·探索·自然", RegionGroup.DOCUMENTARY),
        SubRegion(180, "社会·美食·旅行", RegionGroup.DOCUMENTARY),
        SubRegion(182, "影视杂谈", RegionGroup.TALK),
        SubRegion(241, "娱乐杂谈", RegionGroup.TALK),
        SubRegion(253, "动漫杂谈", RegionGroup.TALK),
        SubRegion(195, "动态漫·广播剧", RegionGroup.VOICE),
        SubRegion(257, "配音", RegionGroup.VOICE),
    )

    private val byTid: Map<Int, SubRegion> = all.associateBy { it.tid }

    fun inGroup(group: RegionGroup): List<SubRegion> = all.filter { it.group == group }

    fun byTid(tid: Int): SubRegion? = byTid[tid]
}
```

`app/src/main/java/com/biliseek/app/region/RegionPolicy.kt`：

```kotlin
package com.biliseek.app.region

// 分区列表的纯函数（设计稿：docs/superpowers/specs/2026-10-21-region-entries-design.md）：
// 时间范围、设置解析、短视频过滤、翻页。RegionController 只负责状态与请求。

import com.biliseek.core.network.ChartItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** 排序：[param] 是 newlist_rank 的 `order`。声明顺序 = 菜单顺序。 */
enum class RegionOrder(val param: String, val label: String) {
    CLICK("click", "播放"),
    STOW("stow", "收藏"),
    COIN("coin", "硬币"),
    DM("dm", "弹幕"),
    SCORES("scores", "评论"),
}

enum class RegionTimeMode { LAST_7_DAYS, MONTH, CUSTOM }

/** 闭区间 [from]–[to]。 */
data class DateSpan(val from: LocalDate, val to: LocalDate)

/**
 * 分区列表的设置，全部分区共用、存 DataStore。
 * [savedSpan] 只在 [RegionTimeMode.MONTH] / [RegionTimeMode.CUSTOM] 用；最近 7 天每次按当天重算。
 */
data class RegionSettings(
    val timeMode: RegionTimeMode = RegionTimeMode.LAST_7_DAYS,
    val savedSpan: DateSpan? = null,
    val order: RegionOrder = RegionOrder.CLICK,
    val hideShort: Boolean = true,
)

/** 短于这个秒数的算短视频（「隐藏 1 分钟以下」）。 */
const val SHORT_VIDEO_SECONDS = 60L

/** 自定义范围最长天数（含首尾）。 */
const val CUSTOM_SPAN_MAX_DAYS = 30L

/** 自定义范围最早可选的日子。 */
val REGION_EARLIEST: LocalDate = LocalDate.of(2009, 1, 1)

private val API_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val LABEL_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val LABEL_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 最近 7 天：今天往前 6 天到今天。 */
fun lastSevenDays(today: LocalDate): DateSpan = DateSpan(today.minusDays(6), today)

/** 某年某月：1 日到月末；本月只到今天。 */
fun monthSpan(year: Int, month: Int, today: LocalDate): DateSpan {
    val ym = YearMonth.of(year, month)
    val end = ym.atEndOfMonth()
    return DateSpan(ym.atDay(1), if (end.isAfter(today)) today else end)
}

enum class CustomSpanError { REVERSED, TOO_LONG, TOO_EARLY, AFTER_TODAY }

/** 自定义范围是否可用；null = 可用。 */
fun validateCustomSpan(span: DateSpan, today: LocalDate): CustomSpanError? = when {
    span.to.isBefore(span.from) -> CustomSpanError.REVERSED
    span.from.isBefore(REGION_EARLIEST) -> CustomSpanError.TOO_EARLY
    span.to.isAfter(today) -> CustomSpanError.AFTER_TODAY
    ChronoUnit.DAYS.between(span.from, span.to) + 1 > CUSTOM_SPAN_MAX_DAYS -> CustomSpanError.TOO_LONG
    else -> null
}

/** 当前要查的范围。月份 / 自定义缺了存的范围时退回最近 7 天。 */
fun RegionSettings.span(today: LocalDate): DateSpan = when (timeMode) {
    RegionTimeMode.LAST_7_DAYS -> lastSevenDays(today)
    RegionTimeMode.MONTH, RegionTimeMode.CUSTOM -> savedSpan ?: lastSevenDays(today)
}

fun formatApiDate(date: LocalDate): String = date.format(API_DATE)

fun parseApiDate(text: String?): LocalDate? =
    text?.let { runCatching { LocalDate.parse(it, API_DATE) }.getOrNull() }

/** 显示用：今年的写「MM-dd 至 MM-dd」，跨年或往年写完整日期。 */
fun formatSpanLabel(span: DateSpan, today: LocalDate): String {
    val sameYear = span.from.year == today.year && span.to.year == today.year
    val f = if (sameYear) LABEL_SHORT else LABEL_FULL
    return "${span.from.format(f)} 至 ${span.to.format(f)}"
}

/** 从 DataStore 读出的原始值还原设置；缺失或非法的项用默认，要范围的模式范围坏了退回最近 7 天。 */
fun resolveRegionSettings(mode: String?, from: String?, to: String?, order: String?, hideShort: Boolean?): RegionSettings {
    val span = parseApiDate(from)?.let { f -> parseApiDate(to)?.let { t -> DateSpan(f, t) } }
    val parsedMode = RegionTimeMode.entries.firstOrNull { it.name == mode } ?: RegionTimeMode.LAST_7_DAYS
    val timeMode = if (parsedMode != RegionTimeMode.LAST_7_DAYS && span == null) RegionTimeMode.LAST_7_DAYS else parsedMode
    return RegionSettings(
        timeMode = timeMode,
        savedSpan = if (timeMode == RegionTimeMode.LAST_7_DAYS) null else span,
        order = RegionOrder.entries.firstOrNull { it.param == order } ?: RegionOrder.CLICK,
        hideShort = hideShort ?: true,
    )
}

/** 要显示的条目：开关打开时去掉短于 [SHORT_VIDEO_SECONDS] 秒的（时长未知 = 0 也去掉）。 */
fun visibleRegionItems(items: List<ChartItem>, hideShort: Boolean): List<ChartItem> =
    if (hideShort) items.filter { it.durationSec >= SHORT_VIDEO_SECONDS } else items

/** 翻页追加：按 bvid 去重（排序在翻页之间变动会让同一条出现两次）。 */
fun appendUnseenRegionItems(existing: List<ChartItem>, incoming: List<ChartItem>): List<ChartItem> {
    val seen = existing.mapTo(HashSet()) { it.bvid }
    return existing + incoming.filter { seen.add(it.bvid) }
}

/** 还有没有下一页：这一页有东西、且没到最后一页。 */
fun regionHasMore(page: Int, totalPages: Int, received: Int): Boolean = received > 0 && page < totalPages
```

- [ ] **Step 4: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.region.RegionPolicyTest"`
Expected: PASS（9 个）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/region/RegionCatalog.kt app/src/main/java/com/biliseek/app/region/RegionPolicy.kt \
  app/src/test/java/com/biliseek/app/region/RegionPolicyTest.kt
git commit -m "feat(region): 分区清单与时间范围、设置、过滤、翻页纯函数"
```

---

### Task 3: 分区设置存储 + RegionController

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/SettingsStore.kt`
- Create: `app/src/main/java/com/biliseek/app/region/RegionController.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（持有 `regions`）

**Interfaces:**
- Consumes: Task 1 `regionVideos`；Task 2 全部
- Produces:
  - `AppSettings.region: RegionSettings`（默认 `RegionSettings()`）；`suspend fun SettingsStore.setRegionSettings(value: RegionSettings)`
  - `data class RegionListState(val items: List<ChartItem> = emptyList(), val page: Int = 0, val hasMore: Boolean = true, val isLoading: Boolean = false, val isLoadingMore: Boolean = false, val errorMessage: String? = null)`
  - `data class RegionDetailState(val group: RegionGroup, val selectedTid: Int)`
  - `class RegionController`：`overviewOpen: StateFlow<Boolean>`、`detail: StateFlow<RegionDetailState?>`、`lists: StateFlow<Map<Int, RegionListState>>`、`settings: StateFlow<RegionSettings>`、`fun openOverview()`、`fun closeOverview()`、`fun openDetail(tid: Int)`、`fun closeDetail()`、`fun closeAll()`、`fun selectTab(tid: Int)`、`fun loadMore(tid: Int)`、`fun retry(tid: Int)`、`fun updateSettings(value: RegionSettings)`、`fun play(tid: Int, item: ChartItem)`
  - `AppViewModel.regions: RegionController`

（控制器依赖实体 `BiliRepository`，没有单测设施；逻辑都在 Task 2 的纯函数里，控制器靠编译 + 真机。）

- [ ] **Step 1: 设置存储**

`SettingsStore.kt`：

- `AppSettings` 末尾（`quickEntries` 之后）加：

```kotlin
    /** 分区列表的时间范围、排序、隐藏短视频（全部分区共用）。 */
    val region: com.biliseek.app.region.RegionSettings = com.biliseek.app.region.RegionSettings(),
```

- `load()` 里 `quickEntries = ...` 之后加：

```kotlin
            region = com.biliseek.app.region.resolveRegionSettings(
                mode = p[KEY_REGION_TIME_MODE],
                from = p[KEY_REGION_TIME_FROM],
                to = p[KEY_REGION_TIME_TO],
                order = p[KEY_REGION_ORDER],
                hideShort = p[KEY_REGION_HIDE_SHORT],
            ),
```

- `setQuickEntries` 之后加：

```kotlin
    suspend fun setRegionSettings(value: com.biliseek.app.region.RegionSettings) = update { it.copy(region = value) }
```

- `update` 的 `edit` 块里 `p[KEY_QUICK_ENTRIES] = ...` 之后加：

```kotlin
            next.region.let { r ->
                p[KEY_REGION_TIME_MODE] = r.timeMode.name
                p[KEY_REGION_TIME_FROM] = r.savedSpan?.from?.let(com.biliseek.app.region::formatApiDate).orEmpty()
                p[KEY_REGION_TIME_TO] = r.savedSpan?.to?.let(com.biliseek.app.region::formatApiDate).orEmpty()
                p[KEY_REGION_ORDER] = r.order.param
                p[KEY_REGION_HIDE_SHORT] = r.hideShort
            }
```

- 键表 `KEY_QUICK_ENTRIES` 之后加：

```kotlin
        val KEY_REGION_TIME_MODE = stringPreferencesKey("region_time_mode")
        val KEY_REGION_TIME_FROM = stringPreferencesKey("region_time_from")
        val KEY_REGION_TIME_TO = stringPreferencesKey("region_time_to")
        val KEY_REGION_ORDER = stringPreferencesKey("region_order")
        val KEY_REGION_HIDE_SHORT = booleanPreferencesKey("region_hide_short")
```

（`resolveRegionSettings` 对空串返回 null 范围，见 Task 2 的 `parseApiDate`。）

- [ ] **Step 2: 控制器**

`app/src/main/java/com/biliseek/app/region/RegionController.kt`：

```kotlin
package com.biliseek.app.region

import com.biliseek.app.remote.ListPlayer
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.ChartItem
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 一个子分区的列表（只在内存）。[items] 是原始结果，显示时按 [RegionSettings.hideShort] 过滤。 */
data class RegionListState(
    val items: List<ChartItem> = emptyList(),
    /** 已加载到第几页；0 = 还没加载。 */
    val page: Int = 0,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
)

/** 打开的分区详情页：哪个大类、停在哪个子分区。 */
data class RegionDetailState(val group: RegionGroup, val selectedTid: Int)

/**
 * 分区入口（设计稿：docs/superpowers/specs/2026-10-21-region-entries-design.md）：总览页 / 详情页的打开状态、
 * 每个子分区的列表、加载与播放。列表不落库；时间范围、排序、短视频开关存 DataStore（经 [saveSettings]）。
 * 风控由 [BiliRepository.regionVideos] 的读出口负责；这里不重试、不循环。失败只记错误，等用户点「重试」。
 */
class RegionController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val playList: ListPlayer,
    initialSettings: RegionSettings,
    private val saveSettings: (RegionSettings) -> Unit,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val _overviewOpen = MutableStateFlow(false)
    val overviewOpen: StateFlow<Boolean> = _overviewOpen.asStateFlow()

    private val _detail = MutableStateFlow<RegionDetailState?>(null)
    val detail: StateFlow<RegionDetailState?> = _detail.asStateFlow()

    private val _lists = MutableStateFlow<Map<Int, RegionListState>>(emptyMap())
    val lists: StateFlow<Map<Int, RegionListState>> = _lists.asStateFlow()

    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<RegionSettings> = _settings.asStateFlow()

    /** 设置在启动时异步读完：宿主收到最新值后调一次，只在没打开过任何列表时生效。 */
    fun onSettingsLoaded(value: RegionSettings) {
        if (_lists.value.isEmpty()) _settings.value = value
    }

    fun openOverview() { _overviewOpen.value = true }

    fun closeOverview() { _overviewOpen.value = false }

    /** 打开 [tid] 所在大类的详情页并停在它上面；没取过就取第 1 页。 */
    fun openDetail(tid: Int) {
        val region = RegionCatalog.byTid(tid) ?: return
        _detail.value = RegionDetailState(region.group, tid)
        ensureLoaded(tid)
    }

    /** 关详情页：从总览进来的回到总览，从快捷入口直接进来的回首页。 */
    fun closeDetail() { _detail.value = null }

    /** 搜索框来了关键词（如外部分享）：分区页都收起。 */
    fun closeAll() {
        _detail.value = null
        _overviewOpen.value = false
    }

    /** 详情页切到 [tid]（点标签或左右滑）。 */
    fun selectTab(tid: Int) {
        val current = _detail.value ?: return
        if (current.selectedTid == tid) return
        _detail.value = current.copy(selectedTid = tid)
        ensureLoaded(tid)
    }

    /** 滚到底：取下一页。正在取、没有下一页、上次失败（等用户点重试）时不动。 */
    fun loadMore(tid: Int) {
        val state = _lists.value[tid] ?: return
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.errorMessage != null) return
        fetch(tid, state.page + 1)
    }

    /** 点「重试」：没有内容时重取第 1 页，有内容时重取下一页。 */
    fun retry(tid: Int) {
        val state = _lists.value[tid] ?: RegionListState()
        if (state.isLoading || state.isLoadingMore) return
        fetch(tid, if (state.items.isEmpty()) 1 else state.page + 1)
    }

    /**
     * 改时间范围 / 排序 / 短视频开关：存下来。时间或排序变了，清掉所有子分区的列表，当前详情页重取第 1 页；
     * 只改短视频开关不重取（显示时过滤）。
     */
    fun updateSettings(value: RegionSettings) {
        val old = _settings.value
        if (value == old) return
        _settings.value = value
        saveSettings(value)
        val requeryNeeded = value.order != old.order || value.span(today()) != old.span(today())
        if (requeryNeeded) {
            _lists.value = emptyMap()
            _detail.value?.let { ensureLoaded(it.selectedTid) }
        }
    }

    /**
     * 点条目：这个子分区当前显示的条目作为临时队列，从这一条开始播（docs/10）；
     * 续页按同样的过滤取下一页，追加到列表里。
     */
    fun play(tid: Int, item: ChartItem) {
        val hideShort = _settings.value.hideShort
        val visible = visibleRegionItems(_lists.value[tid]?.items.orEmpty(), hideShort)
        val index = visible.indexOfFirst { it.bvid == item.bvid }.takeIf { it >= 0 } ?: return
        playList.play(visible.map { it.toRequest() }, index) { loadNextForQueue(tid, hideShort) }
    }

    private suspend fun loadNextForQueue(tid: Int, hideShort: Boolean): List<PlaybackRequest>? {
        val state = _lists.value[tid] ?: return null
        if (!state.hasMore) return null
        val page = state.page + 1
        val span = _settings.value.span(today())
        val result = repository.regionVideos(
            tid, _settings.value.order.param, page, formatApiDate(span.from), formatApiDate(span.to),
        ).getOrNull() ?: return null
        val fresh = result.items.filter { new -> state.items.none { it.bvid == new.bvid } }
        applyPage(tid, page, result.items, result.totalPages, append = true)
        return visibleRegionItems(fresh, hideShort).map { it.toRequest() }
    }

    private fun ensureLoaded(tid: Int) {
        val state = _lists.value[tid]
        if (state == null || (state.page == 0 && !state.isLoading && state.errorMessage == null)) fetch(tid, 1)
    }

    private fun fetch(tid: Int, page: Int) {
        val first = page == 1
        _lists.update { map ->
            val current = map[tid] ?: RegionListState()
            map + (tid to current.copy(isLoading = first, isLoadingMore = !first, errorMessage = null))
        }
        val settings = _settings.value
        val span = settings.span(today())
        scope.launch {
            repository.regionVideos(tid, settings.order.param, page, formatApiDate(span.from), formatApiDate(span.to))
                .onSuccess { result ->
                    // 等待期间改了时间或排序（列表被清空重来）：丢弃这次结果
                    if (settings.order != _settings.value.order || span != _settings.value.span(today())) return@onSuccess
                    applyPage(tid, page, result.items, result.totalPages, append = !first)
                }
                .onFailure { error ->
                    _lists.update { map ->
                        val current = map[tid] ?: return@update map
                        map + (tid to current.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = error.message ?: "加载失败",
                        ))
                    }
                }
        }
    }

    private fun applyPage(tid: Int, page: Int, incoming: List<ChartItem>, totalPages: Int, append: Boolean) {
        _lists.update { map ->
            val current = map[tid] ?: RegionListState()
            val items = if (append) appendUnseenRegionItems(current.items, incoming) else incoming
            map + (tid to current.copy(
                items = items,
                page = page,
                hasMore = regionHasMore(page, totalPages, incoming.size),
                isLoading = false,
                isLoadingMore = false,
                errorMessage = null,
            ))
        }
    }

    // 条目 → 播放请求。cid 留空：轮到播放时取分 P（与榜单一致）
    private fun ChartItem.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = coverUrl,
        author = author,
        durationSec = durationSec,
    )
}
```

- [ ] **Step 3: AppViewModel 持有**

在 `val charts: ChartController = ...` 之后加：

```kotlin
    // ── 分区入口（时光姬式）：见 RegionController ──

    val regions: com.biliseek.app.region.RegionController = com.biliseek.app.region.RegionController(
        scope = viewModelScope,
        repository = network.repository,
        playList = { items, startIndex, loadMore ->
            viewModelScope.launch {
                connection.playFromContext(items, startIndex, expandParts = true, loadMore = loadMore)
            }
        },
        initialSettings = settingsStore.current.region,
        saveSettings = { value -> viewModelScope.launch { settingsStore.setRegionSettings(value) } },
    )
```

设置是在 `init` 里的协程中异步读的（`AppViewModel.kt` 约 1268 行 `viewModelScope.launch { settingsStore.load() ... }`），`regions` 构造时拿到的是默认值。在 `settingsStore.load()` 那一行之后紧跟一行：

```kotlin
            regions.onSettingsLoaded(settingsStore.current.region)
```

- [ ] **Step 4: 编译 + 测试**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部通过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/data/SettingsStore.kt app/src/main/java/com/biliseek/app/region/RegionController.kt \
  app/src/main/java/com/biliseek/app/AppViewModel.kt
git commit -m "feat(region): 分区设置存储与 RegionController"
```

---

### Task 4: 分区页面（总览 / 详情 / 时间设置弹层）+ 接线

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/RegionTimeSheet.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/RegionOverviewScreen.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/RegionDetailScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/ChartScreen.kt`（`ChartRow` 由 `private` 改 `internal`，供详情页复用）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`searchContent` 分支、关键词关闭）

**Interfaces:**
- Consumes: Task 2 `RegionGroup`、`RegionCatalog`、`RegionSettings`、`RegionOrder`、`RegionTimeMode`、`DateSpan`、`span`、`lastSevenDays`、`monthSpan`、`validateCustomSpan`、`formatSpanLabel`、`visibleRegionItems`、`REGION_EARLIEST`；Task 3 `RegionController` 全部
- Produces:
  - `internal fun regionGroupIcon(group: RegionGroup): ImageVector`（`RegionOverviewScreen.kt`，Task 5 用）
  - `@Composable fun RegionOverviewScreen(settings: RegionSettings, onSettingsChange: (RegionSettings) -> Unit, onOpenSubRegion: (Int) -> Unit, onClose: () -> Unit)`
  - `@Composable fun RegionDetailScreen(detail: RegionDetailState, lists: Map<Int, RegionListState>, settings: RegionSettings, onSettingsChange: (RegionSettings) -> Unit, onSelectTab: (Int) -> Unit, onLoadMore: (Int) -> Unit, onRetry: (Int) -> Unit, onItemClick: (Int, ChartItem) -> Unit, onClose: () -> Unit, videoActions: VideoMenuActions)`

- [ ] **Step 1: 时间设置弹层**

`RegionTimeSheet.kt`：`ModalBottomSheet`（`dragHandle = { CompactDragHandle() }`），内容自上而下：

```kotlin
package com.biliseek.app.ui

/**
 * 分区的时间范围与排序（设计稿 2026-10-21-region-entries）。全部分区共用。
 * 三种时间：最近 7 天（每次按当天重算）/ 按月份（年 + 月）/ 自定义（日期范围选择器，最长 30 天，2009-01-01 起，最晚今天）。
 * 选好点「完成」才生效；关掉弹层不改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionTimeSheet(
    settings: RegionSettings,
    today: LocalDate,
    onApply: (RegionSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(settings.timeMode) }
    var order by remember { mutableStateOf(settings.order) }
    val initialSpan = settings.span(today)
    var year by remember { mutableIntStateOf(initialSpan.from.year) }
    var month by remember { mutableIntStateOf(initialSpan.from.monthValue) }
    val zone = java.time.ZoneOffset.UTC
    fun LocalDate.millis() = atStartOfDay(zone).toInstant().toEpochMilli()
    fun Long.date() = java.time.Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    val rangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (settings.savedSpan?.from ?: initialSpan.from).millis(),
        initialSelectedEndDateMillis = (settings.savedSpan?.to ?: initialSpan.to).millis(),
        yearRange = REGION_EARLIEST.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = utcTimeMillis.date()
                return !d.isBefore(REGION_EARLIEST) && !d.isAfter(today)
            }
            override fun isSelectableYear(year: Int) = year in REGION_EARLIEST.year..today.year
        },
    )
    val customSpan = rangeState.selectedStartDateMillis?.let { s ->
        rangeState.selectedEndDateMillis?.let { e -> DateSpan(s.date(), e.date()) }
    }
    val customError = customSpan?.let { validateCustomSpan(it, today) }
    val canApply = mode != RegionTimeMode.CUSTOM || (customSpan != null && customError == null)

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("时间范围", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(RegionTimeMode.LAST_7_DAYS to "最近 7 天", RegionTimeMode.MONTH to "按月份", RegionTimeMode.CUSTOM to "自定义")
                    .forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
            }
            when (mode) {
                RegionTimeMode.LAST_7_DAYS -> Text(formatSpanLabel(lastSevenDays(today), today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                RegionTimeMode.MONTH -> MonthPicker(year, month, today, onYear = { year = it }, onMonth = { month = it })
                RegionTimeMode.CUSTOM -> {
                    DateRangePicker(state = rangeState, modifier = Modifier.heightIn(max = 420.dp), title = null, headline = null, showModeToggle = false)
                    customError?.let { Text(customSpanErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("排序", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionOrder.entries.forEach { o -> FilterChip(selected = order == o, onClick = { order = o }, label = { Text(o.label) }) }
            }
            Button(
                onClick = {
                    val span = when (mode) {
                        RegionTimeMode.LAST_7_DAYS -> null
                        RegionTimeMode.MONTH -> monthSpan(year, month, today)
                        RegionTimeMode.CUSTOM -> customSpan
                    }
                    onApply(settings.copy(timeMode = mode, savedSpan = span, order = order))
                    onDismiss()
                },
                enabled = canApply,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("完成") }
        }
    }
}

/** 按月份：年份左右箭头（2009–今年）+ 12 个月份按钮（今年未来的月份不可选）。 */
@Composable
private fun MonthPicker(year: Int, month: Int, today: LocalDate, onYear: (Int) -> Unit, onMonth: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYear(year - 1) }, enabled = year > REGION_EARLIEST.year) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一年")
            }
            Text("$year 年", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onYear(year + 1) }, enabled = year < today.year) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一年")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..12).forEach { m ->
                val future = year == today.year && m > today.monthValue
                FilterChip(selected = m == month, onClick = { onMonth(m) }, enabled = !future, label = { Text("$m 月") })
            }
        }
    }
}

private fun customSpanErrorText(error: CustomSpanError): String = when (error) {
    CustomSpanError.TOO_LONG -> "最长 30 天"
    CustomSpanError.REVERSED -> "结束日期早于开始日期"
    CustomSpanError.TOO_EARLY -> "最早只能选到 2009-01-01"
    CustomSpanError.AFTER_TODAY -> "不能选今天以后的日子"
}
```

（补齐 import：`androidx.compose.material3.*` 用到的 `DateRangePicker`、`rememberDateRangePickerState`、`SelectableDates`、`SegmentedButton`、`SingleChoiceSegmentedButtonRow`、`SegmentedButtonDefaults`、`FilterChip`、`Button`、`ModalBottomSheet`、`ExperimentalMaterial3Api`、`IconButton`、`Icon`；`Icons.AutoMirrored.Filled.KeyboardArrowLeft/Right`；`FlowRow`；`com.biliseek.app.region.*`；`com.biliseek.app.ui.components.CompactDragHandle`。若切到「按月份」时年份停在未来月份（今年且月 > 当月），`monthSpan` 会把结束截到今天、开始在未来——在 `onYear` 里若新年份是今年且 `month > today.monthValue` 则把 `month` 调到 `today.monthValue`。）

- [ ] **Step 2: 总览页**

`RegionOverviewScreen.kt`：

```kotlin
package com.biliseek.app.ui

/**
 * 分区总览（时光姬式；设计稿 2026-10-21-region-entries）：顶部时间范围卡片（点开时间设置），
 * 下面每个大类一张卡片——标题行（图标 + 名字，点了进第一个子分区）+ 子分区标签（点了进对应子分区）。
 */
@Composable
fun RegionOverviewScreen(
    settings: RegionSettings,
    onSettingsChange: (RegionSettings) -> Unit,
    onOpenSubRegion: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var showTime by remember { mutableStateOf(false) }
    if (showTime) RegionTimeSheet(settings, today, onApply = onSettingsChange, onDismiss = { showTime = false })

    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "分区", onNavigate = onClose)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = miniBarContentPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "time") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showTime = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(formatSpanLabel(settings.span(today), today), style = MaterialTheme.typography.titleMedium)
                        Text("按${settings.order.label}排序 · 点这里修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(RegionGroup.entries, key = { it.name }) { group ->
                val subs = RegionCatalog.inGroup(group)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenSubRegion(subs.first().tid) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(regionGroupIcon(group), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(group.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    FlowRow(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        subs.forEach { sub -> AssistChip(onClick = { onOpenSubRegion(sub.tid) }, label = { Text(sub.name) }) }
                    }
                }
            }
        }
    }
}

/** 大类图标；快捷入口里的子分区卡片也用它。 */
internal fun regionGroupIcon(group: RegionGroup): ImageVector = when (group) {
    RegionGroup.MUSIC -> Icons.Outlined.LibraryMusic
    RegionGroup.KNOWLEDGE -> Icons.Outlined.School
    RegionGroup.DOCUMENTARY -> Icons.Outlined.Movie
    RegionGroup.TALK -> Icons.Outlined.Forum
    RegionGroup.VOICE -> Icons.Outlined.RecordVoiceOver
}
```

（补齐 import。）

- [ ] **Step 3: 详情页**

`ChartScreen.kt`：`private fun ChartRow` → `internal fun ChartRow`。

`RegionDetailScreen.kt`：

```kotlin
package com.biliseek.app.ui

/**
 * 分区详情（设计稿 2026-10-21-region-entries）：顶栏「大类名」+ 排序菜单；子分区标签 + 左右滑；
 * 列表上方一行「时间范围（点开设置）· 隐藏 1 分钟以下」；滚到底自动加载下一页，失败给「重试」不自动再来。
 * 短视频只在显示时过滤；一页全被滤掉时照常续页。
 */
@Composable
fun RegionDetailScreen(
    detail: RegionDetailState,
    lists: Map<Int, RegionListState>,
    settings: RegionSettings,
    onSettingsChange: (RegionSettings) -> Unit,
    onSelectTab: (Int) -> Unit,
    onLoadMore: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onItemClick: (Int, ChartItem) -> Unit,
    onClose: () -> Unit,
    videoActions: VideoMenuActions = VideoMenuActions(),
) {
    val today = remember { LocalDate.now() }
    val subs = remember(detail.group) { RegionCatalog.inGroup(detail.group) }
    val selectedIndex = subs.indexOfFirst { it.tid == detail.selectedTid }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = selectedIndex) { subs.size }
    val scope = rememberCoroutineScope()
    var showTime by remember { mutableStateOf(false) }
    var showOrder by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SearchVideo?>(null) }
    menuFor?.let { VideoMenuSheet(it, videoActions) { menuFor = null } }
    if (showTime) RegionTimeSheet(settings, today, onApply = onSettingsChange, onDismiss = { showTime = false })

    // 左右滑到别的页 → 告诉控制器（取这一页的列表）
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> subs.getOrNull(page)?.let { onSelectTab(it.tid) } }
    }
    // 从外面改了选中（快捷入口进来、点标签）→ 翻到那一页
    LaunchedEffect(selectedIndex) { if (pager.currentPage != selectedIndex) pager.scrollToPage(selectedIndex) }

    Column(Modifier.fillMaxSize()) {
        BsTopBar(
            title = detail.group.title,
            onNavigate = onClose,
            actions = {
                Box {
                    TextButton(onClick = { showOrder = true }) { Text("按${settings.order.label}") }
                    DropdownMenu(expanded = showOrder, onDismissRequest = { showOrder = false }) {
                        RegionOrder.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.label) },
                                onClick = { showOrder = false; onSettingsChange(settings.copy(order = o)) },
                                trailingIcon = if (o == settings.order) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                            )
                        }
                    }
                }
            },
        )
        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 16.dp) {
            subs.forEachIndexed { i, sub ->
                Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(sub.name) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showTime = true }) {
                Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(formatSpanLabel(settings.span(today), today))
            }
            Spacer(Modifier.weight(1f))
            Text("隐藏 1 分钟以下", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = settings.hideShort, onCheckedChange = { onSettingsChange(settings.copy(hideShort = it)) })
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { subs[it].tid }) { page ->
            val tid = subs[page].tid
            val state = lists[tid] ?: RegionListState(isLoading = true)
            val visible = visibleRegionItems(state.items, settings.hideShort)
            when {
                state.isLoading && state.items.isEmpty() -> BsLoadingState()
                state.errorMessage != null && state.items.isEmpty() -> BsErrorState(state.errorMessage, onRetry = { onRetry(tid) })
                state.items.isEmpty() -> BsEmptyState("这段时间这个分区没有投稿", icon = regionGroupIcon(detail.group))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                    items(visible, key = { it.bvid }) { item ->
                        ChartRow(item, onClick = { onItemClick(tid, item) }, onOpenMenu = { menuFor = item.toSearchVideo() })
                    }
                    if (state.hasMore || state.isLoadingMore || state.errorMessage != null) {
                        item(key = "footer") {
                            // 跟原始条数走：一页全被短视频过滤掉时也会接着取。失败后不自动再来（否则会一直重打 B 站）
                            if (shouldAutoLoadMore(state.hasMore, state.isLoadingMore, state.errorMessage)) {
                                LaunchedEffect(state.items.size) { onLoadMore(tid) }
                            }
                            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                when {
                                    state.isLoadingMore -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    state.errorMessage != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(state.errorMessage, style = MaterialTheme.typography.labelSmall)
                                        TextButton(onClick = { onRetry(tid) }) { Text("重试") }
                                    }
                                    else -> Text("加载中…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

（`shouldAutoLoadMore` 是现有函数：`grep -rn "fun shouldAutoLoadMore" app/src/main` 确认签名后 import；补齐其余 import。若 `visible` 为空而 `state.items` 非空，列表只剩 footer，footer 的 `LaunchedEffect` 会触发续页——这就是「一页全被滤掉照常续页」。）

- [ ] **Step 4: MainActivity 接线**

1) 收集状态（第 224 行 `publicFavorite` 之后）：

```kotlin
                val regionOverviewOpen by viewModel.regions.overviewOpen.collectAsState()
                val regionDetail by viewModel.regions.detail.collectAsState()
                val regionLists by viewModel.regions.lists.collectAsState()
                val regionSettings by viewModel.regions.settings.collectAsState()
```

2) 关键词关闭：`LaunchedEffect(search.keyword)` 块里加 `viewModel.regions.closeAll()`。

3) `searchContent` 里 `val openedPublic = publicFavorite` 下加 `val openedRegionDetail = regionDetail`；在 `} else if (openedPublic != null) { ... }` 之后、`} else {` 之前插入：

```kotlin
                        } else if (openedRegionDetail != null) {
                            // 分区详情：返回回到总览（从总览进来时）或首页（从快捷入口直接进来时）
                            BackHandler(onBack = viewModel.regions::closeDetail)
                            com.biliseek.app.ui.RegionDetailScreen(
                                detail = openedRegionDetail,
                                lists = regionLists,
                                settings = regionSettings,
                                onSettingsChange = viewModel.regions::updateSettings,
                                onSelectTab = viewModel.regions::selectTab,
                                onLoadMore = viewModel.regions::loadMore,
                                onRetry = viewModel.regions::retry,
                                onItemClick = viewModel.regions::play,
                                onClose = viewModel.regions::closeDetail,
                                videoActions = videoMenuActions,
                            )
                        } else if (regionOverviewOpen) {
                            BackHandler(onBack = viewModel.regions::closeOverview)
                            com.biliseek.app.ui.RegionOverviewScreen(
                                settings = regionSettings,
                                onSettingsChange = viewModel.regions::updateSettings,
                                onOpenSubRegion = viewModel.regions::openDetail,
                                onClose = viewModel.regions::closeOverview,
                            )
```

- [ ] **Step 5: 编译 + 测试**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/RegionTimeSheet.kt app/src/main/java/com/biliseek/app/ui/RegionOverviewScreen.kt \
  app/src/main/java/com/biliseek/app/ui/RegionDetailScreen.kt app/src/main/java/com/biliseek/app/ui/ChartScreen.kt \
  app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(region): 分区总览、分区详情与时间设置弹层"
```

---

### Task 5: 快捷入口支持「分区」与子分区（各自默认开关）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/QuickEntryPolicy.kt`
- Modify: `app/src/test/java/com/biliseek/app/data/QuickEntryPolicyTest.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/HomeScreen.kt`（`quickEntryIcon`）
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（`openQuickEntry`）
- Modify: 其余引用 `QuickEntry.XXX` 的地方（`grep -rn "QuickEntry\.\(WEEKLY\|PRECIOUS\|HOT_SONGS\|ORIGINAL_SONGS\|PODCAST\|VIDEO_PODCAST\|RANKING\)\|QuickEntry.entries" app/src`）

**Interfaces:**
- Consumes: Task 2 `RegionCatalog`、`SubRegion`；Task 3 `regions.openOverview()` / `openDetail(tid)`；Task 4 `regionGroupIcon`
- Produces（`com.biliseek.app.data`）：
  - `sealed interface QuickEntry { val id: String; val title: String; val defaultEnabled: Boolean }`
  - `enum class FixedQuickEntry(override val id: String, override val title: String, override val defaultEnabled: Boolean = true) : QuickEntry`：`WEEKLY`、`PRECIOUS`、`HOT_SONGS`、`ORIGINAL_SONGS`、`PODCAST`、`VIDEO_PODCAST`、`RANKING`、`REGIONS("regions", "分区")`
  - `data class RegionQuickEntry(val region: SubRegion) : QuickEntry`（`id = "region:${region.tid}"`、`title = region.name`、`defaultEnabled = false`）
  - `val ALL_QUICK_ENTRIES: List<QuickEntry>`（出厂顺序）
  - `DEFAULT_QUICK_ENTRIES` 改为按各自 `defaultEnabled`
  - `resolveQuickEntries(saved: String?)` 规则：存的里没有的按 `defaultEnabled` 追加

- [ ] **Step 1: 改测试（先失败）**

`QuickEntryPolicyTest.kt`：把 `QuickEntry.XXX` 全部换成 `FixedQuickEntry.XXX`，`QuickEntry.entries` 换成 `ALL_QUICK_ENTRIES`；改写/新增：

```kotlin
    private val all = ALL_QUICK_ENTRIES

    @Test
    fun `从没设置过_出厂顺序_固定入口与分区开_子分区关`() {
        val r = resolveQuickEntries(null)
        assertEquals(all, r.map { it.entry })
        assertEquals(DEFAULT_QUICK_ENTRIES, r)
        assertTrue(r.filter { it.entry is FixedQuickEntry }.all { it.enabled })
        assertTrue(r.filter { it.entry is RegionQuickEntry }.none { it.enabled })
        assertEquals(8 + 22, r.size)
    }

    @Test
    fun `出厂顺序`() {
        assertEquals(
            listOf("weekly", "precious", "hot_songs", "original_songs", "podcast", "video_podcast", "ranking", "regions", "region:28"),
            all.take(9).map { it.id },
        )
        assertEquals("region:257", all.last().id)
    }

    @Test
    fun `第一期存的设置_升级后分区总入口追加并打开_子分区追加但关`() {
        val r = resolveQuickEntries("weekly,-precious,hot_songs,original_songs,podcast,video_podcast,ranking")
        assertEquals(FixedQuickEntry.REGIONS, r[7].entry)
        assertTrue(r[7].enabled)
        assertTrue(r.drop(8).all { it.entry is RegionQuickEntry && !it.enabled })
        assertEquals(false, r[1].enabled)
    }

    @Test
    fun `子分区入口往返_未知tid丢掉`() {
        val r = resolveQuickEntries("region:267,region:99999,-regions,weekly")
        assertEquals("region:267", r[0].entry.id)
        assertTrue(r[0].enabled)
        assertEquals(FixedQuickEntry.REGIONS, r[1].entry)
        assertEquals(false, r[1].enabled)
        assertEquals(all.size, r.size)
        assertEquals("电台", r[0].entry.title)
        assertEquals(r, resolveQuickEntries(encodeQuickEntries(r)))
    }
```

（原有「顺序与开关往返」「不认识的 id」「全部关掉」「只显示打开的」四个测试保留，按新类型改名；原「存的里没有的入口_追加到末尾并打开」改为断言追加的固定入口打开、子分区关闭。）

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.QuickEntryPolicyTest"`
Expected: 编译失败，`Unresolved reference: FixedQuickEntry`。

- [ ] **Step 2: 实现**

`QuickEntryPolicy.kt` 的类型部分改为：

```kotlin
/** 首页快捷入口。[id] 是存进设置里的稳定 id；[defaultEnabled] 是出厂开关（也用于以后新增、存的里没有的入口）。 */
sealed interface QuickEntry {
    val id: String
    val title: String
    val defaultEnabled: Boolean
}

/** 固定入口：榜单、播单、分区总入口。声明顺序 = 出厂顺序。 */
enum class FixedQuickEntry(
    override val id: String,
    override val title: String,
    override val defaultEnabled: Boolean = true,
) : QuickEntry {
    WEEKLY("weekly", "每周必看"),
    PRECIOUS("precious", "入站必刷"),
    HOT_SONGS("hot_songs", "热歌榜"),
    ORIGINAL_SONGS("original_songs", "原创音乐榜"),
    PODCAST("podcast", "热门播客"),
    VIDEO_PODCAST("video_podcast", "视频播客"),
    RANKING("ranking", "全站排行榜"),
    REGIONS("regions", "分区"),
}

/** 单个子分区作为快捷入口（设计稿 2026-10-21-region-entries）：出厂关，点了直接进分区详情并停在它上面。 */
data class RegionQuickEntry(val region: com.biliseek.app.region.SubRegion) : QuickEntry {
    override val id: String get() = "$REGION_ID_PREFIX${region.tid}"
    override val title: String get() = region.name
    override val defaultEnabled: Boolean get() = false
}

private const val REGION_ID_PREFIX = "region:"

/** 全部入口，出厂顺序：固定入口 → 分区清单里的子分区。 */
val ALL_QUICK_ENTRIES: List<QuickEntry> =
    FixedQuickEntry.entries + com.biliseek.app.region.RegionCatalog.all.map(::RegionQuickEntry)

/** 出厂：按 [ALL_QUICK_ENTRIES] 顺序，开关取各自的 [QuickEntry.defaultEnabled]。 */
val DEFAULT_QUICK_ENTRIES: List<QuickEntrySetting> = ALL_QUICK_ENTRIES.map { QuickEntrySetting(it, it.defaultEnabled) }
```

`resolveQuickEntries`：`QuickEntry.entries.associateBy { it.id }` → `ALL_QUICK_ENTRIES.associateBy { it.id }`；追加段：

```kotlin
    ALL_QUICK_ENTRIES
        .filter { entry -> result.none { it.entry == entry } }
        .forEach { result += QuickEntrySetting(it, enabled = it.defaultEnabled) }
```

KDoc 第三条改为「代码里有、存的里没有的入口（以后版本新加的）追加到末尾，开关按该入口的出厂默认值」。文件头注释补一句第二期设计稿路径。

- [ ] **Step 3: 调用处**

- `HomeScreen.kt` `quickEntryIcon`：

```kotlin
internal fun quickEntryIcon(entry: QuickEntry): ImageVector = when (entry) {
    FixedQuickEntry.WEEKLY -> Icons.Outlined.CalendarMonth
    FixedQuickEntry.PRECIOUS -> Icons.Outlined.Whatshot
    FixedQuickEntry.HOT_SONGS -> Icons.Outlined.Leaderboard
    FixedQuickEntry.ORIGINAL_SONGS -> Icons.Outlined.MusicNote
    FixedQuickEntry.PODCAST -> Icons.Outlined.Podcasts
    FixedQuickEntry.VIDEO_PODCAST -> Icons.Outlined.Mic
    FixedQuickEntry.RANKING -> Icons.Outlined.EmojiEvents
    FixedQuickEntry.REGIONS -> Icons.Outlined.Category
    is RegionQuickEntry -> regionGroupIcon(entry.region.group)
}
```

- `AppViewModel.openQuickEntry`：各分支改成 `FixedQuickEntry.XXX`，加：

```kotlin
            com.biliseek.app.data.FixedQuickEntry.REGIONS -> regions.openOverview()
            is com.biliseek.app.data.RegionQuickEntry -> regions.openDetail(entry.region.tid)
```

- 其余 `grep` 出来的 `QuickEntry.XXX` / `QuickEntry.entries` 引用按同样方式替换（设置页只用 `entry.id` / `entry.title` / `quickEntryIcon(entry)`，不用改）。

- [ ] **Step 4: 全量验证**

Run: `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/data/QuickEntryPolicy.kt app/src/test/java/com/biliseek/app/data/QuickEntryPolicyTest.kt \
  app/src/main/java/com/biliseek/app/ui/HomeScreen.kt app/src/main/java/com/biliseek/app/AppViewModel.kt
# 以及 Step 3 里改到的其他文件
git commit -m "feat(home): 快捷入口加「分区」与 22 个子分区（子分区默认关）"
```

---

### Task 6: 文档

**Files:**
- Modify: `docs/05-功能优化篇.md`（§13 新增一条；§10 待办里第二期那一行移除或标为已做）
- Modify: `docs/03-架构设计篇.md`（控制器表加 `RegionController`；`RequestThrottle` 一行写明 SEARCH 桶也管分区列表；不落库清单加分区列表）
- Modify: `docs/09-UI规范与重构.md`（§5.1 快捷入口表加「分区」与子分区；新增分区总览 / 详情 / 时间弹层的版式要点）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 14 之后加分区相关检查）

- [ ] **Step 1: 05 §13**：照 13.72 的格式追加 13.73「首页快捷入口第二期：分区入口（2026-10-21）」：做法（总入口 + 子分区快捷入口默认关、总览 / 详情 / 时间弹层、设置键、SEARCH 桶、不签名、不重试、短视频显示过滤）、调研（19:29 授权请求一次 `newlist_rank` 的结论、分区不精确与短视频多）、不做（ASMR、资讯、v2、联网刷新分区清单）、验证（真机待做）、设计稿链接。§10 待办里「第二期 分区入口」一行删掉（已做）。
- [ ] **Step 2: 03**：`grep -n "ChartController\|RequestThrottle\|不落库" docs/03-架构设计篇.md` 找到对应行，按现有格式补 `RegionController`（`app/region/`）、SEARCH 桶说明、不落库清单。
- [ ] **Step 3: 09**：在 §5.1 的入口表加「分区（`regions`，`Category`）」与「子分区（`region:<tid>`，大类图标，默认关）」；新增一小节写分区总览（时间卡片 + 大类卡片 + `AssistChip` 子分区）、分区详情（`ScrollableTabRow` + `HorizontalPager`、顶栏排序菜单、时间 + 「隐藏 1 分钟以下」一行）、时间弹层（分段按钮三种模式、月份选择、`DateRangePicker`、排序 `FilterChip`、「完成」）。
- [ ] **Step 4: 手动测试指南**：按步骤 14 现有表格格式加：首页出现「分区」；总览 5 大类 22 子分区；每个子分区点开能取到数据（逐个记录取不到的）；三种时间模式（自定义超过 30 天不能点完成）；排序切换后列表重取；「隐藏 1 分钟以下」开关立即生效、不重新请求；滚到底续页；点条目播放、播到最后一条自动接下一页；从快捷入口直接进子分区、返回回首页；从总览进、返回回总览；设置重启保留；子分区快捷入口默认关、打开后首页出现；连续切换几个子分区不触发 -352。
- [ ] **Step 5: 提交**

```bash
git add docs/05-功能优化篇.md docs/03-架构设计篇.md docs/09-UI规范与重构.md docs/BiliSeek-手动测试指南.md \
  docs/superpowers/specs/2026-10-21-region-entries-design.md docs/superpowers/plans/2026-10-21-region-entries.md
git commit -m "docs: 首页快捷入口第二期（分区入口）文档同步"
```
