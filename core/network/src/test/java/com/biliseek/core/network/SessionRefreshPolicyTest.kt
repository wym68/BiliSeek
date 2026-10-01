package com.biliseek.core.network

import com.biliseek.core.network.internal.REFRESH_MEMO_MS
import com.biliseek.core.network.internal.RefreshDecision
import com.biliseek.core.network.internal.RefreshFollowUp
import com.biliseek.core.network.internal.RefreshMemo
import com.biliseek.core.network.internal.resolvePassportException
import com.biliseek.core.network.internal.resolvePassportFailure
import com.biliseek.core.network.internal.resolvePassportHttpFailure
import com.biliseek.core.network.internal.resolveRefreshDecision
import com.biliseek.core.network.internal.resolveRefreshFollowUp
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 登录态续期决策（[resolveRefreshDecision] 等）的离线判据。纯函数，绝不联网（AGENTS.md）。
 */
class SessionRefreshPolicyTest {

    private val now = 1_000_000_000L

    // ── 拿到刷新锁之后：要不要真去问 passport ──

    @Test
    fun `本地未登录不问 passport`() {
        assertEquals(
            RefreshDecision.Reuse(RefreshOutcome.NotLoggedIn),
            resolveRefreshDecision(rejectedSessData = "old", currentSessData = "", memo = null, nowMs = now),
        )
    }

    @Test
    fun `等锁期间登录态已换则直接当已续上`() {
        assertEquals(
            RefreshDecision.Reuse(RefreshOutcome.Refreshed),
            resolveRefreshDecision(rejectedSessData = "old", currentSessData = "new", memo = null, nowMs = now),
        )
    }

    @Test
    fun `未登录时发出的请求在登录后被拒也直接重打`() {
        assertEquals(
            RefreshDecision.Reuse(RefreshOutcome.Refreshed),
            resolveRefreshDecision(rejectedSessData = "", currentSessData = "new", memo = null, nowMs = now),
        )
    }

    @Test
    fun `同一登录态十分钟内复用上次结果`() {
        val memo = RefreshMemo("s", RefreshOutcome.NotNeeded, atMs = now - REFRESH_MEMO_MS + 1)
        assertEquals(
            RefreshDecision.Reuse(RefreshOutcome.NotNeeded),
            resolveRefreshDecision(rejectedSessData = "s", currentSessData = "s", memo = memo, nowMs = now),
        )
    }

    @Test
    fun `记忆过期则重新问`() {
        val memo = RefreshMemo("s", RefreshOutcome.NotNeeded, atMs = now - REFRESH_MEMO_MS)
        assertSame(
            RefreshDecision.Ask,
            resolveRefreshDecision(rejectedSessData = "s", currentSessData = "s", memo = memo, nowMs = now),
        )
    }

    @Test
    fun `别的登录态的记忆不复用`() {
        val memo = RefreshMemo("other", RefreshOutcome.Expired, atMs = now)
        assertSame(
            RefreshDecision.Ask,
            resolveRefreshDecision(rejectedSessData = "s", currentSessData = "s", memo = memo, nowMs = now),
        )
    }

    @Test
    fun `启动检查没有被拒的登录态也走记忆`() {
        val memo = RefreshMemo("s", RefreshOutcome.Expired, atMs = now)
        assertEquals(
            RefreshDecision.Reuse(RefreshOutcome.Expired),
            resolveRefreshDecision(rejectedSessData = null, currentSessData = "s", memo = memo, nowMs = now),
        )
        assertSame(
            RefreshDecision.Ask,
            resolveRefreshDecision(rejectedSessData = null, currentSessData = "s", memo = null, nowMs = now),
        )
    }

    // ── 刷新之后的处置 ──

    @Test
    fun `刷新结果到处置的映射`() {
        assertEquals(RefreshFollowUp.RETRY_ONCE, resolveRefreshFollowUp(RefreshOutcome.Refreshed))
        assertEquals(RefreshFollowUp.LOG_OUT, resolveRefreshFollowUp(RefreshOutcome.Expired))
        assertEquals(RefreshFollowUp.COOL_DOWN, resolveRefreshFollowUp(RefreshOutcome.Blocked))
        listOf(
            RefreshOutcome.NotLoggedIn,
            RefreshOutcome.NotNeeded,
            RefreshOutcome.NotConfigured,
            RefreshOutcome.Failed("x"),
        ).forEach { assertEquals("$it", RefreshFollowUp.GIVE_UP, resolveRefreshFollowUp(it)) }
    }

    // ── passport 返回码 / HTTP 失败 / 异常 → 刷新结果 ──

    @Test
    fun `passport 返回码到刷新结果`() {
        assertSame(RefreshOutcome.Blocked, resolvePassportFailure("cookie/info", -352, "风控"))
        assertSame(RefreshOutcome.Expired, resolvePassportFailure("cookie/info", -101, "账号未登录"))
        assertEquals(
            RefreshOutcome.Failed("cookie/refresh code=86095 refresh_csrf 错误"),
            resolvePassportFailure("cookie/refresh", 86095, "refresh_csrf 错误"),
        )
    }

    @Test
    fun `passport HTTP 412 当风控`() {
        assertSame(RefreshOutcome.Blocked, resolvePassportHttpFailure("correspond", 412))
        assertEquals(RefreshOutcome.Failed("correspond HTTP 500"), resolvePassportHttpFailure("correspond", 500))
    }

    @Test
    fun `刷新流程抛出的异常折成结果`() {
        val http412 = HttpException(Response.error<Any>(412, "".toResponseBody()))
        assertSame(RefreshOutcome.Blocked, resolvePassportException(http412))
        val http500 = HttpException(Response.error<Any>(500, "".toResponseBody()))
        assertEquals(RefreshOutcome.Failed("passport HTTP 500"), resolvePassportException(http500))
        assertEquals(RefreshOutcome.Failed("boom"), resolvePassportException(IllegalStateException("boom")))
    }
}
