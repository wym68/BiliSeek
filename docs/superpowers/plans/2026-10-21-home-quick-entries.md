# 首页快捷入口（第一期）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页快捷入口从写死的 4 个榜单变成可配置的 7 个入口（新增原创音乐榜、视频播客、全站排行榜），设置里能开关、拖动排序，首次显示时弹一次提示。

**Architecture:** 入口清单与开关/顺序的读写规则是 `app/data/QuickEntryPolicy.kt` 里的纯函数，结果存进 `AppSettings`（DataStore 一个字符串键）。榜单类入口走现有 `ChartController`（新增 `ORIGINAL_SONGS`、`RANKING` 两种）；视频播客是官方公开收藏夹，在 `RemoteLibraryController` 里单独一份只读状态，复用收藏夹内容页（加只读开关）。设置页的拖动排序复用本地列表的拖动机制（先把它改成按任意 key 的通用版）。

**Tech Stack:** Kotlin、Jetpack Compose（Material3）、Retrofit + kotlinx.serialization、DataStore Preferences、JUnit4。

**Spec:** `docs/superpowers/specs/2026-10-21-home-quick-entries-design.md`

## Global Constraints

- **测试绝不请求 B 站服务器**：只用 `core/network/src/test/resources/` 里的离线样例（`ranking_v2_sample.json`、`fav_video_podcast_sample.json` 已于 10-21 取好并裁剪成 3 条）。
- 新 B 站请求不加重试循环；榜单读走 `chartRead`（FEED 限速桶），收藏夹读走 `accountRead`。
- 排行榜**不加 WBI 签名**：`x/web-interface/ranking/v2?rid=0&type=all`。
- 视频播客收藏夹 media_id：`3628308062`，标题「视频播客」。
- 出厂顺序（全部打开）：每周必看、入站必刷、热歌榜、原创音乐榜、热门播客、视频播客、全站排行榜。稳定 id：`weekly`、`precious`、`hot_songs`、`original_songs`、`podcast`、`video_podcast`、`ranking`。
- 存储键 `home_quick_entries`，格式 `id,id,-id`（`-` = 关）。
- 提示文字：`入口可以在 设置 → 首页快捷入口 里关闭和排序`；HintKey 存储键 `hint_home_quick_entries`。
- 设置行标题「首页快捷入口」，值「显示 N 个」。
- 首页每行 4 张卡片，最后一行不足时靠左、用等宽占位。
- 中文注释，风格与周围代码一致；不新增 `:core:network` 的公开类型（`RankingData` DTO 与已有 DTO 同为 public 数据类，属于 DTO 包惯例，可以）。
- **提交前先问用户**。工作区里有与本功能无关的未提交改动（消息角标、`UnreadPolicy` 等），每次提交只 `git add` 本任务列出的文件。
- 验证命令：`./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`。不装机，等用户开口。

---

### Task 1: 网络层——排行榜、原创榜、不要求登录的收藏夹读取

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/ChartDto.kt`（加 `RankingData`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`（`preciousVideos` 之后加 `rankingV2`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt`（加 `RankingData.toChartPage()`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt`（`ChartItem.rank` 注释）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（`rankingVideos`、`originalSongIssues`、`publicFavoriteResources`）
- Test: `core/network/src/test/java/com/biliseek/core/network/ChartDtoTest.kt`、`core/network/src/test/java/com/biliseek/core/network/FavoriteDtoTest.kt`
- 资源（已存在，不用再取）：`core/network/src/test/resources/ranking_v2_sample.json`、`core/network/src/test/resources/fav_video_podcast_sample.json`

**Interfaces:**
- Produces:
  - `suspend fun BiliRepository.rankingVideos(): Result<ChartPage>`
  - `suspend fun BiliRepository.originalSongIssues(): Result<List<ChartIssue>>`（某一期的内容仍用现有 `hotSongIssue(issue)`，接口相同）
  - `suspend fun BiliRepository.publicFavoriteResources(mediaId: Long, page: Int, pageSize: Int = 20): Result<FavoriteResourcePage>`

- [ ] **Step 1: 写失败的测试（排行榜）**

在 `ChartDtoTest.kt` 末尾（类内）加：

```kotlin
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 10-21 真实返回（不签名、不带 Cookie），裁剪成 3 条。 */
    @Test
    fun `全站排行榜真实样例_按位置给名次_页头是官方说明`() {
        val r = json.decodeFromString<BiliResponse<RankingData>>(resource("ranking_v2_sample.json"))
        val page = r.data!!.toChartPage()
        assertEquals("根据稿件内容质量、近期的数据综合展示，动态更新", page.subtitle)
        assertEquals(listOf("BV1pGnn67EbB", "BV1SknF6KE5g", "BV1Jonu6mEwN"), page.items.map { it.bvid })
        assertEquals(listOf(1, 2, 3), page.items.map { it.rank })
        val first = page.items[0]
        assertEquals("影视飓风", first.author)
        assertEquals(946974L, first.authorMid)
        assertEquals(2682L, first.durationSec)
        assertEquals(4542869L, first.playCount)
        assertEquals("https://i2.hdslb.com/bfs/archive/1bf5beb95e275c81e31f84f4798fdc1eaba4295f.jpg", first.coverUrl)
    }

    @Test
    fun `全站排行榜_没有bvid的条目跳过但名次按原位置`() {
        val r = json.decodeFromString<BiliResponse<RankingData>>(
            """
            {"code":0,"data":{"note":"n","list":[
              {"bvid":"","title":"失效"},
              {"bvid":"BV1xxxxxxxxx","title":"第二名","duration":10}
            ]}}
            """.trimIndent(),
        )
        val page = r.data!!.toChartPage()
        assertEquals(listOf("BV1xxxxxxxxx"), page.items.map { it.bvid })
        assertEquals(listOf(2), page.items.map { it.rank })
    }
```

并在文件头 import 里加：

```kotlin
import com.biliseek.core.network.dto.RankingData
import com.biliseek.core.network.internal.toChartPage
```

（`ChartDtoTest` 在 `com.biliseek.core.network` 包，与 `internal` 同模块，可直接用 `internal fun`。`normalizeCoverUrl` 会把 `http://` 改成 `https://`，所以封面断言是 https。）

- [ ] **Step 2: 写失败的测试（视频播客收藏夹样例）**

在 `FavoriteDtoTest.kt` 类内加：

```kotlin
    /** 10-21 真实返回（官方公开收藏夹「视频播客」，不带 Cookie 读到），裁剪成 3 条。 */
    @Test
    fun `视频播客收藏夹真实样例_未登录可读的结构`() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("fav_video_podcast_sample.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val r = json.decodeFromString<com.biliseek.core.network.dto.BiliResponse<FavoriteResourceData>>(text)
        assertEquals(0, r.code)
        val data = r.data!!
        assertTrue(data.hasMore)
        assertEquals(listOf("BV1dYaJ6PE2z", "BV1Fhad6GEQL", "BV1j4af6rEN7"), data.medias.map { it.bvid })
        assertTrue(data.medias.all { it.type == 2 })
        assertEquals("于谦", data.medias[0].upper?.name)
    }
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.ChartDtoTest" --tests "com.biliseek.core.network.FavoriteDtoTest"`
Expected: 编译失败，`Unresolved reference: RankingData`（收藏夹那条在编译通过后应直接通过——它只验证现有 DTO 能读真实样例）。

