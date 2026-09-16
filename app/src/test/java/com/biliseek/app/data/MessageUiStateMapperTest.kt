package com.biliseek.app.data

import com.biliseek.app.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUiStateMapperTest {

    @Test
    fun `MessageHubState 默认四态为空`() {
        val s = AppViewModel.MessageHubState()
        assertEquals(0, s.reply.items.size)
        assertEquals(0, s.like.items.size)
        assertEquals(0, s.follower.items.size)
        assertEquals(0, s.sessions.items.size)
        assertNull(s.errorMessage)
    }

    @Test
    fun `PrivateChatState 默认无草稿无消息`() {
        val s = AppViewModel.PrivateChatState()
        assertEquals("", s.draft)
        assertEquals(0L, s.minSeqno)
        assertEquals(0L, s.maxSeqno)
        assertTrue(!s.isSending)
        assertTrue(!s.hasMore)
    }

    @Test
    fun `PrivateChatNav 携带 talker 与昵称`() {
        val nav = AppViewModel.PrivateChatNav(talkerId = 123L, peerName = "Alice", peerFace = "//face")
        assertEquals(123L, nav.talkerId)
        assertEquals("Alice", nav.peerName)
    }
}
