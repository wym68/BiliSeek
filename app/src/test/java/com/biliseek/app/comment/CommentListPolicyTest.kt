package com.biliseek.app.comment

import com.biliseek.core.network.dto.ReplyItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentListPolicyTest {

    private fun r(id: Long) = ReplyItem(rpid = id)

    @Test
    fun `翻页合并丢掉重复项和置顶里已有的`() {
        val merged = mergeCommentPage(
            existing = listOf(r(2), r(3)),
            incoming = listOf(r(3), r(1), r(4)),
            pinned = listOf(r(1)),
        )
        assertEquals(listOf(2L, 3L, 4L), merged.map { it.rpid })
    }

    @Test
    fun `第一页也要去掉置顶那条`() {
        val merged = mergeCommentPage(existing = emptyList(), incoming = listOf(r(1), r(5)), pinned = listOf(r(1)))
        assertEquals(listOf(5L), merged.map { it.rpid })
    }
}
