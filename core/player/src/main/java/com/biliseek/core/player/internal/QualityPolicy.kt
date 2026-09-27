package com.biliseek.core.player.internal

import com.biliseek.core.network.dto.Dash
import com.biliseek.core.network.dto.DashTrack
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.QualityFallback

/**
 * 倍速高到这个值以上就不再用无损 / 杜比。
 *
 * 见《架构设计篇》§4.4 边界 1。取 1.5 而不是 2.0：1.5× 本身已经是常用档，
 * 卡在「常用档还能听无损」这一侧。
 */
internal const val LOSSLESS_MAX_SPEED = 1.5f

/** 音质解析结果：**实际会用**的档位 + 与用户所选不一致时的原因。 */
internal data class EffectiveQuality(
    val quality: AudioQuality,
    val fallback: QualityFallback,
)

/**
 * 取流**之前**判断：这次要不要去要无损轨。
 *
 * 倍速是本地状态，不必等网络就能否掉无损——省掉一次注定要回退的请求。
 */
internal fun shouldRequestLossless(requested: AudioQuality, speed: Float): Boolean =
    requested == AudioQuality.LOSSLESS && speed <= LOSSLESS_MAX_SPEED

/**
 * 这次取流实际向接口要哪一档。
 *
 * 选了无损但倍速过高 → 要普通 AAC（自动档）；其余照用户所选。
 */
internal fun resolveAskedAudioQuality(requested: AudioQuality, speed: Float): AudioQuality = when {
    shouldRequestLossless(requested, speed) -> AudioQuality.LOSSLESS
    requested == AudioQuality.LOSSLESS -> AudioQuality.AUTO
    else -> requested
}

/**
 * 播放页换音质后要不要重新取流。
 *
 * 只有「实际要的那一档」变了才重取：比如 2× 下从「无损」换成「自动」，
 * 两者实际要的都是 AAC，重取只会白白缓冲一次。本地文件没有档位可换。
 */
internal fun shouldReloadForAudioQuality(
    before: AudioQuality,
    after: AudioQuality,
    speed: Float,
    isOffline: Boolean,
): Boolean = !isOffline &&
    resolveAskedAudioQuality(before, speed) != resolveAskedAudioQuality(after, speed)

/**
 * 取流**之后**判断：用户所选的档位到底生没生效，没生效是因为什么。
 *
 * [losslessSelected] 由取流层给出——只有真的挑中了 flac / dolby 轨才为真。
 * 判定顺序有讲究：**倍速优先于「无此轨道」**。倍速是用户刚做的动作，
 * 因果关系最直观；反过来报「本视频无无损音轨」会让人以为换个视频就行。
 */
internal fun resolveEffectiveQuality(
    requested: AudioQuality,
    speed: Float,
    losslessSelected: Boolean,
): EffectiveQuality = when {
    requested != AudioQuality.LOSSLESS -> EffectiveQuality(requested, QualityFallback.NONE)
    speed > LOSSLESS_MAX_SPEED ->
        EffectiveQuality(AudioQuality.AUTO, QualityFallback.SPEED_INCOMPATIBLE)
    !losslessSelected -> EffectiveQuality(AudioQuality.AUTO, QualityFallback.UNAVAILABLE)
    else -> EffectiveQuality(AudioQuality.LOSSLESS, QualityFallback.NONE)
}

/** 选出的音频轨，以及「这批轨是不是无损 / 杜比」。 */
internal data class AudioSelection(
    val tracks: List<DashTrack>,
    val lossless: Boolean,
)

/**
 * 按音质偏好挑音频轨。
 *
 * 三条来源见《架构设计篇》§4.4：`dash.flac.audio`（单个对象）、
 * `dash.dolby.audio`（需 `type > 0` 且 codecs 含 ec-3/eac3）、`dash.audio[]`。
 *
 * [AudioQuality.SAVER] 只放**一条**最低码率轨：多放几条 ExoPlayer 会按带宽自适应升上去，
 * 「省流量」就落空了。
 */
internal fun selectAudioTracks(dash: Dash, quality: AudioQuality): AudioSelection {
    val aac = dash.audio.orEmpty().filter { it.url().isNotBlank() }.sortedByDescending { it.bandwidth }

    if (quality == AudioQuality.LOSSLESS) {
        dash.flac?.audio?.takeIf { it.url().isNotBlank() }?.let {
            return AudioSelection(listOf(it), lossless = true)
        }
        val dolby = dash.dolby
            ?.takeIf { it.type > 0 }
            ?.audio.orEmpty()
            .filter { it.url().isNotBlank() && it.codecs.isDolby() }
        if (dolby.isNotEmpty()) return AudioSelection(dolby, lossless = true)
        // 拿不到就回退 AAC，不让「选了无损」变成「放不出声」
        return AudioSelection(aac, lossless = false)
    }

    val tracks = if (quality == AudioQuality.SAVER) aac.takeLast(1) else aac
    return AudioSelection(tracks, lossless = false)
}

private fun String.isDolby(): Boolean =
    contains("ec-3", ignoreCase = true) || contains("eac3", ignoreCase = true)
