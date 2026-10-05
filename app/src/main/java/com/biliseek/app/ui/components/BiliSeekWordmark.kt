package com.biliseek.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.biliseek.app.R

internal val DmSerifDisplay = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.dm_serif_display_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * 首页标题「BiliSeek」（设计稿：docs/superpowers/specs/2026-10-02-home-header-polish-design.md）。
 *
 * DM Serif Display：「Bili」正体，「Seek」斜体并取主题强调色，
 * 所以莫奈取色、深色模式、封面强调色都会带着它一起变。单个 Text，读屏仍读作「BiliSeek」。
 */
@Composable
fun BiliSeekWordmark(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append("Bili")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = accent)) { append("Seek") }
        },
        modifier = modifier,
        style = TextStyle(
            fontFamily = DmSerifDisplay,
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.5).sp,
        ),
    )
}
