package com.biliseek.app.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.biliseek.core.network.dto.DanmakuItem
import com.biliseek.core.player.PlaybackState

/**
 * 弹幕渲染层（自绘 Canvas，不引第三方库）。
 *
 * - 时间：[DanmakuClock] 把 250ms 一次的位置推送插值成逐帧视频时间，暂停即冻结，
 *   倍速下弹幕跟着视频一起快；seek 时清屏、从新位置重新排。
 * - 排布：[DanmakuLayoutPolicy] 分配轨道，放不下的直接丢弃，不堆叠。
 * - 性能：只在出场那一刻排版一次文字；按时间有序 + 游标推进，不每帧遍历全部弹幕；
 *   同屏条数有上限。每帧只触发重绘，不触发重组。
 *
 * [items] 须已按 [filterDanmaku] 过滤并按时间排序。
 */
@Composable
fun DanmakuLayer(
    items: List<DanmakuItem>,
    state: PlaybackState,
    settings: DanmakuSettings,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val renderer = remember { DanmakuRenderer() }
    val clock = remember { DanmakuClock() }
    var frameTick by remember { mutableLongStateOf(0L) }
    val latestItems by rememberUpdatedState(items)
    val latestState by rememberUpdatedState(state)
    val latestSettings by rememberUpdatedState(settings)
    val fontPxPerUnit = with(density) { 1.sp.toPx() }

    LaunchedEffect(renderer) {
        var seenState: PlaybackState? = null
        while (true) {
            withFrameNanos { nanos ->
                val playback = latestState
                // 只在真的收到新推送时对齐时钟；拿着旧位置逐帧对齐会被自己的外推「纠正」回去
                if (playback !== seenState) {
                    seenState = playback
                    if (clock.update(playback.positionMs, nanos, playback.speed, playback.isPlaying)) {
                        renderer.clear()
                    }
                }
                renderer.advance(
                    nowMs = clock.now(nanos),
                    items = latestItems,
                    settings = latestSettings,
                    measurer = textMeasurer,
                    fontPxPerUnit = fontPxPerUnit,
                )
                frameTick = nanos
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { renderer.resize(it) },
    ) {
        // 读一下 frameTick 让每帧重绘；排布状态在 renderer 里，不走快照
        frameTick
        renderer.draw(this, latestSettings.opacity)
    }
}

/** 一条正在屏上的弹幕。 */
private class ActiveDanmaku(
    val item: DanmakuItem,
    val kind: DanmakuKind,
    val track: Int,
    val layout: TextLayoutResult,
)

/** 渲染状态：在屏弹幕、轨道占用、出场游标。只在主线程（帧回调与绘制）上访问。 */
private class DanmakuRenderer {
    private val active = ArrayList<ActiveDanmaku>()
    private var scrollTracks = mutableListOf<TrackOccupant?>()
    private var topTracks = mutableListOf<TrackOccupant?>()
    private var bottomTracks = mutableListOf<TrackOccupant?>()
    private var itemsRef: List<DanmakuItem>? = null
    private var nextIndex = 0
    private var needsReposition = true
    private var size = IntSize.Zero
    private var layoutKey: Any? = null
    private var lineHeight = 0f
    private var nowMs = 0L
    private var scrollDurationMs = DanmakuSettings.BASE_SCROLL_DURATION_MS

    fun resize(newSize: IntSize) {
        if (newSize != size) {
            size = newSize
            clear()
        }
    }

    /** 清屏并在下一帧从当前时间重新定位游标（seek / 尺寸变化 / 设置变化）。 */
    fun clear() {
        active.clear()
        scrollTracks.clear()
        topTracks.clear()
        bottomTracks.clear()
        needsReposition = true
    }

    fun advance(
        nowMs: Long,
        items: List<DanmakuItem>,
        settings: DanmakuSettings,
        measurer: TextMeasurer,
        fontPxPerUnit: Float,
    ) {
        this.nowMs = nowMs
        if (size.width <= 0 || size.height <= 0) return

        val key = listOf(settings.fontScale, settings.area, settings.speed)
        if (key != layoutKey) {
            layoutKey = key
            scrollDurationMs = (DanmakuSettings.BASE_SCROLL_DURATION_MS / settings.speed).toLong()
            lineHeight = measurer.measure("弹", styleFor(STANDARD_FONT_SIZE, settings, fontPxPerUnit, Color.White))
                .size.height * LINE_SPACING
            clear()
        }
        // 新的一段加载进来（列表换了），或者刚清过屏：游标重新落到「现在」
        if (items !== itemsRef || needsReposition) {
            itemsRef = items
            nextIndex = firstDanmakuIndexAtOrAfter(items, nowMs - SPAWN_TOLERANCE_MS)
            needsReposition = false
        }

        val areaHeight = size.height * settings.area
        val trackCount = danmakuTrackCount(areaHeight, lineHeight)
        val fixedTrackCount = danmakuTrackCount(size.height / 2f, lineHeight)
        scrollTracks.ensureSize(trackCount)
        topTracks.ensureSize(fixedTrackCount)
        bottomTracks.ensureSize(fixedTrackCount)

        // 过期的下场
        active.removeAll { dm ->
            val duration = if (dm.kind == DanmakuKind.SCROLL) scrollDurationMs else DanmakuSettings.FIXED_DURATION_MS
            nowMs - dm.item.timeMs > duration
        }

        // 到点的出场
        while (nextIndex < items.size && items[nextIndex].timeMs <= nowMs) {
            val item = items[nextIndex++]
            // 太旧的不补出：刚 seek 过来或刚加载完时，不能一口气把前面几秒的全喷出来
            if (nowMs - item.timeMs > SPAWN_TOLERANCE_MS) continue
            if (active.size >= MAX_ACTIVE) continue
            val kind = danmakuKind(item.mode) ?: continue
            val layout = measurer.measure(
                item.content,
                styleFor(item.fontSize, settings, fontPxPerUnit, Color(0xFF000000.toInt() or item.color)),
            )
            val width = layout.size.width.toFloat()
            val track = when (kind) {
                DanmakuKind.SCROLL -> pickScrollTrack(
                    scrollTracks, item.timeMs, width, size.width.toFloat(), scrollDurationMs, TRACK_GAP_PX,
                )
                DanmakuKind.TOP -> pickFixedTrack(topTracks, item.timeMs, DanmakuSettings.FIXED_DURATION_MS)
                DanmakuKind.BOTTOM -> pickFixedTrack(bottomTracks, item.timeMs, DanmakuSettings.FIXED_DURATION_MS)
            } ?: continue
            val occupant = TrackOccupant(item.timeMs, width)
            when (kind) {
                DanmakuKind.SCROLL -> scrollTracks[track] = occupant
                DanmakuKind.TOP -> topTracks[track] = occupant
                DanmakuKind.BOTTOM -> bottomTracks[track] = occupant
            }
            active += ActiveDanmaku(item, kind, track, layout)
        }
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, opacity: Float) {
        val screenWidth = size.width.toFloat()
        val screenHeight = size.height.toFloat()
        for (dm in active) {
            val width = dm.layout.size.width.toFloat()
            val x = when (dm.kind) {
                DanmakuKind.SCROLL -> scrollX(nowMs, dm.item.timeMs, width, screenWidth, scrollDurationMs)
                else -> (screenWidth - width) / 2f
            }
            val y = when (dm.kind) {
                DanmakuKind.BOTTOM -> screenHeight - (dm.track + 1) * lineHeight
                else -> dm.track * lineHeight
            }
            with(scope) { drawText(dm.layout, topLeft = Offset(x, y), alpha = opacity) }
        }
    }

    private fun MutableList<TrackOccupant?>.ensureSize(n: Int) {
        while (size < n) add(null)
        while (size > n) removeAt(size - 1)
    }

    private fun styleFor(fontSize: Int, settings: DanmakuSettings, fontPxPerUnit: Float, color: Color) = TextStyle(
        color = color,
        // B 站字号 25 为标准，对应手机上约 18sp
        fontSize = (fontSize * FONT_SIZE_TO_SP * settings.fontScale).sp,
        fontWeight = FontWeight.Medium,
        // 白字在亮画面上看不清：加一圈暗影描边
        shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), offset = Offset(1f, 1f), blurRadius = 2f * fontPxPerUnit),
    )

    companion object {
        const val STANDARD_FONT_SIZE = 25
        const val FONT_SIZE_TO_SP = 0.72f
        const val LINE_SPACING = 1.15f
        const val TRACK_GAP_PX = 24f
        const val MAX_ACTIVE = 120
        const val SPAWN_TOLERANCE_MS = 500L
    }
}
