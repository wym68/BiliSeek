package com.biliseek.app.collection

import com.biliseek.app.data.PlayHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeProgressPolicyTest {

    private fun h(positionMs: Long, durationMs: Long, playedAt: Long = 0) = EpisodeHistory(positionMs, durationMs, playedAt)

    private fun row(bvid: String, cid: Long, positionMs: Long, durationSec: Long, playedAt: Long) = PlayHistoryEntity(
        bvid = bvid, cid = cid, title = "", author = "", coverUrl = "",
        durationSec = durationSec, positionMs = positionMs, playedAt = playedAt,
    )

    @Test
    fun `三档判定与边界 同续播口径`() {
        assertEquals(EpisodeProgress.None, EpisodeProgressPolicy.progressOf(null))
        assertEquals(EpisodeProgress.None, EpisodeProgressPolicy.progressOf(h(5_000, 600_000)))
        assertEquals(EpisodeProgress.Partial(5_001, 600_000), EpisodeProgressPolicy.progressOf(h(5_001, 600_000)))
        assertEquals(EpisodeProgress.Partial(584_999, 600_000), EpisodeProgressPolicy.progressOf(h(584_999, 600_000)))
        assertEquals(EpisodeProgress.Finished, EpisodeProgressPolicy.progressOf(h(585_000, 600_000)))
        // 时长未知：只看是不是听过 5 秒
        assertEquals(EpisodeProgress.Partial(900_000, 0), EpisodeProgressPolicy.progressOf(h(900_000, 0)))
        // 不到 5 秒先算没听：起播时就写了一条位置 0 的记录，20 秒以内的短视频不能一点开就算听完
        assertEquals(EpisodeProgress.None, EpisodeProgressPolicy.progressOf(h(0, 10_000)))
        assertEquals(EpisodeProgress.Finished, EpisodeProgressPolicy.progressOf(h(6_000, 10_000)))
    }

    @Test
    fun `同一视频多条记录取最近一条`() {
        val map = EpisodeProgressPolicy.latestByBvid(
            listOf(
                row("BV1", cid = 1, positionMs = 100_000, durationSec = 600, playedAt = 10),
                row("BV1", cid = 2, positionMs = 30_000, durationSec = 300, playedAt = 20),
                row("BV2", cid = 3, positionMs = 1_000, durationSec = 60, playedAt = 5),
            ),
        )
        assertEquals(EpisodeHistory(30_000, 300_000, 20), map["BV1"])
        assertEquals(EpisodeHistory(1_000, 60_000, 5), map["BV2"])
    }

    @Test
    fun `状态文字`() {
        assertEquals("✓ 已听完 · 39:48", EpisodeProgressPolicy.statusLine(EpisodeProgress.Finished, 2388))
        assertEquals("听到 12:30 / 41:37", EpisodeProgressPolicy.statusLine(EpisodeProgress.Partial(750_000, 2_497_000), 2497))
        // 记录里没时长时用列表给的时长
        assertEquals("听到 12:30 / 41:37", EpisodeProgressPolicy.statusLine(EpisodeProgress.Partial(750_000, 0), 2497))
        assertNull(EpisodeProgressPolicy.statusLine(EpisodeProgress.None, 2497))
    }

    @Test
    fun `继续听 取最近听过的那一集 已听完就接下一集`() {
        val bvids = listOf("BV1", "BV2", "BV3")
        // 最近听的是 BV2、听到一半
        assertEquals(1, EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV1" to h(600_000, 600_000, 1), "BV2" to h(60_000, 600_000, 2))))
        // 最近听的是 BV2、已听完 → BV3
        assertEquals(2, EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV2" to h(600_000, 600_000, 2))))
        // 最后一集已听完 → 不显示
        assertNull(EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV3" to h(600_000, 600_000, 3))))
        // 没有记录 → 不显示
        assertNull(EpisodeProgressPolicy.resumeIndex(bvids, emptyMap()))
        // 不在这一页里的记录不算
        assertNull(EpisodeProgressPolicy.resumeIndex(bvids, mapOf("BV9" to h(60_000, 600_000, 9))))
    }

    @Test
    fun `第几集 正序加一 倒序用总数减 总数未知不写`() {
        assertEquals(5, EpisodeProgressPolicy.episodeNumber(index = 4, total = 36, reversed = false))
        assertEquals(32, EpisodeProgressPolicy.episodeNumber(index = 4, total = 36, reversed = true))
        assertNull(EpisodeProgressPolicy.episodeNumber(index = 4, total = 0, reversed = true))
    }

    @Test
    fun `横条文字`() {
        assertEquals("继续听 · 第 5 集 民国 05", EpisodeProgressPolicy.resumeTitle(5, "民国 05"))
        assertEquals("继续听 · 民国 05", EpisodeProgressPolicy.resumeTitle(null, "民国 05"))
        assertEquals("听到 12:30 / 41:37", EpisodeProgressPolicy.resumeDetail(EpisodeProgress.Partial(750_000, 2_497_000), 2497))
        assertEquals("从头开始 · 41:37", EpisodeProgressPolicy.resumeDetail(EpisodeProgress.None, 2497))
        assertEquals("从头开始 · 41:37", EpisodeProgressPolicy.resumeDetail(EpisodeProgress.Finished, 2497))
    }
}
