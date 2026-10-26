# 合集（播放页所属合集 + UP 空间合集）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 播放页显示当前视频所属的合集（入口行 + 底部弹层，可点某一集起播整个合集、可订阅 / 取消订阅），UP 空间加「合集」视图（合集与旧版系列，点开是合集内容页）。

**Architecture:** 网络层给 `view` / `relation` 补 `ugc_season` / `season_fav` 字段，新增 UP 空间合集列表、合集内容、系列内容三个读接口（走 `wbiRequest`）和订阅写接口（走 `writeRequest`）。`:app` 新建 `collection/` 包：`CollectionPolicy`（纯函数）+ `CollectionController`。订阅合集列表和合集内容页从 `RemoteLibraryController` 迁到 `CollectionController`，内容页分 `LIBRARY` / `UP_SPACE` 两个槽。当前视频属于哪个合集由 `VideoInteractionController` 在已有的 `view` 请求里顺带解析。设计稿：`docs/superpowers/specs/2026-10-26-collections-design.md`。

**Tech Stack:** Kotlin、kotlinx.serialization、Retrofit、Jetpack Compose（Material3）、JUnit4、Compose UI Test。

## Global Constraints

- **自动化代码绝不请求 B 站服务器**：所有测试只用本地 JSON / 构造数据；真机效果由用户验证。
- 不在 B 站请求外加任何重试循环。新读接口一律走 `BiliRepository.wbiRequest`（WBI 签名 + `FEED` 限速桶 + 冷却期检查 + `refreshingOn101`）；订阅走 `writeRequest`（自动带 csrf、`-111` / `-101` 只重试一次、`-352` 进冷却）。
- 合集内容只放内存，不写 Room / DataStore。
- 新增的 `public` 类型只有：DTO（`UgcSeason` / `UgcSection` / `UgcEpisode` / `UgcEpisodeArc` / `SeasonsSeriesListData` / `SeasonsSeriesItems` / `SpaceCollectionPage` / `SpaceSeasonItem` / `SpaceSeasonMeta` / `SpaceSeriesItem` / `SpaceSeriesMeta` / `SeasonArchivesData` / `SpaceCollectionArchive`），`SpaceCollectionsPage`，以及 `BiliRepository.spaceCollections` / `spaceSeasonArchives` / `favSeason`、改签名的 `seriesArchives`。`:core:network` 的纯函数放 `internal`。
- 叫法统一：「合集」「系列」「订阅」「已订阅」「取消订阅」。提示文案：「已订阅「X」」「已取消订阅「X」」「订阅失败：…」「取消订阅失败：…」「请先登录」；空状态「这位 UP 主还没有合集」。提示走 `notify` / `emitMessage`，不直接 `Toast.makeText`。
- 底部弹层用 `CompactDragHandle`；NavHost 里的新列表用 `miniBarContentPadding()`；滚到底自动加载用 `shouldAutoLoadMore`（失败后不自动再来）。
- 「列表 → 合集」订阅合集页（列表、翻页、点播续页、取消订阅）迁移后行为不变。
- 所有提交在分支 `feat/collections` 上，提交信息结尾带：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- 全部任务完成、本地验证通过后直接 `./gradlew installDebug` 装到真机（用户 10-24 的要求），装机前提醒用户解锁手机、点 ColorOS 安装确认页。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt` | 改 | `VideoViewInfo.ugcSeason`、`ArchiveRelation.seasonFav`、UP 空间合集 / 合集内容 / 系列内容 DTO（`SeriesArchivesData`、`SeriesPage` 按真实结构重写） |
| `core/network/src/main/java/com/biliseek/core/network/internal/CollectionPagePolicy.kt` | 新建 | `hasMorePages`：按总数 / 本页条数判断还有没有下一页 |
| `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt` | 改 | `seasonsSeriesList`、`seasonArchives`、`seriesArchives`（改成 `@QueryMap`）、`favSeason` |
| `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt` | 改 | `SpaceCollectionsPage`；`spaceCollections`、`spaceSeasonArchives`、`seriesArchives`（重写）、`favSeason` |
| `core/network/src/test/java/com/biliseek/core/network/CollectionDtoTest.kt` | 新建 | DTO 解析 |
| `core/network/src/test/java/com/biliseek/core/network/CollectionPagePolicyTest.kt` | 新建 | `hasMorePages` |
| `app/src/main/java/com/biliseek/app/collection/CollectionModels.kt` | 新建 | `CollectionRef`、`CollectionSlot`、`SpaceCollection`、`VideoCollection`、`CollectionSection`、`CollectionEpisode`、`CollectionSheetRow` |
| `app/src/main/java/com/biliseek/app/collection/CollectionPolicy.kt` | 新建 | 视频详情 → 合集、进度文字、弹层的行、滚动位置、点集是否起播、UP 空间列表合并与文字 |
| `app/src/main/java/com/biliseek/app/collection/CollectionController.kt` | 新建 | 订阅合集列表、合集内容页两个槽、播放页弹层起播、订阅 / 取消订阅 |
| `app/src/main/java/com/biliseek/app/data/RemoteListState.kt` | 改 | `TrackListId.Series` → `TrackListId.Collection(ref)` |
| `app/src/main/java/com/biliseek/app/remote/RemoteLibraryController.kt` | 改 | 删掉迁走的订阅合集部分 |
| `app/src/main/java/com/biliseek/app/interaction/VideoActionPolicy.kt` | 改 | `VideoInteractionState.collection` / `seasonSubscribed`；`applySeasonSubscribed` |
| `app/src/main/java/com/biliseek/app/interaction/VideoInteractionController.kt` | 改 | `bind` 解析合集与订阅状态；`markSeasonSubscribed` |
| `app/src/main/java/com/biliseek/app/upspace/UpSpaceController.kt` | 改 | 「合集」视图加载与翻页；换 UP / 关闭时通知宿主 |
| `app/src/main/java/com/biliseek/app/AppViewModel.kt` | 改 | `UpSpaceView.COLLECTIONS`、`UpSpaceUiState.collections`；接上 `collections` |
| `app/src/main/java/com/biliseek/app/MainActivity.kt` | 改 | 「列表」tab 改接 `collections`；UP 空间合集视图与内容页；播放页弹层参数 |
| `app/src/main/java/com/biliseek/app/ui/SeriesScreen.kt` | 改 | `onUnsubscribe` 可空，空时不显示「取消订阅」 |
| `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt` | 改 | 第三个视图「合集」与列表 |
| `app/src/main/java/com/biliseek/app/ui/CollectionSheet.kt` | 新建 | `CollectionSheetHandlers`、入口行、合集弹层 |
| `app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt` | 改 | 入口行与弹层 |
| `app/src/main/java/com/biliseek/app/ui/AppShell.kt` | 改 | 把弹层参数传给 `PlayerScreen` |
| `app/src/test/java/com/biliseek/app/collection/CollectionPolicyTest.kt` | 新建 | `CollectionPolicy` |
| `app/src/test/java/com/biliseek/app/interaction/VideoActionPolicyTest.kt` | 改 | `applySeasonSubscribed` |
| `app/src/androidTest/java/com/biliseek/app/PlayerUiTest.kt` | 改 | 入口行与弹层（需设备，至少编译通过） |
| `docs/05-功能优化篇.md`、`docs/03-架构设计篇.md`、`docs/BiliSeek-手动测试指南.md`、设计稿 | 改 | 记录、架构表、手动测试项、所选样式 |

## 常用命令

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionDtoTest"
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionPagePolicyTest"
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.*"
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.interaction.VideoActionPolicyTest"
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew test
```

---

### Task 0: 建分支，提交设计稿与计划

- [ ] **Step 1:** 确认工作区只有这两份文档没提交，然后建分支

```bash
git status --short
# 期望只有：
# ?? docs/superpowers/plans/2026-10-26-collections.md
# ?? docs/superpowers/specs/2026-10-26-collections-design.md
git switch -c feat/collections
```

- [ ] **Step 2:** 提交

```bash
git add docs/superpowers/specs/2026-10-26-collections-design.md docs/superpowers/plans/2026-10-26-collections.md
git commit -m "docs: 合集功能设计稿与实施计划

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: 效果图选样式（需要用户选，选定前不做 Task 8）

用户的习惯：纯视觉取舍先在浏览器里看真比例效果图，选定后才实现。这一步只出效果图、记录选择，不改代码。

**Files:**
- Modify: `docs/superpowers/specs/2026-10-26-collections-design.md`（§4 末尾记下所选样式）
- 效果图放 `.superpowers/`（已在 `.gitignore`），不提交

- [ ] **Step 1:** 读 `/home/minifive/.claude/plugins/cache/claude-plugins-official/superpowers/6.1.1/skills/brainstorming/visual-companion.md`，按里面的说明启动浏览器伴侣：

```bash
bash /home/minifive/.claude/plugins/cache/claude-plugins-official/superpowers/6.1.1/skills/brainstorming/scripts/start-server.sh --project-dir /home/minifive/own_work/BiliSeek --open
```

- [ ] **Step 2:** 做一页效果图，按真机尺寸画：PJD110 是 **360×792dp**，1dp = 1px，字体缩放 1.0；中文用 OPPO Sans 4.0 SC，拉丁用 Roboto。画播放页中段（标题、互动栏、「简介 展开 ⌄」那一行、下面的合集入口行），浅色、深色各一份。合集入口行三个方案：
  - **A 文字行**（与「简介」行同一种写法）：左「合集」（labelLarge，onSurface）+ 合集名（bodyMedium，onSurfaceVariant，单行省略，占满中间）+ 右「第 3/24 集」（labelLarge，封面取色）+ `›`（18dp，封面取色）。整行高 40dp，可点。
  - **B 卡片**：surfaceVariant 底、12dp 圆角，左 64dp 宽封面，右两行「合集名」/「合集 · 第 3/24 集」，最右 `›`。
  - **C 胶囊**：一个 AssistChip「合集 · 合集名 · 3/24 ›」。

  再画合集弹层（`CompactDragHandle`；标题区：合集名 titleMedium + 「共 24 集」labelSmall + 右侧「＋ 订阅」Button / 「✓ 已订阅」FilledTonalButton；两个小节、当前是第 3 集）的两个方案：
  - **X 带封面**：每行 64dp 封面 + 标题（两行）+ 时长；当前这集标题和时长用封面取色、加粗，时长前加「正在播放 · 」。
  - **Y 纯文字**：每行左边两位序号 + 标题（单行）+ 右侧时长；当前这集整行用封面取色，序号换成播放中图标。

  推给用户前，先用 Firefox 无头截图检查一遍有没有折行、挤压。

- [ ] **Step 3:** 让用户选一个入口行方案、一个弹层方案。在设计稿 §4「样式」那一条后面补一行：「用户已选：入口行 X 方案、弹层 Y 方案（YYYY-MM-DD）」。

- [ ] **Step 4:** 如果选的不是「A + X」，**先改本计划 Task 8 Step 1 里 `CollectionEntryRow` / `CollectionEpisodeRow` 的代码**，改成所选方案，再往下执行。选的就是「A + X」则不用改。

- [ ] **Step 5:** 提交设计稿改动

```bash
git add docs/superpowers/specs/2026-10-26-collections-design.md
git commit -m "docs: 合集入口行与弹层样式（用户已选）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 网络层 DTO（视频详情的合集、订阅状态、UP 空间合集 / 系列）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（只删旧的 `seriesArchives`）
- Test: `core/network/src/test/java/com/biliseek/core/network/CollectionDtoTest.kt`（新建）

**Interfaces:**
- Produces:
  - `VideoViewInfo.ugcSeason: UgcSeason?`
  - `UgcSeason(id: Long = 0, title: String = "", cover: String = "", mid: Long = 0, epCount: Int = 0, sections: List<UgcSection> = emptyList())`
  - `UgcSection(id: Long = 0, title: String = "", episodes: List<UgcEpisode> = emptyList())`
  - `UgcEpisode(aid: Long = 0, bvid: String = "", cid: Long = 0, title: String = "", arc: UgcEpisodeArc? = null)`
  - `UgcEpisodeArc(pic: String = "", duration: Long = 0)`
  - `ArchiveRelation.seasonFav: Boolean`
  - `SeasonsSeriesListData(itemsLists: SeasonsSeriesItems)`；`SeasonsSeriesItems(page: SpaceCollectionPage, seasonsList: List<SpaceSeasonItem>, seriesList: List<SpaceSeriesItem>)`
  - `SpaceCollectionPage(pageNum: Int = 0, pageSize: Int = 0, total: Int = 0)`
  - `SpaceSeasonItem(meta: SpaceSeasonMeta)`；`SpaceSeasonMeta(seasonId: Long = 0, name: String = "", cover: String = "", total: Int = 0, mid: Long = 0)`
  - `SpaceSeriesItem(meta: SpaceSeriesMeta)`；`SpaceSeriesMeta(seriesId: Long = 0, name: String = "", cover: String = "", total: Int = 0, mid: Long = 0)`
  - `SeasonArchivesData(archives: List<SpaceCollectionArchive>, page: SpaceCollectionPage)`
  - `SeriesArchivesData(archives: List<SpaceCollectionArchive>, page: SeriesPage)`（重写）；`SeriesPage(num: Int = 0, size: Int = 0, total: Int = 0)`（重写）
  - `SpaceCollectionArchive(aid: Long = 0, bvid: String = "", title: String = "", pic: String = "", duration: Long = 0, pubdate: Long = 0)`

