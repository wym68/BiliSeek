package com.biliseek.core.player.internal

/**
 * 睡眠定时器的终止条件 —— 纯数据 + 纯函数，可单测。
 *
 * 与 [resolveNextIndex]（QueuePolicy）/ [resolveStartPosition]（ResumePolicy）同一模式：
 * 判定是纯的，副作用（暂停播放器）留在 `PlayerConnection`。
 */
internal sealed interface SleepTimerSpec {

    /** 到达绝对时间戳 [endAtMs] 即停。 */
    data class Deadline(val endAtMs: Long) : SleepTimerSpec

    /** 播完当前曲目再停。剩余时间不可预估——曲目可能被暂停、被拖动。 */
    data object EndOfTrack : SleepTimerSpec
}

/** 从 [nowMs] 起 [durationMs] 毫秒后停。[durationMs] 非正视为取消，返回 `null`。 */
internal fun deadlineAfter(nowMs: Long, durationMs: Long): SleepTimerSpec? =
    if (durationMs <= 0) null else SleepTimerSpec.Deadline(nowMs + durationMs)

/** 剩余毫秒。只有倒计时型有剩余；「播完当前」返回 `null`。 */
internal fun sleepTimerRemaining(spec: SleepTimerSpec?, nowMs: Long): Long? = when (spec) {
    is SleepTimerSpec.Deadline -> (spec.endAtMs - nowMs).coerceAtLeast(0L)
    else -> null
}

/** 倒计时是否已到点。 */
internal fun hasSleepTimerExpired(spec: SleepTimerSpec?, nowMs: Long): Boolean =
    spec is SleepTimerSpec.Deadline && nowMs >= spec.endAtMs

/** 曲目播完时是否应当停止，而不是推进到下一首。 */
internal fun shouldStopOnTrackEnd(spec: SleepTimerSpec?): Boolean =
    spec is SleepTimerSpec.EndOfTrack
