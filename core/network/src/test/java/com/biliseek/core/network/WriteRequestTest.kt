package com.biliseek.core.network

import com.biliseek.core.network.dto.AddReplyData
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.internal.IdempotentCodes
import com.biliseek.core.network.internal.runWriteRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 写请求编排（[runWriteRequest]）的离线判据（工单 §5.1）。
 *
 * 协作者以 lambda 注入，无需 Context / MockWebServer——全部本地 mock，绝不联网（AGENTS.md）。
 */
class WriteRequestTest {

    private val jct = "myjct123"

    /** 记录每次 fire 收到的 body 字段。 */
    private class Recorder {
        val fires = mutableListOf<Map<String, String>>()
        val refererss = mutableListOf<String?>()
    }

    /** 用默认「就绪、无需刷新、不失效」的协作者跑一次；仅覆写需要的部分。 */
    private suspend fun <T> run(
        rec: Recorder = Recorder(),
        loggedIn: Boolean = true,
        initialJct: String = jct,
        refresh: suspend () -> RefreshOutcome = { RefreshOutcome.NotNeeded },
        currentJct: () -> String = { initialJct },
        onInvalidate: suspend () -> Unit = {},
        onLogOut: suspend () -> Unit = {},
        idempotentCodes: Set<Int> = emptySet(),
        referer: String? = null,
        respond: (call: Int, fields: Map<String, String>) -> BiliResponse<T>,
    ): T? {
        var call = 0
        return runWriteRequest(
            payload = mapOf("bvid" to "BV1"),
            idempotentCodes = idempotentCodes,
            referer = referer,
            loggedIn = loggedIn,
            initialJct = initialJct,
            awaitReady = {},
            refresh = refresh,
            invalidate = onInvalidate,
            logOut = onLogOut,
            currentJct = currentJct,
            block = { fields, r ->
                rec.fires += fields
                rec.refererss += r
                respond(call++, fields)
            },
        )
    }

