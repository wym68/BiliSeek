package com.biliseek.app.ui.richtext

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.biliseek.core.network.dto.Emote
import com.biliseek.designsystem.theme.BsSize

/**
 * 进程级表情字典（`[doge]` → 图片），由表情面板加载后在根部提供。
 * 不下发表情表的接口（回复与@、私信摘要）靠它补齐常用表情；未加载时为空，退化为原文。
 */
val LocalEmoteDictionary = compositionLocalOf<Map<String, Emote>> { emptyMap() }

/** 富文本里可点元素的回调。任一为 null 时对应元素只高亮不可点。 */
data class RichTextActions(
    val onMention: ((mid: Long) -> Unit)? = null,
    val onKeyword: ((keyword: String) -> Unit)? = null,
)

/**
 * 富文本：表情图行内显示、@ / 关键词 / 链接高亮可点。
 *
 * - 行内表情用 [InlineTextContent] 占位，尺寸跟随字号（1.35em），与文字基线居中对齐；
 * - 整条只有一个大表情时单独放大显示（[BsSize.emoteLarge]），与官方一致；
 * - 链接走系统 UriHandler（[LinkAnnotation.Url]），@ / 关键词走 [actions]。
 *
 * [prefix] 插在正文之前（楼中楼的「昵称：」）。
 */
@Composable
fun RichText(
    source: RichTextSource,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    actions: RichTextActions = RichTextActions(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    prefix: (AnnotatedString.Builder.() -> Unit)? = null,
) {
    val dictionary = LocalEmoteDictionary.current
    val segments = remember(source, dictionary) { RichTextPolicy.parse(source, dictionary) }

    if (prefix == null && RichTextPolicy.isStandaloneLargeEmote(segments)) {
        Column(modifier) {
            segments.filterIsInstance<RichSegment.EmoteImage>().forEach { emote ->
                AsyncImage(
                    model = emote.url,
                    contentDescription = emote.key,
                    modifier = Modifier.size(BsSize.emoteLarge),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        return
    }

    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor))
    val annotated = buildAnnotatedString {
        prefix?.invoke(this)
        segments.forEach { seg ->
            when (seg) {
                is RichSegment.Plain -> append(seg.text)
                // 同一个表情多次出现共用一个 inline id（key 本身），占位图只注册一次
                is RichSegment.EmoteImage -> appendInlineContent(inlineId(seg.key), seg.key)
                is RichSegment.Mention -> {
                    val onMention = actions.onMention
                    if (onMention != null && seg.mid > 0) {
                        withLink(LinkAnnotation.Clickable(tag = "at:${seg.mid}", styles = linkStyles) { onMention(seg.mid) }) {
                            append(seg.text)
                        }
                    } else {
                        pushStyle(SpanStyle(color = linkColor)); append(seg.text); pop()
                    }
                }
                is RichSegment.Keyword -> {
                    val onKeyword = actions.onKeyword
                    if (onKeyword != null) {
                        val keyword = seg.text.trim('#')
                        withLink(LinkAnnotation.Clickable(tag = "kw:$keyword", styles = linkStyles) { onKeyword(keyword) }) {
                            append(seg.text)
                        }
                    } else {
                        pushStyle(SpanStyle(color = linkColor)); append(seg.text); pop()
                    }
                }
                is RichSegment.Link -> withLink(LinkAnnotation.Url(seg.url, styles = linkStyles)) { append(seg.url) }
            }
        }
    }

    val inlineContent = remember(segments) {
        segments.filterIsInstance<RichSegment.EmoteImage>()
            .distinctBy { it.key }
            .associate { emote ->
                inlineId(emote.key) to InlineTextContent(
                    Placeholder(
                        width = EMOTE_EM.em,
                        height = EMOTE_EM.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    AsyncImage(
                        model = emote.url,
                        contentDescription = emote.key,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
    }

    Text(
        annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout,
    )
}

/** 纯文本便捷重载：只有正文 + 表情表。 */
@Composable
fun RichText(
    text: String,
    emotes: Map<String, Emote>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    RichText(
        source = RichTextSource(text = text, emotes = emotes),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private const val EMOTE_EM = 1.35f

private fun inlineId(key: String) = "emote:$key"
