package com.biliseek.app.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentDraftPolicyTest {

    private val base = CommentThreadState(oid = 1, type = 1)
    private val target = CommentReplyTarget(root = 10, parent = 11, uname = "小明")

    @Test
    fun `有内容就连回复对象一起留`() {
        assertEquals(CommentDraft("没写完", target), draftOnDismiss(base.copy(content = "没写完", replyTarget = target)))
        assertEquals(CommentDraft("一级评论", null), draftOnDismiss(base.copy(content = "一级评论")))
    }

    @Test
    fun `内容为空或只有空白就不留，只点了回复也不算草稿`() {
        assertNull(draftOnDismiss(base))
        assertNull(draftOnDismiss(base.copy(content = "  \n")))
        assertNull(draftOnDismiss(base.copy(replyTarget = target)))
    }
}
