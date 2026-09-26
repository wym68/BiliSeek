package com.biliseek.app.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUiStateMapperTest {

    @Test
    fun `PrivateChatState 默认无草稿无消息`() {
        val s = PrivateChatState()
        assertEquals("", s.draft)
        assertEquals(0L, s.minSeqno)
        assertEquals(0L, s.maxSeqno)
        assertTrue(!s.isSending)
        assertTrue(!s.hasMore)
    }

    @Test
    fun `PrivateChatNav 携带 talker 与昵称`() {
        val nav = PrivateChatNav(talkerId = 123L, peerName = "Alice", peerFace = "//face")
        assertEquals(123L, nav.talkerId)
        assertEquals("Alice", nav.peerName)
    }
}
