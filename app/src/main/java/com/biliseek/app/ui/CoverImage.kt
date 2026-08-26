package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 列表里的 16:9 封面缩略图。
 *
 * 地址为空时渲染一块占位底色而不是留空——否则列表行高会随封面有无跳动。
 */
@Composable
fun CoverThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
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
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = base,
    )
}
