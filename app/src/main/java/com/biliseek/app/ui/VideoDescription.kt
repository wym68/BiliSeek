package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.components.SettingsSheet
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 播放页简介行：左边「简介 展开」，右边统计行（播放量 · 发布时间）。简介正文的前几行由 [VideoDescriptionPreview] 显示在下面，全文点「简介 展开」在底部弹层里看
 * （[VideoDescriptionSheet]；设计稿 docs/superpowers/specs/2026-10-04-player-desc-expand-design.md）。
 *
 * - 有简介内容（[VideoDescriptionPolicy.shouldShow]）时：「简介」普通字色当标签，旁边「展开」+ 下箭头用链接色，
 *   两者一块点击区。只靠颜色区分可点与否不明显（用户 2026-10-04 反馈），所以加了「展开」。
 * - 没有（空 / 单个 `-`）时「简介」「展开」都不显示，只剩统计行（靠左）。行高至少 40dp，下面的进度条不跟着跳。
 * - 简介和统计都没有（本地文件、详情未加载）时整行不显示。
 */
@Composable
internal fun VideoDescriptionRow(
    desc: String,
    viewCount: Long,
    pubDate: Long,
    linkColor: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDesc = VideoDescriptionPolicy.shouldShow(desc)
    val stats = remember(viewCount, pubDate) {
        VideoDescriptionPolicy.statsLine(viewCount, formatPubDate(pubDate))
    }
    if (!hasDesc && stats.isEmpty()) return

    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasDesc) {
            // 文字本身只有一行高：外面套一块 40dp 高的点击区，左移 8dp 抵掉内边距，文字仍与上面的标题左对齐
            Row(
                Modifier
                    .offset(x = (-8).dp)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "简介",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text("展开", style = MaterialTheme.typography.labelLarge, color = linkColor)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = linkColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
        }
        if (stats.isNotEmpty()) {
            Text(
                stats,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 简介正文预览：放在播放页中段最下面，占满剩下的高度——能放几行放几行，最后一行末尾省略号；一行都放不下就不画。
 * 纯文字（时间点、链接不可点），点一下打开全文弹层（同「简介 展开」）。
 * 设计稿 docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md（方案 P2）。
 */
@Composable
internal fun VideoDescriptionPreview(
    desc: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodySmall
    val lineHeightPx = with(LocalDensity.current) { style.lineHeight.roundToPx() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val lines = VideoDescriptionPolicy.previewLines(constraints.maxHeight, lineHeightPx)
        if (lines > 0) {
            Text(
                desc.trim(),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen),
            )
        }
    }
}

/**
 * 简介弹层：标题「简介」+ 简介正文（弹层可上下滑、正文可长按选择复制）。
 *
 * - 时间点点击 = 跳到该处播放；BV / av 号与 B 站视频链接应用内打开，其余网址交给浏览器（切分规则见
 *   [VideoDescriptionPolicy]）。
 * - 点了时间点或链接之后弹层自动收起，免得挡着播放跳转。
 */
@Composable
internal fun VideoDescriptionSheet(
    desc: String,
    /** 当前分 P 时长，用于过滤超出时长的时间点；未知传 0。 */
    durationMs: Long,
    linkColor: Color,
    onTimestamp: (Long) -> Unit,
    onLink: (DescLinkTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val segments = remember(desc, durationMs) { VideoDescriptionPolicy.parse(desc.trim(), durationMs) }
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor))
    val annotated = buildAnnotatedString {
        segments.forEachIndexed { index, seg ->
            when (seg) {
                is DescSegment.Plain -> append(seg.text)
                is DescSegment.Timestamp ->
                    withLink(
                        LinkAnnotation.Clickable(tag = "ts:$index", styles = linkStyles) {
                            onTimestamp(seg.positionMs)
                            onDismiss()
                        },
                    ) {
                        append(seg.text)
                    }
                is DescSegment.Link ->
                    withLink(
                        LinkAnnotation.Clickable(tag = "link:$index", styles = linkStyles) {
                            onLink(seg.target)
                            onDismiss()
                        },
                    ) {
                        append(seg.text)
                    }
            }
        }
    }

    SettingsSheet(title = "简介", onDismiss = onDismiss) {
        SelectionContainer(Modifier.padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md)) {
            Text(
                annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f),
            )
        }
    }
}
