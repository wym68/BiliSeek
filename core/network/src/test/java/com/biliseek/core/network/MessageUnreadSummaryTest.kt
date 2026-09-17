package com.biliseek.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageUnreadSummaryTest {

    @Test
    fun `replyAtBadge 合并 reply 和 at`() {
        val summary = BiliRepository.MessageUnreadSummary(reply = 3, at = 2, like = 5, sessionUnread = 1)
        assertEquals(5, summary.replyAtBadge)
    }

    @Test
    fun `total 是四项之和`() {
        val summary = BiliRepository.MessageUnreadSummary(reply = 3, at = 2, like = 5, sessionUnread = 1)
        assertEquals(11, summary.total)
    }

    @Test
    fun `默认全零`() {
        val summary = BiliRepository.MessageUnreadSummary()
        assertEquals(0, summary.total)
        assertEquals(0, summary.replyAtBadge)
    }
}
