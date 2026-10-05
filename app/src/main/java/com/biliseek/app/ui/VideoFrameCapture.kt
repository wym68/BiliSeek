package com.biliseek.app.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 截下 [surfaceView] 当前显示的这一帧（设计稿：docs/superpowers/specs/2026-10-04-player-long-press-and-exit-design.md）。
 *
 * 看模式下离开播放页时用：SurfaceView 是单独一层，不跟着页面淡出变透明，所以先截一帧顶替它，
 * 再撤掉画面，整页（连同这一帧）一起淡出。截不到（还没出画面、Surface 无效、超时）返回 null，调用方退回封面。
 */
internal suspend fun captureVideoFrame(surfaceView: SurfaceView, timeoutMs: Long = CAPTURE_TIMEOUT_MS): Bitmap? {
    val width = surfaceView.width
    val height = surfaceView.height
    if (width <= 0 || height <= 0 || !surfaceView.holder.surface.isValid) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val copied = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            // 超时后才回调：continuation 已取消，resume 不起作用；bitmap 不回收，交给 GC（回调可能还在写它）
            PixelCopy.request(surfaceView, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
    }
    return if (copied == true) bitmap else null
}

/** 截帧最多等这么久：再久就不等了，直接撤画面（退回封面），免得点了返回半天没反应。 */
private const val CAPTURE_TIMEOUT_MS = 250L
