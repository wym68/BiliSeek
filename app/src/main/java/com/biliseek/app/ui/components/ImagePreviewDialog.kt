package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage

/**
 * 全屏图片预览（Ch4 §4.1.3）。
 *
 * 简化实现（工单 4.9 #5）：直接全屏切换，不做 Rect→offset 缩放动画。HorizontalPager 滑动切换；
 * 顶部：当前/总数 + 关闭；底部：「保存到相册」占位。
 */
@Composable
fun ImagePreviewDialog(
    pics: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    val state = rememberPagerState(initialPage = initialIndex.coerceIn(0, (pics.size - 1).coerceAtLeast(0))) { pics.size }
    LaunchedEffect(pics.size) { state.scrollToPage(initialIndex.coerceIn(0, pics.lastIndex)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss),
    ) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = pics[page],
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black),
                contentScale = ContentScale.Fit,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.currentPage + 1}/${pics.size}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "关闭",
                Modifier.clickable(onClick = onDismiss).padding(8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // 底部「保存到相册」占位（工单 4.9 #5）
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "保存到相册（未实装）",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}