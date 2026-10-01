package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.internal.runWithLoginRefresh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读请求 -101 编排（[runWithLoginRefresh]）的离线判据：只认 -101、只重打一次、
 * 登出 / 冷却只在对应结果时发生。协作者全部本地 mock，绝不联网（AGENTS.md）。
 */
class LoginRefreshTest {

    private val session = BiliSession("sess-old", "jct", "1", "rt")

    private class Probe {
        var fires = 0
        val refreshedWith = mutableListOf<BiliSession>()
        var loggedOut = false
        var cooledDown = false
    }

    private suspend fun run(
        probe: Probe,
        outcome: RefreshOutcome = RefreshOutcome.NotNeeded,
        currentSession: () -> BiliSession = { session },
        respond: (call: Int) -> BiliResponse<String>,
    ): BiliResponse<String> = runWithLoginRefresh(
        currentSession = currentSession,
        refresh = { rejected ->
            probe.refreshedWith += rejected
            outcome
        },
        logOut = { probe.loggedOut = true },
        coolDown = { probe.cooledDown = true },
        fire = { respond(probe.fires++) },
    )

    @Test
    fun `成功只打一次且不刷新`() = runBlocking {
        val probe = Probe()
        val resp = run(probe) { BiliResponse(code = 0, data = "ok") }
        assertEquals(1, probe.fires)
        assertTrue(probe.refreshedWith.isEmpty())
        assertEquals("ok", resp.data)
    }

    @Test
    fun `负三五二绝不触发刷新`() = runBlocking {
        val probe = Probe()
        val resp = run(probe, outcome = RefreshOutcome.Refreshed) { BiliResponse(code = -352) }
        assertEquals("风控绝不重打", 1, probe.fires)
        assertTrue("风控绝不刷新", probe.refreshedWith.isEmpty())
        assertEquals(-352, resp.code)
    }

    @Test
    fun `其他错误码也不触发刷新`() = runBlocking {
        listOf(-412, -111, -404).forEach { code ->
            val probe = Probe()
            run(probe, outcome = RefreshOutcome.Refreshed) { BiliResponse(code = code) }
            assertEquals("$code", 1, probe.fires)
            assertTrue("$code", probe.refreshedWith.isEmpty())
        }
    }

    @Test
    fun `负一零一续上后恰重打一次并返回第二次结果`() = runBlocking {
        val probe = Probe()
        val resp = run(probe, outcome = RefreshOutcome.Refreshed) { call ->
            if (call == 0) BiliResponse(code = -101) else BiliResponse(code = 0, data = "second")
        }
        assertEquals(2, probe.fires)
        assertEquals("second", resp.data)
    }

    @Test
    fun `重打又回负一零一也不再重打`() = runBlocking {
        val probe = Probe()
        val resp = run(probe, outcome = RefreshOutcome.Refreshed) { BiliResponse(code = -101) }
        assertEquals("绝不循环", 2, probe.fires)
        assertEquals("只刷新一次", 1, probe.refreshedWith.size)
        assertEquals(-101, resp.code)
        assertFalse("刚续上的登录态被拒不等于失效，不登出", probe.loggedOut)
    }

    @Test
    fun `passport 确认失效则登出且不重打`() = runBlocking {
        val probe = Probe()
        val resp = run(probe, outcome = RefreshOutcome.Expired) { BiliResponse(code = -101) }
        assertTrue(probe.loggedOut)
        assertEquals(1, probe.fires)
        assertEquals(-101, resp.code)
    }

    @Test
    fun `刷新撞风控则进冷却且不重打`() = runBlocking {
        val probe = Probe()
        run(probe, outcome = RefreshOutcome.Blocked) { BiliResponse(code = -101) }
        assertTrue(probe.cooledDown)
        assertFalse(probe.loggedOut)
        assertEquals(1, probe.fires)
    }

    @Test
    fun `没续上也不能断定失效时只打一次且无副作用`() = runBlocking {
        listOf(
            RefreshOutcome.NotNeeded,
            RefreshOutcome.NotLoggedIn,
            RefreshOutcome.NotConfigured,
            RefreshOutcome.Failed("network"),
        ).forEach { outcome ->
            val probe = Probe()
            run(probe, outcome = outcome) { BiliResponse(code = -101) }
            assertEquals("$outcome", 1, probe.fires)
            assertFalse("$outcome 不能登出", probe.loggedOut)
            assertFalse("$outcome 不进冷却", probe.cooledDown)
        }
    }

    @Test
    fun `交给刷新的是发请求前的登录态`() = runBlocking {
        val probe = Probe()
        var current = session
        run(probe, currentSession = { current }) {
            // 请求过程中登录态被别处换掉
            current = session.copy(sessData = "sess-new")
            BiliResponse(code = -101)
        }
        assertSame(session, probe.refreshedWith.single())
    }
}