- [ ] **Step 4: 实现 DTO、接口、解析**

`ChartDto.kt`，在 `PreciousData` 之后加：

```kotlin
/** `x/web-interface/ranking/v2` —— 全站排行榜（`rid=0&type=all`），一次 100 条，没有期数。公开接口，不需要登录、不需要 WBI。 */
@Serializable
data class RankingData(
    /** 官方说明，如「根据稿件内容质量、近期的数据综合展示，动态更新」。 */
    val note: String = "",
    val list: List<PopularVideo> = emptyList(),
)
```

`BiliApi.kt`，在 `preciousVideos()` 之后加：

```kotlin
    /** 全站排行榜。`rid=0` 全站，`type=all` 全部稿件。10-21 实测不签名返回正常。 */
    @GET("x/web-interface/ranking/v2")
    suspend fun rankingV2(
        @Query("rid") rid: Int = 0,
        @Query("type") type: String = "all",
    ): BiliResponse<RankingData>
```

（在 `BiliApi.kt` 头部的 dto import 里加 `RankingData`，与 `PreciousData` 的 import 写法一致。）

`ChartPolicy.kt`，在 `PreciousData.toChartPage()` 之后加，并 import `com.biliseek.core.network.dto.RankingData`：

```kotlin
/** 全站排行榜：页头 = 官方说明；名次 = 在榜单里的位置（跳过的条目不让后面的名次前移）。 */
internal fun RankingData.toChartPage(): ChartPage = ChartPage(
    subtitle = note,
    items = list.mapIndexedNotNull { index, video -> video.toChartItem()?.copy(rank = index + 1) },
)
```

`ChartModels.kt` 里 `ChartItem.rank` 的注释改成：

```kotlin
    /** 名次；热歌榜、原创音乐榜、全站排行榜有，其余为 null。 */
```

- [ ] **Step 5: 仓库层**

`BiliRepository.kt`：

1) 在 `preciousVideos()` 之后加：

```kotlin
    /** 全站排行榜：页头 = 官方说明，条目按名次。一次请求，走 [chartRead]；不签名。 */
    suspend fun rankingVideos(): Result<ChartPage> = runCatching {
        chartRead("全站排行榜") { api.rankingV2() }?.toChartPage() ?: ChartPage.EMPTY
    }
```

2) 把 `hotSongIssues()` 改成与原创榜共用一段，`hotSongIssue` 注释补一句：

```kotlin
    /**
     * 热歌榜的全部期（所有年份合并），最新在前。
     * 公开接口，不需要登录；一次请求，走 [chartRead]。没有任何期时返回空列表。
     */
    suspend fun hotSongIssues(): Result<List<ChartIssue>> = musicIssues(listType = 1, what = "热歌榜期数")

    /** 原创音乐榜的全部期：与热歌榜同一个接口，`list_type=2`；某一期的歌曲同样用 [hotSongIssue] 取。 */
    suspend fun originalSongIssues(): Result<List<ChartIssue>> = musicIssues(listType = 2, what = "原创榜期数")

    private suspend fun musicIssues(listType: Int, what: String): Result<List<ChartIssue>> = runCatching {
        resolveMusicIssues(chartRead(what) { api.musicToplistPeriods(listType = listType) }?.list.orEmpty())
    }

    /** 热歌榜 / 原创音乐榜的某一期：页头 =「第 N 期」，条目按名次排。一次请求，走 [chartRead]。 */
    suspend fun hotSongIssue(issue: ChartIssue): Result<ChartPage> = runCatching {
        chartRead("音乐榜") { api.musicToplistSongs(issue.id) }?.toChartPage(issue.number) ?: ChartPage.EMPTY
    }
```

（保留原 `hotSongIssues` 上方已有的 KDoc 意思，删掉被替换的旧函数体。）

3) 收藏夹内容拆成「要登录」和「公开」两个入口，共用私有实现：

```kotlin
    /** 收藏夹内容（分页，`ps ≤ 20`）。必须登录。 */
    suspend fun favoriteResources(
        mediaId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<FavoriteResourcePage> = runCatching {
        requireLoggedInMid()
        fetchFavoriteResources(mediaId, page, pageSize)
    }

    /**
     * 别人的公开收藏夹内容（首页「视频播客」）。同一个接口，**不要求登录**——10-21 实测不带 Cookie 可读。
     */
    suspend fun publicFavoriteResources(
        mediaId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<FavoriteResourcePage> = runCatching { fetchFavoriteResources(mediaId, page, pageSize) }

    private suspend fun fetchFavoriteResources(mediaId: Long, page: Int, pageSize: Int): FavoriteResourcePage {
        val data = accountRead("收藏夹内容") { api.favoriteResources(mediaId, pn = page, ps = pageSize) }
            ?: throw IllegalStateException("收藏夹内容为空")
        return FavoriteResourcePage(
            items = data.medias.filter { it.bvid.isNotBlank() && it.type == 2 },
            page = page,
            hasMore = data.hasMore,
        )
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :core:network:test`
Expected: BUILD SUCCESSFUL，全部通过。

- [ ] **Step 7: 提交（先问用户）**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/ChartDto.kt \
  core/network/src/main/java/com/biliseek/core/network/BiliApi.kt \
  core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt \
  core/network/src/main/java/com/biliseek/core/network/ChartModels.kt \
  core/network/src/test/java/com/biliseek/core/network/ChartDtoTest.kt \
  core/network/src/test/java/com/biliseek/core/network/FavoriteDtoTest.kt \
  core/network/src/test/resources/ranking_v2_sample.json \
  core/network/src/test/resources/fav_video_podcast_sample.json
git add -p core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt   # 只选本任务的块
git commit -m "feat(network): 全站排行榜、原创音乐榜期数、公开收藏夹读取"
```

---

### Task 2: 榜单页支持原创音乐榜、全站排行榜

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/chart/ChartController.kt`

**Interfaces:**
- Consumes: Task 1 的 `rankingVideos()`、`originalSongIssues()`、`hotSongIssue(issue)`
- Produces: `ChartKind.ORIGINAL_SONGS`（title「原创音乐榜」）、`ChartKind.RANKING`（title「全站排行榜」）

（`ChartController` 依赖 `BiliRepository` 实体类、没有单测；这一任务靠编译 + 真机验证。）

- [ ] **Step 1: 加枚举项**

```kotlin
/** 榜单页的种类：首页快捷入口里的榜单，加上搜索框里认出来的活动页。 */
enum class ChartKind(val title: String, val isEraPage: Boolean = false) {
    WEEKLY("每周必看"),
    PRECIOUS("入站必刷"),
    HOT_SONGS("热歌榜"),
    ORIGINAL_SONGS("原创音乐榜"),
    PODCAST("热门播客", isEraPage = true),
    RANKING("全站排行榜"),

    /** 搜索框里认出来的活动页（b23 短链展开，或直接粘贴的网址）；标题用页面自己的 `<title>`。 */
    ERA_PAGE("活动页", isEraPage = true),
}
```

