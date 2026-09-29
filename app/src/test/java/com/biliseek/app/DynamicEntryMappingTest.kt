package com.biliseek.app

import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicVideo
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.parseDurationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视频动态 → 搜索结果模型。
 *
 * 长按菜单的四个动作（加入列表 / 队列 / 下一首 / 下载）全部复用搜索结果那套机制，
 * 映射错了不会崩，只会让歌单、队列、下载里出现错的标题、UP 主或时长。
 */
class DynamicEntryMappingTest {

    private fun entry(video: DynamicVideo?) = DynamicEntry(
        idStr = "1000",
        authorName = "某 UP",
        authorFace = "https://i0.hdslb.com/face.jpg",
        pubTs = 1_700_000_000,
        descText = "动态正文",
        rawType = if (video != null) "DYNAMIC_TYPE_AV" else "DYNAMIC_TYPE_DRAW",
        video = video,
        coverUrl = video?.coverUrl.orEmpty(),
        authorMid = 42,
    )

    @Test
    fun `视频动态映射成搜索结果`() {
        val video = DynamicVideo(
            bvid = "BV1xx411c7mD",
            title = "某视频",
            coverUrl = "https://i0.hdslb.com/bfs/archive/cover.jpg",
            durationText = "03:27",
        )
        assertEquals(
            SearchVideo(
                bvid = "BV1xx411c7mD",
                title = "某视频",
                author = "某 UP",
                mid = 42,
                duration = "03:27",
                pic = "https://i0.hdslb.com/bfs/archive/cover.jpg",
            ),
            entry(video).toSearchVideo(),
        )
    }

    @Test
    fun `时长文本能被播放请求解析成秒`() {
        val mapped = entry(DynamicVideo("BV1", "t", "https://c", durationText = "03:27")).toSearchVideo()
        assertEquals(207L, parseDurationText(mapped!!.duration))
    }

    @Test
    fun `图文动态没有视频，返回 null`() {
        assertNull(entry(video = null).toSearchVideo())
    }
}
