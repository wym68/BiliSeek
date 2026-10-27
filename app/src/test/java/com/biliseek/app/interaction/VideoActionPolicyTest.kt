package com.biliseek.app.interaction

import com.biliseek.app.collection.CollectionEpisode
import com.biliseek.app.collection.CollectionSection
import com.biliseek.app.collection.VideoCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoActionPolicyTest {

    private val base = VideoInteractionState(bvid = "BV1", likeCount = 10, coinCount = 5, favoriteCount = 3)

    @Test
    fun `同一视频已拉到就不重拉 上次失败了再进播放页重拉`() {
        assertTrue(VideoActionPolicy.shouldLoad(null, "BV1"))
        assertTrue(VideoActionPolicy.shouldLoad(base, "BV2"))
        assertFalse(VideoActionPolicy.shouldLoad(base, "BV1"))
        assertFalse(VideoActionPolicy.shouldLoad(base.copy(isLoading = true), "BV1"))
        // 没网时拉失败落了全 0：网回来后再进播放页要重拉，不能一直空着
        assertTrue(VideoActionPolicy.shouldLoad(base.copy(loadFailed = true), "BV1"))
    }

    @Test
    fun `投币上限_自制2转载1`() {
        assertEquals(2, VideoActionPolicy.maxCoins(1))
        assertEquals(1, VideoActionPolicy.maxCoins(2))
        assertEquals(2, VideoActionPolicy.maxCoins(0))
    }

    @Test
    fun `可选枚数随已投减少`() {
        assertEquals(listOf(1, 2), VideoActionPolicy.coinChoices(base))
        assertEquals(listOf(1), VideoActionPolicy.coinChoices(base.copy(coined = 1)))
        assertEquals(emptyList<Int>(), VideoActionPolicy.coinChoices(base.copy(coined = 2)))
        assertEquals(emptyList<Int>(), VideoActionPolicy.coinChoices(base.copy(maxCoins = 1, coined = 1)))
    }

    @Test
    fun `点赞与取消_计数不为负_重复无副作用`() {
        val liked = VideoActionPolicy.applyLike(base, true)
        assertTrue(liked.liked); assertEquals(11, liked.likeCount)
        assertSame(liked, VideoActionPolicy.applyLike(liked, true))
        val zero = VideoActionPolicy.applyLike(base.copy(likeCount = 0, liked = true), false)
        assertEquals(0, zero.likeCount)
    }

    @Test
    fun `投币夹紧到剩余额度并可同时点赞`() {
        val s = VideoActionPolicy.applyCoin(base.copy(coined = 1), 2, alsoLike = true)
        assertEquals(2, s.coined); assertEquals(6, s.coinCount)
        assertTrue(s.liked); assertEquals(11, s.likeCount)
    }

    @Test
    fun `已投满时投币不变`() {
        val full = base.copy(coined = 2)
        assertSame(full, VideoActionPolicy.applyCoin(full, 1, alsoLike = true))
    }

    @Test
    fun `三连只补未完成的部分`() {
        val s = VideoActionPolicy.applyTriple(base.copy(liked = true, coined = 1))
        assertEquals(10, s.likeCount)
        assertEquals(2, s.coined); assertEquals(6, s.coinCount)
        assertTrue(s.favored); assertEquals(4, s.favoriteCount)
        assertTrue(VideoActionPolicy.isTripleDone(s))
        assertFalse(VideoActionPolicy.isTripleDone(base))
    }

    @Test
    fun `收藏标记只加一次`() {
        val once = VideoActionPolicy.applyFavored(base)
        assertEquals(4, once.favoriteCount)
        assertSame(once, VideoActionPolicy.applyFavored(once))
    }

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
}
