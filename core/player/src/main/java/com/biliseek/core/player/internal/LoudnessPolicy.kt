package com.biliseek.core.player.internal

import kotlin.math.pow

/** 接口没给目标响度时用的兜底值，单位 LUFS。B 站自己给的 target 通常也在这一带。 */
internal const val DEFAULT_TARGET_LOUDNESS = -18.0

/**
 * 响度均衡的增益。
 *
 * 数据是 B 站白送的：playurl 带 `voice_balance=1` 就会返回 EBU R128 的
 * `measured_i`（实测积分响度）与 `target_i`（目标响度），不必自己分析音频。
 *
 * **只能衰减，不能增益。** `Player.setVolume` 的上限是 1.0，算出 >1 的增益若不夹住，
 * 表现是「开了响度均衡但毫无变化」——比不做更难排查。因此这里的语义是
 * 「把过响的投稿压下来」，而不是「把过轻的抬上去」。
 *
 * @param measuredI 实测响度。0.0 表示接口没给数据，此时不做任何处理。
 * @param targetI 目标响度。非负值视为无效，回退 [DEFAULT_TARGET_LOUDNESS]。
 * @return 线性音量系数，落在 (0, 1]
 */
internal fun loudnessGain(measuredI: Double?, targetI: Double?): Float {
    val measured = measuredI ?: 0.0
    if (measured == 0.0) return 1.0f

    val target = targetI?.takeIf { it < 0.0 } ?: DEFAULT_TARGET_LOUDNESS
    // dB 差转线性系数：10^(ΔdB / 20)
    val gain = 10.0.pow((target - measured) / 20.0)
    return gain.coerceIn(MIN_GAIN, 1.0).toFloat()
}

/** 别把响度压到听不见——数据异常时也要留住可听性。 */
private const val MIN_GAIN = 0.1