- [ ] **Step 2: 分派**

`fetch`：

```kotlin
            when (kind) {
                ChartKind.PRECIOUS, ChartKind.RANKING -> fetchChart(kind)
                ChartKind.WEEKLY, ChartKind.HOT_SONGS, ChartKind.ORIGINAL_SONGS -> fetchIssued(kind, issue)
                ChartKind.PODCAST, ChartKind.ERA_PAGE -> fetchEra(kind, issue)
            }
```

`fetchChart` 的注释与第一行：

```kotlin
    /** 入站必刷、全站排行榜：没有期数，一次取全。 */
    private suspend fun fetchChart(kind: ChartKind) {
        val result = if (kind == ChartKind.RANKING) repository.rankingVideos() else repository.preciousVideos()
```

`fetchIssued` 的注释改成「每周必看、热歌榜、原创音乐榜」，取期数：

```kotlin
        val issues = when (kind) {
            ChartKind.WEEKLY -> repository.weeklyIssues()
            ChartKind.ORIGINAL_SONGS -> repository.originalSongIssues()
            else -> repository.hotSongIssues()
        }.getOrElse { error ->
```

`fetchIssue`：

```kotlin
        ChartKind.HOT_SONGS, ChartKind.ORIGINAL_SONGS -> chartIssues.getOrNull(index)?.let { repository.hotSongIssue(it) }
        ...
        ChartKind.PRECIOUS, ChartKind.RANKING -> null
```

类 KDoc 第一行改为「首页榜单（每周必看 / 入站必刷 / 热歌榜 / 原创音乐榜 / 热门播客 / 全站排行榜）与搜索框里的活动页」，`selectIssue` 注释里的「每周必看、热歌榜」改为「每周必看、热歌榜、原创音乐榜」。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`when` 穷尽检查通过）。

- [ ] **Step 4: 提交（先问用户）**

```bash
git add app/src/main/java/com/biliseek/app/chart/ChartController.kt
git commit -m "feat(chart): 榜单页加原创音乐榜、全站排行榜"
```

---

### Task 3: 入口清单与设置存储（QuickEntryPolicy + AppSettings）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/data/QuickEntryPolicy.kt`
- Create: `app/src/test/java/com/biliseek/app/data/QuickEntryPolicyTest.kt`
- Modify: `app/src/main/java/com/biliseek/app/data/SettingsStore.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（加 `setQuickEntries`）

**Interfaces:**
- Produces（全部在 `com.biliseek.app.data`）：
  - `enum class QuickEntry(val id: String, val title: String)`：`WEEKLY, PRECIOUS, HOT_SONGS, ORIGINAL_SONGS, PODCAST, VIDEO_PODCAST, RANKING`
  - `data class QuickEntrySetting(val entry: QuickEntry, val enabled: Boolean)`
  - `val DEFAULT_QUICK_ENTRIES: List<QuickEntrySetting>`
  - `const val VIDEO_PODCAST_MEDIA_ID: Long = 3628308062L`
  - `fun resolveQuickEntries(saved: String?): List<QuickEntrySetting>`
  - `fun encodeQuickEntries(list: List<QuickEntrySetting>): String`
  - `fun visibleQuickEntries(list: List<QuickEntrySetting>): List<QuickEntry>`
  - `fun List<QuickEntrySetting>.withEnabled(entry: QuickEntry, enabled: Boolean): List<QuickEntrySetting>`
  - `AppSettings.quickEntries: List<QuickEntrySetting>`（默认 `DEFAULT_QUICK_ENTRIES`）
  - `suspend fun SettingsStore.setQuickEntries(value: List<QuickEntrySetting>)`
  - `fun AppViewModel.setQuickEntries(value: List<QuickEntrySetting>)`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/biliseek/app/data/QuickEntryPolicyTest.kt`：

```kotlin
package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickEntryPolicyTest {

    private val all = QuickEntry.entries.toList()

    @Test
    fun `从没设置过_出厂顺序全部打开`() {
        val r = resolveQuickEntries(null)
        assertEquals(all, r.map { it.entry })
        assertEquals(true, r.all { it.enabled })
        assertEquals(DEFAULT_QUICK_ENTRIES, r)
    }

    @Test
    fun `出厂顺序`() {
        assertEquals(
            listOf("weekly", "precious", "hot_songs", "original_songs", "podcast", "video_podcast", "ranking"),
            all.map { it.id },
        )
    }

    @Test
    fun `顺序与开关往返`() {
        val list = listOf(
            QuickEntrySetting(QuickEntry.RANKING, true),
            QuickEntrySetting(QuickEntry.WEEKLY, false),
            QuickEntrySetting(QuickEntry.PRECIOUS, true),
            QuickEntrySetting(QuickEntry.HOT_SONGS, true),
            QuickEntrySetting(QuickEntry.ORIGINAL_SONGS, false),
            QuickEntrySetting(QuickEntry.PODCAST, true),
            QuickEntrySetting(QuickEntry.VIDEO_PODCAST, true),
        )
        val text = encodeQuickEntries(list)
        assertEquals("ranking,-weekly,precious,hot_songs,-original_songs,podcast,video_podcast", text)
        assertEquals(list, resolveQuickEntries(text))
    }

    @Test
    fun `不认识的id丢掉_重复的只留第一个`() {
        val r = resolveQuickEntries("region:2024,precious,-precious,weekly")
        assertEquals(QuickEntrySetting(QuickEntry.PRECIOUS, true), r[0])
        assertEquals(QuickEntrySetting(QuickEntry.WEEKLY, true), r[1])
        assertEquals(all.size, r.size)
    }

    @Test
    fun `存的里没有的入口_追加到末尾并打开`() {
        val r = resolveQuickEntries("-ranking,weekly")
        assertEquals(listOf(QuickEntry.RANKING, QuickEntry.WEEKLY), r.take(2).map { it.entry })
        assertEquals(false, r[0].enabled)
        assertEquals(
            listOf(QuickEntry.PRECIOUS, QuickEntry.HOT_SONGS, QuickEntry.ORIGINAL_SONGS, QuickEntry.PODCAST, QuickEntry.VIDEO_PODCAST),
            r.drop(2).map { it.entry },
        )
        assertEquals(true, r.drop(2).all { it.enabled })
    }

    @Test
    fun `全部关掉_首页一个都不显示`() {
        val off = DEFAULT_QUICK_ENTRIES.map { it.copy(enabled = false) }
        assertEquals(off, resolveQuickEntries(encodeQuickEntries(off)))
        assertEquals(emptyList<QuickEntry>(), visibleQuickEntries(off))
    }

    @Test
    fun `首页只显示打开的_保持顺序`() {
        val list = DEFAULT_QUICK_ENTRIES.withEnabled(QuickEntry.PRECIOUS, false)
        assertEquals(all - QuickEntry.PRECIOUS, visibleQuickEntries(list))
        assertEquals(all, list.map { it.entry })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.QuickEntryPolicyTest"`
Expected: 编译失败，`Unresolved reference: resolveQuickEntries`。

- [ ] **Step 3: 实现**

`app/src/main/java/com/biliseek/app/data/QuickEntryPolicy.kt`：

