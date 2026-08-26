package com.biliseek.core.player

/**
 * 播放态。与 [PresentationMode] 构成两个正交维度。
 *
 * 见《架构设计篇》§5.2。核心不变式：
 * `PlaybackMode == VIDEO` ⟺ `PresentationMode ∈ {INLINE, FULLSCREEN, PIP}`
 */
enum class PlaybackMode {
    /** 无内容 */
    IDLE,

    /** 纯音频（BiliSeek 的默认模式）。视频轨禁用，不下载视频分片。 */
    AUDIO,

    /** 含画面。视频轨启用，且必然存在已 attach 的 surface。 */
    VIDEO,
}

/**
 * 呈现态。纯 UI 概念，除 [PresentationMode.NONE] 与非 NONE 的切换外，不产生播放器副作用。
 */
enum class PresentationMode {
    /** 无可见画面：纯听、进后台、锁屏 */
    NONE,

    /** 播放页内原地展开 */
    INLINE,

    /** 全屏 */
    FULLSCREEN,

    /** 画中画。注意：PiP 属于**可见**，不触发回落音频。 */
    PIP,
}

/**
 * 一次播放请求。
 *
 * 两种来源：[Direct] 直链（M0 测试流用）、[Bili] B 站视频（需取流解析）。
 */
sealed interface PlaybackRequest {
    val title: String

    /** 直接给 URI，不走取流。 */
    data class Direct(
        val uri: String,
        override val title: String = "",
    ) : PlaybackRequest

    /** B 站视频。cid 为空时自动取第一个分 P。 */
    data class Bili(
        val bvid: String,
        val cid: Long? = null,
        override val title: String = "",
        /** 封面。随请求一路带到 [PlaybackState]，展开播放器与通知栏都要用。 */
        val coverUrl: String = "",
        /**
         * UP 主与时长。**播放历史要用**：队列自动推进时只有请求本身知道这些字段，
         * 不随请求带着，落库的就是残缺行。
         */
        val author: String = "",
        val durationSec: Long = 0,
    ) : PlaybackRequest
}

/**
 * 播放偏好。由设置页提供，取流时现读。
 *
 * 只放**会影响取流与音频输出**的项——界面偏好（默认呈现态、流量策略）留在 app 层，
 * 播放内核不该知道有「页面」这回事。
 */
data class PlaybackPreferences(
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val loudnessNormalization: Boolean = true,
)

/**
 * 音质偏好。用户在设置页里选，播放时按《架构设计篇》§4.4 解析成实际轨道。
 */
enum class AudioQuality {
    /** 跟随网络自适应，从普通 AAC 里挑。出厂默认。 */
    AUTO,

    /** 优先无损 / 杜比（需大会员）。拿不到时回退 AAC，回退原因见 [QualityFallback]。 */
    LOSSLESS,

    /** 省流量：固定选最低码率的那条音频轨。 */
    SAVER,
}

/** 音质没能按用户所选生效时的原因。UI 要如实说出来，而不是默默降级。 */
enum class QualityFallback {
    /** 按用户所选生效，没有回退。 */
    NONE,

    /** 这个视频没有无损 / 杜比轨，或账号没有权限。 */
    UNAVAILABLE,

    /**
     * 倍速与无损不兼容，已降到 AAC。
     *
     * 见《架构设计篇》§4.4 边界 1：高倍速下 Hi-Res / 杜比有兼容问题。
     */
    SPEED_INCOMPATIBLE,
}

/**
 * 一次听 ⇄ 看切换的实测诊断，用于 M0 验收。
 *
 * 对应《验收清单》M0 B-3 的两条硬判据：切换过程中不应出现 buffering，
 * 也不应出现 SEEK 造成的位置不连续。
 */
data class SwitchDiagnostics(
    val label: String,
    val positionBeforeMs: Long,
    val positionAfterMs: Long,
    val bufferingEvents: Int,
    val seekDiscontinuities: Int,
) {
    /** 无缝的判据：既没有重新缓冲，也没有 seek 跳变。 */
    val seamless: Boolean get() = bufferingEvents == 0 && seekDiscontinuities == 0
}

/** 对 UI 暴露的播放状态快照。 */
data class PlaybackState(
    val mode: PlaybackMode = PlaybackMode.IDLE,
    val presentation: PresentationMode = PresentationMode.NONE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoTrackSelected: Boolean = false,
    /** 当前条目标题，MiniPlayer 展示用 */
    val title: String = "",
    val bvid: String = "",
    val cid: Long = 0,
    /** 当前条目封面，纯音频模式下顶替黑框 */
    val coverUrl: String = "",
    val lastSwitch: SwitchDiagnostics? = null,
    /** 取流中（网络请求阶段，尚未进入播放器） */
    val isResolving: Boolean = false,
    val error: String? = null,
    /** 播放队列与当前位置 */
    val queue: List<PlaybackRequest> = emptyList(),
    val queueIndex: Int = -1,
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    /** 睡眠定时器剩余毫秒。null 表示没设倒计时（可能设了「播完当前」）。 */
    val sleepTimerRemainingMs: Long? = null,
    /** 「播完当前曲目再停」已生效。此模式剩余时间不可预估，故与上一字段并存。 */
    val sleepTimerEndOfTrack: Boolean = false,
    val speed: Float = 1.0f,
    /** **实际生效**的音质，不是用户请求的那个。两者不一致时 [qualityFallback] 说明原因。 */
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val qualityFallback: QualityFallback = QualityFallback.NONE,
) {
    /** 定时器是否已设置——两种终止条件任一生效即为真。 */
    val sleepTimerActive: Boolean
        get() = sleepTimerRemainingMs != null || sleepTimerEndOfTrack

    val hasPrevious: Boolean
        get() = queue.size > 1 && (playMode != PlayMode.SEQUENTIAL || queueIndex > 0)

    val hasNext: Boolean
        get() = queue.size > 1 && (playMode != PlayMode.SEQUENTIAL || queueIndex < queue.size - 1)
}

/** 播放模式。 */
enum class PlayMode {
    /** 顺序播放，播完最后一首停止 */
    SEQUENTIAL,

    /** 列表循环 */
    REPEAT_ALL,

    /** 单曲循环 */
    REPEAT_ONE,

    /** 随机 */
    SHUFFLE,
}
