package com.biliseek.core.network

import com.biliseek.core.network.internal.resolveReplyRootParent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回复目标 root/parent 解析（B 站 x/v2/reply/add 语义）。 */
class ReplyRootParentTest {

    @Test
    fun `回复一级评论时 root 等于 parent`() {
        val (root, parent) = resolveReplyRootParent(rpid = 42, rootRpid = null)
        assertEquals(42L, root)
        assertEquals(42L, parent)
    }

    @Test
    fun `回复楼中楼时 root 是根评论 parent 是该楼中楼`() {
        val (root, parent) = resolveReplyRootParent(rpid = 101, rootRpid = 42)
        assertEquals(42L, root)
        assertEquals(101L, parent)
    }
}