```kotlin
package com.biliseek.app.data

// 首页快捷入口的清单与「显示哪些、什么顺序」（设计稿：docs/superpowers/specs/2026-10-21-home-quick-entries-design.md）。
// 纯函数；SettingsStore 只负责落盘，入口 → 榜单 / 收藏夹的映射在 AppViewModel.openQuickEntry。

/**
 * 首页快捷入口。[id] 是存进设置里的稳定 id（不用枚举名，改名不影响已存的设置）；
 * 声明顺序 = 出厂顺序。
 */
enum class QuickEntry(val id: String, val title: String) {
    WEEKLY("weekly", "每周必看"),
    PRECIOUS("precious", "入站必刷"),
    HOT_SONGS("hot_songs", "热歌榜"),
    ORIGINAL_SONGS("original_songs", "原创音乐榜"),
    PODCAST("podcast", "热门播客"),
    VIDEO_PODCAST("video_podcast", "视频播客"),
    RANKING("ranking", "全站排行榜"),
}

data class QuickEntrySetting(val entry: QuickEntry, val enabled: Boolean)

/** 出厂：按声明顺序，全部打开。 */
val DEFAULT_QUICK_ENTRIES: List<QuickEntrySetting> = QuickEntry.entries.map { QuickEntrySetting(it, enabled = true) }

/** 「视频播客」：官方账号的公开收藏夹（网页首页导航「视频播客」页里唯一的播单）。运营换了收藏夹就改这里。 */
const val VIDEO_PODCAST_MEDIA_ID: Long = 3628308062L

private const val OFF_PREFIX = "-"

/**
 * 读设置：[saved] 形如 `weekly,-precious,ranking`（`-` = 关）。
 * - null（从没设置过）→ 出厂；
 * - 不认识的 id 丢掉，重复的只留第一个；
 * - 代码里有、存的里没有的入口（以后版本新加的）追加到末尾，按出厂打开。
 */
fun resolveQuickEntries(saved: String?): List<QuickEntrySetting> {
    if (saved == null) return DEFAULT_QUICK_ENTRIES
    val byId = QuickEntry.entries.associateBy { it.id }
    val result = mutableListOf<QuickEntrySetting>()
    for (token in saved.split(',')) {
        val text = token.trim()
        val entry = byId[text.removePrefix(OFF_PREFIX)] ?: continue
        if (result.none { it.entry == entry }) result += QuickEntrySetting(entry, enabled = !text.startsWith(OFF_PREFIX))
    }
    QuickEntry.entries
        .filter { entry -> result.none { it.entry == entry } }
        .forEach { result += QuickEntrySetting(it, enabled = true) }
    return result
}

/** 写设置，与 [resolveQuickEntries] 互逆。 */
fun encodeQuickEntries(list: List<QuickEntrySetting>): String =
    list.joinToString(",") { if (it.enabled) it.entry.id else OFF_PREFIX + it.entry.id }

/** 首页要显示的：只留打开的，顺序不变。 */
fun visibleQuickEntries(list: List<QuickEntrySetting>): List<QuickEntry> =
    list.filter { it.enabled }.map { it.entry }

/** 开 / 关某一个，位置不变。 */
fun List<QuickEntrySetting>.withEnabled(entry: QuickEntry, enabled: Boolean): List<QuickEntrySetting> =
    map { if (it.entry == entry) it.copy(enabled = enabled) else it }
```

`SettingsStore.kt`：

- `AppSettings` 末尾（`downloadConcurrency` 之后）加：

```kotlin
    /** 首页快捷入口的顺序与开关（设置 → 首页快捷入口）。出厂全部打开。 */
    val quickEntries: List<QuickEntrySetting> = DEFAULT_QUICK_ENTRIES,
```

- `load()` 的 `AppSettings(...)` 里 `downloadConcurrency = ...` 之后加：

```kotlin
            quickEntries = resolveQuickEntries(p[KEY_QUICK_ENTRIES]),
```

- `setDownloadConcurrency` 之后加：

```kotlin
    suspend fun setQuickEntries(value: List<QuickEntrySetting>) = update { it.copy(quickEntries = value) }
```

- `update` 的 `edit` 块里 `p[KEY_DOWNLOAD_CONCURRENCY] = ...` 之后加：

```kotlin
            p[KEY_QUICK_ENTRIES] = encodeQuickEntries(next.quickEntries)
```

- 键表里 `KEY_DOWNLOAD_CONCURRENCY` 之后加：

```kotlin
        val KEY_QUICK_ENTRIES = stringPreferencesKey("home_quick_entries")
```

（同包，不需要 import。）

`AppViewModel.kt`，在 `setThemeMode` 之后加：

```kotlin
    fun setQuickEntries(value: List<com.biliseek.app.data.QuickEntrySetting>) = viewModelScope.launch {
        settingsStore.setQuickEntries(value)
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.QuickEntryPolicyTest"`
Expected: PASS（7 个）。

- [ ] **Step 5: 提交（先问用户）**

```bash
git add app/src/main/java/com/biliseek/app/data/QuickEntryPolicy.kt \
  app/src/test/java/com/biliseek/app/data/QuickEntryPolicyTest.kt \
  app/src/main/java/com/biliseek/app/data/SettingsStore.kt
git add -p app/src/main/java/com/biliseek/app/AppViewModel.kt
git commit -m "feat(home): 快捷入口清单与开关/顺序的存储规则"
```

---

### Task 4: 拖动排序改成通用版（本地列表行为不变）

**Files:**
- Rename + Modify: `app/src/main/java/com/biliseek/app/ui/PlaylistReorder.kt` → `app/src/main/java/com/biliseek/app/ui/DragReorder.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt:725`（调用处）

**Interfaces:**
- Produces（`com.biliseek.app.ui`，`internal`）：
  - `class DragReorderState<T>`：`val order: List<T>`、`val draggingId: Any?`、`val settlingId: Any?`、`fun offsetOf(key: Any): Float`、`fun start(key: Any)`、`fun dragBy(dy: Float)`、`fun end()`、`fun cancel()`
  - `@Composable fun <T> rememberDragReorderState(listState: LazyListState, items: List<T>, keyOf: (T) -> Any, onMove: (key: Any, from: Int, to: Int) -> Unit): DragReorderState<T>`

- [ ] **Step 1: 先确认现有测试是绿的**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.ReorderPolicyTest"`
Expected: PASS。

- [ ] **Step 2: 改名并泛化**

```bash
git mv app/src/main/java/com/biliseek/app/ui/PlaylistReorder.kt app/src/main/java/com/biliseek/app/ui/DragReorder.kt
```

`DragReorder.kt` 的改动（其余行不动）：

- 删掉 import `com.biliseek.app.data.PlaylistTrack`、`com.biliseek.app.data.reorderNeighbors`。
- KDoc 第一行改为「长按拖动排序（本地列表、设置 → 首页快捷入口共用；设计稿：…playlist-reorder-design.md）」，「松手：…按『最初位置 → 最终位置』写一次库（两侧 sortKey 取中值）」改为「松手：这一行从手指处滑进空位，回调一次『最初位置 → 最终位置』（本地列表据此算两侧 sortKey 写库）」。
- 类头与成员：

