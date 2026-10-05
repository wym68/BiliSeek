package com.biliseek.app.remote

import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 远程列表的纯函数：删过条目后的收藏夹翻页、接页去重、撤销 / 提交失败时放回原位、移除用的 aid、
 * 加入稍后再看的提示。
 */
class RemoteListPolicyTest {

    // ── 收藏夹翻页 ───────────────────────────────

    @Test
    fun `没删过条目时就是按页码往下翻`() {
        assertEquals(2, favoriteFetchPage(loadedOnServer = 20, pageSize = 20))
        assertEquals(3, favoriteFetchPage(loadedOnServer = 40, pageSize = 20))
    }

    @Test
    fun `删过条目后从还没取全的那一页重取，不漏条`() {
        // 第 1 页 20 条删了 1 条：原来的第 21 条前移进了第 1 页，要重取第 1 页才拿得到
        assertEquals(1, favoriteFetchPage(loadedOnServer = 19, pageSize = 20))
        assertEquals(2, favoriteFetchPage(loadedOnServer = 39, pageSize = 20))
    }

    @Test
    fun `一条都没加载时取第 1 页`() {
        assertEquals(1, favoriteFetchPage(loadedOnServer = 0, pageSize = 20))
    }

    // ── 接上新一页 ───────────────────────────────

    @Test
    fun `接上新一页时去掉已在列表里的和正等着提交移除的`() {
        val merged = appendUnseen(
            existing = listOf("a", "b"),
            incoming = listOf("b", "c", "d", "e"),
            excludedKeys = setOf("d"),
            key = { it },
        )
        assertEquals(listOf("a", "b", "c", "e"), merged)
    }

    @Test
    fun `新一页全是已有的就原样返回`() {
        assertEquals(listOf("a"), appendUnseen(listOf("a"), listOf("a"), emptySet()) { it })
    }

    // ── 放回原位 ─────────────────────────────────

    @Test
    fun `撤销时放回原来的位置`() {
        assertEquals(listOf("a", "b", "c"), restoreAt(listOf("a", "c"), "b", index = 1) { it })
        assertEquals(listOf("b", "a", "c"), restoreAt(listOf("a", "c"), "b", index = 0) { it })
    }

    @Test
    fun `原位超出当前长度就放到末尾`() {
        assertEquals(listOf("a", "b"), restoreAt(listOf("a"), "b", index = 5) { it })
    }

    @Test
    fun `已经在列表里（比如刚刷新过）就不重复放`() {
        assertEquals(listOf("a", "b"), restoreAt(listOf("a", "b"), "b", index = 0) { it })
    }

    // ── 移除用的 aid ─────────────────────────────

    @Test
    fun `有 aid 直接用`() {
        assertEquals(170001L, resolveAid(aid = 170001, bvid = ""))
    }

    @Test
    fun `没有 aid 时用 bvid 本地换算`() {
        assertEquals(170001L, resolveAid(aid = 0, bvid = "BV17x411w7KC"))
    }

    @Test
    fun `都没有或 bvid 不合法时返回 null，由调用方按失败处理`() {
        assertNull(resolveAid(aid = 0, bvid = ""))
        assertNull(resolveAid(aid = -1, bvid = "not-a-bvid"))
    }

    // ── 加入稍后再看的提示 ─────────────────────────

    private fun serverError(code: Int, message: String) =
        WriteException(WriteError.Unknown(code, message), "操作失败：code=$code $message")

    @Test
    fun `全部加入成功`() {
        assertEquals("已加入 3 项到稍后再看", watchLaterAddMessage(ok = 3, failed = 0, firstError = null))
    }

    @Test
    fun `全部失败时说原因 列表满了说人话`() {
        assertEquals(
            "加入稍后再看失败：稍后再看已满",
            watchLaterAddMessage(ok = 0, failed = 2, firstError = serverError(90001, "列表已满")),
        )
    }

    @Test
    fun `部分失败时说成功几项 失败几项和原因`() {
        assertEquals(
            "已加入 2 项，1 项失败：视频已失效",
            watchLaterAddMessage(ok = 2, failed = 1, firstError = serverError(90003, "稿件已被删除")),
        )
    }

    @Test
    fun `其他失败照常用异常自带的说明`() {
        assertEquals(
            "触发风控(-352)，已进入退避",
            watchLaterFailureReason(WriteException(WriteError.Blocked, "触发风控(-352)，已进入退避")),
        )
        assertEquals("找不到视频编号", watchLaterFailureReason(IllegalStateException("找不到视频编号")))
        assertEquals("未知错误", watchLaterFailureReason(null))
    }
}
