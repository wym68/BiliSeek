package com.biliseek.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * 总结正文的 Markdown 视图，字号 / 间距按手机阅读调过。
 *
 * 库默认的 H1 是 displayLarge（57sp），一行放不下 6 个汉字；这里整体压到「标题 22 / 二级 19 / 三级 17 / 正文 16」，
 * 正文行高 26sp（中文舒服的 1.6 倍），引用（元信息）用小一号的次要色、不斜体（中文斜体是假斜体，难看）。
 */
@Composable
fun SummaryMarkdownView(content: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val body = type.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp)
    fun heading(size: Int, height: Int) =
        type.titleLarge.copy(fontSize = size.sp, lineHeight = height.sp, fontWeight = FontWeight.SemiBold)

    val typography = markdownTypography(
        h1 = heading(22, 30),
        h2 = heading(19, 27),
        h3 = heading(17, 25),
        h4 = heading(16, 24),
        h5 = heading(16, 24),
        h6 = heading(16, 24),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        quote = type.bodySmall.copy(
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontStyle = FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        // 时间戳与链接：主题色 + 半粗，不加下划线（一屏里有很多个时下划线太吵）
        textLink = TextLinkStyles(
            style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
        ),
    )
    val padding = markdownPadding(
        block = 8.dp,
        list = 4.dp,
        listItemTop = 3.dp,
        listItemBottom = 3.dp,
        listIndent = 12.dp,
        blockQuoteText = PaddingValues(vertical = 2.dp),
    )
    Markdown(
        content = content,
        typography = typography,
        padding = padding,
        modifier = modifier.fillMaxWidth(),
    )
}