```kotlin
@Stable
internal class DragReorderState<T>(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
    private val items: () -> List<T>,
    private val keyOf: (T) -> Any,
    private val onMove: (key: Any, from: Int, to: Int) -> Unit,
) {
    /** 拖动中（以及松手后新顺序还没回来时）的本地顺序；null 时直接显示 [items]。 */
    private var localOrder by mutableStateOf<List<T>?>(null)
    val order: List<T> get() = localOrder ?: items()

    /** 正在拖的那一条的 key。 */
    var draggingId by mutableStateOf<Any?>(null)
        private set
```

- `settlingId` 类型改 `Any?`；`onTracksChanged` 改名 `onItemsChanged`，注释「列表变了（写库 / 存设置回来、增删）」。
- `fun offsetOf(id: Long)` → `fun offsetOf(key: Any)`，函数体里 `id` → `key`。
- `start`：

```kotlin
    fun start(key: Any) {
        val list = order
        val index = list.indexOfFirst { keyOf(it) == key }
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        localOrder = list
        fromIndex = items().indexOfFirst { keyOf(it) == key }
        draggedIndex = index
        startTop = info.offset
        delta = 0f
        settlingId = null
        draggingId = key
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
```

- `end` 里写库那段：

```kotlin
        if (to != null && fromIndex >= 0 && to != fromIndex) {
            onMove(id, fromIndex, to)
        } else {
            localOrder = null
        }
```

- `remember` 函数：

```kotlin
@Composable
internal fun <T> rememberDragReorderState(
    listState: LazyListState,
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (key: Any, from: Int, to: Int) -> Unit,
): DragReorderState<T> {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    val currentKeyOf by rememberUpdatedState(keyOf)
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(listState) {
        DragReorderState(
            listState = listState,
            scope = scope,
            haptic = haptic,
            edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() },
            maxSpeedPx = with(density) { AUTO_SCROLL_MAX_SPEED.toPx() },
            items = { currentItems },
            keyOf = { currentKeyOf(it) },
            onMove = { key, from, to -> currentOnMove(key, from, to) },
        )
    }
    LaunchedEffect(items) { state.onItemsChanged() }
    LaunchedEffect(state.draggingId) {
        if (state.draggingId != null) state.autoScrollWhileDragging()
    }
    return state
}
```

`LibraryScreen.kt:725` 改为（加 import `com.biliseek.app.data.reorderNeighbors`，若已有则不加）：

```kotlin
    // 松手：按「最初位置 → 最终位置」算两侧 sortKey（取中值）写一次库
    val reorder = rememberDragReorderState(listState, tracks, keyOf = { it.track.id }) { key, from, to ->
        val (before, after) = reorderNeighbors(tracks.map { it.sortKey }, from, to)
        onTrackReorder(key as Long, before, after)
    }
```

同文件 KDoc 里的 `[PlaylistReorderState]` 改为 `[DragReorderState]`。其余用法（`reorder.order`、`draggingId == id`、`offsetOf(id)`、`start(id)`）不用改。

- [ ] **Step 3: 编译 + 测试**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.biliseek.app.data.ReorderPolicyTest"`
Expected: BUILD SUCCESSFUL，PASS。另 `grep -rn "PlaylistReorderState\|rememberPlaylistReorderState" app/src` 应无结果。

- [ ] **Step 4: 提交（先问用户）**

```bash
git add app/src/main/java/com/biliseek/app/ui/DragReorder.kt app/src/main/java/com/biliseek/app/ui/PlaylistReorder.kt
git add -p app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt
git commit -m "refactor(ui): 拖动排序改成按任意 key 的通用 DragReorderState"
```

---

### Task 5: 视频播客——只读打开公开收藏夹

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/remote/RemoteLibraryController.kt`（131–151 行附近、386–430 行）
- Modify: `app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt`（`FavoriteDetail`，890–1051 行）

**Interfaces:**
- Consumes: Task 1 `publicFavoriteResources`
- Produces:
  - `RemoteLibraryController.openedPublicFavorite: StateFlow<RemoteListState<FavoriteTrack>?>`
  - `fun openPublicFavorite(mediaId: Long, title: String)`、`fun closePublicFavorite()`、`fun loadPublicFavoritePage(page: Int)`
  - `internal fun FavoriteDetail(..., readOnly: Boolean = false)`（`com.biliseek.app.ui`）

（控制器没有单测设施；这一任务靠编译 + 真机。）

- [ ] **Step 1: 控制器**

在 `renameOpenedFavorite` 之前（`openedFavorite` 声明之后）加：

```kotlin
    /**
     * 首页「视频播客」：别人的公开收藏夹，只读。状态与 [openedFavorite] 分开——
     * 那一份不为空时「我的」页就显示收藏夹内容页，共用会让「我的」页也冒出视频播客。
     */
    private val _openedPublicFavorite =
        MutableStateFlow<RemoteListState<FavoriteTrack>?>(null)
    val openedPublicFavorite: StateFlow<RemoteListState<FavoriteTrack>?> = _openedPublicFavorite.asStateFlow()

    fun openPublicFavorite(mediaId: Long, title: String) {
        _openedPublicFavorite.value = RemoteListState(id = TrackListId.Favorite(mediaId), title = title)
        loadPublicFavoritePage(1)
    }

    fun closePublicFavorite() { _openedPublicFavorite.value = null }
