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

    @Test
    fun `点正在播的那集不重新播 点别的集起播 越界不播`() {
        val c = CollectionPolicy.fromView(view("BV2", UgcSection(episodes = listOf(ep("BV1"), ep("BV2")))))!!
        assertFalse(CollectionPolicy.shouldPlayEpisode(c, 1, playingBvid = "BV2"))
        assertTrue(CollectionPolicy.shouldPlayEpisode(c, 0, playingBvid = "BV2"))
        assertFalse(CollectionPolicy.shouldPlayEpisode(c, 5, playingBvid = "BV2"))
    }

    @Test
    fun `放进列表的提示 替换掉两项以上时由撤销提示代替`() {
        assertEquals("已把「合集甲」放进播放列表", CollectionPolicy.adoptMessage("合集甲", lost = 0))
        assertEquals("已把「合集甲」放进播放列表", CollectionPolicy.adoptMessage("合集甲", lost = 1))
        assertNull(CollectionPolicy.adoptMessage("合集甲", lost = 2))
    }

    @Test
    fun `合集封面 没有时用第一集的`() {
        val noCover = CollectionPolicy.fromView(view("BV1", UgcSection(episodes = listOf(ep("BV1"), ep("BV2")))))!!
        assertEquals("//i0/BV1.jpg", noCover.cover)

        val withCover = CollectionPolicy.fromView(
            VideoViewInfo(
                bvid = "BV1",
                ugcSeason = UgcSeason(
                    id = 100,
                    title = "合集甲",
                    cover = "//i0/season.jpg",
                    sections = listOf(UgcSection(episodes = listOf(ep("BV1")))),
                ),
            ),
        )!!
        assertEquals("//i0/season.jpg", withCover.cover)
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

    @Test
    fun `UP 空间合集名用 title 不带合集前缀 没有 title 时退回 name`() {
        // 真实的 seasons_series_list：meta.name 是「合集·…」，meta.title 才是干净的名字（2026-10-27 真机日志）
        val page = SpaceCollectionsPage(
            seasons = listOf(
                SpaceSeasonMeta(seasonId = 1, name = "合集·女工100天双线毕业开荒流程", title = "女工100天双线毕业开荒流程"),
                SpaceSeasonMeta(seasonId = 2, name = "合集乙"),
            ),
            series = emptyList(),
            page = 1,
            hasMore = false,
        )
        assertEquals(
            listOf("女工100天双线毕业开荒流程", "合集乙"),
            CollectionPolicy.spaceCollections(page, mid = 9, upName = "UP甲").map { it.name },
        )
    }

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
}
