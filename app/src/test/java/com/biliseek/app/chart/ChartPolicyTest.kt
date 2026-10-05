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
