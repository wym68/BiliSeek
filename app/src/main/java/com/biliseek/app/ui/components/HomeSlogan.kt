package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 字形在行高里上下居中（与 CSS 半行距一致），效果图与真机排版才对得上。 */
private val CenteredLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * 首页新用户引导：引文式 slogan（设计稿：docs/superpowers/specs/2026-10-02-home-slogan-design.md）。
 *
 * 大引号 + 一行 DM Serif Display 斜体「Seek and you shall find.」+ 短横线引出中文副句。
 * 颜色全取主题色，所以莫奈取色、深色模式、封面强调色都会带着它一起变。
 */
@Composable
fun HomeSlogan(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // 大引号是装饰图形，字号按 dp 换算成 sp，不随系统字体大小放大
    val quoteSize = with(LocalDensity.current) { 120.dp.toSp() }
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = "“",
            modifier = Modifier.offset(x = (-4).dp, y = (-4).dp).clearAndSetSemantics {},
            color = colors.primary.copy(alpha = 0.3f),
            style = TextStyle(
                fontFamily = DmSerifDisplay,
                fontSize = quoteSize,
                lineHeight = quoteSize,
                lineHeightStyle = CenteredLine,
            ),
        )
        Column(Modifier.padding(top = 50.dp, bottom = 20.dp)) {
            Text(
                text = "Seek and you shall find.",
                color = colors.onSurface,
                style = TextStyle(
                    fontFamily = DmSerifDisplay,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    letterSpacing = (-0.3).sp,
                    lineHeightStyle = CenteredLine,
                ),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 28.dp, height = 2.dp)
                        .background(colors.primary, RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "探索你的 B 站",
                    color = colors.onSurfaceVariant,
                    style = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        letterSpacing = 3.sp,
                        lineHeightStyle = CenteredLine,
                    ),
                )
            }
        }
    }
}
