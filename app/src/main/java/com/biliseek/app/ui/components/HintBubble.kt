package com.biliseek.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 首次演示的说明气泡（设计稿 2026-10-07-first-use-hints，效果图选 B）：白底圆角，尖角指着控件。
 * 放在要指的控件里（它的父布局就是锚点）；锚点下方放得下就放下方，否则放上方（[preferAbove] 反过来）。
 * 不抢焦点、不拦截别处的触摸；碰到气泡本身也算碰了屏幕（当前演示结束）。
 *
 * @param pointX 尖角指向锚点里的哪个横坐标（从锚点左缘算起）；null = 锚点正中。
 * @param extraAbove 放上方时再额外抬高多少（迷你条刻度尺演示：让出刻度尺的位置）。
 */
@Composable
fun HintBubble(
    text: String,
    pointX: Dp? = null,
    preferAbove: Boolean = false,
    extraAbove: Dp = 0.dp,
) {
    val hints = LocalHints.current
    val density = LocalDensity.current
    var arrowX by remember { mutableFloatStateOf(0f) }
    var below by remember { mutableStateOf(!preferAbove) }
    val provider = remember(pointX, preferAbove, extraAbove, density) {
        with(density) {
            BubblePositionProvider(
                pointXPx = pointX?.roundToPx(),
                gapPx = BUBBLE_GAP.roundToPx(),
                extraAbovePx = extraAbove.roundToPx(),
                marginPx = BUBBLE_MARGIN.roundToPx(),
                preferAbove = preferAbove,
            ) { x, isBelow ->
                arrowX = x
                below = isBelow
            }
        }
    }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        val scheme = MaterialTheme.colorScheme
        val isDark = scheme.surface.luminance() < 0.5f
        val shape = remember(arrowX, below, density) {
            with(density) {
                BubbleShape(
                    arrowCenterPx = arrowX,
                    arrowOnTop = below,
                    cornerPx = BUBBLE_CORNER.toPx(),
                    arrowWidthPx = ARROW_WIDTH.toPx(),
                    arrowHeightPx = ARROW_HEIGHT.toPx(),
                )
            }
        }
        Surface(
            modifier = Modifier.pointerInput(hints) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hints?.onTouch()
                }
            },
            shape = shape,
            color = if (isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
            contentColor = scheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp + if (below) ARROW_HEIGHT else 0.dp,
                    bottom = 10.dp + if (below) 0.dp else ARROW_HEIGHT,
                ),
            )
        }
    }
}

/** 定位：横向让尖角对准 [pointXPx]、整体不超出屏幕左右 [marginPx]；纵向放锚点下方或上方。 */
private class BubblePositionProvider(
    private val pointXPx: Int?,
    private val gapPx: Int,
    private val extraAbovePx: Int,
    private val marginPx: Int,
    private val preferAbove: Boolean,
    private val onPlaced: (arrowX: Float, below: Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val pointX = anchorBounds.left + (pointXPx ?: (anchorBounds.width / 2))
        val maxX = (windowSize.width - marginPx - popupContentSize.width).coerceAtLeast(marginPx)
        val x = (pointX - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val belowY = anchorBounds.bottom + gapPx
        val aboveY = anchorBounds.top - gapPx - extraAbovePx - popupContentSize.height
        val below = if (preferAbove) aboveY < 0 else belowY + popupContentSize.height <= windowSize.height
        onPlaced((pointX - x).toFloat(), below)
        return IntOffset(x, if (below) belowY else aboveY)
    }
}

/** 圆角矩形 + 一个尖角（尖角在上沿或下沿，横向中心 [arrowCenterPx]，离圆角至少半个尖角宽）。 */
private class BubbleShape(
    private val arrowCenterPx: Float,
    private val arrowOnTop: Boolean,
    private val cornerPx: Float,
    private val arrowWidthPx: Float,
    private val arrowHeightPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bodyTop = if (arrowOnTop) arrowHeightPx else 0f
        val bodyBottom = if (arrowOnTop) size.height else size.height - arrowHeightPx
        val body = Path().apply {
            addRoundRect(RoundRect(0f, bodyTop, size.width, bodyBottom, CornerRadius(cornerPx)))
        }
        val half = arrowWidthPx / 2
        val minX = cornerPx + half
        val maxX = size.width - cornerPx - half
        val cx = if (minX <= maxX) arrowCenterPx.coerceIn(minX, maxX) else size.width / 2
        // 尖角底边伸进主体 1px，合并后不留缝
        val arrow = Path().apply {
            if (arrowOnTop) {
                moveTo(cx - half, bodyTop + 1f)
                lineTo(cx, 0f)
                lineTo(cx + half, bodyTop + 1f)
            } else {
                moveTo(cx - half, bodyBottom - 1f)
                lineTo(cx, size.height)
                lineTo(cx + half, bodyBottom - 1f)
            }
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, arrow))
    }
}

private val BUBBLE_GAP = 8.dp
private val BUBBLE_MARGIN = 16.dp
private val BUBBLE_CORNER = 12.dp
private val ARROW_WIDTH = 16.dp
private val ARROW_HEIGHT = 8.dp
