package com.biliseek.core.network

import com.biliseek.core.network.internal.RiskCooldown
import com.biliseek.core.network.internal.riskCooldownMs
import com.biliseek.core.network.internal.throttleDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

/** B3 冷却/限速的纯函数与注入时钟判据（工单 §4.1）。 */
class CooldownPolicyTest {

    // ── riskCooldownMs：指数退避 + 上限 ──────────────────────

    @Test
    fun `零次或负数不冷却`() {
        assertEquals(0L, riskCooldownMs(0))
        assertEquals(0L, riskCooldownMs(-3))
    }

    @Test
    fun `连击冷却指数递增`() {
        assertEquals(60_000L, riskCooldownMs(1))
        assertEquals(120_000L, riskCooldownMs(2))
        assertEquals(240_000L, riskCooldownMs(3))
        assertEquals(480_000L, riskCooldownMs(4))
    }

    @Test
    fun `冷却有上限`() {
        assertEquals(30 * 60_000L, riskCooldownMs(100))
        // 达到上限后不再增长
        assertEquals(riskCooldownMs(50), riskCooldownMs(99))
    }

    // ── throttleDelayMs：最小间隔 ───────────────────────────

    @Test
    fun `首次请求不等待`() {
        assertEquals(0L, throttleDelayMs(lastAtMs = null, nowMs = 1000, minIntervalMs = 500))
    }

    @Test
    fun `间隔未到需等待剩余`() {
        // 上次 1000，最小间隔 500，现在 1200 → 还需等 300
        assertEquals(300L, throttleDelayMs(lastAtMs = 1000, nowMs = 1200, minIntervalMs = 500))
    }

    @Test
    fun `间隔已过不等待`() {
        assertEquals(0L, throttleDelayMs(lastAtMs = 1000, nowMs = 2000, minIntervalMs = 500))
    }

    // ── RiskCooldown：注入时钟 ──────────────────────────────

    @Test
    fun `撞墙进入冷却剩余递增`() {
        val cd = RiskCooldown()
        cd.onRiskControl(now = 0)
        assertEquals(60_000L, cd.remaining(now = 0))
        cd.onRiskControl(now = 0)
        assertEquals(120_000L, cd.remaining(now = 0))
    }

    @Test
    fun `冷却随时间流逝递减到零`() {
        val cd = RiskCooldown()
        cd.onRiskControl(now = 0)
        assertEquals(10_000L, cd.remaining(now = 50_000))
        assertEquals(0L, cd.remaining(now = 60_000))
        assertEquals(0L, cd.remaining(now = 999_999))
    }

    @Test
    fun `成功清零冷却与连击计数`() {
        val cd = RiskCooldown()
        cd.onRiskControl(now = 0)
        cd.onRiskControl(now = 0)
        cd.onSuccess()
        assertEquals(0L, cd.remaining(now = 0))
        // 清零后再撞墙，从最短冷却重新起算（连击计数已归零）
        cd.onRiskControl(now = 0)
        assertEquals(60_000L, cd.remaining(now = 0))
    }
}
