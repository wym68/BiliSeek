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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** 封面右上角的角标：白字 + 底色（如粉色「充电」，规则见 `ChargeBadgePolicy`）。 */
data class CoverBadge(val text: String, val color: Color)

/**
 * 列表里的 16:9 封面缩略图。
 *
 * 地址为空时渲染一块占位底色而不是留空——否则列表行高会随封面有无跳动。
 *
 * [badge] 右上角角标（如粉色「充电」），见 [CoverBadgeLabel]。
 */
@Composable
fun CoverThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    badge: CoverBadge? = null,
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
        if (badge != null) {
            CoverBadgeLabel(badge, small = width < SMALL_COVER_WIDTH, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * 封面右上角的角标（设计稿：2026-10-04-charge-badge，用户选的「短字」样式）：
 * 底色 + 白字，右上、左下两个角圆 4dp，贴着封面右上角；窄封面（< 80dp）用 9sp，其余 10sp。
 * 动态卡片的视频封面不是 [CoverThumbnail]，直接用它叠上去。
 */
@Composable
fun CoverBadgeLabel(badge: CoverBadge, small: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(badge.color, RoundedCornerShape(topEnd = 4.dp, bottomStart = 4.dp))
            .padding(horizontal = if (small) 3.dp else 4.dp, vertical = 1.dp),
    ) {
        androidx.compose.material3.Text(
            text = badge.text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = if (small) 9.sp else 10.sp,
                lineHeight = if (small) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            ),
        )
    }
}

/** 窄于这个宽度的封面用小一号的角标（搜索结果、UP 空间的 64dp 封面）。 */
private val SMALL_COVER_WIDTH = 80.dp
