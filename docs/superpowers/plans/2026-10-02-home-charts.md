# 首页快捷入口改为榜单 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页「快捷入口」换成三张不需要登录的榜单卡片（每周必看 / 入站必刷 / 热歌榜），点开是榜单页，点条目起播。

**Architecture:** `:core:network` 新增 5 个公开接口、响应 DTO、对外的 `ChartPage` / `ChartItem`、三个仓库方法（走 `accountRead` 风控出口 + `FEED` 节流桶），以及放在 `internal/ChartPolicy.kt` 的纯函数（取最新一期、歌曲选 MV 还是关联稿件、映射）。`:app` 新增 `ChartController`（挂在 `AppViewModel.charts`）和 `ChartScreen`，榜单页作为「搜索」tab 里的覆盖页，与热力图点进去的日历史页同一做法，不新增导航路由。

**Tech Stack:** Kotlin、kotlinx.serialization、Retrofit、Jetpack Compose（Material 3）、JUnit4。

设计稿：[2026-10-02-home-charts-design.md](../specs/2026-10-02-home-charts-design.md)

## Global Constraints

以下抄自设计稿，每个任务都隐含遵守：

- 首页「快捷入口」一行三张卡片：每周必看（`Icons.Outlined.CalendarMonth`）、入站必刷（`Icons.Outlined.Whatshot`）、热歌榜（`Icons.Outlined.Leaderboard`），均不需要登录；分区标题仍是「快捷入口」；样式沿用现有 `QuickEntryCard`。
- 「稍后再看」「近期歌单」从首页去掉；「列表」页里的稍后再看入口和列表 tab 本身不动。`tabSwitchRequests` 仍被「去设置」使用，机制保留，只更新过时注释。
- 榜单页覆盖在「搜索」tab 里，**不新增导航路由**；`BackHandler` 关闭回首页；搜索关键词变为非空时关闭榜单页。
- 点条目：整张榜单作为临时队列，`ListPlayer.play(items, index, loadMore = null)`，与稍后再看一致，不覆盖用户加入的队列。
- 热歌榜每首歌：官方 MV（`mv_bvid`）优先，没有 MV 用关联稿件（`creation_bvid`），两者都空的歌曲不显示。播 MV 时时长填 0；播关联稿件时用 `creation_duration`。按名次升序。显示标题用歌名，作者用歌手。
- 5 个公开接口（无需登录、无需 WBI）：`GET x/web-interface/popular/series/list`、`GET x/web-interface/popular/series/one?number=N`、`GET x/web-interface/popular/precious`、`GET x/copyright-music-publicity/toplist/all_period?list_type=1`、`GET x/copyright-music-publicity/toplist/music_list?list_id=ID`。
- 风控：全部走 `accountRead`（冷却期内本地快速失败；`-352` 记入全局冷却、**绝不重试**；`-412` 重置设备凭据），请求套 `ThrottleBucket.FEED`。**不加任何重试循环**。不学 `pages` / `videoView` / `userCard` 只 `require(code == 0)` 的写法。
- 每周必看、热歌榜各 2 次请求（串行：先取最新一期，再取内容），入站必刷 1 次。每次打开榜单页或下拉刷新重新取，**不落库**，不做缓存。
- 对外新增的类型仅 `ChartPage(subtitle, items)`、`ChartItem(bvid, title, coverUrl, author, durationSec, playCount, rank)`，以及 `BiliApi` 签名里要用的响应 DTO（沿用现有 DTO 公开的惯例）。
- 纯函数在 `core/network/internal/ChartPolicy.kt`：`resolveLatestWeeklyNumber`、`resolveLatestMusicPeriod`、`resolveSongVideo` 和三个响应到 `ChartPage` 的映射（跳过没有 `bvid` 的条目，封面经 `normalizeCoverUrl`）。
- 页头一行说明：每周必看 = `config.name` 与 `config.subject` 用「 · 」连起来；入站必刷 = 官方 `explain`；热歌榜 =「第 N 期」。
- 不含热门播客；不做长按菜单、下载、往期切换、原创榜、榜单缓存。不动「稍后再看」页与列表 tab。

执行层面的约束：

- **不执行 `git commit` / `git push`**：用户尚未同意提交，改完留在工作区。
- **不访问 Bilibili 服务器**（CLAUDE.md 安全规则）：不 `curl` B 站、不运行 App、不跑设备上的测试。测试全部离线，样例 JSON 按社区文档手写，没对照过真实响应。
- 工作区里 `docs/01-开发环境搭建.md` 的未提交修改不是本次改动，**不要动、不要纳入**；首页标题栏小改（`BiliSeekWordmark`、头像提示等）的未提交改动也保持原样。
- 不要用 `cd` 改变工作目录，命令都在仓库根目录 `/home/minifive/own_work/BiliSeek` 下执行。
- Gradle 命令放后台跑并轮询日志末尾，不要靠退出码一个信号下结论。

---

### Task 1: 榜单的 DTO、对外模型与纯函数（`:core:network`，测试先行）

**Files:**
- Create: `core/network/src/main/java/com/biliseek/core/network/dto/ChartDto.kt`
- Create: `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt`
- Create: `core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt`
- Test: `core/network/src/test/java/com/biliseek/core/network/ChartDtoTest.kt`
- Test: `core/network/src/test/java/com/biliseek/core/network/ChartPolicyTest.kt`

**Interfaces:**
- Produces（Task 2、3 依赖）：
  - `data class ChartItem(bvid: String, title: String, coverUrl: String, author: String, durationSec: Long, playCount: Long?, rank: Int?)`
  - `data class ChartPage(subtitle: String, items: List<ChartItem>)`，`ChartPage.EMPTY`
  - DTO：`WeeklySeriesListData(list: List<WeeklySeriesEntry>)`、`WeeklySeriesOneData(config, list: List<PopularVideo>)`、`PreciousData(title, explain, list)`、`MusicPeriodsData(list: Map<String, List<MusicPeriod>>)`、`MusicListData(list: List<MusicSong>)`
  - 纯函数：`resolveLatestWeeklyNumber(List<WeeklySeriesEntry>): Int?`、`resolveLatestMusicPeriod(Map<String, List<MusicPeriod>>): MusicPeriod?`、`resolveSongVideo(MusicSong): SongVideo?`、`WeeklySeriesOneData.toChartPage(): ChartPage`、`PreciousData.toChartPage(): ChartPage`、`MusicListData.toChartPage(period: MusicPeriod): ChartPage`

- [ ] **Step 1: 写 DTO 解析测试**

创建 `core/network/src/test/java/com/biliseek/core/network/ChartDtoTest.kt`：

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriodsData
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.WeeklySeriesListData
import com.biliseek.core.network.dto.WeeklySeriesOneData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 榜单接口（每周必看 / 入站必刷 / 热歌榜）的 DTO 解析。
 *
 * 样例 JSON 按社区文档（bilibili-API-collect）的示例手写，**没有对照过真实响应**：
 * 这里钉住字段名映射和脏数据回退——解析错了不会崩，只会少显示。
 */
class ChartDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `每周必看期数列表解析`() {
        val r = json.decodeFromString<BiliResponse<WeeklySeriesListData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{"list":[
              {"number":279,"subject":"身残志坚钻石大盗","status":2,"name":"2024第279期 07.19 - 07.25"},
              {"number":278,"subject":"中国诸神最强简史","status":2,"name":"2024第278期 07.12 - 07.18"}
            ]}}
            """.trimIndent(),
        )
        val list = r.data!!.list
        assertEquals(listOf(279, 278), list.map { it.number })
        assertEquals("身残志坚钻石大盗", list[0].subject)
    }

    @Test
    fun `每周必看某一期解析`() {
        val r = json.decodeFromString<BiliResponse<WeeklySeriesOneData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "config":{"id":3,"type":"weekly_selected","number":3,"subject":"人类首张黑洞照片",
                        "name":"2019第3期 04.05 - 04.11","label":"第3期(0412更新)"},
              "reminder":"每周五晚18:00更新",
              "list":[
                {"aid":48918074,"bvid":"BV1Nb411M7r8","title":"人类第一张黑洞照片，来了！",
                 "pic":"http://i0.hdslb.com/bfs/archive/a2cb.jpg","duration":89,
                 "owner":{"mid":407045223,"name":"二次元的中科院物理所","face":"https://i0.hdslb.com/bfs/face/978e.jpg"},
                 "stat":{"aid":48918074,"view":1594796,"danmaku":23255},
                 "rcmd_reason":"和B站小伙伴一起见证历史！"}
              ]}}
            """.trimIndent(),
        )
        val data = r.data!!
        assertEquals(3, data.config.number)
        assertEquals("人类首张黑洞照片", data.config.subject)
        assertEquals("2019第3期 04.05 - 04.11", data.config.name)
        val v = data.list.single()
        assertEquals("BV1Nb411M7r8", v.bvid)
        assertEquals("http://i0.hdslb.com/bfs/archive/a2cb.jpg", v.pic)
        assertEquals(89L, v.duration)
        assertEquals("二次元的中科院物理所", v.owner?.name)
        assertEquals(1594796L, v.stat?.view)
    }

    @Test
    fun `入站必刷解析`() {
        val r = json.decodeFromString<BiliResponse<PreciousData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "title":"入站必刷","media_id":496307088,"explain":"我不允许还有人没看过这98个宝藏视频！",
              "list":[
                {"aid":706,"bvid":"BV1xx411c79H","title":"【東方】Bad Apple!! ＰＶ【影絵】",
                 "pic":"http://i1.hdslb.com/bfs/archive/7534.jpg","duration":219,
                 "owner":{"mid":37,"name":"折射"},"stat":{"view":11329388},
                 "achievement":"东方project标志性视频"}
              ]}}
            """.trimIndent(),
        )
        val data = r.data!!
        assertEquals("我不允许还有人没看过这98个宝藏视频！", data.explain)
        assertEquals("BV1xx411c79H", data.list.single().bvid)
        assertEquals("折射", data.list.single().owner?.name)
    }

    @Test
    fun `热歌榜期数列表按年份对象解析`() {
        val r = json.decodeFromString<BiliResponse<MusicPeriodsData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{"list":{
              "2022":[{"ID":38,"priod":29,"publish_time":1672394399},{"ID":36,"priod":28,"publish_time":1671789599}],
              "2023":[{"ID":76,"priod":48,"publish_time":1700000000}]
            }}}
            """.trimIndent(),
        )
        val byYear = r.data!!.list
        assertEquals(setOf("2022", "2023"), byYear.keys)
        val first = byYear.getValue("2022")[0]
        assertEquals(38L, first.id)
        assertEquals(29, first.period)
        assertEquals(1672394399L, first.publishTime)
    }

    @Test
    fun `热歌榜歌曲解析`() {
        val r = json.decodeFromString<BiliResponse<MusicListData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{"list":[
              {"music_id":"MA409252256362326366","music_title":"極楽浄土","singer":"GARNiDELiA",
               "album":"約束 -Promise code-","mv_aid":28361833,"mv_bvid":"BV1us41137Fd",
               "mv_cover":"https://i0.hdslb.com/bfs/station_src/music_metadata/07e0.jpg",
               "heat":5328979,"rank":1,"can_listen":true,"recommendation":"",
               "creation_aid":910787823,"creation_bvid":"BV1HM4y1b79Z",
               "creation_cover":"http://i1.hdslb.com/bfs/archive/5780.jpg",
               "creation_title":"【MARiA】乘风2023初舞台！","creation_up":110352985,
               "creation_nickname":"GARNiDELiA","creation_duration":100,"creation_play":13487670,
               "creation_reason":"官方现场","achievements":["重回榜单","最高排名1"]}
            ]}}
            """.trimIndent(),
        )
        val song = r.data!!.list.single()
        assertEquals("極楽浄土", song.title)
        assertEquals("GARNiDELiA", song.singer)
        assertEquals("BV1us41137Fd", song.mvBvid)
        assertEquals("https://i0.hdslb.com/bfs/station_src/music_metadata/07e0.jpg", song.mvCover)
        assertEquals(1, song.rank)
        assertEquals("BV1HM4y1b79Z", song.creationBvid)
        assertEquals("http://i1.hdslb.com/bfs/archive/5780.jpg", song.creationCover)
        assertEquals(100L, song.creationDuration)
    }

    @Test
    fun `缺字段和 null 都回退到默认值，不抛错`() {
        val one = json.decodeFromString<BiliResponse<WeeklySeriesOneData>>(
            """
            {"code":0,"data":{"list":[
              {"bvid":"BV1a","title":"没有 owner 和 stat","pic":"","duration":null,"owner":null,"stat":null}
            ]}}
            """.trimIndent(),
        ).data!!
        assertEquals("", one.config.name)
        val v = one.list.single()
        assertNull(v.owner)
        assertNull(v.stat)
        assertEquals(0L, v.duration)

        val songs = json.decodeFromString<BiliResponse<MusicListData>>(
            """{"code":0,"data":{"list":[{"music_title":"无 MV","mv_bvid":null,"rank":null,"creation_bvid":"BV1b"}]}}""",
        ).data!!
        val s = songs.list.single()
        assertEquals("", s.mvBvid)
        assertEquals(0, s.rank)
        assertEquals("BV1b", s.creationBvid)
    }
}
```

- [ ] **Step 2: 写纯函数测试**

创建 `core/network/src/test/java/com/biliseek/core/network/ChartPolicyTest.kt`：

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriod
import com.biliseek.core.network.dto.MusicSong
import com.biliseek.core.network.dto.PopularOwner
import com.biliseek.core.network.dto.PopularStat
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.WeeklySeriesConfig
import com.biliseek.core.network.dto.WeeklySeriesEntry
import com.biliseek.core.network.dto.WeeklySeriesOneData
import com.biliseek.core.network.internal.resolveLatestMusicPeriod
import com.biliseek.core.network.internal.resolveLatestWeeklyNumber
import com.biliseek.core.network.internal.resolveSongVideo
import com.biliseek.core.network.internal.toChartPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 榜单的取最新一期、歌曲选视频、响应到 [ChartPage] 的映射。 */
class ChartPolicyTest {

    private fun song(
        title: String = "歌",
        mvBvid: String = "",
        mvCover: String = "",
        rank: Int = 0,
        creationBvid: String = "",
        creationCover: String = "",
        creationDuration: Long = 0,
    ) = MusicSong(
        title = title,
        singer = "歌手",
        mvBvid = mvBvid,
        mvCover = mvCover,
        rank = rank,
        creationBvid = creationBvid,
        creationCover = creationCover,
        creationDuration = creationDuration,
    )

    // ── 取最新一期 ──────────────────────────────────

    @Test
    fun `每周必看取最大期数，不依赖接口返回的顺序`() {
        val entries = listOf(
            WeeklySeriesEntry(number = 277),
            WeeklySeriesEntry(number = 279),
            WeeklySeriesEntry(number = 278),
        )
        assertEquals(279, resolveLatestWeeklyNumber(entries))
    }

    @Test
    fun `期数列表为空或期数无效时返回 null`() {
        assertNull(resolveLatestWeeklyNumber(emptyList()))
        assertNull(resolveLatestWeeklyNumber(listOf(WeeklySeriesEntry(number = 0))))
    }

    @Test
    fun `热歌榜取所有年份里发布时间最晚的一期`() {
        val byYear = mapOf(
            "2022" to listOf(MusicPeriod(id = 38, period = 29, publishTime = 1_672_394_399)),
            "2023" to listOf(
                MusicPeriod(id = 70, period = 45, publishTime = 1_690_000_000),
                MusicPeriod(id = 76, period = 48, publishTime = 1_700_000_000),
            ),
        )
        assertEquals(76L, resolveLatestMusicPeriod(byYear)?.id)
    }

    @Test
    fun `发布时间相同时取期数大的`() {
        val byYear = mapOf(
            "2023" to listOf(
                MusicPeriod(id = 10, period = 5, publishTime = 100),
                MusicPeriod(id = 11, period = 6, publishTime = 100),
            ),
        )
        assertEquals(11L, resolveLatestMusicPeriod(byYear)?.id)
    }

    @Test
    fun `没有任何期或期 id 无效时返回 null`() {
        assertNull(resolveLatestMusicPeriod(emptyMap()))
        assertNull(resolveLatestMusicPeriod(mapOf("2023" to listOf(MusicPeriod(id = 0, period = 1, publishTime = 9)))))
    }

    // ── 歌曲选哪个视频 ───────────────────────────────

    @Test
    fun `有 MV 时优先播 MV，时长未知填 0`() {
        val v = resolveSongVideo(
            song(
                mvBvid = "BVmv", mvCover = "https://c/mv.jpg",
                creationBvid = "BVcr", creationCover = "https://c/cr.jpg", creationDuration = 100,
            ),
        )!!
        assertEquals("BVmv", v.bvid)
        assertEquals("https://c/mv.jpg", v.coverUrl)
        assertEquals(0L, v.durationSec)
    }

    @Test
    fun `没有 MV 时用关联稿件和它的时长`() {
        val v = resolveSongVideo(
            song(creationBvid = "BVcr", creationCover = "https://c/cr.jpg", creationDuration = 100),
        )!!
        assertEquals("BVcr", v.bvid)
        assertEquals("https://c/cr.jpg", v.coverUrl)
        assertEquals(100L, v.durationSec)
    }

    @Test
    fun `MV 与关联稿件都没有时返回 null`() {
        assertNull(resolveSongVideo(song()))
    }

    @Test
    fun `封面缺失时用另一个兜底，无协议和明文地址升 https`() {
        val viaCreation = resolveSongVideo(song(mvBvid = "BVmv", creationCover = "//i1.hdslb.com/c.jpg"))!!
        assertEquals("https://i1.hdslb.com/c.jpg", viaCreation.coverUrl)
        val plain = resolveSongVideo(song(mvBvid = "BVmv", mvCover = "http://i0.hdslb.com/m.jpg"))!!
        assertEquals("https://i0.hdslb.com/m.jpg", plain.coverUrl)
    }

    // ── 响应 → ChartPage ────────────────────────────

    @Test
    fun `每周必看页头是期名加主题，条目跳过没有 bvid 的，封面升 https`() {
        val page = WeeklySeriesOneData(
            config = WeeklySeriesConfig(number = 279, subject = "钻石大盗", name = "2024第279期 07.19 - 07.25"),
            list = listOf(
                PopularVideo(
                    bvid = "BV1", title = "甲", pic = "http://i0.hdslb.com/a.jpg", duration = 89,
                    owner = PopularOwner(name = "UP甲"), stat = PopularStat(view = 1_594_796),
                ),
                PopularVideo(bvid = "", title = "无 bvid"),
            ),
        ).toChartPage()
        assertEquals("2024第279期 07.19 - 07.25 · 钻石大盗", page.subtitle)
        assertEquals(
            listOf(
                ChartItem(
                    bvid = "BV1", title = "甲", coverUrl = "https://i0.hdslb.com/a.jpg",
                    author = "UP甲", durationSec = 89, playCount = 1_594_796, rank = null,
                ),
            ),
            page.items,
        )
    }

    @Test
    fun `期名或主题缺失时页头不留多余的分隔符`() {
        assertEquals("2024第1期", WeeklySeriesOneData(config = WeeklySeriesConfig(name = "2024第1期")).toChartPage().subtitle)
        assertEquals("钻石大盗", WeeklySeriesOneData(config = WeeklySeriesConfig(subject = "钻石大盗")).toChartPage().subtitle)
        assertEquals("", WeeklySeriesOneData().toChartPage().subtitle)
    }

    @Test
    fun `没有 owner 或播放量为 0 时对应字段留空`() {
        val item = PreciousData(
            list = listOf(PopularVideo(bvid = "BV2", title = "乙", stat = PopularStat(view = 0))),
        ).toChartPage().items.single()
        assertEquals("", item.author)
        assertNull(item.playCount)
    }

    @Test
    fun `入站必刷页头是官方说明`() {
        val page = PreciousData(explain = "我不允许还有人没看过这98个宝藏视频！").toChartPage()
        assertEquals("我不允许还有人没看过这98个宝藏视频！", page.subtitle)
    }

    @Test
    fun `热歌榜按名次升序，跳过没有可播视频的歌曲，页头是第 N 期`() {
        val data = MusicListData(
            list = listOf(
                song(title = "三", rank = 3, mvBvid = "BV3"),
                song(title = "无视频", rank = 2),
                song(title = "一", rank = 1, creationBvid = "BV1", creationDuration = 100),
            ),
        )
        val page = data.toChartPage(MusicPeriod(id = 76, period = 48, publishTime = 0))
        assertEquals("第 48 期", page.subtitle)
        assertEquals(listOf("一", "三"), page.items.map { it.title })
        assertEquals(listOf(1, 3), page.items.map { it.rank })
        assertEquals(listOf("歌手", "歌手"), page.items.map { it.author })
        assertNull(page.items[0].playCount)
        assertEquals(100L, page.items[0].durationSec)
        assertEquals(0L, page.items[1].durationSec)
    }

    @Test
    fun `名次缺失的歌曲排在最后，名次字段留空`() {
        val data = MusicListData(
            list = listOf(
                song(title = "无名次", rank = 0, mvBvid = "BV0"),
                song(title = "第二", rank = 2, mvBvid = "BV2"),
            ),
        )
        val page = data.toChartPage(MusicPeriod(id = 1, period = 0, publishTime = 0))
        assertEquals("", page.subtitle)
        assertEquals(listOf("第二", "无名次"), page.items.map { it.title })
        assertEquals(listOf(2, null), page.items.map { it.rank })
    }
}
```

- [ ] **Step 3: 跑测试，确认因为类型还不存在而编译失败**

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.ChartDtoTest" --tests "com.biliseek.core.network.ChartPolicyTest" --console=plain > /tmp/biliseek-chart-t1-red.log 2>&1; echo "exit=$?"; grep -nE '^e: ' /tmp/biliseek-chart-t1-red.log | head -n 8
```

Expected：`exit=1`，日志里有 `e: ... Unresolved reference: WeeklySeriesListData`（或 `ChartItem`、`MusicSong` 等）之类的编译错误。

- [ ] **Step 4: 写响应 DTO**

创建 `core/network/src/main/java/com/biliseek/core/network/dto/ChartDto.kt`：

```kotlin
package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 榜单（首页快捷入口）───────────────────────────────
// 字段按社区文档整理（bilibili-API-collect：popular.md / precious_videos.md / audio/rank.md），
// 只取用得到的。全部带默认值：缺字段不会让解析崩，只会少显示。

/** `x/web-interface/popular/series/list` —— 每周必看的全部期数。公开接口，不需要登录。 */
@Serializable
data class WeeklySeriesListData(
    val list: List<WeeklySeriesEntry> = emptyList(),
)

@Serializable
data class WeeklySeriesEntry(
    val number: Int = 0,
    val subject: String = "",
    val name: String = "",
)

/** `x/web-interface/popular/series/one` —— 每周必看某一期。 */
@Serializable
data class WeeklySeriesOneData(
    val config: WeeklySeriesConfig = WeeklySeriesConfig(),
    val list: List<PopularVideo> = emptyList(),
)

@Serializable
data class WeeklySeriesConfig(
    val number: Int = 0,
    val subject: String = "",
    /** 形如「2024第279期 07.19 - 07.25」。 */
    val name: String = "",
)

/** `x/web-interface/popular/precious` —— 入站必刷。 */
@Serializable
data class PreciousData(
    val title: String = "",
    /** 官方说明，如「我不允许还有人没看过这98个宝藏视频！」。 */
    val explain: String = "",
    val list: List<PopularVideo> = emptyList(),
)

/** 每周必看 / 入站必刷的视频条目（同视频详情的 data，只取要用的）。 */
@Serializable
data class PopularVideo(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** 秒。 */
    val duration: Long = 0,
    val owner: PopularOwner? = null,
    val stat: PopularStat? = null,
)

@Serializable
data class PopularOwner(
    val name: String = "",
)

@Serializable
data class PopularStat(
    val view: Long = 0,
)

/** `x/copyright-music-publicity/toplist/all_period` —— 音频榜单每期列表。 */
@Serializable
data class MusicPeriodsData(
    /** 年份 → 该年的各期，如 `{"2022": [{...}, {...}]}`。 */
    val list: Map<String, List<MusicPeriod>> = emptyMap(),
)

@Serializable
data class MusicPeriod(
    @SerialName("ID") val id: Long = 0,
    /** 期数。字段名按接口原样拼写（`priod`）。 */
    @SerialName("priod") val period: Int = 0,
    /** 秒时间戳。 */
    @SerialName("publish_time") val publishTime: Long = 0,
)

/** `x/copyright-music-publicity/toplist/music_list` —— 某一期榜单的歌曲。 */
@Serializable
data class MusicListData(
    val list: List<MusicSong> = emptyList(),
)

@Serializable
data class MusicSong(
    @SerialName("music_title") val title: String = "",
    val singer: String = "",
    /** 官方 MV；没有 MV 时为空串。 */
    @SerialName("mv_bvid") val mvBvid: String = "",
    @SerialName("mv_cover") val mvCover: String = "",
    /** 名次，1 为最高。 */
    val rank: Int = 0,
    /** 关联稿件（MV 缺失时的兜底）。 */
    @SerialName("creation_bvid") val creationBvid: String = "",
    @SerialName("creation_cover") val creationCover: String = "",
    /** 关联稿件时长，秒。 */
    @SerialName("creation_duration") val creationDuration: Long = 0,
)
```

- [ ] **Step 5: 写对外模型**

创建 `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt`：

```kotlin
package com.biliseek.core.network

/** 榜单里的一条可播放视频（首页快捷入口的三个榜单共用）。 */
data class ChartItem(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    /** UP 主（视频榜）或歌手（热歌榜）。 */
    val author: String,
    /** 秒；未知为 0（热歌榜播 MV 时）。 */
    val durationSec: Long,
    /** 播放量；没有该数据（热歌榜、或接口给 0）为 null。 */
    val playCount: Long?,
    /** 名次；只有热歌榜有，其余为 null。 */
    val rank: Int?,
)

/** 一份榜单：页头说明 + 条目。 */
data class ChartPage(
    val subtitle: String,
    val items: List<ChartItem>,
) {
    companion object {
        val EMPTY = ChartPage(subtitle = "", items = emptyList())
    }
}
```

- [ ] **Step 6: 写纯函数**

创建 `core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt`：

```kotlin
package com.biliseek.core.network.internal

import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.ChartPage
import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriod
import com.biliseek.core.network.dto.MusicSong
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.WeeklySeriesEntry
import com.biliseek.core.network.dto.WeeklySeriesOneData
import com.biliseek.core.network.normalizeCoverUrl

/** 每周必看：期数列表里最大的期数，不依赖接口返回的顺序。没有有效期数返回 null。 */
internal fun resolveLatestWeeklyNumber(entries: List<WeeklySeriesEntry>): Int? =
    entries.maxOfOrNull { it.number }?.takeIf { it > 0 }

/** 热歌榜：所有年份里发布时间最晚的一期（发布时间相同取期数大的）。没有有效期返回 null。 */
internal fun resolveLatestMusicPeriod(byYear: Map<String, List<MusicPeriod>>): MusicPeriod? =
    byYear.values.flatten()
        .maxWithOrNull(compareBy<MusicPeriod>({ it.publishTime }, { it.period }))
        ?.takeIf { it.id > 0 }

/** 热歌榜里一首歌实际要播的视频。 */
internal data class SongVideo(
    val bvid: String,
    val coverUrl: String,
    /** 秒。MV 的时长接口不给，为 0，由播放器取到真实时长后回填。 */
    val durationSec: Long,
)

/**
 * 热歌榜每首歌取哪个视频：官方 MV 优先；没有 MV 用关联稿件；两者都没有返回 null（该歌曲不展示）。
 * 封面缺失时用另一个兜底。
 */
internal fun resolveSongVideo(song: MusicSong): SongVideo? = when {
    song.mvBvid.isNotBlank() -> SongVideo(
        bvid = song.mvBvid,
        coverUrl = normalizeCoverUrl(song.mvCover.ifBlank { song.creationCover }),
        durationSec = 0,
    )
    song.creationBvid.isNotBlank() -> SongVideo(
        bvid = song.creationBvid,
        coverUrl = normalizeCoverUrl(song.creationCover.ifBlank { song.mvCover }),
        durationSec = song.creationDuration,
    )
    else -> null
}

private fun PopularVideo.toChartItem(): ChartItem? =
    if (bvid.isBlank()) {
        null
    } else {
        ChartItem(
            bvid = bvid,
            title = title,
            coverUrl = normalizeCoverUrl(pic),
            author = owner?.name.orEmpty(),
            durationSec = duration,
            playCount = stat?.view?.takeIf { it > 0 },
            rank = null,
        )
    }

/** 每周必看：页头 =「期名 · 主题」（缺哪个就省哪个）。 */
internal fun WeeklySeriesOneData.toChartPage(): ChartPage = ChartPage(
    subtitle = listOf(config.name, config.subject).filter { it.isNotBlank() }.joinToString(" · "),
    items = list.mapNotNull { it.toChartItem() },
)

/** 入站必刷：页头 = 官方说明。 */
internal fun PreciousData.toChartPage(): ChartPage = ChartPage(
    subtitle = explain,
    items = list.mapNotNull { it.toChartItem() },
)

/** 热歌榜：页头 =「第 N 期」；按名次升序（没有名次的排最后），没有可播视频的歌曲跳过。 */
internal fun MusicListData.toChartPage(period: MusicPeriod): ChartPage = ChartPage(
    subtitle = if (period.period > 0) "第 ${period.period} 期" else "",
    items = list
        .sortedBy { if (it.rank > 0) it.rank else Int.MAX_VALUE }
        .mapNotNull { song ->
            val video = resolveSongVideo(song) ?: return@mapNotNull null
            ChartItem(
                bvid = video.bvid,
                title = song.title,
                coverUrl = video.coverUrl,
                author = song.singer,
                durationSec = video.durationSec,
                playCount = null,
                rank = song.rank.takeIf { it > 0 },
            )
        },
)
```

- [ ] **Step 7: 跑测试，确认通过**

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.ChartDtoTest" --tests "com.biliseek.core.network.ChartPolicyTest" --console=plain > /tmp/biliseek-chart-t1-green.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-chart-t1-green.log
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
for name in ("ChartDtoTest", "ChartPolicyTest"):
    for f in glob.glob(f"core/network/build/test-results/testDebugUnitTest/*.{name}.xml"):
        r = ET.parse(f).getroot()
        print(name, "tests=%s failures=%s errors=%s skipped=%s" % (r.get("tests"), r.get("failures"), r.get("errors"), r.get("skipped")))
EOF
```

Expected：`exit=0`，日志末尾 `BUILD SUCCESSFUL`；两个测试类各打印一行，`failures=0 errors=0 skipped=0`（`ChartDtoTest` 6 例，`ChartPolicyTest` 15 例）。有失败就停下来如实报告，不要改测试期望去迁就实现，先判断是实现错了还是期望写错了。

---

### Task 2: 接口声明与仓库方法（`:core:network`）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`（5 个 import；新增榜单区块的 5 个接口）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（3 个 import；新增 `chartRead` 与三个公开方法）

**Interfaces:**
- Consumes（Task 1）：DTO 类型、`ChartPage` / `ChartPage.EMPTY`、`resolveLatestWeeklyNumber`、`resolveLatestMusicPeriod`、`toChartPage`。
- Produces（Task 3 依赖）：`BiliRepository.weeklyMustWatch(): Result<ChartPage>`、`preciousVideos(): Result<ChartPage>`、`hotSongs(): Result<ChartPage>`。

仓库本身在现有测试里不会被构造（没有仓库级测试的先例），这一步没有新单测，验证靠编译加整个模块的既有测试不被破坏。

- [ ] **Step 1: `BiliApi.kt` 加 import**

把

```kotlin
import com.biliseek.core.network.dto.CollectionSeasonData
```

改成

```kotlin
import com.biliseek.core.network.dto.CollectionSeasonData
import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriodsData
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.WeeklySeriesListData
import com.biliseek.core.network.dto.WeeklySeriesOneData
```

- [ ] **Step 2: `BiliApi.kt` 加 5 个接口**

把

```kotlin
    /** 稍后再看列表。需登录 Cookie（未登录返回 -101）。 */
    @GET("x/v2/history/toview")
    suspend fun watchLater(): BiliResponse<WatchLaterData>
```

改成

```kotlin
    /** 稍后再看列表。需登录 Cookie（未登录返回 -101）。 */
    @GET("x/v2/history/toview")
    suspend fun watchLater(): BiliResponse<WatchLaterData>

    // ── 榜单（首页快捷入口）──────────────────────────────
    // 均为公开接口，不需要登录，也不需要 WBI 签名。

    /** 每周必看：全部期数。 */
    @GET("x/web-interface/popular/series/list")
    suspend fun weeklySeriesList(): BiliResponse<WeeklySeriesListData>

    /** 每周必看：某一期的视频。 */
    @GET("x/web-interface/popular/series/one")
    suspend fun weeklySeriesOne(@Query("number") number: Int): BiliResponse<WeeklySeriesOneData>

    /** 入站必刷。 */
    @GET("x/web-interface/popular/precious")
    suspend fun preciousVideos(): BiliResponse<PreciousData>

    /** 音频榜单每期列表。`list_type`：1 = 热榜（热歌榜），2 = 原创榜。 */
    @GET("x/copyright-music-publicity/toplist/all_period")
    suspend fun musicToplistPeriods(@Query("list_type") listType: Int): BiliResponse<MusicPeriodsData>

    /** 音频榜单某一期的歌曲。 */
    @GET("x/copyright-music-publicity/toplist/music_list")
    suspend fun musicToplistSongs(@Query("list_id") listId: Long): BiliResponse<MusicListData>
```

- [ ] **Step 3: `BiliRepository.kt` 加 import**

把

```kotlin
import com.biliseek.core.network.internal.resolveRetryAction
```

改成

```kotlin
import com.biliseek.core.network.internal.resolveLatestMusicPeriod
import com.biliseek.core.network.internal.resolveLatestWeeklyNumber
import com.biliseek.core.network.internal.resolveRetryAction
import com.biliseek.core.network.internal.toChartPage
```

- [ ] **Step 4: `BiliRepository.kt` 加 `chartRead` 与三个公开方法**

在 `watchLater()` 之后、`// ── UP 主搜索与空间投稿（M11-D）` 之前插入。把

```kotlin
        accountRead("稍后再看") { api.watchLater() }
            ?.list.orEmpty().filter { it.bvid.isNotBlank() }
    }
```

改成

```kotlin
        accountRead("稍后再看") { api.watchLater() }
            ?.list.orEmpty().filter { it.bvid.isNotBlank() }
    }

    // ── 榜单（首页快捷入口）────────────────────────────────

    /**
     * 每周必看：先取期数列表里最大的一期，再取这一期的视频。公开接口，不需要登录。
     * 两次请求串行，各自走 [chartRead]；没有任何期数时返回空榜单。
     */
    suspend fun weeklyMustWatch(): Result<ChartPage> = runCatching {
        val number = resolveLatestWeeklyNumber(
            chartRead("每周必看期数") { api.weeklySeriesList() }?.list.orEmpty(),
        ) ?: return@runCatching ChartPage.EMPTY
        chartRead("每周必看") { api.weeklySeriesOne(number) }?.toChartPage() ?: ChartPage.EMPTY
    }

    /** 入站必刷。公开接口，一次请求取全部。 */
    suspend fun preciousVideos(): Result<ChartPage> = runCatching {
        chartRead("入站必刷") { api.preciousVideos() }?.toChartPage() ?: ChartPage.EMPTY
    }

    /**
     * 热歌榜（音频榜单 `list_type=1` 的「热榜」）：先取发布时间最晚的一期，再取这一期的歌曲。
     * 公开接口，不需要登录。两次请求串行；没有任何期时返回空榜单。
     */
    suspend fun hotSongs(): Result<ChartPage> = runCatching {
        val period = resolveLatestMusicPeriod(
            chartRead("热歌榜期数") { api.musicToplistPeriods(listType = 1) }?.list.orEmpty(),
        ) ?: return@runCatching ChartPage.EMPTY
        chartRead("热歌榜") { api.musicToplistSongs(period.id) }?.toChartPage(period) ?: ChartPage.EMPTY
    }

    /**
     * 榜单读请求的统一出口：走 [accountRead] 的风控分流（冷却期内本地快速失败，-352 记入冷却且绝不重试，
     * -412 重置设备凭据），请求本身套 FEED 限速桶。**不加重试循环。**
     */
    private suspend fun <T> chartRead(what: String, request: suspend () -> BiliResponse<T>): T? =
        accountRead(what) {
            requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) { request() }
        }
```

- [ ] **Step 5: 编译并跑整个模块的测试**

```bash
./gradlew :core:network:testDebugUnitTest --console=plain > /tmp/biliseek-chart-t2.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-chart-t2.log; grep -nE '^(e|w): .*(BiliApi|BiliRepository|Chart)' /tmp/biliseek-chart-t2.log | head -n 8; echo "(以上为空 = 这几个文件没有新警告或错误)"
```

Expected：`exit=0`，日志末尾 `BUILD SUCCESSFUL`；`:core:network` 的既有测试和 Task 1 的两个新测试类全部通过；没有 `Chart` / `BiliApi` / `BiliRepository` 相关的新警告。

---

### Task 3: `ChartController`、副行文字与接线（`:app`）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/chart/ChartController.kt`
- Create: `app/src/main/java/com/biliseek/app/chart/ChartPolicy.kt`
- Test: `app/src/test/java/com/biliseek/app/chart/ChartPolicyTest.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（1 个 import；新增 `charts`）

**Interfaces:**
- Consumes（Task 1、2）：`ChartItem`、`BiliRepository.weeklyMustWatch()` / `preciousVideos()` / `hotSongs()`；已有的 `com.biliseek.app.remote.ListPlayer`（`fun interface`，`play(items, startIndex, loadMore)`）、`formatPlayCount`（`com.biliseek.app.ui`）、`formatDuration`（`com.biliseek.core.player`）。
- Produces（Task 4 依赖）：
  - `enum class ChartKind(val title: String) { WEEKLY, PRECIOUS, HOT_SONGS }`
  - `data class ChartState(kind, subtitle = "", items = emptyList(), isLoading = false, errorMessage: String? = null)`
  - `class ChartController`：`val opened: StateFlow<ChartState?>`，`fun open(kind: ChartKind)`、`fun close()`、`fun retry()`、`fun play(item: ChartItem)`
  - `internal fun chartRowSubtitle(item: ChartItem): String`
  - `AppViewModel.charts: ChartController`

- [ ] **Step 1: 写副行文字的测试**

创建 `app/src/test/java/com/biliseek/app/chart/ChartPolicyTest.kt`：

```kotlin
package com.biliseek.app.chart

import com.biliseek.core.network.ChartItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 榜单行的副行文字：「作者 · 时长 · 播放量」，没有的项省略。
 *
 * 播放量用不到「万」的数值，避免小数点格式随系统地区变化让断言不稳；
 * 「万」「亿」的换算由 `formatPlayCount` 自己负责。
 */
class ChartPolicyTest {

    private fun item(author: String = "", durationSec: Long = 0, playCount: Long? = null) = ChartItem(
        bvid = "BV1", title = "t", coverUrl = "", author = author,
        durationSec = durationSec, playCount = playCount, rank = null,
    )

    @Test
    fun `作者、时长、播放量齐全`() {
        assertEquals("UP甲 · 01:29 · 9876 播放", chartRowSubtitle(item("UP甲", 89, 9876)))
    }

    @Test
    fun `没有作者时从时长开始`() {
        assertEquals("01:29 · 9876 播放", chartRowSubtitle(item("", 89, 9876)))
    }

    @Test
    fun `热歌榜只有歌手，时长未知、没有播放量`() {
        assertEquals("歌手", chartRowSubtitle(item("歌手", 0, null)))
    }

    @Test
    fun `什么都没有时是空串`() {
        assertEquals("", chartRowSubtitle(item()))
    }

    @Test
    fun `超过一小时用 h_mm_ss`() {
        assertEquals("1:02:03", chartRowSubtitle(item("", 3723, null)))
    }

    @Test
    fun `播放量为 0 时省略`() {
        assertEquals("UP · 00:10", chartRowSubtitle(item("UP", 10, 0)))
    }
}
```

- [ ] **Step 2: 跑测试，确认因为 `chartRowSubtitle` 不存在而编译失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.chart.ChartPolicyTest" --console=plain > /tmp/biliseek-chart-t3-red.log 2>&1; echo "exit=$?"; grep -nE '^e: ' /tmp/biliseek-chart-t3-red.log | head -n 5
```

Expected：`exit=1`，有 `e: ... Unresolved reference: chartRowSubtitle`。

- [ ] **Step 3: 写副行文字函数**

创建 `app/src/main/java/com/biliseek/app/chart/ChartPolicy.kt`：

```kotlin
package com.biliseek.app.chart

import com.biliseek.app.ui.formatPlayCount
import com.biliseek.core.network.ChartItem
import com.biliseek.core.player.formatDuration

/** 榜单行的副行：「作者 · 时长 · 播放量」，没有的项省略（热歌榜只有歌手）。 */
internal fun chartRowSubtitle(item: ChartItem): String = listOfNotNull(
    item.author.takeIf { it.isNotBlank() },
    item.durationSec.takeIf { it > 0 }?.let { formatDuration(it * 1000) },
    item.playCount?.takeIf { it > 0 }?.let { "${formatPlayCount(it)} 播放" },
).joinToString(" · ")
```

- [ ] **Step 4: 写控制器**

创建 `app/src/main/java/com/biliseek/app/chart/ChartController.kt`：

```kotlin
package com.biliseek.app.chart

import com.biliseek.app.remote.ListPlayer
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.ChartItem
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页快捷入口里的三个榜单。 */
enum class ChartKind(val title: String) {
    WEEKLY("每周必看"),
    PRECIOUS("入站必刷"),
    HOT_SONGS("热歌榜"),
}

/** 榜单页状态。只在内存里，不落库。 */
data class ChartState(
    val kind: ChartKind,
    /** 页头一行说明（期名 · 主题 / 官方说明 / 第 N 期）。 */
    val subtitle: String = "",
    val items: List<ChartItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 首页榜单（每周必看 / 入站必刷 / 热歌榜）。公开接口，不需要登录。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.charts` 持有。每次打开或下拉刷新重新取，不落库、不缓存
 * （设计稿：docs/superpowers/specs/2026-10-02-home-charts-design.md）。
 * 风控由 [BiliRepository] 的读请求出口负责；这里不重试、不循环。
 * 点条目起播经 [playList] 回到宿主，整张榜单作为临时队列（docs/10）。
 */
class ChartController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val playList: ListPlayer,
) {

    private val _opened = MutableStateFlow<ChartState?>(null)

    /** 当前打开的榜单页。null = 未打开。 */
    val opened: StateFlow<ChartState?> = _opened.asStateFlow()

    fun open(kind: ChartKind) {
        _opened.value = ChartState(kind, isLoading = true)
        fetch(kind)
    }

    fun close() {
        _opened.value = null
    }

    /** 下拉刷新 / 失败后重试。已在加载时忽略。 */
    fun retry() {
        val state = _opened.value ?: return
        if (state.isLoading) return
        _opened.value = state.copy(isLoading = true, errorMessage = null)
        fetch(state.kind)
    }

    private fun fetch(kind: ChartKind) {
        scope.launch {
            val result = when (kind) {
                ChartKind.WEEKLY -> repository.weeklyMustWatch()
                ChartKind.PRECIOUS -> repository.preciousVideos()
                ChartKind.HOT_SONGS -> repository.hotSongs()
            }
            // 榜单页已被关闭、或换成了别的榜：丢弃这次结果
            val current = _opened.value?.takeIf { it.kind == kind } ?: return@launch
            _opened.value = result.fold(
                onSuccess = { page ->
                    current.copy(
                        subtitle = page.subtitle,
                        items = page.items,
                        isLoading = false,
                        errorMessage = null,
                    )
                },
                onFailure = { current.copy(isLoading = false, errorMessage = it.message ?: "加载失败") },
            )
        }
    }

    /**
     * 点榜单条目：整张榜单作为临时队列，从这一条开始播，不覆盖用户加入的队列（docs/10）。
     * 榜单接口一次取全，不需要续页。
     */
    fun play(item: ChartItem) {
        val items = _opened.value?.items.orEmpty()
        val index = items.indexOf(item).takeIf { it >= 0 } ?: return
        playList.play(items.map { it.toRequest() }, index, loadMore = null)
    }

    // 榜单条目 → 播放请求。cid 留空：轮到播放时取分 P，多 P 原地展开（与稍后再看一致）
    private fun ChartItem.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = coverUrl,
        author = author,
        durationSec = durationSec,
    )
}
```

- [ ] **Step 5: `AppViewModel` 接线**

(a) 加 import。把

```kotlin
import com.biliseek.app.remote.RemoteLibraryController
```

改成

```kotlin
import com.biliseek.app.remote.RemoteLibraryController
import com.biliseek.app.chart.ChartController
```

(b) 在 `remoteLibrary` 之后新增 `charts`。把

```kotlin
    // ── 日历史页（M12）
```

改成

```kotlin
    // ── 首页榜单（每周必看 / 入站必刷 / 热歌榜）：见 ChartController ──

    val charts: ChartController = ChartController(
        scope = viewModelScope,
        repository = network.repository,
        playList = { items, startIndex, loadMore ->
            viewModelScope.launch {
                connection.playFromContext(items, startIndex, expandParts = true, loadMore = loadMore)
                _playbackStarted.tryEmit(Unit)
            }
        },
    )

    // ── 日历史页（M12）
```

- [ ] **Step 6: 跑测试并编译**

```bash
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.chart.ChartPolicyTest" --console=plain > /tmp/biliseek-chart-t3-green.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-chart-t3-green.log
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
for f in glob.glob("app/build/test-results/testDebugUnitTest/*chart.ChartPolicyTest.xml"):
    r = ET.parse(f).getroot()
    print("tests=%s failures=%s errors=%s skipped=%s" % (r.get("tests"), r.get("failures"), r.get("errors"), r.get("skipped")))
EOF
```

Expected：`exit=0`，`BUILD SUCCESSFUL`；打印一行 `tests=6 failures=0 errors=0 skipped=0`；`AppViewModel` 能编译（`charts` 构造的 lambda 与 `remoteLibrary` 的写法一致）。

---

### Task 4: 榜单页与首页接线（`:app` UI）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/ChartScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/HomeScreen.kt`（imports、KDoc、参数、快捷入口三张卡片、`QuickEntryCard`）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（import、收集状态、`searchContent` 加榜单分支和关闭守卫、`HomeEmptyState` 调用、一处过时注释）
- Modify: `app/src/main/java/com/biliseek/app/ui/AppShell.kt`（两处过时注释）

**Interfaces:**
- Consumes（Task 3）：`ChartKind`、`ChartState`、`ChartController`（`opened` / `open` / `close` / `retry` / `play`）、`chartRowSubtitle`、`AppViewModel.charts`；`ChartItem`（Task 1）。
- Produces：`@Composable fun ChartScreen(state: ChartState, onClose: () -> Unit, onRetry: () -> Unit, onItemClick: (ChartItem) -> Unit, modifier: Modifier = Modifier)`；`HomeEmptyState` 新参数 `onOpenChart: (ChartKind) -> Unit`（去掉 `onOpenWatchLater`、`onOpenRecentPlaylists`、`isLoggedIn`）。

纯界面接线，没有可抽出的判断逻辑（副行文字已在 Task 3 抽出并有单测），这一步的验证是编译与 grep，真机效果由用户看。

- [ ] **Step 1: 新建 `ChartScreen.kt`**

创建 `app/src/main/java/com/biliseek/app/ui/ChartScreen.kt`：

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.chart.ChartState
import com.biliseek.app.chart.chartRowSubtitle
import com.biliseek.core.network.ChartItem
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsTopBar

/**
 * 榜单页（每周必看 / 入站必刷 / 热歌榜）。公开接口，不需要登录。
 *
 * 形态照「稍后再看」页：顶栏（副标题放页头说明）+ 下拉刷新 + 加载中 / 失败可重试 / 空列表 + 列表。
 * 列表不给 key：热歌榜里不同的歌可能指向同一个视频，用 bvid 当 key 会因重复而崩溃。
 */
@Composable
fun ChartScreen(
    state: ChartState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (ChartItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        BsTopBar(title = state.kind.title, subtitle = state.subtitle, onNavigate = onClose)

        PullRefreshContainer(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            errorMessage = state.errorMessage,
            successText = "${state.kind.title}已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> BsLoadingState()

                state.errorMessage != null && state.items.isEmpty() ->
                    BsErrorState(state.errorMessage, onRetry = onRetry)

                state.items.isEmpty() -> BsEmptyState("榜单暂时是空的", icon = Icons.Outlined.Leaderboard)

                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.items) { item ->
                        ChartRow(item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(item: ChartItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.rank?.let { rank ->
            Text(
                rank.toString(),
                modifier = Modifier.widthIn(min = 28.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (rank <= 3) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        CoverThumbnail(item.coverUrl, width = 96.dp)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chartRowSubtitle(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 2: `HomeScreen.kt`：imports**

把

```kotlin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.LibraryMusic
```

改成

```kotlin
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Whatshot
```

再把

```kotlin
import com.biliseek.app.data.SearchHistoryEntity
```

改成

```kotlin
import com.biliseek.app.chart.ChartKind
import com.biliseek.app.data.SearchHistoryEntity
```

（`Schedule` 与 `LibraryMusic` 只被被替换掉的两张旧卡片使用，改完后用 `grep -n "Schedule\|LibraryMusic" app/src/main/java/com/biliseek/app/ui/HomeScreen.kt` 确认没有残留引用。）

- [ ] **Step 3: `HomeScreen.kt`：KDoc 与参数**

把

```kotlin
/**
 * 首页空状态。
 *
 * 「没有推荐」不等于「空屏」——展示的全是**用户自己的行为数据**：
 * 搜索历史、播放热力图、快捷入口。无算法、无个性化推荐，不破坏产品身份。
 *
 * [showSearchHistory]：搜索历史改为「聚焦搜索框时才弹出」（M11 真机反馈），
 * 未聚焦时首页不直接显示历史，避免占版面。
 *
 * M12：不再展示「最近播放」竖排列表——快捷入口的「近期歌单」承担了进列表页的职责。
 */
```

改成

```kotlin
/**
 * 首页空状态。
 *
 * 「没有推荐」不等于「空屏」——展示的以**用户自己的行为数据**为主：搜索历史、播放热力图；
 * 快捷入口是官方榜单（每周必看 / 入站必刷 / 热歌榜），全站同一份，不需要登录。
 * 无算法、无个性化推荐，不破坏产品身份。
 *
 * [showSearchHistory]：搜索历史改为「聚焦搜索框时才弹出」（M11 真机反馈），
 * 未聚焦时首页不直接显示历史，避免占版面。
 *
 * M12：不再展示「最近播放」竖排列表。
 */
```

再把

```kotlin
    /** 快捷入口「稍后再看」。登录后才显示。 */
    onOpenWatchLater: () -> Unit = {},
    /** 快捷入口「近期歌单」→ 跳底部列表 tab。 */
    onOpenRecentPlaylists: () -> Unit = {},
    /** 是否已登录（决定「稍后再看」入口是否显示）。 */
    isLoggedIn: Boolean = false,
```

改成

```kotlin
    /** 点快捷入口里的榜单卡片 → 打开对应的榜单页。 */
    onOpenChart: (ChartKind) -> Unit = {},
```

- [ ] **Step 4: `HomeScreen.kt`：三张卡片与 `QuickEntryCard`**

把

```kotlin
        // 快捷入口：稍后再看（需登录）+ 近期歌单（跳列表 tab）
        Section(title = "快捷入口") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    icon = Icons.Outlined.Schedule,
                    label = "稍后再看",
                    onClick = onOpenWatchLater,
                    visible = isLoggedIn,
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.LibraryMusic,
                    label = "近期歌单",
                    onClick = onOpenRecentPlaylists,
                    visible = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
```

改成

```kotlin
        // 快捷入口：官方榜单（每周必看 / 入站必刷 / 热歌榜），不需要登录
        Section(title = "快捷入口") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    icon = Icons.Outlined.CalendarMonth,
                    label = ChartKind.WEEKLY.title,
                    onClick = { onOpenChart(ChartKind.WEEKLY) },
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.Whatshot,
                    label = ChartKind.PRECIOUS.title,
                    onClick = { onOpenChart(ChartKind.PRECIOUS) },
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.Leaderboard,
                    label = ChartKind.HOT_SONGS.title,
                    onClick = { onOpenChart(ChartKind.HOT_SONGS) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
```

再把

```kotlin
    label: String,
    onClick: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Column(
```

改成

```kotlin
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
```

- [ ] **Step 5: `MainActivity.kt`：import 与状态收集**

(a) 把

```kotlin
import com.biliseek.app.ui.AppShell
```

改成

```kotlin
import com.biliseek.app.ui.AppShell
import com.biliseek.app.ui.ChartScreen
```

(b) 把

```kotlin
                val watchLater by viewModel.remoteLibrary.watchLater.collectAsState()
```

改成

```kotlin
                val watchLater by viewModel.remoteLibrary.watchLater.collectAsState()
                val chart by viewModel.charts.opened.collectAsState()
```

- [ ] **Step 6: `MainActivity.kt`：`searchContent` 加榜单分支与关闭守卫**

(a) 把

```kotlin
                    searchContent = {
                        val dayDate = dayHistoryDate
                        if (dayDate != null) {
```

改成

```kotlin
                    searchContent = {
                        // 外部分享链接会触发搜索：榜单页不能盖住结果页
                        LaunchedEffect(search.keyword) {
                            if (search.keyword.isNotBlank()) viewModel.charts.close()
                        }
                        val dayDate = dayHistoryDate
                        val openedChart = chart
                        if (dayDate != null) {
```

(b) 把

```kotlin
                        } else {
                        // 首页空状态：无搜索、无结果时展示用户自己的行为数据
```

改成

```kotlin
                        } else if (openedChart != null) {
                            // 榜单页：从首页快捷入口点入，返回手势收起到首页
                            BackHandler(onBack = viewModel.charts::close)
                            ChartScreen(
                                state = openedChart,
                                onClose = viewModel.charts::close,
                                onRetry = viewModel.charts::retry,
                                onItemClick = viewModel.charts::play,
                            )
                        } else {
                        // 首页空状态：无搜索、无结果时展示用户自己的行为数据
```

- [ ] **Step 7: `MainActivity.kt`：`HomeEmptyState` 调用与过时注释**

(a) 把

```kotlin
                                        onDayClick = { date -> viewModel.openDayHistory(date) },
                                        onOpenWatchLater = {
                                            tabSwitchRequests.tryEmit(Destination.LIBRARY)
                                            viewModel.remoteLibrary.openWatchLater()
                                        },
                                        onOpenRecentPlaylists = { tabSwitchRequests.tryEmit(Destination.LIBRARY) },
                                        isLoggedIn = session.isLoggedIn,
```

改成

```kotlin
                                        onDayClick = { date -> viewModel.openDayHistory(date) },
                                        onOpenChart = viewModel.charts::open,
```

(b) 把

```kotlin
                // 程序化 tab 切换（首页快捷入口「近期歌单」→ 列表 tab）。
```

改成

```kotlin
                // 程序化 tab 切换（如「去设置」→ 设置 tab）。
```

- [ ] **Step 8: `AppShell.kt`：两处过时注释**

(a) 把

```kotlin
     * 程序化切换 tab（如首页快捷入口「近期歌单」→ 列表 tab）。
```

改成

```kotlin
     * 程序化切换 tab（如「去设置」→ 设置 tab）。
```

(b) 把

```kotlin
    // 程序化 tab 切换（快捷入口「近期歌单」）。
```

改成

```kotlin
    // 程序化 tab 切换（如「去设置」→ 设置 tab）。
```

- [ ] **Step 9: 验证**

```bash
grep -rn "近期歌单" app/src/main --include='*.kt' || echo "「近期歌单」已无残留引用"
grep -n "Schedule\|LibraryMusic" app/src/main/java/com/biliseek/app/ui/HomeScreen.kt || echo "HomeScreen 无残留的旧图标引用"
grep -nE "onOpenWatchLater|onOpenRecentPlaylists" app/src/main/java/com/biliseek/app/ui/HomeScreen.kt app/src/main/java/com/biliseek/app/MainActivity.kt
./gradlew :app:compileDebugKotlin --console=plain > /tmp/biliseek-chart-t4.log 2>&1; echo "exit=$?"; tail -n 6 /tmp/biliseek-chart-t4.log; grep -nE '^(e|w): .*(ChartScreen|ChartController|ChartPolicy|HomeScreen|MainActivity|AppViewModel)' /tmp/biliseek-chart-t4.log | head -n 10; echo "(以上为空 = 这几个文件没有新警告或错误)"
```

Expected：
- 第一条输出 `「近期歌单」已无残留引用`
- 第二条输出 `HomeScreen 无残留的旧图标引用`
- 第三条只剩 `MainActivity.kt` 里 `onOpenWatchLater = viewModel.remoteLibrary::openWatchLater`（列表 tab 用的，不是首页的）这一行
- Gradle `exit=0`，末尾 `BUILD SUCCESSFUL`；这几个文件没有新警告（`MainActivity.kt` 里原有的 `LocalLifecycleOwner` 弃用警告不是本次引入的，可以出现）

---

### Task 5: 全量验证与文档同步

**Files:**
- Modify: `docs/05-功能优化篇.md`（§10.0 待办一行；§13.1 总览表加一行；末尾追加 §13.28）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 14 一行改写、加检查点）
- Modify: `docs/03-架构设计篇.md`（不落库清单、控制器表、首页描述）

**Interfaces:** 无。

- [ ] **Step 1: 全量验证（先于写文档，文档里要写验证结果）**

```bash
./gradlew test assembleDebug --console=plain > /tmp/biliseek-home-charts-verify.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-home-charts-verify.log
```

这一步耗时较长，放后台跑并轮询日志末尾。Expected：`exit=0`，日志末尾 `BUILD SUCCESSFUL`。有测试失败就停下来如实报告，不继续写「通过」。

然后数出这次实际的测试例数，Step 3 要写进文档：

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
def count(pattern):
    t = f = e = s = 0
    for p in glob.glob(pattern):
        r = ET.parse(p).getroot()
        t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
        e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
    return t, f, e, s
print("core:network  tests=%d failures=%d errors=%d skipped=%d" % count("core/network/build/test-results/testDebugUnitTest/*.xml"))
print("app           tests=%d failures=%d errors=%d skipped=%d" % count("app/build/test-results/testDebugUnitTest/*.xml"))
EOF
```

Expected：两行都是 `failures=0 errors=0 skipped=0`。

- [ ] **Step 2: `docs/05-功能优化篇.md`：待办与总览表**

(a) §10.0 的「纯粹没排」一行。把

```markdown
**纯粹没排（产品待办）**：歌词（先问清数据来源与授权）、外部歌单导入、桌面歌词（卡 Lyricon 授权回溯）。
```

改成

```markdown
**纯粹没排（产品待办）**：歌词（先问清数据来源与授权）、外部歌单导入、桌面歌词（卡 Lyricon 授权回溯）、热门播客入口（App 热门页的运营活动页，接口待抓包，见 §13.28）。
```

(b) §13.1 总览表。把

```markdown
| 13.27 | 首页标题栏：标题改衬线艺术字、新用户文案改「探索你的 B 站」、未登录点头像弹提示 | ✅ 已实现；真机待用户验证 |
```

改成

```markdown
| 13.27 | 首页标题栏：标题改衬线艺术字、新用户文案改「探索你的 B 站」、未登录点头像弹提示 | ✅ 已实现；真机待用户验证 |
| 13.28 | 首页快捷入口改为榜单：每周必看 / 入站必刷 / 热歌榜 | ✅ 已实现（纯函数与解析离线单测）；真机待用户验证 |
```

- [ ] **Step 3: `docs/05-功能优化篇.md`：追加 §13.28**

在文件最后一条（§13.27 的「验证」那行）之后追加。把

```markdown
头像提示、设置 → 开源许可里的新条目。
```

改成下面的内容（例数 21 = `ChartDtoTest` 6 + `ChartPolicyTest` 15，`:app` 6 例，与 Task 1 Step 7、Task 3 Step 6 的实际输出一致；若实际输出不同，以实际为准并改这里）：

```markdown
头像提示、设置 → 开源许可里的新条目。

### 13.28 首页快捷入口改为榜单（2026-10-02）

设计稿：[home-charts](superpowers/specs/2026-10-02-home-charts-design.md)。

- **入口**：首页「快捷入口」由「稍后再看」（登录后才有）+「近期歌单」改为三张卡片：每周必看 / 入站必刷 / 热歌榜，都不需要登录。被换掉的两项在「列表」页里本来就有，列表 tab 与稍后再看页不动。
- **榜单页**：点卡片进页，覆盖在搜索 tab 里（与热力图点进去的日历史页同一做法，不新增路由），返回回到首页；搜索关键词变为非空时（含外部分享触发的搜索）自动关闭。样式照「稍后再看」页：顶栏、下拉刷新、加载中 / 失败重试 / 空列表。每行封面 + 标题 +「UP 或歌手 · 时长 · 播放量」，热歌榜左侧带名次；页头一行说明（每周必看：期名 · 主题；入站必刷：官方说明；热歌榜：第 N 期）。点条目整张榜单作临时队列起播。热歌榜每首歌优先播 MV，没有 MV 播关联稿件，两者都没有的歌曲不显示。
- **接口**（均为公开接口，无需登录、无需 WBI）：每周必看 `popular/series/list` 取最大期数 → `popular/series/one?number=`；入站必刷 `popular/precious`；热歌榜 `toplist/all_period?list_type=1` 取发布时间最晚的一期 → `toplist/music_list?list_id=`。全部走 `accountRead`（冷却期快速失败、`-352` 记冷却不重试、`-412` 重置凭据）+ `FEED` 节流桶，不加重试循环；每次打开 / 下拉刷新重新取，不落库、不缓存。
- **结构**：`:core:network` 新增 `ChartPage` / `ChartItem`、响应 DTO（`dto/ChartDto.kt`）、纯函数 `internal/ChartPolicy.kt`（取最新一期、歌曲选视频、映射）、仓库方法 `weeklyMustWatch()` / `preciousVideos()` / `hotSongs()`；`:app` 新增 `chart/ChartController`（`AppViewModel.charts`）、`ChartScreen`、`chartRowSubtitle`。
- **热门播客**：不做。它是 App 热门页「分品类热门」入口（App 端 gRPC `Popular/Index` + `entrance_id`，不走），网页端对应的是运营搭建的 `blackboard/era` 活动页，列表接口未知，需要在浏览器里抓包；即使找到，也只绑定这一个活动页，运营改版就会失效。见设计稿「待定」。
- **验证**：`ChartDtoTest`、`ChartPolicyTest`（`:core:network`，共 21 例，样例 JSON 按社区文档手写，**没对照过真实响应**）、`ChartPolicyTest`（`:app`，副行文字，6 例）；`./gradlew test assembleDebug` 通过。**未验证**：三个榜单真实返回的字段与数据（尤其热歌榜是否就是 App「音乐榜」那份、MV 能否播、`-352` 之外的错误提示）、榜单页真机外观、断网后重试。
```

- [ ] **Step 4: `docs/BiliSeek-手动测试指南.md`：步骤 14**

(a) 改写「快捷入口」那一行。把

```markdown
| 搜索历史（未聚焦） | **不直接显示**——首页只显示 slogan（新用户）/ 播放热力 / 快捷入口（稍后再看、近期歌单） |
```

改成

```markdown
| 搜索历史（未聚焦） | **不直接显示**——首页只显示 slogan（新用户）/ 播放热力 / 快捷入口（每周必看、入站必刷、热歌榜，不需要登录） |
```

(b) 在步骤 14 表格最后一行之后加榜单检查点。把

```markdown
| **时长** | 当日记录里**右边不应是 `00:00`** ← 时长回写生效的标志 |
```

改成

```markdown
| **时长** | 当日记录里**右边不应是 `00:00`** ← 时长回写生效的标志 |
| 点「每周必看」 | 进入榜单页：顶栏标题「每周必看」，副标题是「2024第N期 … · 主题」；列表每行有封面、标题、「UP · 时长 · 播放量」；下拉能刷新 |
| 点「入站必刷」 | 副标题是官方那句「我不允许还有人没看过这N个宝藏视频！」；列表同上 |
| 点「热歌榜」 | 副标题「第 N 期」；每行左侧有名次，前三名用主题色；**榜单内容和 B 站 App「音乐榜」对得上** ← 关键 |
| 点榜单里的条目 | 起播，MiniPlayer 出现；热歌榜的歌能播（有 MV 播 MV，没有播关联视频）；多 P 视频原地展开 |
| 榜单页按返回（手势或顶栏箭头） | 回到首页，**不是退出 App、不是跳到别的 tab** |
| 打开榜单页后切到别的 tab 再切回来 / 从播放页返回 | 榜单页还在 |
| 打开榜单页时从别的 App 分享一个 B 站链接进来 | 榜单页自动关闭，显示搜索 / 播放的结果，没有被榜单页盖住 |
| 断网后点榜单卡片 | 页面显示失败原因和「重试」；恢复网络后点重试能加载出来 |
| 登录前后各点一次榜单卡片 | 两种状态下都能打开、内容一致（榜单不依赖账号） |
```

- [ ] **Step 5: `docs/03-架构设计篇.md`**

(a) 不落库清单。把

```markdown
不落库：收藏夹**内容**、动态 feed、UP 投稿、关注列表、合集、稍后再看、消息、评论、搜索结果——全部内存缓存 + 分页。
```

改成

```markdown
不落库：收藏夹**内容**、动态 feed、UP 投稿、关注列表、合集、稍后再看、消息、评论、搜索结果、首页榜单（每周必看 / 入站必刷 / 热歌榜）——全部只在内存里，需要时分页。
```

(b) 控制器表。把

```markdown
| `RemoteLibraryController` | `app/remote/` | 收藏夹 / 合集 / 稍后再看 |
```

改成

```markdown
| `RemoteLibraryController` | `app/remote/` | 收藏夹 / 合集 / 稍后再看 |
| `ChartController` | `app/chart/` | 首页榜单：每周必看 / 入站必刷 / 热歌榜（公开接口，不需登录） |
```

(c) 首页描述。把

```
搜索（默认）  搜索框（聚焦时弹出搜索历史 15 条）+ 快捷入口 + 近一年（53 周）播放热力图；AV/BV/b23.tv 直达
```

改成

```
搜索（默认）  搜索框（聚焦时弹出搜索历史 15 条）+ 快捷入口（每周必看 / 入站必刷 / 热歌榜，点开是 Tab 内覆盖的榜单页）+ 近一年（53 周）播放热力图；AV/BV/b23.tv 直达
```

- [ ] **Step 6: 设计稿对齐实现**

计划比设计稿多了一个 `:app` 纯函数 `chartRowSubtitle` 和它的单测，设计稿同步改两处（`docs/superpowers/specs/2026-10-02-home-charts-design.md`）：

(a) 把

```markdown
- 新增 `ChartScreen`（`app/ui/ChartScreen.kt`）。
```

改成

```markdown
- 新增 `ChartScreen`（`app/ui/ChartScreen.kt`）和纯函数 `chartRowSubtitle`（`app/chart/ChartPolicy.kt`，榜单行的副行文字「作者 · 时长 · 播放量」，没有的项省略）。
```

(b) 把

```markdown
- 不新增 `:app` 单测：`ChartController` 是薄胶水，现有控制器也没有仓库级测试。
```

改成

```markdown
- `:app`：`ChartPolicyTest` 覆盖 `chartRowSubtitle`；`ChartController` 是薄胶水，现有控制器也没有仓库级测试，不另测。
```

- [ ] **Step 7: 收尾检查**

```bash
git status --short
```

Expected（不含任何提交；标注「既有」的几行不是本次改的）：

```
 M "docs/01-…"                                                   ← 既有
 M NOTICE                                                        ← 既有（首页标题栏小改）
 M app/src/main/assets/NOTICE.txt                                ← 既有（首页标题栏小改）
 M app/src/main/java/com/biliseek/app/AppViewModel.kt
 M app/src/main/java/com/biliseek/app/MainActivity.kt            ← 含首页标题栏小改的既有改动
 M app/src/main/java/com/biliseek/app/SearchScreen.kt            ← 既有（首页标题栏小改）
 M app/src/main/java/com/biliseek/app/ui/AppShell.kt
 M app/src/main/java/com/biliseek/app/ui/HomeScreen.kt           ← 含首页标题栏小改的既有改动
 M core/network/src/main/java/com/biliseek/core/network/BiliApi.kt
 M core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
 M docs/03-架构设计篇.md
 M docs/05-功能优化篇.md
 M docs/BiliSeek-手动测试指南.md
?? app/src/main/java/com/biliseek/app/chart/
?? app/src/main/java/com/biliseek/app/ui/ChartScreen.kt
?? app/src/main/java/com/biliseek/app/ui/components/BiliSeekWordmark.kt   ← 既有
?? app/src/main/res/font/                                                  ← 既有
?? app/src/test/java/com/biliseek/app/chart/
?? core/network/src/main/java/com/biliseek/core/network/ChartModels.kt
?? core/network/src/main/java/com/biliseek/core/network/dto/ChartDto.kt
?? core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt
?? core/network/src/test/java/com/biliseek/core/network/ChartDtoTest.kt
?? core/network/src/test/java/com/biliseek/core/network/ChartPolicyTest.kt
?? docs/superpowers/plans/
?? docs/superpowers/specs/
```

然后向用户汇报：改了什么、验证了什么（`./gradlew test assembleDebug` 结果与例数）、哪些没验证（真机上榜单的真实数据）、热门播客仍在等抓包结果；并问是否现在装到手机（装机前提醒先点亮并解锁手机，安装时要在手机上点确认）。**不提交。**