- [ ] **Step 1: 写失败的测试**

新建 `core/network/src/test/java/com/biliseek/core/network/CollectionDtoTest.kt`：

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.ArchiveRelation
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.SeasonArchivesData
import com.biliseek.core.network.dto.SeasonsSeriesListData
import com.biliseek.core.network.dto.SeriesArchivesData
import com.biliseek.core.network.dto.VideoViewInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合集相关 DTO 解析（设计稿 docs/superpowers/specs/2026-10-26-collections-design.md）。
 *
 * 样本是照 BiliPai 的模型和接口文档手写的，**不是真实响应**——真机跑过后用日志里的真实响应替换。
 */
class CollectionDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `视频详情带合集 各小节各集都解析出来`() {
        val r = json.decodeFromString<BiliResponse<VideoViewInfo>>(
            """
            {
              "code": 0,
              "data": {
                "bvid": "BV1b", "aid": 2, "owner": { "mid": 9, "name": "UP甲" },
                "ugc_season": {
                  "id": 100, "title": "合集甲", "cover": "http://i0.hdslb.com/c.jpg", "mid": 9, "ep_count": 3,
                  "sections": [
                    { "id": 1, "title": "上篇", "episodes": [
                      { "aid": 1, "bvid": "BV1a", "cid": 11, "title": "第一集",
                        "arc": { "pic": "//i0.hdslb.com/a.jpg", "duration": 61 } },
                      { "aid": 2, "bvid": "BV1b", "cid": 12, "title": "第二集",
                        "arc": { "pic": "//i0.hdslb.com/b.jpg", "duration": 62 } }
                    ] },
                    { "id": 2, "title": "下篇", "episodes": [
                      { "aid": 3, "bvid": "BV1c", "cid": 13, "title": "第三集", "arc": null }
                    ] }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val season = r.data!!.ugcSeason!!
        assertEquals(100L, season.id)
        assertEquals("合集甲", season.title)
        assertEquals(3, season.epCount)
        assertEquals(listOf("上篇", "下篇"), season.sections.map { it.title })
        assertEquals(listOf("BV1a", "BV1b"), season.sections[0].episodes.map { it.bvid })
        assertEquals("第二集", season.sections[0].episodes[1].title)
        assertEquals("//i0.hdslb.com/b.jpg", season.sections[0].episodes[1].arc!!.pic)
        assertEquals(62L, season.sections[0].episodes[1].arc!!.duration)
        assertNull(season.sections[1].episodes[0].arc)
    }

    @Test
    fun `视频详情不属于合集时 ugcSeason 为 null`() {
        val r = json.decodeFromString<BiliResponse<VideoViewInfo>>("""{"code":0,"data":{"bvid":"BV1x"}}""")
        assertNull(r.data!!.ugcSeason)
    }

    @Test
    fun `互动状态的 season_fav 兼容 1 和 true 缺省为 false`() {
        val a = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"season_fav":1}}""")
        val b = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"season_fav":true}}""")
        val c = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"like":true}}""")
        assertTrue(a.data!!.seasonFav)
        assertTrue(b.data!!.seasonFav)
        assertFalse(c.data!!.seasonFav)
    }

    @Test
    fun `UP 空间合集与系列列表`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """
            {
              "code": 0,
              "data": {
                "items_lists": {
                  "page": { "page_num": 1, "page_size": 20, "total": 2 },
                  "seasons_list": [
                    { "meta": { "season_id": 100, "name": "合集甲", "cover": "//c1.jpg", "total": 24, "mid": 9 },
                      "recent_aids": [1, 2] }
                  ],
                  "series_list": [
                    { "meta": { "series_id": 200, "name": "系列乙", "cover": "//c2.jpg", "total": 3, "mid": 9 } }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val lists = r.data!!.itemsLists
        assertEquals(2, lists.page.total)
        assertEquals(100L, lists.seasonsList.single().meta.seasonId)
        assertEquals("合集甲", lists.seasonsList.single().meta.name)
        assertEquals(24, lists.seasonsList.single().meta.total)
        assertEquals(200L, lists.seriesList.single().meta.seriesId)
        assertEquals("系列乙", lists.seriesList.single().meta.name)
    }

    @Test
    fun `UP 空间合集列表缺字段不崩`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """{"code":0,"data":{"items_lists":{"seasons_list":null}}}""",
        )
        assertTrue(r.data!!.itemsLists.seasonsList.isEmpty())
        assertTrue(r.data!!.itemsLists.seriesList.isEmpty())
        assertEquals(0, r.data!!.itemsLists.page.total)
    }

    @Test
    fun `合集内容一页`() {
        val r = json.decodeFromString<BiliResponse<SeasonArchivesData>>(
            """
            {
              "code": 0,
              "data": {
                "aids": [1, 2],
                "archives": [
                  { "aid": 1, "bvid": "BV1a", "title": "第一集", "pic": "http://a.jpg", "duration": 61, "pubdate": 1700000000 },
                  { "aid": 2, "bvid": "BV1b", "title": "第二集", "pic": "http://b.jpg", "duration": 62, "pubdate": 1700000100 }
                ],
                "meta": { "season_id": 100, "name": "合集甲", "total": 24 },
                "page": { "page_num": 1, "page_size": 30, "total": 24 }
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals(listOf("BV1a", "BV1b"), d.archives.map { it.bvid })
        assertEquals("http://a.jpg", d.archives[0].pic)
        assertEquals(61L, d.archives[0].duration)
        assertEquals(24, d.page.total)
    }

    @Test
    fun `系列内容一页`() {
        val r = json.decodeFromString<BiliResponse<SeriesArchivesData>>(
            """
            {
              "code": 0,
              "data": {
                "aids": [5],
                "archives": [
                  { "aid": 5, "bvid": "BV1e", "title": "系列第一个", "pic": "http://e.jpg", "duration": 300, "pubdate": 1600000000 }
                ],
                "page": { "num": 1, "size": 30, "total": 1 }
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals("BV1e", d.archives.single().bvid)
        assertEquals(300L, d.archives.single().duration)
        assertEquals(1, d.page.total)
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionDtoTest"`
Expected: 编译失败，`Unresolved reference: ugcSeason` / `SeasonsSeriesListData` / `SeasonArchivesData` 等。

- [ ] **Step 3: 改 `BiliDto.kt`**

3a. `VideoViewInfo` 的最后一个字段 `duration` 后面加一个字段：

```kotlin
    /** 时长（秒，全部分 P 合计）。 */
    val duration: Long = 0,
    /**
     * 所属合集（新版合集）；不属于合集时为 null。播放页合集入口与弹层用：各小节各集都在里面，不另外翻页
     * （几百集的大合集是否全量返回待真机确认）。
     */
    @SerialName("ugc_season") val ugcSeason: UgcSeason? = null,
)
```

3b. 紧接在 `VideoStat` 这个类后面，加：

```kotlin
/** `x/web-interface/view` 的 `data.ugc_season`：视频所属的合集。 */
@Serializable
data class UgcSeason(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val mid: Long = 0,
    @SerialName("ep_count") val epCount: Int = 0,
    val sections: List<UgcSection> = emptyList(),
)

/** 合集的一个小节（只有一个小节时 B 站默认叫「正片」之类）。 */
@Serializable
data class UgcSection(
    val id: Long = 0,
    val title: String = "",
    val episodes: List<UgcEpisode> = emptyList(),
)

@Serializable
data class UgcEpisode(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val arc: UgcEpisodeArc? = null,
)

@Serializable
data class UgcEpisodeArc(
    /** 封面（`//` 或 `http:` 开头，展示前规整）。 */
    val pic: String = "",
    /** 秒。 */
    val duration: Long = 0,
)
```

3c. `ArchiveRelation` 里 `coin` 后面加：

```kotlin
    /** 已投币数（0 / 1 / 2）。 */
    val coin: Int = 0,
    /** 当前视频所在合集已订阅（1/0 或 true/false）。播放页合集弹层的「订阅 / 已订阅」用。 */
    @SerialName("season_fav")
    @Serializable(with = FlexibleBooleanSerializer::class) val seasonFav: Boolean = false,
)
```

3d. 把现有的这两个类整段替换掉（原先的字段名是猜的，app 里没有地方用）：

原来：
```kotlin
/** `x/series/archives` —— 合集内容（分页）。需登录。 */
@Serializable
data class SeriesArchivesData(
    val count: Int = 0,
    val list: List<SeriesArchive> = emptyList(),
    val page: SeriesPage = SeriesPage(),
)
```
和
```kotlin
@Serializable
data class SeriesPage(
    val pn: Int = 1,
    val ps: Int = 20,
    val count: Int = 0,
)
```

换成（`SeriesArchive` 类保持不动，夹在中间）：

```kotlin
/** `x/series/archives` —— 旧版系列的内容（分页）。不需登录。 */
@Serializable
data class SeriesArchivesData(
    val archives: List<SpaceCollectionArchive> = emptyList(),
    val page: SeriesPage = SeriesPage(),
)
```
和
```kotlin
@Serializable
data class SeriesPage(
    val num: Int = 0,
    val size: Int = 0,
    val total: Int = 0,
)

// ── UP 空间的合集与系列（x/polymer/web-space）────────────────

/** `x/polymer/web-space/seasons_series_list` —— UP 主的合集与系列。不需登录。 */
@Serializable
data class SeasonsSeriesListData(
    @SerialName("items_lists") val itemsLists: SeasonsSeriesItems = SeasonsSeriesItems(),
)

@Serializable
data class SeasonsSeriesItems(
    val page: SpaceCollectionPage = SpaceCollectionPage(),
    @SerialName("seasons_list") val seasonsList: List<SpaceSeasonItem> = emptyList(),
    @SerialName("series_list") val seriesList: List<SpaceSeriesItem> = emptyList(),
)

@Serializable
data class SpaceCollectionPage(
    @SerialName("page_num") val pageNum: Int = 0,
    @SerialName("page_size") val pageSize: Int = 0,
    val total: Int = 0,
)

@Serializable
data class SpaceSeasonItem(
    val meta: SpaceSeasonMeta = SpaceSeasonMeta(),
)

@Serializable
data class SpaceSeasonMeta(
    @SerialName("season_id") val seasonId: Long = 0,
    val name: String = "",
    val cover: String = "",
    /** 视频数。 */
    val total: Int = 0,
    val mid: Long = 0,
)

@Serializable
data class SpaceSeriesItem(
    val meta: SpaceSeriesMeta = SpaceSeriesMeta(),
)

@Serializable
data class SpaceSeriesMeta(
    @SerialName("series_id") val seriesId: Long = 0,
    val name: String = "",
    val cover: String = "",
    /** 视频数。 */
    val total: Int = 0,
    val mid: Long = 0,
)

/** `x/polymer/web-space/seasons_archives_list` —— UP 空间合集的内容（分页）。不需登录。 */
@Serializable
data class SeasonArchivesData(
    val archives: List<SpaceCollectionArchive> = emptyList(),
    val page: SpaceCollectionPage = SpaceCollectionPage(),
)

/** UP 空间合集 / 系列内容的一条。接口不带 UP 主名，由调用方补。 */
@Serializable
data class SpaceCollectionArchive(
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** 秒。 */
    val duration: Long = 0,
    val pubdate: Long = 0,
)
```

3e. `BiliRepository.kt` 里旧的 `seriesArchives`（注释「合集内容（分页，`ps ≤ 20`）。必须登录。」，方法体用 `data.list`）整个删掉：它没有调用方，字段又是按旧 DTO 写的，Task 3 按真实结构重写。

Run: `grep -rn "seriesArchives" app/src core/network/src/main`
Expected: 只剩 `BiliApi.kt` 里那一个接口声明（Task 3 改）。

- [ ] **Step 4: 跑测试，确认通过**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionDtoTest"`
Expected: 7 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add core/network
git commit -m "feat(network): 合集相关 DTO（视频详情的合集、订阅状态、UP 空间合集 / 系列）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 网络层接口与仓库（UP 空间合集、合集内容、系列内容、订阅）

**Files:**
- Create: `core/network/src/main/java/com/biliseek/core/network/internal/CollectionPagePolicy.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`
- Test: `core/network/src/test/java/com/biliseek/core/network/CollectionPagePolicyTest.kt`（新建）

**Interfaces:**
- Consumes: Task 2 的 DTO。
- Produces:
  - `internal fun hasMorePages(page: Int, pageSize: Int, total: Int, received: Int): Boolean`
  - `data class SpaceCollectionsPage(val seasons: List<SpaceSeasonMeta>, val series: List<SpaceSeriesMeta>, val page: Int, val hasMore: Boolean)`（顶层，`com.biliseek.core.network`）
  - `suspend fun BiliRepository.spaceCollections(mid: Long, page: Int = 1, pageSize: Int = 20): Result<SpaceCollectionsPage>`
  - `suspend fun BiliRepository.spaceSeasonArchives(mid: Long, seasonId: Long, page: Int, pageSize: Int = 30): Result<SeriesArchivesPage>`
  - `suspend fun BiliRepository.seriesArchives(mid: Long, seriesId: Long, page: Int, pageSize: Int = 30): Result<SeriesArchivesPage>`（改签名）
  - `suspend fun BiliRepository.favSeason(seasonId: Long): Result<Unit>`
  - 两个内容接口返回的 `SeriesArchive` 只填 `bvid` / `title` / `cover` / `duration`，`author` 为空、`authorMid` 为 0（由 `:app` 补）。

- [ ] **Step 1: 写失败的测试**

新建 `core/network/src/test/java/com/biliseek/core/network/CollectionPagePolicyTest.kt`：

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.internal.hasMorePages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPagePolicyTest {

    @Test
    fun `给了总数就按总数算`() {
        assertTrue(hasMorePages(page = 1, pageSize = 20, total = 21, received = 20))
        assertFalse(hasMorePages(page = 2, pageSize = 20, total = 40, received = 20))
        assertFalse(hasMorePages(page = 1, pageSize = 20, total = 3, received = 3))
    }

    @Test
    fun `没给总数就看这一页满不满`() {
        assertTrue(hasMorePages(page = 1, pageSize = 20, total = 0, received = 20))
        assertFalse(hasMorePages(page = 1, pageSize = 20, total = 0, received = 19))
    }

    @Test
    fun `这一页一条都没有就到底 不管总数怎么说`() {
        assertFalse(hasMorePages(page = 3, pageSize = 20, total = 100, received = 0))
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CollectionPagePolicyTest"`
Expected: 编译失败，`Unresolved reference: hasMorePages`。

- [ ] **Step 3: 新建 `internal/CollectionPagePolicy.kt`**

```kotlin
package com.biliseek.core.network.internal

/**
 * 合集 / 系列这类带总数的分页接口还有没有下一页。
 *
 * - 这一页一条都没有：到底（总数对不上时也不会一直往后翻）；
 * - 给了总数：按「已取到的页 × 每页条数 < 总数」；
 * - 没给总数（0）：这一页满了才认为还有。
 */
internal fun hasMorePages(page: Int, pageSize: Int, total: Int, received: Int): Boolean = when {
    received == 0 -> false
    total > 0 -> page * pageSize < total
    else -> received >= pageSize
}
```

- [ ] **Step 4: 改 `BiliApi.kt`**

4a. import 区加（`SeriesArchivesData` 已经 import 过）：

```kotlin
import com.biliseek.core.network.dto.SeasonArchivesData
import com.biliseek.core.network.dto.SeasonsSeriesListData
```

4b. 把现有的

```kotlin
    /** 合集内容（分页）。 */
    @GET("x/series/archives")
    suspend fun seriesArchives(
        @Query("series_id") seriesId: Long,
        @Query("pn") pn: Int,
        @Query("ps") ps: Int = 20,
    ): BiliResponse<SeriesArchivesData>
```

替换为：

```kotlin
    /** 旧版系列的内容（分页）。参数由调用方签好 WBI 后整体传入。 */
    @GET("x/series/archives")
    suspend fun seriesArchives(@QueryMap params: Map<String, String>): BiliResponse<SeriesArchivesData>

    /** UP 主的合集与系列列表（分页）。参数由调用方签好 WBI 后整体传入。 */
    @GET("x/polymer/web-space/seasons_series_list")
    suspend fun seasonsSeriesList(@QueryMap params: Map<String, String>): BiliResponse<SeasonsSeriesListData>

    /** UP 空间合集的内容（分页）。参数由调用方签好 WBI 后整体传入。 */
    @GET("x/polymer/web-space/seasons_archives_list")
    suspend fun seasonArchives(@QueryMap params: Map<String, String>): BiliResponse<SeasonArchivesData>
```

4c. 在 `unfavSeason` 声明后面加：

```kotlin
    @FormUrlEncoded
    @POST("x/v3/fav/season/fav")
    suspend fun favSeason(
        @FieldMap fields: Map<String, String>,          // platform(web), season_id, csrf
    ): BiliResponse<JsonElement>
```

- [ ] **Step 5: 改 `BiliRepository.kt`**

5a. import 区加：

```kotlin
import com.biliseek.core.network.dto.SpaceCollectionArchive
import com.biliseek.core.network.dto.SpaceSeasonMeta
import com.biliseek.core.network.dto.SpaceSeriesMeta
import com.biliseek.core.network.internal.hasMorePages
```

5b. 顶层数据类区，`CollectionSeasonPage` 后面加：

```kotlin
/** UP 空间合集与系列列表的一页（合集、系列按接口原样分两组）。 */
data class SpaceCollectionsPage(
    val seasons: List<SpaceSeasonMeta>,
    val series: List<SpaceSeriesMeta>,
    val page: Int,
    val hasMore: Boolean,
)
```

5c. 在 `collectionSeasonList` 方法前面（旧 `seriesArchives` 原来的位置，Task 2 已删）加下面三个方法：

```kotlin
    /** UP 主的合集与系列（一页里合集、系列各一组）。不需登录；统一 WBI 签名、走 FEED 限速桶，不重试。 */
    suspend fun spaceCollections(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 20,
    ): Result<SpaceCollectionsPage> = wbiRequest(
        params = mapOf(
            "mid" to mid.toString(),
            "page_num" to page.toString(),
            "page_size" to pageSize.toString(),
        ),
    ) { api.seasonsSeriesList(it) }.map { d ->
        val lists = d.itemsLists
        SpaceCollectionsPage(
            seasons = lists.seasonsList.map { it.meta }.filter { it.seasonId > 0 && it.name.isNotBlank() },
            series = lists.seriesList.map { it.meta }.filter { it.seriesId > 0 && it.name.isNotBlank() },
            page = page,
            hasMore = hasMorePages(page, pageSize, lists.page.total, lists.seasonsList.size + lists.seriesList.size),
        )
    }

    /** UP 空间合集的内容，按 UP 主排的顺序。不需登录；统一 WBI 签名、走 FEED 限速桶，不重试。 */
    suspend fun spaceSeasonArchives(
        mid: Long,
        seasonId: Long,
        page: Int,
        pageSize: Int = 30,
    ): Result<SeriesArchivesPage> = wbiRequest(
        params = mapOf(
            "mid" to mid.toString(),
            "season_id" to seasonId.toString(),
            "sort_reverse" to "false",
            "page_num" to page.toString(),
            "page_size" to pageSize.toString(),
        ),
    ) { api.seasonArchives(it) }.map { d ->
        SeriesArchivesPage(
            items = d.archives.filter { it.bvid.isNotBlank() }.map { it.toSeriesArchive() },
            page = page,
            hasMore = hasMorePages(page, pageSize, d.page.total, d.archives.size),
        )
    }

    /**
     * 旧版系列的内容，按发布时间从旧到新（往后播 = 往更新的播；B 站网页默认从新到旧，这里有意不同）。
     * 不需登录；统一 WBI 签名、走 FEED 限速桶，不重试。
     */
    suspend fun seriesArchives(
        mid: Long,
        seriesId: Long,
        page: Int,
        pageSize: Int = 30,
    ): Result<SeriesArchivesPage> = wbiRequest(
        params = mapOf(
            "mid" to mid.toString(),
            "series_id" to seriesId.toString(),
            "only_normal" to "true",
            "sort" to "asc",
            "pn" to page.toString(),
            "ps" to pageSize.toString(),
        ),
    ) { api.seriesArchives(it) }.map { d ->
        SeriesArchivesPage(
            items = d.archives.filter { it.bvid.isNotBlank() }.map { it.toSeriesArchive() },
            page = page,
            hasMore = hasMorePages(page, pageSize, d.page.total, d.archives.size),
        )
    }
```

5d. 紧挨着 `private fun requireLoggedInMid()` 后面加：

```kotlin
    /** UP 空间合集 / 系列的一条 → 列表条目。UP 主由调用方补（接口不带）。 */
    private fun SpaceCollectionArchive.toSeriesArchive() = SeriesArchive(
        bvid = bvid,
        title = title,
        cover = pic,
        duration = duration,
    )
```

5e. 把 `unfavSeason` 的注释改掉，并在它后面加 `favSeason`：

```kotlin
    /** 取消订阅合集（「列表 → 合集」内容页、播放页合集弹层）。 */
    suspend fun unfavSeason(seasonId: Long): Result<Unit> =
        writeRequest(
            payload = mapOf("platform" to "web", "season_id" to seasonId.toString()),
        ) { f, _ -> api.unfavSeason(f) }
            .map { }

    /** 订阅合集（播放页合集弹层）。 */
    suspend fun favSeason(seasonId: Long): Result<Unit> =
        writeRequest(
            payload = mapOf("platform" to "web", "season_id" to seasonId.toString()),
        ) { f, _ -> api.favSeason(f) }
            .map { }
```

- [ ] **Step 6: 跑 `:core:network` 全部单测**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: 全部 PASS（含 Task 2 的 `CollectionDtoTest` 和本任务的 `CollectionPagePolicyTest`）。

- [ ] **Step 7: 提交**

```bash
git add core/network
git commit -m "feat(network): UP 空间合集 / 系列接口、订阅合集

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `:app` 合集模型与 `CollectionPolicy`

**Files:**
- Create: `app/src/main/java/com/biliseek/app/collection/CollectionModels.kt`
- Create: `app/src/main/java/com/biliseek/app/collection/CollectionPolicy.kt`
- Test: `app/src/test/java/com/biliseek/app/collection/CollectionPolicyTest.kt`（新建）

**Interfaces:**
- Consumes: `VideoViewInfo` / `UgcSeason` 等（Task 2），`SpaceCollectionsPage`（Task 3）。
- Produces（包 `com.biliseek.app.collection`）：
  - `sealed interface CollectionRef { Subscribed(seasonId: Long); Season(mid: Long, seasonId: Long, upName: String); Series(mid: Long, seriesId: Long, upName: String) }`
  - `enum class CollectionSlot { LIBRARY, UP_SPACE }`
  - `data class SpaceCollection(ref: CollectionRef, name: String, cover: String, total: Int)`，属性 `isSeries: Boolean`、`key: String`
  - `data class VideoCollection(seasonId: Long, title: String, sections: List<CollectionSection>, currentIndex: Int?)`，属性 `episodes: List<CollectionEpisode>`
  - `data class CollectionSection(title: String, episodes: List<CollectionEpisode>)`
  - `data class CollectionEpisode(bvid: String, title: String, cover: String, durationSec: Long, author: String, authorMid: Long)`
  - `sealed interface CollectionSheetRow { Header(title: String); Episode(index: Int, episode: CollectionEpisode) }`
  - `object CollectionPolicy`：`fromView(view: VideoViewInfo): VideoCollection?`、`progressLabel(collection: VideoCollection): String`、`sheetRows(collection: VideoCollection): List<CollectionSheetRow>`、`currentRowIndex(rows: List<CollectionSheetRow>, currentIndex: Int?): Int`、`shouldPlayEpisode(collection: VideoCollection, index: Int, playingBvid: String): Boolean`、`spaceCollections(page: SpaceCollectionsPage, mid: Long, upName: String): List<SpaceCollection>`、`countLabel(item: SpaceCollection): String`

- [ ] **Step 1: 写失败的测试**

新建 `app/src/test/java/com/biliseek/app/collection/CollectionPolicyTest.kt`：

```kotlin
package com.biliseek.app.collection

import com.biliseek.core.network.SpaceCollectionsPage
import com.biliseek.core.network.dto.SpaceSeasonMeta
import com.biliseek.core.network.dto.SpaceSeriesMeta
import com.biliseek.core.network.dto.UgcEpisode
import com.biliseek.core.network.dto.UgcEpisodeArc
import com.biliseek.core.network.dto.UgcSeason
import com.biliseek.core.network.dto.UgcSection
import com.biliseek.core.network.dto.VideoOwner
import com.biliseek.core.network.dto.VideoViewInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPolicyTest {

    private fun ep(bvid: String) =
        UgcEpisode(bvid = bvid, title = "标题$bvid", arc = UgcEpisodeArc(pic = "//i0/$bvid.jpg", duration = 60))

    private fun view(bvid: String, vararg sections: UgcSection, seasonId: Long = 100) = VideoViewInfo(
        bvid = bvid,
        owner = VideoOwner(mid = 9, name = "UP甲"),
        ugcSeason = UgcSeason(id = seasonId, title = "合集甲", sections = sections.toList()),
    )

    @Test
    fun `不属于合集 合集编号无效 一集都没有 都不显示`() {
        assertNull(CollectionPolicy.fromView(VideoViewInfo(bvid = "BV1")))
        assertNull(CollectionPolicy.fromView(view("BV1", UgcSection(episodes = listOf(ep("BV1"))), seasonId = 0)))
        assertNull(CollectionPolicy.fromView(view("BV1", UgcSection(episodes = emptyList()))))
        assertNull(CollectionPolicy.fromView(view("BV1", UgcSection(episodes = listOf(ep(""))))))
    }

    @Test
    fun `多个小节拼成一个列表 空小节去掉 当前集按 bvid 找 UP 主取视频作者`() {
        val c = CollectionPolicy.fromView(
            view(
                "BV3",
                UgcSection(title = "上篇", episodes = listOf(ep("BV1"), ep("BV2"))),
                UgcSection(title = "空的"),
                UgcSection(title = "下篇", episodes = listOf(ep("BV3"))),
            ),
        )!!
        assertEquals(100L, c.seasonId)
        assertEquals("合集甲", c.title)
        assertEquals(listOf("上篇", "下篇"), c.sections.map { it.title })
        assertEquals(listOf("BV1", "BV2", "BV3"), c.episodes.map { it.bvid })
        assertEquals(2, c.currentIndex)
        val first = c.episodes.first()
        assertEquals("标题BV1", first.title)
        assertEquals("//i0/BV1.jpg", first.cover)
        assertEquals(60L, first.durationSec)
        assertEquals("UP甲", first.author)
        assertEquals(9L, first.authorMid)
    }

    @Test
    fun `找不到当前集时 currentIndex 为 null 进度显示共 N 集`() {
        val c = CollectionPolicy.fromView(view("BV9", UgcSection(episodes = listOf(ep("BV1"), ep("BV2")))))!!
        assertNull(c.currentIndex)
        assertEquals("共 2 集", CollectionPolicy.progressLabel(c))
    }

    @Test
    fun `进度显示第 n 和总集数`() {
        val c = CollectionPolicy.fromView(view("BV2", UgcSection(episodes = listOf(ep("BV1"), ep("BV2"), ep("BV3")))))!!
        assertEquals("第 2/3 集", CollectionPolicy.progressLabel(c))
    }

    @Test
    fun `只有一个小节不显示小节标题 多个小节显示 集的序号跨小节连续`() {
        val single = CollectionPolicy.fromView(
            view("BV1", UgcSection(title = "正片", episodes = listOf(ep("BV1"), ep("BV2")))),
        )!!
        assertEquals(
            listOf(0, 1),
            CollectionPolicy.sheetRows(single).map { (it as CollectionSheetRow.Episode).index },
        )

        val multi = CollectionPolicy.fromView(
            view(
                "BV3",
                UgcSection(title = "上篇", episodes = listOf(ep("BV1"), ep("BV2"))),
                UgcSection(title = "下篇", episodes = listOf(ep("BV3"))),
            ),
        )!!
        val rows = CollectionPolicy.sheetRows(multi)
        assertEquals(
            listOf("h:上篇", "e:0", "e:1", "h:下篇", "e:2"),
            rows.map {
                when (it) {
                    is CollectionSheetRow.Header -> "h:${it.title}"
                    is CollectionSheetRow.Episode -> "e:${it.index}"
                }
            },
        )
        // 当前是第 3 集（index 2），在第 5 行（行号 4）
        assertEquals(4, CollectionPolicy.currentRowIndex(rows, multi.currentIndex))
        assertEquals(0, CollectionPolicy.currentRowIndex(rows, null))
    }

    @Test
    fun `点正在播的那集不重新播 点别的集起播 越界不播`() {
        val c = CollectionPolicy.fromView(view("BV2", UgcSection(episodes = listOf(ep("BV1"), ep("BV2")))))!!
        assertFalse(CollectionPolicy.shouldPlayEpisode(c, 1, playingBvid = "BV2"))
        assertTrue(CollectionPolicy.shouldPlayEpisode(c, 0, playingBvid = "BV2"))
        assertFalse(CollectionPolicy.shouldPlayEpisode(c, 5, playingBvid = "BV2"))
    }

    @Test
    fun `UP 空间合集在前系列在后 编号相同也不撞 key 第二行写类型和视频数`() {
        val page = SpaceCollectionsPage(
            seasons = listOf(SpaceSeasonMeta(seasonId = 100, name = "合集甲", cover = "c1", total = 24)),
            series = listOf(SpaceSeriesMeta(seriesId = 100, name = "系列乙", cover = "c2", total = 3)),
            page = 1,
            hasMore = false,
        )
        val items = CollectionPolicy.spaceCollections(page, mid = 9, upName = "UP甲")
        assertEquals(listOf("合集甲", "系列乙"), items.map { it.name })
        assertEquals(CollectionRef.Season(mid = 9, seasonId = 100, upName = "UP甲"), items[0].ref)
        assertEquals(CollectionRef.Series(mid = 9, seriesId = 100, upName = "UP甲"), items[1].ref)
        assertEquals(listOf("season:100", "series:100"), items.map { it.key })
        assertFalse(items[0].isSeries)
        assertTrue(items[1].isSeries)
        assertEquals("合集 · 24 个视频", CollectionPolicy.countLabel(items[0]))
        assertEquals("系列 · 3 个视频", CollectionPolicy.countLabel(items[1]))
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.*"`
Expected: 编译失败，`Unresolved reference: CollectionPolicy` 等。

- [ ] **Step 3: 新建 `CollectionModels.kt`**

```kotlin
package com.biliseek.app.collection

/**
 * 打开一个合集内容页时的来源，决定用哪个接口取内容
 * （设计稿 docs/superpowers/specs/2026-10-26-collections-design.md）。
 */
sealed interface CollectionRef {
    /** 「列表 → 合集」里我订阅的合集：`x/space/fav/season/list`（要登录）。 */
    data class Subscribed(val seasonId: Long) : CollectionRef

    /** UP 空间里的合集：`seasons_archives_list`。接口条目不带 UP 主名，用 [upName] 补。 */
    data class Season(val mid: Long, val seasonId: Long, val upName: String) : CollectionRef

    /** UP 空间里的旧版系列：`x/series/archives`。 */
    data class Series(val mid: Long, val seriesId: Long, val upName: String) : CollectionRef
}

/** 合集内容页挂在哪：「列表」tab 里一层，或 UP 空间页上一层。各管各的，互不覆盖。 */
enum class CollectionSlot { LIBRARY, UP_SPACE }

/** UP 空间「合集」视图的一行（合集或系列）。 */
data class SpaceCollection(
    val ref: CollectionRef,
    val name: String,
    /** 原始封面地址，展示前 normalizeCoverUrl。 */
    val cover: String,
    /** 视频数。 */
    val total: Int,
) {
    val isSeries: Boolean get() = ref is CollectionRef.Series

    /** 列表 key：合集和系列的编号可能相同，带上类型。 */
    val key: String
        get() = when (ref) {
            is CollectionRef.Season -> "season:${ref.seasonId}"
            is CollectionRef.Series -> "series:${ref.seriesId}"
            is CollectionRef.Subscribed -> "subscribed:${ref.seasonId}"
        }
}

/** 播放页：当前视频所属的合集（出自视频详情的 `ugc_season`）。 */
data class VideoCollection(
    val seasonId: Long,
    val title: String,
    /** 至少一个小节，每个小节至少一集（空的在 [CollectionPolicy.fromView] 里去掉了）。 */
    val sections: List<CollectionSection>,
    /** 当前视频在 [episodes] 里的位置；找不到为 null。 */
    val currentIndex: Int?,
) {
    /** 各小节按顺序拼成的全部集数。 */
    val episodes: List<CollectionEpisode> = sections.flatMap { it.episodes }
}

data class CollectionSection(
    val title: String,
    val episodes: List<CollectionEpisode>,
)

data class CollectionEpisode(
    val bvid: String,
    val title: String,
    /** 原始封面地址（可能 `//` 开头），展示和起播前 normalizeCoverUrl。 */
    val cover: String,
    val durationSec: Long,
    val author: String,
    val authorMid: Long,
)

/** 合集弹层的一行：小节标题，或第 [Episode.index] 集（[Episode.index] 是在 [VideoCollection.episodes] 里的位置）。 */
sealed interface CollectionSheetRow {
    data class Header(val title: String) : CollectionSheetRow
    data class Episode(val index: Int, val episode: CollectionEpisode) : CollectionSheetRow
}
```

- [ ] **Step 4: 新建 `CollectionPolicy.kt`**

```kotlin
package com.biliseek.app.collection

import com.biliseek.core.network.SpaceCollectionsPage
import com.biliseek.core.network.dto.VideoViewInfo

/** 合集的纯函数：播放页的合集信息与弹层、UP 空间的合集列表。无副作用，配单测。 */
object CollectionPolicy {

    /**
     * 视频详情 → 播放页的合集。不属于合集、合集编号无效、一集都没有时为 null（不显示入口行）。
     * 各集的 UP 主取这个视频的作者（合集是 UP 主自己建的）。
     */
    fun fromView(view: VideoViewInfo): VideoCollection? {
        val season = view.ugcSeason ?: return null
        if (season.id <= 0) return null
        val author = view.owner?.name.orEmpty()
        val authorMid = view.owner?.mid ?: 0
        val sections = season.sections.map { section ->
            CollectionSection(
                title = section.title,
                episodes = section.episodes.filter { it.bvid.isNotBlank() }.map { ep ->
                    CollectionEpisode(
                        bvid = ep.bvid,
                        title = ep.title,
                        cover = ep.arc?.pic.orEmpty(),
                        durationSec = ep.arc?.duration ?: 0,
                        author = author,
                        authorMid = authorMid,
                    )
                },
            )
        }.filter { it.episodes.isNotEmpty() }
        if (sections.isEmpty()) return null
        val index = sections.flatMap { it.episodes }.indexOfFirst { it.bvid == view.bvid }
        return VideoCollection(
            seasonId = season.id,
            title = season.title,
            sections = sections,
            currentIndex = index.takeIf { it >= 0 },
        )
    }

    /** 入口行的进度：「第 3/24 集」；找不到当前集时「共 24 集」。 */
    fun progressLabel(collection: VideoCollection): String {
        val total = collection.episodes.size
        return collection.currentIndex?.let { "第 ${it + 1}/$total 集" } ?: "共 $total 集"
    }

    /** 弹层的行。只有一个小节时不显示小节标题（B 站默认小节名多是「正片」，没有信息量）。 */
    fun sheetRows(collection: VideoCollection): List<CollectionSheetRow> {
        val showHeaders = collection.sections.size > 1
        var index = 0
        return buildList {
            collection.sections.forEach { section ->
                if (showHeaders) add(CollectionSheetRow.Header(section.title))
                section.episodes.forEach { add(CollectionSheetRow.Episode(index++, it)) }
            }
        }
    }

    /** 弹层打开时滚到哪一行：当前这集所在的行；没有当前集就从头。 */
    fun currentRowIndex(rows: List<CollectionSheetRow>, currentIndex: Int?): Int {
        if (currentIndex == null) return 0
        return rows.indexOfFirst { it is CollectionSheetRow.Episode && it.index == currentIndex }.coerceAtLeast(0)
    }

    /** 点弹层里的第 [index] 集要不要起播：点的是正在播的那集就只关弹层，不重新播。 */
    fun shouldPlayEpisode(collection: VideoCollection, index: Int, playingBvid: String): Boolean {
        val bvid = collection.episodes.getOrNull(index)?.bvid ?: return false
        return bvid != playingBvid
    }

    /** UP 空间一页 → 「合集」视图的行：合集在前、系列在后。 */
    fun spaceCollections(page: SpaceCollectionsPage, mid: Long, upName: String): List<SpaceCollection> =
        page.seasons.map {
            SpaceCollection(CollectionRef.Season(mid, it.seasonId, upName), it.name, it.cover, it.total)
        } + page.series.map {
            SpaceCollection(CollectionRef.Series(mid, it.seriesId, upName), it.name, it.cover, it.total)
        }

    /** 「合集」视图每行的第二行：「合集 · 24 个视频」/「系列 · 24 个视频」。 */
    fun countLabel(item: SpaceCollection): String =
        "${if (item.isSeries) "系列" else "合集"} · ${item.total} 个视频"
}
```

- [ ] **Step 5: 跑测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.collection.*"`
Expected: 7 个测试全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/biliseek/app/collection app/src/test/java/com/biliseek/app/collection
git commit -m "feat(collection): 合集模型与 CollectionPolicy

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 播放页互动状态带上合集与订阅状态

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/interaction/VideoActionPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/interaction/VideoInteractionController.kt`
- Test: `app/src/test/java/com/biliseek/app/interaction/VideoActionPolicyTest.kt`

**Interfaces:**
- Consumes: `CollectionPolicy.fromView`、`VideoCollection`（Task 4），`ArchiveRelation.seasonFav`（Task 2）。
- Produces:
  - `VideoInteractionState.collection: VideoCollection? = null`、`VideoInteractionState.seasonSubscribed: Boolean = false`
  - `VideoActionPolicy.applySeasonSubscribed(state: VideoInteractionState, seasonId: Long, subscribed: Boolean): VideoInteractionState`（不属于这个合集时返回同一个对象）
  - `VideoInteractionController.markSeasonSubscribed(seasonId: Long, subscribed: Boolean)`

- [ ] **Step 1: 写失败的测试**

在 `VideoActionPolicyTest.kt` 的 import 区加：

```kotlin
import com.biliseek.app.collection.CollectionEpisode
import com.biliseek.app.collection.CollectionSection
import com.biliseek.app.collection.VideoCollection
```

类里加一个测试：

```kotlin
    @Test
    fun `合集订阅状态只改同一合集的视频 别的原样返回`() {
        val collection = VideoCollection(
            seasonId = 100,
            title = "合集",
            sections = listOf(CollectionSection("", listOf(CollectionEpisode("BV1", "第一集", "", 60, "UP", 9)))),
            currentIndex = 0,
        )
        val inSeason = base.copy(collection = collection)
        assertTrue(VideoActionPolicy.applySeasonSubscribed(inSeason, 100, true).seasonSubscribed)
        assertFalse(
            VideoActionPolicy.applySeasonSubscribed(inSeason.copy(seasonSubscribed = true), 100, false).seasonSubscribed,
        )
        assertSame(inSeason, VideoActionPolicy.applySeasonSubscribed(inSeason, 200, true))
        assertSame(base, VideoActionPolicy.applySeasonSubscribed(base, 100, true))
    }
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.interaction.VideoActionPolicyTest"`
Expected: 编译失败，`No parameter with name 'collection'` / `Unresolved reference: applySeasonSubscribed`。

- [ ] **Step 3: 改 `VideoActionPolicy.kt`**

3a. import 区加 `import com.biliseek.app.collection.VideoCollection`。

3b. `VideoInteractionState` 里 `loadFailed` 后面、`busy` 前面加：

```kotlin
    /** 所属合集（view 的 `ugc_season`）；不属于合集为 null。播放页合集入口与弹层用。 */
    val collection: VideoCollection? = null,
    /** 已订阅 [collection]（relation 的 `season_fav`）；未登录为 false。 */
    val seasonSubscribed: Boolean = false,
```

3c. `object VideoActionPolicy` 里加：

```kotlin
    /** 合集订阅状态变了：属于这个合集的视频跟着改；别的原样返回（同一个对象）。 */
    fun applySeasonSubscribed(state: VideoInteractionState, seasonId: Long, subscribed: Boolean): VideoInteractionState =
        if (state.collection?.seasonId == seasonId) state.copy(seasonSubscribed = subscribed) else state
```

- [ ] **Step 4: 改 `VideoInteractionController.kt`**

4a. import 区加 `import com.biliseek.app.collection.CollectionPolicy`。

4b. 类注释第一句改成：「播放页视频互动栏（点赞 / 投币 / 收藏 / 三连）的状态与动作；简介区和合集入口的数据也出自这里的那次 view 请求。」

4c. `bind` 里构造 `loaded` 时，`loadFailed = view == null,` 后面加两行：

```kotlin
                loadFailed = view == null,
                collection = view?.let(CollectionPolicy::fromView),
                seasonSubscribed = relation?.seasonFav ?: false,
            )
```

4d. `markFavored` 后面加：

```kotlin
    /** 合集弹层订阅 / 取消订阅成功后（宿主调）：当前视频和缓存里同一合集的视频都跟着改。 */
    fun markSeasonSubscribed(seasonId: Long, subscribed: Boolean) {
        cache.entries.forEach { it.setValue(VideoActionPolicy.applySeasonSubscribed(it.value, seasonId, subscribed)) }
        val cur = _state.value ?: return
        val next = VideoActionPolicy.applySeasonSubscribed(cur, seasonId, subscribed)
        if (next !== cur) _state.value = next
    }
```

- [ ] **Step 5: 跑测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.interaction.VideoActionPolicyTest"`
Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/biliseek/app/interaction app/src/test/java/com/biliseek/app/interaction
git commit -m "feat(interaction): 播放页互动状态带上所属合集与订阅状态

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `CollectionController`，订阅合集从 `RemoteLibraryController` 迁过来

这一步是迁移：做完以后「列表 → 合集」的行为必须和原来完全一样。

**Files:**
- Create: `app/src/main/java/com/biliseek/app/collection/CollectionController.kt`
- Modify: `app/src/main/java/com/biliseek/app/data/RemoteListState.kt`
- Modify: `app/src/main/java/com/biliseek/app/remote/RemoteLibraryController.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/SeriesScreen.kt`

**Interfaces:**
- Consumes: Task 3 的仓库方法，Task 4 的模型，Task 5 的 `markSeasonSubscribed`，现有 `ListPlayer` / `PagedQueueSource` / `QueuePage` / `appendUnseen`（`com.biliseek.app.remote`）。
- Produces（`CollectionController`）：
  - `collectedList: StateFlow<List<CollectedItem>>`、`collectedListState: StateFlow<CollectedListLoadState>`、`loadCollectedList(page: Int = 1)`
  - `openedInLibrary: StateFlow<RemoteListState<SeriesArchive>?>`、`openedInUpSpace: StateFlow<RemoteListState<SeriesArchive>?>`
  - `open(slot: CollectionSlot, ref: CollectionRef, title: String)`、`close(slot: CollectionSlot)`、`loadPage(slot: CollectionSlot, page: Int)`、`play(slot: CollectionSlot, archive: SeriesArchive)`
  - `unsubscribeOpenedInLibrary()`
  - `playEpisodes(collection: VideoCollection, index: Int)`
  - `subscribeBusy: StateFlow<Boolean>`、`setSubscribed(seasonId: Long, title: String, subscribe: Boolean)`
  - `TrackListId.Collection(ref: CollectionRef)`（替换 `TrackListId.Series`）
  - `SeriesScreen(onUnsubscribe: (() -> Unit)?)`
  - `AppViewModel.collections: CollectionController`

- [ ] **Step 1: 改 `TrackListId`**（`app/src/main/java/com/biliseek/app/data/RemoteListState.kt`）

import 区加 `import com.biliseek.app.collection.CollectionRef`，把

```kotlin
    /** 合集（M13-N）：订阅合集分页拉取。 */
    data class Series(val seriesId: Long) : TrackListId
```

换成：

```kotlin
    /** 合集 / 系列（M13-N；2026-10-26 起含 UP 空间的合集与系列）：内容分页拉取，[ref] 决定用哪个接口。 */
    data class Collection(val ref: CollectionRef) : TrackListId
```

- [ ] **Step 2: 新建 `CollectionController.kt`**

```kotlin
package com.biliseek.app.collection

import com.biliseek.app.data.CollectedListLoadState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.TrackListId
import com.biliseek.app.remote.ListPlayer
import com.biliseek.app.remote.PagedQueueSource
import com.biliseek.app.remote.QueuePage
import com.biliseek.app.remote.appendUnseen
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.SeriesArchivesPage
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.dto.CollectionMediaItem
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 合集：我订阅的合集列表、合集内容页（「列表」tab 与 UP 空间各一份）、播放页合集弹层的起播与订阅。
 *
 * 订阅合集部分从 RemoteLibraryController 迁来（设计稿 docs/superpowers/specs/2026-10-26-collections-design.md）。
 * 内容只在内存里分页，不落库。当前视频属于哪个合集不在这里，是 VideoInteractionController 那次 view 请求顺带解析的。
 * 订阅状态变了经 [onSubscriptionChanged] 告诉宿主（同步播放页按钮），提示经 [notify]。
 */
class CollectionController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val playList: ListPlayer,
    private val notify: (String) -> Unit,
    private val onSubscriptionChanged: (seasonId: Long, subscribed: Boolean) -> Unit,
) {

    // ── 我订阅的合集列表（「列表 → 合集」）──

    private val _collectedList = MutableStateFlow<List<CollectedItem>>(emptyList())
    val collectedList: StateFlow<List<CollectedItem>> = _collectedList.asStateFlow()

    private val _collectedListState = MutableStateFlow(CollectedListLoadState())
    val collectedListState: StateFlow<CollectedListLoadState> = _collectedListState.asStateFlow()

    /** 加载订阅合集列表第 [page] 页。 */
    fun loadCollectedList(page: Int = 1) {
        val mid = session.value.dedeUserId.toLongOrNull() ?: return
        val state = _collectedListState.value
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        scope.launch {
            val loadingMore = page > 1
            _collectedListState.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            repository.collectedList(mid, page)
                .onSuccess { result ->
                    val base = _collectedListState.value
                    _collectedList.value = if (loadingMore) _collectedList.value + result.items else result.items
                    _collectedListState.value = base.copy(
                        page = result.page,
                        hasMore = result.hasMore,
                        totalCount = result.totalCount,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    _collectedListState.value = _collectedListState.value.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    // ── 合集内容页（两个槽）──

    private val slots = CollectionSlot.entries.associateWith {
        MutableStateFlow<RemoteListState<SeriesArchive>?>(null)
    }

    /** 「列表」tab 里打开的合集内容页。 */
    val openedInLibrary: StateFlow<RemoteListState<SeriesArchive>?> =
        slots.getValue(CollectionSlot.LIBRARY).asStateFlow()

    /** UP 空间页上打开的合集 / 系列内容页。 */
    val openedInUpSpace: StateFlow<RemoteListState<SeriesArchive>?> =
        slots.getValue(CollectionSlot.UP_SPACE).asStateFlow()

    private fun slot(slot: CollectionSlot) = slots.getValue(slot)

    private fun RemoteListState<*>.ref(): CollectionRef? = (id as? TrackListId.Collection)?.ref

    /** 打开合集内容页并取第 1 页。 */
    fun open(slot: CollectionSlot, ref: CollectionRef, title: String) {
        slot(slot).value = RemoteListState(id = TrackListId.Collection(ref), title = title)
        loadPage(slot, 1)
    }

    fun close(slot: CollectionSlot) {
        slot(slot).value = null
    }

    /** 加载内容第 [page] 页。结果回来时页面已换成别的合集就丢掉。 */
    fun loadPage(slot: CollectionSlot, page: Int) {
        val target = slot(slot)
        val state = target.value ?: return
        val ref = state.ref() ?: return
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        scope.launch {
            val loadingMore = page > 1
            target.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            fetchPage(ref, page)
                .onSuccess { result ->
                    val base = target.value ?: return@onSuccess
                    if (base.ref() != ref) return@onSuccess
                    val existing = if (loadingMore) base.items else emptyList()
                    target.value = base.copy(
                        // 列表 key 是 bvid：重复的去掉，免得 LazyColumn 撞 key
                        items = appendUnseen(existing, result.items.distinctBy { it.bvid }, emptySet()) { it.bvid },
                        page = result.page,
                        hasMore = result.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    val base = target.value ?: return@onFailure
                    if (base.ref() != ref) return@onFailure
                    target.value = base.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    /**
     * 点合集内容页里的一项：已加载的内容作为临时块，从这一项开始播；播到末尾附近自动续下一页（docs/10）。
     * 续页用自己的页码，不动界面上的列表状态。
     */
    fun play(slot: CollectionSlot, archive: SeriesArchive) {
        val state = slot(slot).value ?: return
        val ref = state.ref() ?: return
        val index = state.items.indexOf(archive)
        if (index < 0) {
            playList.play(listOf(archive.toRequest()), 0, loadMore = null)
            return
        }
        val source = PagedQueueSource(nextPage = state.page + 1, hasMore = state.hasMore) { page ->
            fetchPage(ref, page).map { result -> QueuePage(result.items.map { it.toRequest() }, result.hasMore) }
        }
        playList.play(state.items.map { it.toRequest() }, index, loadMore = source::next)
    }

    /** 不同来源的一页统一成 [SeriesArchivesPage]。UP 空间的条目接口不带 UP 主，用 ref 里的补上。 */
    private suspend fun fetchPage(ref: CollectionRef, page: Int): Result<SeriesArchivesPage> = when (ref) {
        is CollectionRef.Subscribed -> repository.collectionSeasonList(ref.seasonId, page).map { result ->
            SeriesArchivesPage(result.items.map { it.toArchive() }, result.page, result.hasMore)
        }
        is CollectionRef.Season -> repository.spaceSeasonArchives(ref.mid, ref.seasonId, page)
            .map { it.withAuthor(ref.upName, ref.mid) }
        is CollectionRef.Series -> repository.seriesArchives(ref.mid, ref.seriesId, page)
            .map { it.withAuthor(ref.upName, ref.mid) }
    }

    private fun SeriesArchivesPage.withAuthor(name: String, mid: Long) = copy(
        items = items.map {
            it.copy(
                author = it.author.ifBlank { name },
                authorMid = if (it.authorMid > 0) it.authorMid else mid,
            )
        },
    )

    // ── 播放页合集弹层 ──

    /** 合集弹层里点第 [index] 集：整个合集作为临时块，从这一集开始播（带图钉的条目不动，docs/10）。 */
    fun playEpisodes(collection: VideoCollection, index: Int) {
        if (index !in collection.episodes.indices) return
        playList.play(collection.episodes.map { it.toRequest() }, index, loadMore = null)
    }

    // ── 订阅 / 取消订阅 ──

    private val _subscribeBusy = MutableStateFlow(false)

    /** 订阅请求进行中：按钮不能点，再点也不重复发。 */
    val subscribeBusy: StateFlow<Boolean> = _subscribeBusy.asStateFlow()

    /** 取消订阅「列表」tab 里打开的合集（内容页标题右侧「取消订阅」）。 */
    fun unsubscribeOpenedInLibrary() {
        val state = slot(CollectionSlot.LIBRARY).value ?: return
        val ref = state.ref() as? CollectionRef.Subscribed ?: return
        setSubscribed(ref.seasonId, state.title, subscribe = false)
    }

    /**
     * 订阅 / 取消订阅合集。成功后：订阅 → 重新取订阅列表第 1 页；取消 → 列表里有就去掉、
     * 「列表」tab 开着的正是它就关掉；两种都经 [onSubscriptionChanged] 同步播放页。失败只提示，状态不动。
     */
    fun setSubscribed(seasonId: Long, title: String, subscribe: Boolean) {
        if (!session.value.isLoggedIn) {
            notify("请先登录")
            return
        }
        if (_subscribeBusy.value) return
        _subscribeBusy.value = true
        scope.launch {
            try {
                val result = if (subscribe) repository.favSeason(seasonId) else repository.unfavSeason(seasonId)
                result
                    .onSuccess {
                        if (subscribe) loadCollectedList(1) else removeFromCollected(seasonId)
                        onSubscriptionChanged(seasonId, subscribe)
                        notify(if (subscribe) "已订阅「$title」" else "已取消订阅「$title」")
                    }
                    .onFailure {
                        notify("${if (subscribe) "订阅" else "取消订阅"}失败：${it.message ?: "网络错误"}")
                    }
            } finally {
                _subscribeBusy.value = false
            }
        }
    }

    private fun removeFromCollected(seasonId: Long) {
        val before = _collectedList.value
        val after = before.filterNot { it.id == seasonId }
        if (after.size < before.size) {
            _collectedList.value = after
            _collectedListState.value = _collectedListState.value.let {
                it.copy(totalCount = (it.totalCount - 1).coerceAtLeast(0))
            }
        }
        val library = slot(CollectionSlot.LIBRARY)
        if ((library.value?.ref() as? CollectionRef.Subscribed)?.seasonId == seasonId) library.value = null
    }

    private fun CollectionMediaItem.toArchive() = SeriesArchive(
        bvid = bvid,
        cid = id,
        title = title,
        cover = cover,
        duration = duration.toLong(),
        author = upper.name,
        authorMid = upper.mid,
    )

    // 条目 → 播放请求。cid 留空：轮到播放时取分 P，多 P 原地展开（docs/10）
    private fun SeriesArchive.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = normalizeCoverUrl(cover),
        author = author,
        durationSec = duration,
        authorMid = authorMid,
    )

    private fun CollectionEpisode.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = normalizeCoverUrl(cover),
        author = author,
        durationSec = durationSec,
        authorMid = authorMid,
    )
}
```

- [ ] **Step 3: 从 `RemoteLibraryController.kt` 删掉迁走的部分**

3a. 删掉这几段（按内容找，不要按行号）：
   - 从 `    // ── 合集（M13-N）────────────────────────────────` 开始，到 `fun playSeriesArchive(archive: SeriesArchive) { … }` 这个方法结束（含 `_collectedList`、`collectedList`、`_openedSeries`、`openedSeries`、`_collectedListState`、`collectedListState`、`loadCollectedList`、`openSeries`、`closeSeries`、`loadSeriesPage`、`playSeriesArchive`）。
   - `    /** 取消订阅请求进行中：再点不重复发。 */` + `private var unsubscribing = false`，以及紧跟着的整个 `fun unsubscribeOpenedSeries() { … }`。
   - `    private fun CollectionMediaItem.toArchive() = SeriesArchive( … )`。
   - `    private fun SeriesArchive.toRequest() = PlaybackRequest.Bili( … )`。

3b. 删掉不再用的 import：

```kotlin
import com.biliseek.app.data.CollectedListLoadState
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.dto.CollectionMediaItem
import com.biliseek.core.network.dto.SeriesArchive
```

3c. 类注释改掉两处：
   - 第一句 `B 站远程列表：收藏夹（目录快照 + 内容分页）、稍后再看、订阅合集（列表 + 内容分页）。` → `B 站远程列表：收藏夹（目录快照 + 内容分页）、稍后再看。订阅合集在 CollectionController。`
   - `从稍后再看 / 收藏夹移除条目、取消订阅合集在这里` → `从稍后再看 / 收藏夹移除条目在这里`

3d. 分节注释 `    // ── 移除条目（稍后再看 / 收藏夹内容）、取消订阅合集 ─────────────────` → `    // ── 移除条目（稍后再看 / 收藏夹内容）─────────────────`

3e. 确认没有残留：

Run: `grep -n "Series\|collected\|Collected\|unsubscrib" app/src/main/java/com/biliseek/app/remote/RemoteLibraryController.kt`
Expected: 无输出。

- [ ] **Step 4: `AppViewModel.kt` 接上 `collections`**

4a. import 区加：

```kotlin
import com.biliseek.app.collection.CollectionController
```

4b. 紧接在 `val remoteLibrary: RemoteLibraryController = RemoteLibraryController( … )` 这一段后面加：

```kotlin

    // ── 合集（订阅列表、合集内容页、播放页合集弹层）：见 CollectionController ──

    val collections: CollectionController = CollectionController(
        scope = viewModelScope,
        repository = network.repository,
        session = session,
        playList = { items, startIndex, loadMore ->
            viewModelScope.launch {
                connection.playFromContext(items, startIndex, expandParts = true, loadMore = loadMore)
            }
        },
        notify = { emitMessage(it) },
        // videoInteraction 在后面才声明；这里的 lambda 只在订阅成功后才调，那时早已初始化
        onSubscriptionChanged = { seasonId, subscribed -> videoInteraction.markSeasonSubscribed(seasonId, subscribed) },
    )
```

- [ ] **Step 5: `MainActivity.kt` 的「列表」tab 改接 `collections`**

5a. import 区加：

```kotlin
import com.biliseek.app.collection.CollectionRef
import com.biliseek.app.collection.CollectionSlot
```

5b. 把

```kotlin
                val openedSeries by viewModel.remoteLibrary.openedSeries.collectAsState()
                val collectedList by viewModel.remoteLibrary.collectedList.collectAsState()
                val collectedListState by viewModel.remoteLibrary.collectedListState.collectAsState()
```

换成

```kotlin
                val openedSeries by viewModel.collections.openedInLibrary.collectAsState()
                val collectedList by viewModel.collections.collectedList.collectAsState()
                val collectedListState by viewModel.collections.collectedListState.collectAsState()
```

5c. 「已登录且合集列表从未加载过」那个 `LaunchedEffect(session.isLoggedIn)` 里的三处 `viewModel.remoteLibrary.` 换成 `viewModel.collections.`：

```kotlin
                LaunchedEffect(session.isLoggedIn) {
                    if (session.isLoggedIn && viewModel.collections.collectedList.value.isEmpty() &&
                        viewModel.collections.collectedListState.value.isLoading.not()
                    ) {
                        viewModel.collections.loadCollectedList(1)
                    }
                }
```

5d. `LibraryScreen(…)` 调用里，把

```kotlin
                                onCloseSeries = viewModel.remoteLibrary::closeSeries,
                                collectedList = collectedList,
                                collectedListState = collectedListState,
                                onOpenSeries = viewModel.remoteLibrary::openSeries,
                                onCollectedLoadMore = viewModel.remoteLibrary::loadCollectedList,
                                onSeriesLoadMore = viewModel.remoteLibrary::loadSeriesPage,
                                onPlaySeriesArchive = viewModel.remoteLibrary::playSeriesArchive,
                                onUnsubscribeSeries = viewModel.remoteLibrary::unsubscribeOpenedSeries,
```

换成

```kotlin
                                onCloseSeries = { viewModel.collections.close(CollectionSlot.LIBRARY) },
                                collectedList = collectedList,
                                collectedListState = collectedListState,
                                onOpenSeries = { id, title ->
                                    viewModel.collections.open(CollectionSlot.LIBRARY, CollectionRef.Subscribed(id), title)
                                },
                                onCollectedLoadMore = viewModel.collections::loadCollectedList,
                                onSeriesLoadMore = { viewModel.collections.loadPage(CollectionSlot.LIBRARY, it) },
                                onPlaySeriesArchive = { viewModel.collections.play(CollectionSlot.LIBRARY, it) },
                                onUnsubscribeSeries = viewModel.collections::unsubscribeOpenedInLibrary,
```

- [ ] **Step 6: `SeriesScreen.kt` 的「取消订阅」改成可选**

6a. 参数 `onUnsubscribe: () -> Unit,` 改成：

```kotlin
    /** 取消订阅这个合集；null = 不显示「取消订阅」（UP 空间里打开的合集 / 系列）。 */
    onUnsubscribe: (() -> Unit)?,
```

6b. 确认框里 `onUnsubscribe()` 改成 `onUnsubscribe?.invoke()`。

6c. 顶栏 `actions` 改成：

```kotlin
            actions = {
                if (onUnsubscribe != null) {
                    TextButton(onClick = { confirmingUnsubscribe = true }) { Text("取消订阅") }
                }
            },
```

6d. 类注释第二段改成：「结构与 [FavoriteDetail] 一致。「列表」tab 里打开时标题右侧有「取消订阅」：确认后取消订阅这个合集（成功后宿主关掉本页）；UP 空间里打开的合集 / 系列不显示。」

- [ ] **Step 7: 编译并跑 `:app` 单测**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全部 PASS。

Run: `grep -rn "remoteLibrary.openedSeries\|remoteLibrary.collected\|TrackListId.Series\|unsubscribeOpenedSeries\|playSeriesArchive\|loadSeriesPage" app/src`
Expected: 无输出。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java
git commit -m "refactor(collection): 订阅合集列表与合集内容页迁到 CollectionController

内容页分 LIBRARY / UP_SPACE 两个槽；SeriesScreen 的取消订阅改为可选。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: UP 空间「合集」视图

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`
- Modify: `app/src/main/java/com/biliseek/app/upspace/UpSpaceController.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`

**Interfaces:**
- Consumes: `BiliRepository.spaceCollections`（Task 3），`CollectionPolicy.spaceCollections` / `countLabel`、`SpaceCollection`（Task 4），`CollectionController.open` / `close` / `loadPage` / `play` / `openedInUpSpace`（Task 6）。
- Produces:
  - `UpSpaceView.COLLECTIONS`；`UpSpaceUiState.collections: RemoteListState<SpaceCollection>`
  - `UpSpaceController(onSpaceChanged: () -> Unit)`；`loadUpCollections(page: Int)`、`loadMoreUpCollections()`
  - `UpSpaceScreen(onCollectionClick: (SpaceCollection) -> Unit, onLoadMoreCollections: () -> Unit, onRetryCollections: () -> Unit)`

- [ ] **Step 1: `AppViewModel.kt` 改 UP 空间状态**

1a. import 区加 `import com.biliseek.app.collection.CollectionSlot` 和 `import com.biliseek.app.collection.SpaceCollection`。

1b. `enum class UpSpaceView { POSTS, DYNAMIC }` 改成：

```kotlin
enum class UpSpaceView { POSTS, DYNAMIC, COLLECTIONS }
```

1c. `UpSpaceUiState` 的注释改成 `/** UP 空间页状态：UP 头部卡 + 投稿 / 动态 / 合集三个视图。 */`，并在 `dynamic` 字段后面加：

```kotlin
    /** 「合集」视图：UP 主的合集与系列（合集在前）。第一次切过来才取。 */
    val collections: RemoteListState<SpaceCollection> = RemoteListState.empty(),
```

1d. `val upSpace: UpSpaceController = UpSpaceController( … )` 里 `onFollowChanged = { dynamics.refreshUpChips() },` 后面加：

```kotlin
        // 换 UP 主或关闭空间页：盖在上面的合集内容页一起关
        onSpaceChanged = { collections.close(CollectionSlot.UP_SPACE) },
```

- [ ] **Step 2: `UpSpaceController.kt`**

2a. import 区加：

```kotlin
import com.biliseek.app.collection.CollectionPolicy
import com.biliseek.app.remote.appendUnseen
```

2b. 类注释第一句改成「UP 空间页：UP 信息卡、关注状态与关注/取关、投稿 / 动态 / 合集三个视图及各自分页。」，耦合说明那句末尾补「、[onSpaceChanged]（换 UP 主或关闭时，宿主关掉盖在上面的合集内容页）」。

2c. 构造参数 `private val onFollowChanged: () -> Unit,` 后面加：

```kotlin
    private val onSpaceChanged: () -> Unit,
```

2d. `openUpSpace` 第一行（`// 本人的空间：…` 注释之前）加 `onSpaceChanged()`；`closeUpSpace` 里 `onExitSelection()` 后面加 `onSpaceChanged()`。

2e. `setUpSpaceView` 末尾（DYNAMIC 那个 `if` 后面）加：

```kotlin
        if (view == UpSpaceView.COLLECTIONS && cur.collections.page == 0 && !cur.collections.isLoading) {
            loadUpCollections(1)
        }
```

2f. 文件末尾 `loadMoreUpDynamic()` 后面加：

```kotlin
    /** 「合集」视图第 [page] 页。结果回来时已换成别的 UP 主就丢掉。 */
    fun loadUpCollections(page: Int) {
        val cur = _upSpace.value
        val up = cur.up ?: return
        if (cur.collections.isLoading || cur.collections.isLoadingMore) return
        val loadingMore = page > 1
        if (loadingMore && !cur.collections.hasMore) return
        scope.launch {
            _upSpace.value = cur.copy(
                collections = cur.collections.copy(
                    isLoading = !loadingMore,
                    isLoadingMore = loadingMore,
                    errorMessage = null,
                ),
            )
            repository.spaceCollections(up.mid, page)
                .onSuccess { result ->
                    if (_upSpace.value.up?.mid != up.mid) return@onSuccess
                    val base = _upSpace.value.collections
                    val incoming = CollectionPolicy.spaceCollections(result, up.mid, up.uname)
                    _upSpace.value = _upSpace.value.copy(
                        collections = base.copy(
                            items = appendUnseen(if (loadingMore) base.items else emptyList(), incoming, emptySet()) { it.key },
                            page = page,
                            hasMore = result.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                    )
                }
                .onFailure {
                    if (_upSpace.value.up?.mid != up.mid) return@onFailure
                    val base = _upSpace.value.collections
                    _upSpace.value = _upSpace.value.copy(
                        collections = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    fun loadMoreUpCollections() {
        val cur = _upSpace.value
        if (!cur.collections.hasMore) return
        loadUpCollections(cur.collections.page + 1)
    }
```

- [ ] **Step 3: `UpSpaceScreen.kt`**

3a. import 区加：

```kotlin
import androidx.compose.foundation.clickable
import com.biliseek.app.collection.CollectionPolicy
import com.biliseek.app.collection.SpaceCollection
```

3b. 类注释「双视图：…」那句改成「三个视图：**投稿**（arc/search，全可播）、**动态**（feed/space，复用动态 tab 的卡片组件）、**合集**（UP 主的合集与旧版系列，点开是合集内容页，由宿主盖在本页上）。各视图分页状态独立。」

3c. 参数 `onLoadMoreDynamic: () -> Unit,` 后面加：

```kotlin
    onCollectionClick: (SpaceCollection) -> Unit = {},
    onLoadMoreCollections: () -> Unit = {},
    onRetryCollections: () -> Unit = {},
```

3d. 分段按钮改成三个（两个现有按钮的 `count = 2` 改成 `count = 3`，再加第三个）：

```kotlin
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = state.view == UpSpaceView.POSTS,
                onClick = { onViewChange(UpSpaceView.POSTS) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            ) { Text("投稿") }
            SegmentedButton(
                selected = state.view == UpSpaceView.DYNAMIC,
                onClick = { onViewChange(UpSpaceView.DYNAMIC) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            ) { Text("动态") }
            SegmentedButton(
                selected = state.view == UpSpaceView.COLLECTIONS,
                onClick = { onViewChange(UpSpaceView.COLLECTIONS) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            ) { Text("合集") }
        }
```

3e. `when (state.view)` 里 `UpSpaceView.DYNAMIC -> UpDynamicList( … )` 后面加分支：

```kotlin
            UpSpaceView.COLLECTIONS -> UpCollectionsList(
                collections = state.collections,
                onItemClick = onCollectionClick,
                onLoadMore = onLoadMoreCollections,
                onRetry = onRetryCollections,
            )
```

3f. 文件末尾 `POST_COVER_CENTER_X` 之前加：

```kotlin
/** 「合集」视图：每行封面 + 名称 +「合集 / 系列 · N 个视频」（行样式同「列表 → 合集」）。 */
@Composable
private fun UpCollectionsList(
    collections: com.biliseek.app.data.RemoteListState<SpaceCollection>,
    onItemClick: (SpaceCollection) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        collections.isLoading && collections.items.isEmpty() -> BsLoadingState()
        collections.errorMessage != null && collections.items.isEmpty() ->
            BsErrorState(collections.errorMessage, onRetry = onRetry)
        collections.items.isEmpty() -> BsEmptyState("这位 UP 主还没有合集", icon = Icons.Outlined.VideoLibrary)
        else -> {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                items(collections.items, key = { it.key }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverThumbnail(normalizeCoverUrl(item.cover), width = 72.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                CollectionPolicy.countLabel(item),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (collections.hasMore || collections.isLoadingMore) {
                    item(key = "footer") {
                        // 失败后不自动再来（否则会一直重打 B 站），等用户点「重试」
                        if (shouldAutoLoadMore(collections.hasMore, collections.isLoadingMore, collections.errorMessage)) {
                            LaunchedEffect(collections.items.size) { onLoadMore() }
                        }
                        BsLoadMoreFooter(
                            isLoadingMore = collections.isLoadingMore,
                            errorMessage = collections.errorMessage,
                            onRetry = onLoadMore,
                            endText = "加载中…",
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: `MainActivity.kt` 接上 UP 空间的合集**

4a. `val upSpace by viewModel.upSpace.state.collectAsState()` 后面加：

```kotlin
                val upSpaceCollection by viewModel.collections.openedInUpSpace.collectAsState()
```

4b. `upSpaceContent = { onBack -> … }` 改成下面的结构：保留原来那行 `BackHandler`，在它后面判断有没有打开的合集；`UpSpaceScreen(…)` 原有参数全部不动，只在 `onLoadMoreDynamic = viewModel.upSpace::loadMoreUpDynamic,` 后面加三个参数：

```kotlin
                    upSpaceContent = { onBack ->
                        BackHandler(onBack = { viewModel.upSpace.closeUpSpace(); onBack() })
                        val openedUpCollection = upSpaceCollection
                        if (openedUpCollection != null) {
                            // UP 空间里点开的合集 / 系列：盖在空间页上；返回先关它（后注册的 BackHandler 优先）
                            BackHandler(onBack = { viewModel.collections.close(CollectionSlot.UP_SPACE) })
                            com.biliseek.app.ui.SeriesScreen(
                                state = openedUpCollection,
                                onClose = { viewModel.collections.close(CollectionSlot.UP_SPACE) },
                                onItemClick = { viewModel.collections.play(CollectionSlot.UP_SPACE, it) },
                                onLoadMore = { viewModel.collections.loadPage(CollectionSlot.UP_SPACE, it) },
                                onUnsubscribe = null,
                                videoActions = videoMenuActions,
                            )
                        } else {
                            UpSpaceScreen(
                                // …原有参数一个不动，在 onLoadMoreDynamic 后面加：
                                onCollectionClick = { item ->
                                    viewModel.collections.open(CollectionSlot.UP_SPACE, item.ref, item.name)
                                },
                                onLoadMoreCollections = viewModel.upSpace::loadMoreUpCollections,
                                onRetryCollections = { viewModel.upSpace.loadUpCollections(1) },
                                // …原有参数
                            )
                        }
                    },
```

（「…原有参数」指现有 `UpSpaceScreen(` 调用里 `state = upSpace,` 到 `onPostSummarize = …` 的全部参数，原样保留。）

- [ ] **Step 5: 编译并跑 `:app` 单测**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java
git commit -m "feat(upspace): UP 空间加「合集」视图，点开合集 / 系列内容页

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 播放页合集入口行与合集弹层

先确认 Task 1 已选定样式；不是「A + X」的话，本任务 Step 1 的代码应已按所选方案改过。

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/CollectionSheet.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/PlayerScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/AppShell.kt`
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`
- Test: `app/src/androidTest/java/com/biliseek/app/PlayerUiTest.kt`

**Interfaces:**
- Consumes: `VideoInteractionState.collection` / `seasonSubscribed`（Task 5），`CollectionPolicy`（Task 4），`CollectionController.playEpisodes` / `setSubscribed` / `subscribeBusy`（Task 6）。
- Produces:
  - `data class CollectionSheetHandlers(onPlayEpisode: (VideoCollection, Int) -> Unit, onSetSubscribed: (VideoCollection, Boolean) -> Unit)`
  - `internal fun CollectionEntryRow(collection: VideoCollection, linkColor: Color, onOpen: () -> Unit, modifier: Modifier = Modifier)`
  - `internal fun CollectionSheet(collection, playingBvid, subscribed, showSubscribe, subscribeBusy, accent, onPlayEpisode: (Int) -> Unit, onSetSubscribed: (Boolean) -> Unit, onDismiss)`
  - `PlayerScreen` / `AppShell` 新参数：`collectionHandlers: CollectionSheetHandlers`、`isLoggedIn: Boolean`、`subscribeBusy: Boolean`

- [ ] **Step 1: 新建 `CollectionSheet.kt`**（方案 A 入口行 + 方案 X 弹层行）

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.collection.CollectionEpisode
import com.biliseek.app.collection.CollectionPolicy
import com.biliseek.app.collection.CollectionSheetRow
import com.biliseek.app.collection.VideoCollection
import com.biliseek.app.ui.components.CompactDragHandle
import com.biliseek.app.ui.components.SettingsSheetSection
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/** 播放页合集弹层的动作。宿主实现（CollectionController）。 */
data class CollectionSheetHandlers(
    val onPlayEpisode: (VideoCollection, Int) -> Unit = { _, _ -> },
    val onSetSubscribed: (VideoCollection, Boolean) -> Unit = { _, _ -> },
)

/**
 * 播放页简介下面的合集入口：「合集  名称 …… 第 3/24 集 ›」，点开合集弹层。
 * 设计稿 docs/superpowers/specs/2026-10-26-collections-design.md §4（样式是用户在效果图里选的）。
 */
@Composable
internal fun CollectionEntryRow(
    collection: VideoCollection,
    linkColor: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "合集",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            collection.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(CollectionPolicy.progressLabel(collection), style = MaterialTheme.typography.labelLarge, color = linkColor)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = linkColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 合集弹层：标题区（合集名、共 N 集、订阅按钮）+ 全部集数（多个小节时带小节标题），打开时滚到当前这集。
 * 点某一集：是正在播的就只关弹层，否则从这一集起播整个合集再关弹层。点「已订阅」先确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionSheet(
    collection: VideoCollection,
    playingBvid: String,
    subscribed: Boolean,
    /** 已登录才显示订阅按钮。 */
    showSubscribe: Boolean,
    subscribeBusy: Boolean,
    accent: Color,
    onPlayEpisode: (Int) -> Unit,
    onSetSubscribed: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(collection) { CollectionPolicy.sheetRows(collection) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = CollectionPolicy.currentRowIndex(rows, collection.currentIndex),
    )
    var confirmingUnsubscribe by remember { mutableStateOf(false) }
    if (confirmingUnsubscribe) {
        // 与「列表 → 合集」内容页同一个确认框；两个键是「保留 / 取消订阅」，不用「取消」免得看混
        BsConfirmDialog(
            title = "取消订阅合集",
            text = "确定不再订阅「${collection.title}」？它会从合集列表里移除。",
            confirmLabel = "取消订阅",
            dismissLabel = "保留",
            destructive = true,
            onConfirm = {
                confirmingUnsubscribe = false
                onSetSubscribed(false)
            },
            onDismiss = { confirmingUnsubscribe = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactDragHandle() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    collection.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "共 ${collection.episodes.size} 集",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showSubscribe) {
                Spacer(Modifier.width(BsSpacing.md))
                // 按钮规范同 UP 空间的关注：未订阅 = 主操作（Button）；已订阅 = 次要（FilledTonalButton）
                if (subscribed) {
                    FilledTonalButton(onClick = { confirmingUnsubscribe = true }, enabled = !subscribeBusy) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                        Spacer(Modifier.width(BsSpacing.xs))
                        Text("已订阅")
                    }
                } else {
                    Button(onClick = { onSetSubscribed(true) }, enabled = !subscribeBusy) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                        Spacer(Modifier.width(BsSpacing.xs))
                        Text("订阅")
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(
                rows,
                key = { i, row -> if (row is CollectionSheetRow.Episode) "e${row.index}" else "h$i" },
            ) { _, row ->
                when (row) {
                    is CollectionSheetRow.Header -> SettingsSheetSection(row.title)
                    is CollectionSheetRow.Episode -> CollectionEpisodeRow(
                        episode = row.episode,
                        isCurrent = row.episode.bvid == playingBvid,
                        accent = accent,
                        onClick = {
                            if (CollectionPolicy.shouldPlayEpisode(collection, row.index, playingBvid)) {
                                onPlayEpisode(row.index)
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 弹层里的一集：封面 + 标题 + 时长；正在播的那集用封面取色、加粗，时长前加「正在播放」。 */
@Composable
private fun CollectionEpisodeRow(
    episode: CollectionEpisode,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val duration = formatDuration(episode.durationSec * 1000)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = BsSpacing.xl, vertical = BsSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(normalizeCoverUrl(episode.cover), width = 64.dp)
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isCurrent) "正在播放 · $duration" else duration,
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: `PlayerScreen.kt`**

2a. 参数列表末尾（`frozenFrame` 后面）加：

```kotlin
    /** 合集弹层的动作（点某一集、订阅 / 取消订阅）。 */
    collectionHandlers: CollectionSheetHandlers = CollectionSheetHandlers(),
    /** 已登录：合集弹层显示「订阅 / 已订阅」。 */
    isLoggedIn: Boolean = false,
    /** 订阅请求进行中：订阅按钮不能点。 */
    subscribeBusy: Boolean = false,
```

2b. `var showDescriptionSheet by remember(state.bvid) { mutableStateOf(false) }` 后面加：

```kotlin
    // 换视频时合集弹层跟着收起
    var showCollectionSheet by remember(state.bvid) { mutableStateOf(false) }
```

2c. 中段 Column 里 `boundInteraction?.let { info -> VideoDescriptionRow( … ) }` 后面加：

```kotlin
                    boundInteraction?.collection?.let { collection ->
                        CollectionEntryRow(
                            collection = collection,
                            linkColor = activeColor,
                            onOpen = { showCollectionSheet = true },
                        )
                    }
```

2d. `if (showDescriptionSheet && boundInteraction != null) { … }` 后面加：

```kotlin
        val collection = boundInteraction?.collection
        if (showCollectionSheet && collection != null) {
            CollectionSheet(
                collection = collection,
                playingBvid = state.bvid,
                subscribed = boundInteraction?.seasonSubscribed == true,
                showSubscribe = isLoggedIn,
                subscribeBusy = subscribeBusy,
                accent = activeColor,
                onPlayEpisode = { index -> collectionHandlers.onPlayEpisode(collection, index) },
                onSetSubscribed = { subscribe -> collectionHandlers.onSetSubscribed(collection, subscribe) },
                onDismiss = { showCollectionSheet = false },
            )
        }
```

- [ ] **Step 3: `AppShell.kt`**

3a. 参数 `videoActionHandlers: VideoActionHandlers = VideoActionHandlers(),` 后面加：

```kotlin
    /** 播放页合集弹层的动作与状态。 */
    collectionHandlers: CollectionSheetHandlers = CollectionSheetHandlers(),
    isLoggedIn: Boolean = false,
    subscribeBusy: Boolean = false,
```

3b. `PlayerScreen(` 调用里 `interactionHandlers = videoActionHandlers,` 后面加：

```kotlin
                                collectionHandlers = collectionHandlers,
                                isLoggedIn = isLoggedIn,
                                subscribeBusy = subscribeBusy,
```

- [ ] **Step 4: `MainActivity.kt`**

4a. `val upSpaceCollection by …`（Task 7 加的）后面加：

```kotlin
                val collectionSubscribeBusy by viewModel.collections.subscribeBusy.collectAsState()
```

4b. `AppShell(` 调用里 `videoActionHandlers = remember { … },` 后面加：

```kotlin
                    collectionHandlers = remember {
                        com.biliseek.app.ui.CollectionSheetHandlers(
                            onPlayEpisode = viewModel.collections::playEpisodes,
                            onSetSubscribed = { collection, subscribe ->
                                viewModel.collections.setSubscribed(collection.seasonId, collection.title, subscribe)
                            },
                        )
                    },
                    isLoggedIn = session.isLoggedIn,
                    subscribeBusy = collectionSubscribeBusy,
```

- [ ] **Step 5: 加 UI 测试（需设备运行；这里至少保证编译通过）**

`PlayerUiTest.kt` import 区加：

```kotlin
import com.biliseek.app.collection.CollectionEpisode
import com.biliseek.app.collection.CollectionSection
import com.biliseek.app.collection.VideoCollection
```

类里加：

```kotlin
    // ── 合集 ────────────────────────────────────────────

    private fun collection(currentIndex: Int?) = VideoCollection(
        seasonId = 100,
        title = "测试合集",
        sections = listOf(
            CollectionSection(
                "正片",
                listOf("BV1" to "第一集", "BV2" to "第二集", "BV3" to "第三集").map { (bvid, title) ->
                    CollectionEpisode(bvid, title, "", 60, "UP", 9)
                },
            ),
        ),
        currentIndex = currentIndex,
    )

    @Test
    fun 播放页_属于合集时显示入口行_点开弹层列出各集() {
        setPlayerScreen(
            playingState.copy(bvid = "BV2"),
            interaction = VideoInteractionState(bvid = "BV2", collection = collection(currentIndex = 1)),
        )

        compose.onNodeWithText("测试合集").assertIsDisplayed()
        compose.onNodeWithText("第 2/3 集").assertIsDisplayed().performClick()

        compose.onNodeWithText("共 3 集").assertIsDisplayed()
        compose.onNodeWithText("第三集").assertIsDisplayed()
    }

    @Test
    fun 播放页_不属于合集时没有入口行() {
        setPlayerScreen(playingState.copy(bvid = "BV2"), interaction = VideoInteractionState(bvid = "BV2"))

        compose.onNodeWithText("合集").assertDoesNotExist()
    }
```

- [ ] **Step 6: 编译（含 androidTest）并跑单测**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全部 PASS。

如果连着设备（用户手机或模拟器）：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.biliseek.app.PlayerUiTest`，期望全部 PASS。没有设备就在汇报里写「UI 测试只编译、未运行」。

- [ ] **Step 7: 提交**

```bash
git add app/src
git commit -m "feat(player): 播放页显示所属合集，弹层选集起播、订阅合集

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 文档、全量验证、装机

**Files:**
- Modify: `docs/05-功能优化篇.md`
- Modify: `docs/03-架构设计篇.md`
- Modify: `docs/BiliSeek-手动测试指南.md`

- [ ] **Step 1: `docs/05-功能优化篇.md`**：文件末尾（§13.83 后面）加：

```markdown
### 13.84 合集：播放页显示所属合集、UP 空间合集（2026-10-26）

- **需求**：播放时看不出视频属于哪个合集，也没法顺着合集往下听；UP 空间看不到 UP 主的合集。设计稿 `docs/superpowers/specs/2026-10-26-collections-design.md`。
- **播放页**：简介下面加一行「合集 · 名称 · 第 3/24 集 ›」，点开是合集弹层：合集名、共 N 集、登录后「订阅 / 已订阅」（点「已订阅」先确认）；全部集数（多个小节带小节标题），打开时滚到当前这集。点某一集：整个合集作为临时块从这一集开始播，带图钉的条目不动；点正在播的那集只关弹层。合集信息出自播放页本来就发的 `view`（`ugc_season`），订阅状态出自 `relation`（`season_fav`），**不多发请求**。播一个合集里的视频**不自动**接后续集。
- **UP 空间**：视图变成「投稿 / 动态 / 合集」，第一次切到「合集」才发请求（`x/polymer/web-space/seasons_series_list`）。合集在前、旧版系列在后，第二行「合集 / 系列 · N 个视频」；点开在空间页上盖一层合集内容页（`SeriesScreen`，不显示「取消订阅」）。合集内容按 UP 主排的顺序（`seasons_archives_list`），系列按发布时间从旧到新（`x/series/archives`，B 站网页默认从新到旧，这里有意不同）。三个读接口统一 WBI 签名、走 `FEED` 限速桶，不重试。
- **订阅**：新增写接口 `x/v3/fav/season/fav`，与取消订阅对称。订阅后「列表 → 合集」重新取第 1 页；取消后从列表里去掉。
- **结构**：新建 `CollectionController`（`app/collection/`），订阅合集列表和合集内容页从 `RemoteLibraryController` 迁过来；内容页分「列表」tab、UP 空间两个槽，互不覆盖。判断逻辑在 `CollectionPolicy`。`TrackListId.Series` 改为 `TrackListId.Collection(ref)`。
- **公开 API**：`:core:network` 新增合集相关 DTO、`SpaceCollectionsPage`、`BiliRepository.spaceCollections` / `spaceSeasonArchives` / `favSeason`；`seriesArchives` 改签名（加 `mid`，原先没有调用方）。
- **验证**：`CollectionDtoTest`、`CollectionPagePolicyTest`、`CollectionPolicyTest`、`VideoActionPolicyTest` 离线单测；`PlayerUiTest` 两个合集用例（是否在设备上跑过按实际写）。DTO 样本是照参考项目手写的，**接口字段未经真机验证**。**待真机确认**：`seasons_series_list` 签名后是否正常、几百集的大合集 `view` 是否返回全部集数、订阅 / 取消订阅后「列表 → 合集」是否同步、不登录能否看系列内容；「列表 → 合集」迁移后整套回归。
```

- [ ] **Step 2: `docs/03-架构设计篇.md`** 控制器表：

`RemoteLibraryController` 那一行改成：

```markdown
| `RemoteLibraryController` | `app/remote/` | 收藏夹 / 稍后再看。从稍后再看、收藏夹移除条目：先从界面拿掉，撤销窗口过后才提交，判断逻辑在 `RemoteListPolicy`；「加入列表 → 稍后再看」（`addToWatchLater`，按视频去重、逐个写，提示文案在 `RemoteListPolicy`） |
| `CollectionController` | `app/collection/` | 合集：我订阅的合集列表、合集内容页（「列表」tab 与 UP 空间各一个槽，`CollectionRef` 决定用哪个接口）、播放页合集弹层的起播与订阅 / 取消订阅（成功后经回调同步 `VideoInteractionController`）；当前视频属于哪个合集由 `VideoInteractionController` 解析 `view` 的 `ugc_season`；判断逻辑在 `CollectionPolicy` |
```

`UpSpaceController` 那一行改成：

```markdown
| `UpSpaceController` | `app/upspace/` | UP 空间：投稿 / 动态 / 合集三个视图（合集与旧版系列列表；点开的内容页在 `CollectionController` 的 UP 空间槽） |
```

- [ ] **Step 3: `docs/BiliSeek-手动测试指南.md`**：在「步骤 33.1」那一节表格之后、`## A5. 模拟器特有场景` 之前插入：

```markdown
### 步骤 33.2 — 合集（2026-10-26 新增，规则见 `docs/superpowers/specs/2026-10-26-collections-design.md`）

| 检查点 | 期望 |
| ---- | ---- |
| 播放一个属于合集的视频，打开播放页 | 简介那一行下面多一行「合集  合集名 …… 第 n/N 集 ›」 |
| 播放一个不属于合集的视频 | 没有这一行，也没有报错 |
| 点合集那一行 | 底部弹出合集弹层（窄横杠）：合集名、「共 N 集」；列表自动滚到当前这集，当前这集高亮、写「正在播放」 |
| 合集有多个小节 | 列表里有小节标题；只有一个小节时没有 |
| 弹层里点另一集 | 弹层关掉，从这一集开始播；打开队列：合集各集在队列里，之前「加入队列 / 下一首播放」加的（带图钉）还在 |
| 弹层里点正在播的那集 | 弹层关掉，**不重新播** |
| 未登录时打开弹层 | 没有「订阅」按钮，列表照常能点 |
| 登录后点「＋ 订阅」 | 提示「已订阅「X」」，按钮变「✓ 已订阅」；去「列表 → 合集」能看到它 |
| 点「✓ 已订阅」 | 弹确认框「取消订阅合集」，两个键「保留 / 取消订阅（红）」；确认后提示「已取消订阅「X」」，按钮变回「订阅」，「列表 → 合集」里没有了 |
| 订阅时断网 | 提示「订阅失败：…」，按钮不变 |
| 几百集的大合集 | 弹层里集数是否齐全（与 B 站对比；**这一条是确认接口行为**） |
| 只在后台听、没打开过播放页 | 不会为合集多发任何请求（日志里只有播放相关请求） |
| 打开 UP 空间 | 视图是「投稿 / 动态 / 合集」三段；切到「合集」才加载 |
| 「合集」视图 | 每行封面 + 名称 +「合集 · N 个视频」或「系列 · N 个视频」；合集在前、系列在后；没有合集的 UP 显示「这位 UP 主还没有合集」 |
| 「合集」视图断网 | 显示错误和「重试」，不会自己一直重试；恢复网络点「重试」能加载 |
| 点一个合集 | 盖一层合集内容页，标题是合集名，**没有「取消订阅」**；返回手势先回到 UP 空间的「合集」视图 |
| 点一个系列 | 同上；顺序是从旧到新 |
| 内容页里点一项 | 从这一项开始播，快播完时自动接下一页 |
| 内容页三点菜单「查看UP主」 | 回到这位 UP 主的空间页，合集内容页已关 |
| 「列表 → 合集」（迁移回归） | 列表、翻页、点开、滚到底加载、点播续页、「取消订阅」确认与提示，和之前完全一样 |
```

- [ ] **Step 4: 全量验证**

Run: `./gradlew test :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL，全部单测 PASS。

- [ ] **Step 5: 提交文档**

```bash
git add docs
git commit -m "docs: 合集功能记录、架构表与手动测试项

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: 装机**

先提醒用户解锁手机、准备点 ColorOS 的安装确认页，然后：

Run: `./gradlew installDebug`
Expected: `Installed on 1 device.`。adb 看不到手机时按记忆里的办法处理（查内核日志 `error -71`，重插后等链路稳定再装）。装完在汇报里写装机时间，列出 Task 9 Step 3 里要用户真机确认的项目。
