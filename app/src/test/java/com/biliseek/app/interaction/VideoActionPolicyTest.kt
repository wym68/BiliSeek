package com.biliseek.app.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoActionPolicyTest {

    private val base = VideoInteractionState(bvid = "BV1", likeCount = 10, coinCount = 5, favoriteCount = 3)

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
}
