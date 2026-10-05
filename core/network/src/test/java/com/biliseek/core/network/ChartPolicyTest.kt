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
import com.biliseek.core.network.internal.resolveCardItems
import com.biliseek.core.network.internal.resolveMusicIssues
import com.biliseek.core.network.internal.resolveSongVideo
import com.biliseek.core.network.internal.resolveWeeklyIssues
import com.biliseek.core.network.internal.toChartPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 榜单的期数列表、歌曲选视频、响应到 [ChartPage] 的映射。 */
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

    // ── 期数列表（最新在前）────────────────────────────

    @Test
    fun `每周必看的期数最新在前，不依赖接口返回的顺序`() {
        val entries = listOf(
            WeeklySeriesEntry(number = 277),
            WeeklySeriesEntry(number = 279),
            WeeklySeriesEntry(number = 278),
        )
        val issues = resolveWeeklyIssues(entries)
        assertEquals(listOf(279, 278, 277), issues.map { it.number })
        assertEquals(listOf(279L, 278L, 277L), issues.map { it.id })
    }

    @Test
    fun `每周必看的期数跳过无效的，同一期只留一个`() {
        val entries = listOf(
            WeeklySeriesEntry(number = 0),
            WeeklySeriesEntry(number = -3),
            WeeklySeriesEntry(number = 5),
            WeeklySeriesEntry(number = 5),
        )
        assertEquals(listOf(5), resolveWeeklyIssues(entries).map { it.number })
    }

    @Test
    fun `没有期数时返回空列表`() {
        assertEquals(emptyList<ChartIssue>(), resolveWeeklyIssues(emptyList()))
        assertEquals(emptyList<ChartIssue>(), resolveMusicIssues(emptyMap()))
    }

    @Test
    fun `热歌榜的期数跨年份合并，发布时间晚的在前`() {
        val byYear = mapOf(
            "2022" to listOf(MusicPeriod(id = 38, period = 29, publishTime = 1_672_394_399)),
            "2023" to listOf(
                MusicPeriod(id = 70, period = 45, publishTime = 1_690_000_000),
                MusicPeriod(id = 76, period = 48, publishTime = 1_700_000_000),
            ),
        )
        val issues = resolveMusicIssues(byYear)
        assertEquals(listOf(76L, 70L, 38L), issues.map { it.id })
        assertEquals(listOf(48, 45, 29), issues.map { it.number })
    }

    @Test
    fun `热歌榜发布时间相同时期数大的在前`() {
        val byYear = mapOf(
            "2023" to listOf(
                MusicPeriod(id = 10, period = 5, publishTime = 100),
                MusicPeriod(id = 11, period = 6, publishTime = 100),
            ),
        )
        assertEquals(listOf(11L, 10L), resolveMusicIssues(byYear).map { it.id })
    }

    @Test
    fun `热歌榜的期数跳过 id 无效的，同一个 id 只留一个`() {
        val byYear = mapOf(
            "2023" to listOf(
                MusicPeriod(id = 0, period = 9, publishTime = 900),
                MusicPeriod(id = 7, period = 3, publishTime = 300),
            ),
            "2024" to listOf(MusicPeriod(id = 7, period = 3, publishTime = 300)),
        )
        assertEquals(listOf(7L), resolveMusicIssues(byYear).map { it.id })
    }

    @Test
    fun `期数标签是第 N 期，接口没给期数时退成往期`() {
        assertEquals("第279期", ChartIssue(id = 279, number = 279).label)
        assertEquals("往期", ChartIssue(id = 7, number = 0).label)
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
    fun `UP 主编号随条目带出，热歌榜的歌没有`() {
        val video = PreciousData(
            list = listOf(PopularVideo(bvid = "BV3", title = "丙", owner = PopularOwner(name = "UP丙", mid = 77))),
        ).toChartPage().items.single()
        assertEquals(77L, video.authorMid)

        val noOwner = PreciousData(list = listOf(PopularVideo(bvid = "BV4", title = "丁"))).toChartPage().items.single()
        assertEquals(0L, noOwner.authorMid)

        val songItem = MusicListData(list = listOf(song(mvBvid = "BV9", rank = 1))).toChartPage(1).items.single()
        assertEquals(0L, songItem.authorMid)
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
        val page = data.toChartPage(48)
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
        val page = data.toChartPage(0)
        assertEquals("", page.subtitle)
        assertEquals(listOf("第二", "无名次"), page.items.map { it.title })
        assertEquals(listOf(2, null), page.items.map { it.rank })
    }

    // ── 批量视频卡片 → 条目 ──────────────────────────

    @Test
    fun `卡片按请求顺序整理，按 bvid 对应而不是 map 的键`() {
        val cards = mapOf(
            "k2" to PopularVideo(
                bvid = "BV1bbbbbbbbb", title = "乙", pic = "http://i0.hdslb.com/b.jpg", duration = 20,
                owner = PopularOwner(name = "UP乙"), stat = PopularStat(view = 5),
            ),
            "k1" to PopularVideo(bvid = "BV1aaaaaaaaa", title = "甲", duration = 10),
        )
        val items = resolveCardItems(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb"), cards)
        assertEquals(listOf("甲", "乙"), items.map { it.title })
        assertEquals("https://i0.hdslb.com/b.jpg", items[1].coverUrl)
        assertEquals("UP乙", items[1].author)
        assertEquals(20L, items[1].durationSec)
        assertEquals(5L, items[1].playCount)
    }

    @Test
    fun `取不到的卡片和 null 值都跳过`() {
        val cards = mapOf<String, PopularVideo?>(
            "BV1aaaaaaaaa" to PopularVideo(bvid = "BV1aaaaaaaaa", title = "甲"),
            "BV1ccccccccc" to null,
        )
        val items = resolveCardItems(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb", "BV1ccccccccc"), cards)
        assertEquals(listOf("甲"), items.map { it.title })
    }

    @Test
    fun `没有 bvid 的卡片当作不可用`() {
        val cards = mapOf<String, PopularVideo?>("x" to PopularVideo(bvid = "", title = "坏卡片"))
        assertEquals(emptyList<ChartItem>(), resolveCardItems(listOf("BV1aaaaaaaaa"), cards))
    }
}
