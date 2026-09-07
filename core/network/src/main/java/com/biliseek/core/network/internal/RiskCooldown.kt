package com.biliseek.core.network.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局风控冷却闸门（进程级单例状态）。
 *
 * 撞到 -352 就进入冷却，冷却期内请求快速失败而非硬顶；连续风控冷却时长递增；
 * 任意成功清零。时钟以入参注入，便于离线单测。
 */
internal class RiskCooldown {
    private val _cooldownUntil = MutableStateFlow(0L)
    val cooldownUntil: StateFlow<Long> = _cooldownUntil
    private var consecutive = 0

    /** 收到 -352 调用：累加并延长冷却。 */
    @Synchronized
    fun onRiskControl(now: Long = System.currentTimeMillis()) {
        consecutive++
        _cooldownUntil.value = now + riskCooldownMs(consecutive)
    }

    /** 任意成功调用：清零。 */
    @Synchronized
    fun onSuccess() {
        consecutive = 0
        _cooldownUntil.value = 0
    }

    /** 请求前检查：处于冷却期返回剩余 ms（>0 表示应拒发）。 */
    fun remaining(now: Long = System.currentTimeMillis()): Long =
        (_cooldownUntil.value - now).coerceAtLeast(0)
}
