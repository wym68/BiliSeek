package com.biliseek.app.ui

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 承载播放画面的 SurfaceView。
 *
 * **同一个 View 实例只能有一个父容器。** 播放器在「内联」与「全屏」两个组合分支
 * 里各有一个宿主，切换时若不先从旧父容器摘下，会直接抛
 * `ViewGroup.addViewInner: The specified child already has a parent`。
 *
 * 复用同一个 SurfaceView 而非各建一个，是为了保持 surface 连续——
 * 重建 surface 会让画面闪断，破坏「无缝」。
 */
@Composable
fun PlayerSurface(surfaceView: SurfaceView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = {
            (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
            surfaceView
        },
        modifier = modifier,
    )
}
