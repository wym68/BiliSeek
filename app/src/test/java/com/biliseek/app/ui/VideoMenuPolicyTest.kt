package com.biliseek.app.ui

import com.biliseek.app.FavoriteTrack
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.TrackEntity
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.VideoOwner
import com.biliseek.core.network.dto.VideoStat
import com.biliseek.core.network.dto.VideoViewInfo
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.dto.WatchLaterOwner
import com.biliseek.core.network.parseDurationText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视频条目三点菜单：菜单项与顺序（与搜索结果一致）、没有 UP 编号时少「查看UP主」，
 * 以及四种条目映射成搜索结果模型后的字段与时长文本（`startSingleAction` 靠 `parseDurationText` 还原秒数）。
 */
class VideoMenuPolicyTest {

    // ── 菜单项 ───────────────────────────────────

    @Test
    fun `有 UP 编号时七项齐全，顺序和文案与搜索结果的三点菜单一致`() {
        assertEquals(
            listOf("加入列表", "加入队列", "下一首播放", "查看UP主", "复制链接", "下载", "AI 总结"),
            videoMenuActions(authorMid = 123).map { it.label },
        )
    }

    @Test
    fun `没有 UP 编号时去掉查看UP主，其余顺序不变`() {
        val expected = listOf("加入列表", "加入队列", "下一首播放", "复制链接", "下载", "AI 总结")
        assertEquals(expected, videoMenuActions(authorMid = 0).map { it.label })
        assertEquals(expected, videoMenuActions(authorMid = -1).map { it.label })
    }

    @Test
    fun `能从当前列表移除时末尾多一项移除，前面不变`() {
        assertEquals(
            listOf("加入列表", "加入队列", "下一首播放", "查看UP主", "复制链接", "下载", "AI 总结", "移除"),
            videoMenuActions(authorMid = 123, removable = true).map { it.label },
        )
        assertEquals(
            listOf("加入列表", "加入队列", "下一首播放", "复制链接", "下载", "AI 总结", "移除"),
            videoMenuActions(authorMid = 0, removable = true).map { it.label },
        )
    }

    @Test
    fun `已下载的条目不再有下载，其余顺序不变`() {
        assertEquals(
            listOf("加入列表", "加入队列", "下一首播放", "复制链接", "AI 总结", "移除"),
            videoMenuActions(authorMid = 0, removable = true, downloadable = false).map { it.label },
        )
    }

    // ── 条目 → 搜索结果模型 ──────────────────────

    @Test
    fun `本地列表条目映射：没有 UP 编号，所以菜单里没有查看UP主`() {
        val video = PlaylistTrack(
            TrackEntity(bvid = "BV9", cid = 3, title = "歌", author = "UP乙", coverUrl = "c.jpg", durationSec = 125),
            sortKey = 1.0,
        ).toSearchVideo()
        assertEquals("BV9", video.bvid)
        assertEquals("歌", video.title)
        assertEquals("UP乙", video.author)
        assertEquals(0L, video.mid)
        assertEquals("c.jpg", video.pic)
        assertEquals(125L, parseDurationText(video.duration))
        assertEquals(false, videoMenuActions(video.mid).contains(VideoMenuAction.ViewUp))
    }

    @Test
    fun `榜单条目映射，UP 编号、封面和时长文本带过去`() {
        val video = ChartItem(
            bvid = "BV1", title = "标题", coverUrl = "https://i0.hdslb.com/a.jpg", author = "UP甲",
            durationSec = 231, playCount = 9876, rank = 3, authorMid = 77,
        ).toSearchVideo()
        assertEquals("BV1", video.bvid)
        assertEquals("标题", video.title)
        assertEquals("UP甲", video.author)
        assertEquals(77L, video.mid)
        assertEquals("https://i0.hdslb.com/a.jpg", video.pic)
        assertEquals(231L, parseDurationText(video.duration))
    }

    @Test
    fun `热歌榜的歌没有 UP 编号也没有时长，映射后都是 0`() {
        val video = ChartItem(
            bvid = "BV2", title = "歌", coverUrl = "", author = "歌手",
            durationSec = 0, playCount = null, rank = 1,
        ).toSearchVideo()
        assertEquals(0L, video.mid)
        assertEquals(0L, parseDurationText(video.duration))
    }

