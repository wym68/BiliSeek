package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 图文动态多图宫格（Ch4 §4.1.2）。
 * - 1 张图：单图占位（最大宽 240dp，1.5:1）；
 * - 2-9 张：3 列 9 宫格，每格 1:1，间距 4dp，圆角 4dp；
 * - ≥10 张：前 9 张 + 最后一格「+N」角标（仍可点击进入第 N 张全屏预览）。
 * 点击任意图 → [onImageClick](index)。
 *
 * 用普通 Column + Row 而非 LazyVerticalGrid：宫格最多 9 个固定 96dp 单元格，
 * 懒加载没有意义；且 LazyColumn 条目以无限高度测量子项，
 * 嵌套可纵向滚动的 LazyVerticalGrid 会直接崩溃（动态「全部」模式图文帖触发）。
 */
@Composable
fun DrawGrid(
    pics: List<String>,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pics.isEmpty()) return
    if (pics.size == 1) {
        Box(modifier.fillMaxWidth()) {
            AsyncImage(
                model = pics[0],
                contentDescription = null,
                modifier = Modifier
                    .size(width = 240.dp, height = 160.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImageClick(0) },
                contentScale = ContentScale.Crop,
            )
        }
        return
    }
    val showCount = minOf(pics.size, 9)
    val overflow = pics.size - showCount
    // 每行 3 格，从左到右、从上到下排布（与原 3 列网格视觉一致）
    val rows = (showCount + 2) / 3
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 3) {
                    val idx = row * 3 + col
                    if (idx >= showCount) break
                    val isOverflowCell = idx == showCount - 1 && overflow > 0
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onImageClick(idx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = pics[idx],
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        if (isOverflowCell) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    "+$overflow",
                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
