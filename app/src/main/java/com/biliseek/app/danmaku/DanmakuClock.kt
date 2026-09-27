package com.biliseek.app.danmaku

import kotlin.math.abs

/**
 * 弹幕时钟：把 250ms 一次的播放位置推送插值成逐帧的视频时间。
 *
 * 直接用推送来的 `positionMs` 画，弹幕会每 250ms 跳一下；逐帧按倍速外推才顺滑。
 * 外推与真实位置的偏差超过 [RESYNC_MS] 才重新对齐——每次推送都对齐的话，
 * 推送本身的抖动会让弹幕来回抽动。偏差超过 [SEEK_JUMP_MS] 视为 seek：渲染层清屏重排。
 */
class DanmakuClock {
    private var anchorPositionMs = 0L
    private var anchorNanos = 0L
    private var speed = 1f
    private var playing = false
    private var initialized = false

    /** 当前视频时间（ms）。 */
    fun now(frameNanos: Long): Long {
        if (!playing) return anchorPositionMs
        val elapsedMs = (frameNanos - anchorNanos) / 1_000_000.0 * speed
        return anchorPositionMs + elapsedMs.toLong()
    }

    /**
     * 收到一次播放状态。返回 true 表示发生了跳变（seek、换视频），渲染层应清屏。
     */
    fun update(positionMs: Long, frameNanos: Long, speed: Float, playing: Boolean): Boolean {
        if (!initialized) {
            anchor(positionMs, frameNanos, speed, playing)
            initialized = true
            return true
        }
        val drift = abs(now(frameNanos) - positionMs)
        val jumped = drift > SEEK_JUMP_MS
        if (jumped || drift > RESYNC_MS || speed != this.speed || playing != this.playing) {
            anchor(positionMs, frameNanos, speed, playing)
        }
        return jumped
    }

    private fun anchor(positionMs: Long, frameNanos: Long, speed: Float, playing: Boolean) {
        anchorPositionMs = positionMs
        anchorNanos = frameNanos
        this.speed = speed
        this.playing = playing
    }

    companion object {
        const val RESYNC_MS = 300L
        const val SEEK_JUMP_MS = 1_500L
    }
}