```

把 `loadFavoritePage` 改成按目标状态与取数函数参数化（函数体只把 `_openedFavorite` 换成 `target`、`repository.favoriteResources(...)` 换成 `fetch(...)`，其余一字不改）：

```kotlin
    fun loadFavoritePage(page: Int) = loadFavoritePageInto(_openedFavorite, page, repository::favoriteResources)

    /** 公开收藏夹翻页：同一套逻辑，取数不要求登录；只读，没有待提交的移除。 */
    fun loadPublicFavoritePage(page: Int) =
        loadFavoritePageInto(_openedPublicFavorite, page, repository::publicFavoriteResources)

    /**
     * 加载收藏夹内容。[page] 为 1 是刷新（从第 1 页替换），大于 1 是往下翻（追加）。
     * （原 loadFavoritePage 的 KDoc 其余两行原样保留）
     */
    private fun loadFavoritePageInto(
        target: MutableStateFlow<RemoteListState<FavoriteTrack>?>,
        page: Int,
        fetch: suspend (mediaId: Long, page: Int, pageSize: Int) -> Result<FavoriteResourcePage>,
    ) {
        val state = target.value ?: return
        val id = state.id as? TrackListId.Favorite ?: return
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        scope.launch {
            val loadingMore = page > 1
            val fetchPage = if (loadingMore) {
                favoriteFetchPage(state.items.size + pendingFavoriteRemovals(id.mediaId).size, FAVORITE_PAGE_SIZE)
            } else {
                1
            }
            target.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            fetch(id.mediaId, fetchPage, FAVORITE_PAGE_SIZE)
                .onSuccess { result ->
                    val base = target.value ?: return@onSuccess
                    val existing = if (loadingMore) base.items else emptyList()
                    val items = appendUnseen(
                        existing,
                        result.items.map { it.toTrack() },
                        pendingFavoriteRemovals(id.mediaId),
                    ) { it.bvid }
                    target.value = base.copy(
                        items = items,
                        page = result.page,
                        // 往下翻却一条新的都没有（别处同时改了这个收藏夹）：停止自动加载，免得底部一直转圈
                        hasMore = result.hasMore && (!loadingMore || items.size > existing.size),
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    val base = target.value ?: return@onFailure
                    target.value = base.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }
```

（这就是原 `loadFavoritePage` 的函数体，只把 `_openedFavorite` 换成 `target`、`repository.favoriteResources(...)` 换成 `fetch(...)`。公开收藏夹的 `pendingFavoriteRemovals` 永远为空，翻页算法与自己的收藏夹一致。`FavoriteResourcePage` 在 `com.biliseek.core.network` 包，加 `import com.biliseek.core.network.FavoriteResourcePage`。）

- [ ] **Step 2: 收藏夹内容页加只读**

`FavoriteDetail`：`private` → `internal`，参数末尾加：

```kotlin
    /** 别人的公开收藏夹（首页「视频播客」）：没有编辑 / 删除收藏夹、右滑移除、菜单里的「移除」。 */
    readOnly: Boolean = false,
```

KDoc 末尾补一句「[readOnly] 时只能看、播。」。函数体内：

- `val mediaId = ...` 改为 `val mediaId = (state.id as? TrackListId.Favorite)?.mediaId?.takeIf { !readOnly }`（编辑 / 删除的对话框和标题栏按钮都已经判断 `mediaId != null`，自然消失）。
- 三点菜单：

```kotlin
        VideoMenuSheet(
            track.toSearchVideo(),
            videoActions,
            onRemove = if (readOnly) null else ({ onRemoveTrack(track) }),
        ) { menuFor = null }
```

- 列表行：`SwipeToRemoveRow` 已有 `enabled` 参数（为 false 时不能右滑、也不放右滑演示），只读时关掉即可：

```kotlin
                        SwipeToRemoveRow(
                            background = MaterialTheme.colorScheme.background,
                            onRemove = { onRemoveTrack(track) },
                            enabled = !readOnly,
                            hintKey = HintKey.SWIPE_FAVORITE,
                            showHint = track.bvid == state.items.firstOrNull()?.bvid,
                        ) {
                            // …Row 不变
```

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交（先问用户）**

```bash
git add -p app/src/main/java/com/biliseek/app/remote/RemoteLibraryController.kt app/src/main/java/com/biliseek/app/ui/LibraryScreen.kt
git commit -m "feat(remote): 只读打开公开收藏夹（首页视频播客）"
```

---

### Task 6: 首页——按设置显示入口（每行 4 张）+ 首次提示 + 接线

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/hint/HintPolicy.kt`
- Modify: `app/src/test/java/com/biliseek/app/hint/HintPolicyTest.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（`openQuickEntry`）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`searchContent` 分支、`HomeEmptyState` 调用处）

**Interfaces:**
- Consumes: Task 2 `ChartKind.*`；Task 3 `QuickEntry`、`visibleQuickEntries`、`VIDEO_PODCAST_MEDIA_ID`、`AppSettings.quickEntries`；Task 5 `openPublicFavorite`、`openedPublicFavorite`、`loadPublicFavoritePage`、`closePublicFavorite`、`FavoriteDetail(readOnly)`
- Produces:
  - `HintKey.HOME_QUICK_ENTRIES`
  - `internal fun quickEntryIcon(entry: QuickEntry): ImageVector`（`HomeScreen.kt`，Task 7 用）
  - `fun AppViewModel.openQuickEntry(entry: QuickEntry)`
  - `HomeEmptyState(... quickEntries: List<QuickEntry>, onOpenQuickEntry: (QuickEntry) -> Unit ...)`（替换 `onOpenChart`）

- [ ] **Step 1: 写失败的测试（提示）**

`HintPolicyTest.kt` 类内加：

```kotlin
    @Test
    fun `首页快捷入口提示_没看过能放_放完记为看过`() {
        assertTrue(canStartHint(HintKey.HOME_QUICK_ENTRIES, state()))
        val started = startHint(state(), HintKey.HOME_QUICK_ENTRIES)
        assertEquals(HintKey.HOME_QUICK_ENTRIES, started.active)
        val seen = markHintSeen(started, HintKey.HOME_QUICK_ENTRIES)
        assertNull(seen.active)
        assertFalse(canStartHint(HintKey.HOME_QUICK_ENTRIES, seen))
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.hint.HintPolicyTest"`
Expected: 编译失败，`Unresolved reference: HOME_QUICK_ENTRIES`。

- [ ] **Step 2: 加 HintKey**

`HintPolicy.kt`：注释「13 项演示」改「14 项演示」，枚举末尾加：

```kotlin
    /** 首页快捷入口：只有说明气泡，没有手势动画（设计稿 2026-10-21-home-quick-entries）。 */
    HOME_QUICK_ENTRIES("hint_home_quick_entries"),
```

Run 同上，Expected: PASS（`HintStore` 按 `HintKey.entries` 读写，自动带上新键）。

- [ ] **Step 3: 首页网格**

`HomeScreen.kt`：

- 文件头 KDoc 里「快捷入口是官方榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客）」改为「快捷入口是官方榜单与官方播单（设置 → 首页快捷入口 里选显示哪些、调顺序）」。
- `HomeEmptyState` 参数：删掉 `onOpenChart`，在原位置加：

```kotlin
    /** 要显示的快捷入口（已按设置过滤、排好序）；空 = 整节不显示。 */
    quickEntries: List<QuickEntry> = emptyList(),
    /** 点快捷入口 → 打开对应的榜单页 / 播单。 */
    onOpenQuickEntry: (QuickEntry) -> Unit = {},
```

- 原来「快捷入口」那一整段 `Section(title = "快捷入口") { Row { 4 个 QuickEntryCard } }` 换成：

```kotlin
        if (quickEntries.isNotEmpty()) {
            Section(title = "快捷入口") { QuickEntryGrid(quickEntries, onOpenQuickEntry) }
        }
```

- `QuickEntryCard` 之前加：

```kotlin
/**
 * 快捷入口网格：每行 [QUICK_ENTRY_COLUMNS] 张，最后一行不足时靠左、空位等宽占住。
 * 第一次显示时在上方弹一次说明气泡（没有手势动画，碰一下或停满时长后记为看过）。
 */
@Composable
private fun QuickEntryGrid(entries: List<QuickEntry>, onOpen: (QuickEntry) -> Unit) {
    val demo = rememberHintDemo(HintKey.HOME_QUICK_ENTRIES, ready = entries.isNotEmpty())
    var showBubble by remember { mutableStateOf(false) }
    LaunchedEffect(demo.running) {
        showBubble = false
        if (!demo.running) return@LaunchedEffect
        delay(HintTiming.START_DELAY_MS)
        showBubble = true
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
    }
    Box {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entries.chunked(QUICK_ENTRY_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        QuickEntryCard(
                            icon = quickEntryIcon(entry),
                            label = entry.title,
                            onClick = { onOpen(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(QUICK_ENTRY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (showBubble) HintBubble(text = QUICK_ENTRY_HINT_TEXT, preferAbove = true)
    }
}

/** 快捷入口的图标；首页与设置页共用。 */
internal fun quickEntryIcon(entry: QuickEntry): ImageVector = when (entry) {
    QuickEntry.WEEKLY -> Icons.Outlined.CalendarMonth
    QuickEntry.PRECIOUS -> Icons.Outlined.Whatshot
    QuickEntry.HOT_SONGS -> Icons.Outlined.Leaderboard
    QuickEntry.ORIGINAL_SONGS -> Icons.Outlined.MusicNote
    QuickEntry.PODCAST -> Icons.Outlined.Podcasts
    QuickEntry.VIDEO_PODCAST -> Icons.Outlined.Mic
    QuickEntry.RANKING -> Icons.Outlined.EmojiEvents
}

private const val QUICK_ENTRY_COLUMNS = 4

private const val QUICK_ENTRY_HINT_TEXT = "入口可以在 设置 → 首页快捷入口 里关闭和排序"
```

- `QuickEntryCard` 的 `icon` 参数类型写成 `ImageVector`（加 import），其余不变。
- import：删 `com.biliseek.app.chart.ChartKind`；加 `androidx.compose.material.icons.outlined.EmojiEvents`、`...outlined.Mic`、`...outlined.MusicNote`、`androidx.compose.ui.graphics.vector.ImageVector`、`androidx.compose.runtime.getValue`、`androidx.compose.runtime.mutableStateOf`、`androidx.compose.runtime.setValue`、`com.biliseek.app.data.QuickEntry`、`com.biliseek.app.hint.HintKey`、`com.biliseek.app.ui.components.HintBubble`、`com.biliseek.app.ui.components.HintTiming`、`com.biliseek.app.ui.components.rememberHintDemo`、`kotlinx.coroutines.delay`。

- [ ] **Step 4: AppViewModel.openQuickEntry**

在 Task 3 加的 `setQuickEntries` 之后：

```kotlin
    /** 首页点快捷入口：榜单类打开榜单页，视频播客只读打开官方公开收藏夹。 */
    fun openQuickEntry(entry: com.biliseek.app.data.QuickEntry) {
        when (entry) {
            com.biliseek.app.data.QuickEntry.WEEKLY -> charts.open(ChartKind.WEEKLY)
            com.biliseek.app.data.QuickEntry.PRECIOUS -> charts.open(ChartKind.PRECIOUS)
            com.biliseek.app.data.QuickEntry.HOT_SONGS -> charts.open(ChartKind.HOT_SONGS)
            com.biliseek.app.data.QuickEntry.ORIGINAL_SONGS -> charts.open(ChartKind.ORIGINAL_SONGS)
            com.biliseek.app.data.QuickEntry.PODCAST -> charts.open(ChartKind.PODCAST)
            com.biliseek.app.data.QuickEntry.RANKING -> charts.open(ChartKind.RANKING)
            com.biliseek.app.data.QuickEntry.VIDEO_PODCAST ->
                remoteLibrary.openPublicFavorite(com.biliseek.app.data.VIDEO_PODCAST_MEDIA_ID, entry.title)
        }
    }
```

（若文件顶部已 import `ChartKind` 就直接用；否则加 `import com.biliseek.app.chart.ChartKind`。若 `AppViewModel` 已 import 了 `com.biliseek.app.data.*` 的东西，改用短名。）

- [ ] **Step 5: MainActivity 接线**

1) `val chart by viewModel.charts.opened.collectAsState()`（第 223 行）之后加：

```kotlin
                val publicFavorite by viewModel.remoteLibrary.openedPublicFavorite.collectAsState()
```

2) `searchContent` 里：

```kotlin
                        LaunchedEffect(search.keyword) {
                            if (search.keyword.isNotBlank()) {
                                viewModel.charts.close()
                                viewModel.remoteLibrary.closePublicFavorite()
                            }
                        }
```

（注释「榜单页不能盖住结果页」改为「榜单页 / 视频播客不能盖住结果页」。）在 `} else if (openedChart != null) { ... ChartScreen(...) }` 之后、`} else {` 之前插入：

```kotlin
                        } else if (publicFavorite != null) {
                            // 视频播客：从首页快捷入口点入，只读的收藏夹内容页；返回手势收起到首页
                            BackHandler(onBack = viewModel.remoteLibrary::closePublicFavorite)
                            com.biliseek.app.ui.FavoriteDetail(
                                state = publicFavorite!!,
                                onClose = viewModel.remoteLibrary::closePublicFavorite,
                                onTrackClick = viewModel.remoteLibrary::playFavoriteTrack,
                                onLoadMore = viewModel.remoteLibrary::loadPublicFavoritePage,
                                onEditFolder = { _, _ -> },
                                onDeleteFolder = {},
                                onRemoveTrack = {},
                                videoActions = videoMenuActions,
                                readOnly = true,
                            )
```

（`publicFavorite` 是委托属性，智能转换不了，用局部 `val openedPublic = publicFavorite` 与 `openedChart` 同样写法更好：在 `val openedChart = chart` 下加 `val openedPublic = publicFavorite`，分支用 `openedPublic != null` 和 `state = openedPublic`。）

3) `HomeEmptyState(...)` 调用处：删 `onOpenChart = viewModel.charts::open,`，加：

```kotlin
                                        quickEntries = visibleQuickEntries(settings.quickEntries),
                                        onOpenQuickEntry = viewModel::openQuickEntry,
```

（`settings` 已在第 245 行收集；加 import `com.biliseek.app.data.visibleQuickEntries`。若 `settings` 声明在该调用之后、不在作用域内，挪用同一个 `viewModel.settings` 收集结果。）

- [ ] **Step 6: 编译 + 测试**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部通过。

- [ ] **Step 7: 提交（先问用户）**

```bash
git add app/src/main/java/com/biliseek/app/hint/HintPolicy.kt app/src/test/java/com/biliseek/app/hint/HintPolicyTest.kt \
  app/src/main/java/com/biliseek/app/ui/HomeScreen.kt
git add -p app/src/main/java/com/biliseek/app/AppViewModel.kt app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(home): 快捷入口按设置显示（每行 4 张）、首次提示、视频播客入口"
```

---

### Task 7: 设置 → 首页快捷入口（开关 + 拖动排序）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/QuickEntrySettingsScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt`（参数 + 「界面」分组一行）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`settingsContent`、第 314 行附近的状态）

**Interfaces:**
- Consumes: Task 3 `QuickEntrySetting`、`withEnabled`、`visibleQuickEntries`、`moved`（`com.biliseek.app.data.moved`，已存在）、`AppViewModel.setQuickEntries`；Task 4 `rememberDragReorderState`；Task 6 `quickEntryIcon`
- Produces: `@Composable fun QuickEntrySettingsScreen(entries: List<QuickEntrySetting>, onChange: (List<QuickEntrySetting>) -> Unit, onClose: () -> Unit)`；`SettingsScreen(... onOpenQuickEntries: () -> Unit = {})`

- [ ] **Step 1: 新页面**

`app/src/main/java/com/biliseek/app/ui/QuickEntrySettingsScreen.kt`：

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.biliseek.app.data.QuickEntrySetting
import com.biliseek.app.data.moved
import com.biliseek.app.data.withEnabled
import com.biliseek.app.ui.components.SettingsIcon
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsTopBar

/**
 * 设置 → 首页快捷入口（设计稿：docs/superpowers/specs/2026-10-21-home-quick-entries-design.md）。
 * 每行：图标、名字、开关、拖动手柄。整行点 = 开关；长按拖动排序（与本地列表同一套：浮起、让位、每格轻震、边缘自动滚动）。
 * 关掉的入口留在原位，再打开回到原位。每次改动立刻存。
 */
@Composable
fun QuickEntrySettingsScreen(
    entries: List<QuickEntrySetting>,
    onChange: (List<QuickEntrySetting>) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = rememberDragReorderState(listState, entries, keyOf = { it.entry.id }) { _, from, to ->
        onChange(entries.moved(from, to))
    }
    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "首页快捷入口", onNavigate = onClose)
        Text(
            "长按拖动调整顺序；关掉的入口不在首页显示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
            items(reorder.order, key = { it.entry.id }) { item ->
                val id = item.entry.id
                val lifted = reorder.draggingId == id
                val floating = lifted || reorder.settlingId == id
                Box(
                    (
                        if (floating) {
                            Modifier.zIndex(1f).graphicsLayer {
                                translationY = reorder.offsetOf(id)
                                shadowElevation = if (lifted) LIFT_ELEVATION.toPx() else 0f
                            }
                        } else {
                            // 别的行在本地顺序变化时滑到新位置：这就是「让位」
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        }
                        )
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { reorder.start(id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    reorder.dragBy(amount.y)
                                },
                                onDragEnd = { reorder.end() },
                                onDragCancel = { reorder.cancel() },
                            )
                        },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = item.enabled, role = Role.Switch) {
                                onChange(entries.withEnabled(item.entry, it))
                            }
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIcon(quickEntryIcon(item.entry))
                        Text(item.entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = item.enabled, onCheckedChange = null)
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = "拖动排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 拿起那一行的阴影。 */
private val LIFT_ELEVATION = 8.dp
```


- [ ] **Step 2: 设置页加一行**

`SettingsScreen.kt`：参数表 `onDownloadConcurrencyChange` 之后加：

```kotlin
    /** 打开「首页快捷入口」页。 */
    onOpenQuickEntries: () -> Unit = {},
```

「界面」分组里「迷你播放器样式」之后、「重新显示操作提示」之前加：

```kotlin
                SettingsRow(
                    title = "首页快捷入口",
                    icon = Icons.Outlined.GridView,
                    value = "显示 ${visibleQuickEntries(settings.quickEntries).size} 个",
                    onClick = onOpenQuickEntries,
                )
```

import：`androidx.compose.material.icons.outlined.GridView`、`com.biliseek.app.data.visibleQuickEntries`。KDoc 无需改。

- [ ] **Step 3: MainActivity 接线**

`var aiSettingsOpen by remember { mutableStateOf(false) }`（第 314 行）之后加：

```kotlin
                var quickEntriesOpen by remember { mutableStateOf(false) }
```

`settingsContent` 里 `if (aiSettingsOpen) { ... }` 之后、`else SettingsScreen(` 之前改成：

```kotlin
                        } else if (quickEntriesOpen) {
                            BackHandler(onBack = { quickEntriesOpen = false })
                            com.biliseek.app.ui.QuickEntrySettingsScreen(
                                entries = settings.quickEntries,
                                onChange = viewModel::setQuickEntries,
                                onClose = { quickEntriesOpen = false },
                            )
                        } else SettingsScreen(
```

并在 `SettingsScreen(` 的参数里加 `onOpenQuickEntries = { quickEntriesOpen = true },`。

- [ ] **Step 4: 全量验证**

Run: `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交（先问用户）**

```bash
git add app/src/main/java/com/biliseek/app/ui/QuickEntrySettingsScreen.kt app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt
git add -p app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(settings): 首页快捷入口开关与拖动排序"
```

---

### Task 8: 文档

**Files:**
- Modify: `docs/05-功能优化篇.md`（§13 改动记录加一条）
- Modify: `docs/09-UI规范与重构.md`（首页快捷入口、设置页一节）
- Modify: `docs/BiliSeek-手动测试指南.md`（加步骤）
- Add: `docs/superpowers/specs/2026-10-21-home-quick-entries-design.md`、本计划文件

注意：这三份 docs 里已有消息角标那批**未提交**的改动，提交时用 `git add -p` 只选本功能的块。

- [ ] **Step 1: 05 §13**

先 `grep -n "^### 13\.\|^## 13" docs/05-功能优化篇.md | tail -5` 找到最后一条的编号与格式，照格式追加一条「首页快捷入口可配置（第一期）」：新增原创音乐榜（`list_type=2`）、视频播客（官方公开收藏夹 `3628308062`，只读）、全站排行榜（`ranking/v2`，不签名）；设置 → 界面 → 首页快捷入口（开关 + 拖动排序，键 `home_quick_entries`）；首次提示 `HOME_QUICK_ENTRIES`；`PlaylistReorderState` → 通用 `DragReorderState`；第二期（分区入口，`newlist_rank` + v1 tid）待做。设计稿链接。

- [ ] **Step 2: 09**

`grep -n "快捷入口" docs/09-UI规范与重构.md` 找到首页一节，改为：快捷入口每行 4 张卡片、自动换行、最后一行靠左等宽占位；全部关掉整节不显示；入口清单与图标对照（7 个）。设置页「界面」分组新增「首页快捷入口」行；入口设置页的行结构（图标 / 名字 / 开关 / 拖动手柄，整行点切换，长按拖动）。

- [ ] **Step 3: 手动测试指南**

`grep -n "快捷入口\|^## \|^### 步骤\|^[0-9]\+\." docs/BiliSeek-手动测试指南.md | head -40` 找到首页榜单的步骤，在其后按原格式加：
1. 首页快捷入口两行共 7 个；第一次显示时上方有说明气泡，碰一下或约 4 秒后消失，之后不再出现（设置 → 重新显示操作提示后会再出现一次）。
2. 原创音乐榜：有期数选择条，能切期、能播。
3. 全站排行榜：100 条，名次 1–100，页头是官方说明，能播。
4. 视频播客：未登录也能打开；滚到底接着加载；标题栏没有编辑 / 删除，条目不能右滑，三点菜单没有「移除」；返回回到首页；切到「我的」页不受影响。
5. 设置 → 界面 → 首页快捷入口：关掉一个，首页立刻少一个，行显示「显示 6 个」；长按拖动换顺序，首页顺序跟着变；杀进程重开后保持；全部关掉首页没有「快捷入口」一节。

- [ ] **Step 4: 提交（先问用户）**

```bash
git add docs/superpowers/specs/2026-10-21-home-quick-entries-design.md docs/superpowers/plans/2026-10-21-home-quick-entries.md
git add -p docs/05-功能优化篇.md docs/09-UI规范与重构.md docs/BiliSeek-手动测试指南.md
git commit -m "docs: 首页快捷入口（第一期）设计稿、计划与文档同步"
```
