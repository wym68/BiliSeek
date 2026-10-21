package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.MsgfeedUnreadData
import com.biliseek.core.network.dto.SessionUnreadData
import com.biliseek.core.network.internal.resolveUnreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnreadPolicyTest {

    private val feedOk = BiliResponse(code = 0, data = MsgfeedUnreadData(at = 1, reply = 2, like = 3))
    private val chatOk = BiliResponse(code = 0, data = SessionUnreadData(unfollowUnread = 4, followUnread = 5))

    @Test
    fun `两路都成功才合成角标`() {
        val s = resolveUnreadSummary(feedOk, chatOk)!!
        assertEquals(BiliRepository.MessageUnreadSummary(reply = 2, at = 1, like = 3, sessionUnread = 9), s)
    }

    @Test
    fun `任一路抛异常返回 null 保留旧角标`() {
        assertNull(resolveUnreadSummary(null, chatOk))
        assertNull(resolveUnreadSummary(feedOk, null))
    }

    @Test
    fun `任一路错误码返回 null 而不是按 0 算`() {
        assertNull(resolveUnreadSummary(BiliResponse(code = -101), chatOk))
        assertNull(resolveUnreadSummary(feedOk, BiliResponse(code = -352)))
    }

    @Test
    fun `code 为 0 但没有 data 也算失败`() {
        assertNull(resolveUnreadSummary(BiliResponse(code = 0, data = null), chatOk))
    }
}
