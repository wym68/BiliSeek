package com.biliseek.app.upspace

import com.biliseek.core.network.BiliSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpSpacePolicyTest {

    private fun session(uid: String, sessData: String = "sess") =
        BiliSession(sessData = sessData, biliJct = "jct", dedeUserId = uid, refreshToken = "")

    @Test
    fun `登录账号的 mid 与 UP 相同就是本人`() {
        assertTrue(UpSpacePolicy.isSelf(12345, session("12345")))
    }

    @Test
    fun `别人的空间不是本人`() {
        assertFalse(UpSpacePolicy.isSelf(67890, session("12345")))
    }

    @Test
    fun `未登录一律不算本人`() {
        assertFalse(UpSpacePolicy.isSelf(12345, BiliSession.EMPTY))
        // 本地残留 uid 但没有 SESSDATA，也按未登录处理
        assertFalse(UpSpacePolicy.isSelf(12345, session("12345", sessData = "")))
    }

    @Test
    fun `mid 无效时不算本人`() {
        assertFalse(UpSpacePolicy.isSelf(0, session("0")))
        assertFalse(UpSpacePolicy.isSelf(12345, session("")))
        assertFalse(UpSpacePolicy.isSelf(12345, session("abc")))
    }
}
