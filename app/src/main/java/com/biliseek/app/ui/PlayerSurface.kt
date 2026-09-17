package com.biliseek.app.ui

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 *
 * **z-ordering 修复**：SurfaceView 在独立的 surface 上渲染，默认位于窗口内容之上。
 * 仅将其从父容器移除不足以隐藏它——必须同时调用 `setVisibility(GONE)` 让 SurfaceFlinger
 * 不再合成该层。退出组合时 `onDispose` 立即隐藏并移除，确保 exit 动画期间不残留。
 *
 * **所有权竞争修复**：内联播放区和全屏 overlay 在切换的同一帧里，一个在摘、
 * 一个在挂，谁先谁后不由我们控制。如果 `onDispose` 不分青红皂白就摘，摘到的
 * 可能是刚被另一处重新接管的 view——全屏进去黑屏、画面消失就是这么来的。
 * 用 `attachedHost` 记住"这次是我把它挂到哪个容器上的"，`onDispose` 只清理
 * 当前父容器仍然是自己那份，所有权已经转移的不动。
 */
@Composable
fun PlayerSurface(surfaceView: SurfaceView, modifier: Modifier = Modifier) {
    val attachedHost = remember { mutableStateOf<ViewGroup?>(null) }

    DisposableEffect(surfaceView) {
        onDispose {
            if (surfaceView.parent === attachedHost.value) {
                surfaceView.visibility = View.GONE
                (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
            }
        }
    }

    AndroidView(
        factory = {
            (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
            surfaceView.visibility = View.VISIBLE
            surfaceView
        },
        update = { view ->
            attachedHost.value = view.parent as? ViewGroup
        },
        modifier = modifier,
    )
}
