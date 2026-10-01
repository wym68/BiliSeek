package com.biliseek.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/**
 * 播放页简介区：「简介」+ 统计行（播放量 · 发布时间），下面是简介正文。
 *
 * - 正文默认显示 [VideoDescriptionPolicy.COLLAPSED_LINES] 行，超出时给「展开全部」，展开后可「收起」；
 *   页面中段本身可滑动（见 PlayerScreen），长简介展开后滑着看。
 * - 时间点点击 = 跳到该处播放；BV / av 号与 B 站视频链接应用内打开，其余网址交给浏览器（切分规则见
 *   [VideoDescriptionPolicy]）。
 * - 正文可长按选择、复制（[SelectionContainer]，链接照常可点）。
 * - 没有简介（空 / 单个 `-`）时只显示统计行；两者都没有时整块不显示（本地文件、详情未加载）。
 */
@Composable
internal fun VideoDescriptionSection(
    desc: String,
    viewCount: Long,
    pubDate: Long,
    /** 当前分 P 时长，用于过滤超出时长的时间点；未知传 0。 */
    durationMs: Long,
    onTimestamp: (Long) -> Unit,
    onLink: (DescLinkTarget) -> Unit,
    linkColor: Color,
    modifier: Modifier = Modifier,
) {
    val showDesc = VideoDescriptionPolicy.shouldShow(desc)
    val stats = remember(viewCount, pubDate) {
        VideoDescriptionPolicy.statsLine(viewCount, formatPubDate(pubDate))
    }
    if (!showDesc && stats.isEmpty()) return

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showDesc) {
                Text(
                    "简介",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            }
            Spacer(Modifier.weight(1f))
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
        if (showDesc) {
            var expanded by remember(desc) { mutableStateOf(false) }
            var overflowing by remember(desc) { mutableStateOf(false) }
            val segments = remember(desc, durationMs) { VideoDescriptionPolicy.parse(desc.trim(), durationMs) }
            val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor))
            val annotated = buildAnnotatedString {
                segments.forEachIndexed { index, seg ->
                    when (seg) {
                        is DescSegment.Plain -> append(seg.text)
                        is DescSegment.Timestamp ->
                            withLink(LinkAnnotation.Clickable(tag = "ts:$index", styles = linkStyles) { onTimestamp(seg.positionMs) }) {
                                append(seg.text)
                            }
                        is DescSegment.Link ->
                            withLink(LinkAnnotation.Clickable(tag = "link:$index", styles = linkStyles) { onLink(seg.target) }) {
                                append(seg.text)
                            }
                    }
                }
            }

            SelectionContainer(Modifier.padding(top = 4.dp)) {
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f),
                    maxLines = if (expanded) Int.MAX_VALUE else VideoDescriptionPolicy.COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                )
            }
            if (overflowing || expanded) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "收起" else "展开全部",
                        style = MaterialTheme.typography.labelLarge,
                        color = linkColor,
                    )
                }
            }
        }
    }
}