    @Test
    fun `时长文本能往返，超过一小时也行`() {
        for (sec in listOf(0L, 59L, 60L, 231L, 3599L, 3600L, 3723L)) {
            val video = FavoriteTrack(
                bvid = "BV3", title = "t", coverUrl = "", durationSec = sec, author = "a",
            ).toSearchVideo()
            assertEquals("sec=$sec", sec, parseDurationText(video.duration))
        }
    }

    @Test
    fun `收藏夹条目映射`() {
        val video = FavoriteTrack(
            bvid = "BV4", title = "收藏的", coverUrl = "https://c/4.jpg", durationSec = 120,
            author = "UP乙", authorMid = 88,
        ).toSearchVideo()
        assertEquals("BV4", video.bvid)
        assertEquals("收藏的", video.title)
        assertEquals("UP乙", video.author)
        assertEquals(88L, video.mid)
        assertEquals("https://c/4.jpg", video.pic)
    }

    @Test
    fun `稍后再看条目映射，没有 owner 时作者为空、编号为 0`() {
        val withOwner = WatchLaterItem(
            bvid = "BV5", title = "稍后", pic = "//i0.hdslb.com/5.jpg", duration = 90,
            owner = WatchLaterOwner(mid = 99, name = "UP丙"),
        ).toSearchVideo()
        assertEquals("UP丙", withOwner.author)
        assertEquals(99L, withOwner.mid)
        assertEquals("//i0.hdslb.com/5.jpg", withOwner.pic)
        assertEquals(90L, parseDurationText(withOwner.duration))

        val noOwner = WatchLaterItem(bvid = "BV6", title = "无主").toSearchVideo()
        assertEquals("", noOwner.author)
        assertEquals(0L, noOwner.mid)
    }

    @Test
    fun `合集条目映射`() {
        val video = SeriesArchive(
            bvid = "BV7", cid = 1, title = "合集里的", cover = "https://c/7.jpg", duration = 300,
            author = "UP丁", authorMid = 66,
        ).toSearchVideo()
        assertEquals("BV7", video.bvid)
        assertEquals("UP丁", video.author)
        assertEquals(66L, video.mid)
        assertEquals("https://c/7.jpg", video.pic)
        assertEquals(300L, parseDurationText(video.duration))
    }

    @Test
    fun `搜 BV 号时把视频详情映射成一条搜索结果`() {
        val video = VideoViewInfo(
            bvid = "BV1u1a662Ewa", aid = 3, title = "极度真实的晚明生活实录", pic = "http://i0.hdslb.com/8.jpg",
            owner = VideoOwner(mid = 77, name = "UP戊"), duration = 1786, pubdate = 1_700_000_000,
            stat = VideoStat(view = 12_345, danmaku = 67),
        ).toSearchVideo()
        assertEquals("BV1u1a662Ewa", video.bvid)
        assertEquals(3L, video.aid)
        assertEquals("极度真实的晚明生活实录", video.title)
        assertEquals("UP戊", video.author)
        assertEquals(77L, video.mid)
        assertEquals("http://i0.hdslb.com/8.jpg", video.pic)
        assertEquals(1786L, parseDurationText(video.duration))
        assertEquals(12_345L, video.play)
        assertEquals(67L, video.danmaku)
        assertEquals(1_700_000_000L, video.pubdate)
    }

    @Test
    fun `已下载条目映射 没有 UP 编号`() {
        val video = com.biliseek.app.download.DownloadTask(
            id = 1, bvid = "BV9", cid = 3, title = "下载的", author = "UP己", coverUrl = "https://c/9.jpg",
            content = "AUDIO", location = "PRIVATE", status = "DONE", progress = 1f, error = "", fileSize = 1,
            durationSec = 200,
        ).toSearchVideo()
        assertEquals("BV9", video.bvid)
        assertEquals("下载的", video.title)
        assertEquals("UP己", video.author)
        assertEquals(0L, video.mid)
        assertEquals("https://c/9.jpg", video.pic)
        assertEquals(200L, parseDurationText(video.duration))
    }
}