    @Test
    fun `未登录直接 NeedLogin 且不发请求`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(rec = rec, initialJct = "") { _, _ -> BiliResponse(code = 0) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.NeedLogin, e.error)
        }
        assertTrue("未发出任何请求", rec.fires.isEmpty())
    }

    @Test
    fun `请求 body 必含 csrf 且等于 bili_jct`() = runBlocking {
        val rec = Recorder()
        run<Unit>(rec = rec) { _, _ -> BiliResponse(code = 0) }
        assertEquals(1, rec.fires.size)
        assertEquals(jct, rec.fires[0]["csrf"])
        // 原 payload 不被丢
        assertEquals("BV1", rec.fires[0]["bvid"])
    }

    @Test
    fun `负一一一刷新成功则用新 jct 恰重试一次`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(
                rec = rec,
                refresh = { RefreshOutcome.Refreshed },
                currentJct = { "newjct456" },
            ) { _, _ -> BiliResponse(code = -111) }  // 两次都 -111
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.CsrfInvalid, e.error)
        }
        assertEquals("恰重试一次", 2, rec.fires.size)
        assertEquals(jct, rec.fires[0]["csrf"])
        assertEquals("newjct456", rec.fires[1]["csrf"])
    }

    @Test
    fun `负一一一刷新成功后第二次成功则整体成功`() = runBlocking {
        val rec = Recorder()
        val out = run<Unit>(
            rec = rec,
            refresh = { RefreshOutcome.Refreshed },
            currentJct = { "newjct456" },
        ) { call, _ -> if (call == 0) BiliResponse(code = -111) else BiliResponse(code = 0) }
        assertEquals(2, rec.fires.size)
        assertNull(out)  // 写接口成功常返回空 data
    }

    @Test
    fun `负一一一但刷新未换新则不重试直接 CsrfInvalid`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(rec = rec, refresh = { RefreshOutcome.NotNeeded }) { _, _ ->
                BiliResponse(code = -111)
            }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.CsrfInvalid, e.error)
        }
        assertEquals("不重试", 1, rec.fires.size)
    }

    @Test
    fun `负三五二 Blocked 且只发一次`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(rec = rec) { _, _ -> BiliResponse(code = -352) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.Blocked, e.error)
        }
        assertEquals("风控绝不重试", 1, rec.fires.size)
    }

    @Test
    fun `负四一二触发 invalidate 且 CredentialsInvalid`() = runBlocking {
        var invalidated = false
        try {
            run<Unit>(onInvalidate = { invalidated = true }) { _, _ -> BiliResponse(code = -412) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.CredentialsInvalid, e.error)
        }
        assertTrue("应重置设备凭据", invalidated)
    }

    @Test
    fun `幂等码 65006 折叠成成功`() = runBlocking {
        val rec = Recorder()
        // 不抛异常即成功
        run<Unit>(rec = rec, idempotentCodes = IdempotentCodes.LIKE) { _, _ ->
            BiliResponse(code = 65006)
        }
        assertEquals(1, rec.fires.size)
    }

    @Test
    fun `发评论成功返回 rpid 数据`() = runBlocking {
        val data = run<AddReplyData> { _, _ ->
            BiliResponse(code = 0, data = AddReplyData(rpid = 987, rpidStr = "987"))
        }
        assertEquals(987L, data?.rpid)
    }

    @Test
    fun `其它错误码抛 Unknown 且带原始码`() = runBlocking {
        try {
            run<Unit> { _, _ -> BiliResponse(code = 12345, message = "boom") }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            val err = e.error
            assertTrue(err is WriteError.Unknown)
            assertEquals(12345, (err as WriteError.Unknown).code)
        }
    }

    @Test
    fun `referer 透传给底层请求`() = runBlocking {
        val rec = Recorder()
        run<Unit>(rec = rec, referer = "https://www.bilibili.com/video/BV1") { _, _ ->
            BiliResponse(code = 0)
        }
        assertEquals("https://www.bilibili.com/video/BV1", rec.refererss[0])
        assertFalse(rec.refererss.isEmpty())
    }

    @Test
    fun `负一零一续上后用新 jct 恰重试一次`() = runBlocking {
        val rec = Recorder()
        val out = run<Unit>(
            rec = rec,
            refresh = { RefreshOutcome.Refreshed },
            currentJct = { "newjct456" },
        ) { call, _ -> if (call == 0) BiliResponse(code = -101) else BiliResponse(code = 0) }
        assertEquals("恰重试一次", 2, rec.fires.size)
        assertEquals("newjct456", rec.fires[1]["csrf"])
        assertNull(out)
    }

    @Test
    fun `负一零一重打仍失败则 NeedLogin 且不再重试`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(rec = rec, refresh = { RefreshOutcome.Refreshed }) { _, _ -> BiliResponse(code = -101) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.NeedLogin, e.error)
        }
        assertEquals("绝不循环", 2, rec.fires.size)
    }

    @Test
    fun `负一零一且 passport 确认失效则登出并 NeedLogin`() = runBlocking {
        val rec = Recorder()
        var loggedOut = false
        try {
            run<Unit>(rec = rec, refresh = { RefreshOutcome.Expired }, onLogOut = { loggedOut = true }) { _, _ ->
                BiliResponse(code = -101)
            }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.NeedLogin, e.error)
            assertEquals("登录已失效，请重新登录", e.message)
        }
        assertTrue("应自动登出", loggedOut)
        assertEquals("不重打", 1, rec.fires.size)
    }

    @Test
    fun `负一零一刷新撞风控则 Blocked 且不重打`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(rec = rec, refresh = { RefreshOutcome.Blocked }) { _, _ -> BiliResponse(code = -101) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.Blocked, e.error)
        }
        assertEquals(1, rec.fires.size)
    }

    @Test
    fun `负一零一续期失败则不登出直接 NeedLogin`() = runBlocking {
        val rec = Recorder()
        var loggedOut = false
        try {
            run<Unit>(
                rec = rec,
                refresh = { RefreshOutcome.Failed("network") },
                onLogOut = { loggedOut = true },
            ) { _, _ -> BiliResponse(code = -101) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.NeedLogin, e.error)
        }
        assertFalse("网络失败不能登出", loggedOut)
        assertEquals(1, rec.fires.size)
    }

    @Test
    fun `负一一一与负一零一共用一次重打`() = runBlocking {
        val rec = Recorder()
        var refreshes = 0
        try {
            run<Unit>(
                rec = rec,
                refresh = {
                    refreshes++
                    RefreshOutcome.Refreshed
                },
                currentJct = { "newjct456" },
            ) { call, _ -> if (call == 0) BiliResponse(code = -111) else BiliResponse(code = -101) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.NeedLogin, e.error)
        }
        assertEquals("只刷新一次", 1, refreshes)
        assertEquals("总共只打两次", 2, rec.fires.size)
    }

    @Test
    fun `负一一一刷新撞风控则 Blocked`() = runBlocking {
        val rec = Recorder()
        try {
            run<Unit>(rec = rec, refresh = { RefreshOutcome.Blocked }) { _, _ -> BiliResponse(code = -111) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.Blocked, e.error)
        }
        assertEquals(1, rec.fires.size)
    }

    @Test
    fun `负三五二绝不触发刷新`() = runBlocking {
        var refreshed = false
        try {
            run<Unit>(refresh = {
                refreshed = true
                RefreshOutcome.Refreshed
            }) { _, _ -> BiliResponse(code = -352) }
            fail("应抛 WriteException")
        } catch (e: WriteException) {
            assertSame(WriteError.Blocked, e.error)
        }
        assertFalse("-352 绝不刷新", refreshed)
    }
}
