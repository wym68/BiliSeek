package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 列表里的 16:9 封面缩略图。
 *
 * 地址为空时渲染一块占位底色而不是留空——否则列表行高会随封面有无跳动。
 *
 * [badge] 左上角角标文字（如「充电」）。非空时显示红底白字小标签。
 */
@Composable
fun CoverThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(4.dp)
    val base = modifier
        .width(width)
        .aspectRatio(16f / 9f)
        .clip(shape)

    if (url.isBlank()) {
        Box(base.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    Box(base) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        // 左上角角标：B站粉色底 + 白字
        if (!badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(
                        Color(0xFFFB7299),
                        RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                androidx.compose.material3.Text(
                    text = badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
