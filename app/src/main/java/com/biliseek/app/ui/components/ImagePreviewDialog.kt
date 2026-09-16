package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import kotlinx.coroutines.launch

@Composable
fun ImagePreviewDialog(
    pics: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onSaveImage: (String) -> Unit = {},
) {
    val state = rememberPagerState(initialPage = initialIndex.coerceIn(0, (pics.size - 1).coerceAtLeast(0))) { pics.size }
    LaunchedEffect(pics.size) { state.scrollToPage(initialIndex.coerceIn(0, pics.lastIndex)) }

    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
    ) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(
                imageUrl = pics[page],
                onDismiss = onDismiss,
                onLongPress = {
                    onSaveImage(pics[state.currentPage])
                },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = systemBarsPadding.calculateTopPadding() + 16.dp, start = 24.dp, end = 24.dp),
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
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = systemBarsPadding.calculateBottomPadding() + 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "保存到相册",
                Modifier
                    .clickable { onSaveImage(pics[state.currentPage]) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    imageUrl: String,
    onDismiss: () -> Unit,
    onLongPress: () -> Unit,
) {
    val zoomState = rememberCoilZoomState()
    val coroutineScope = rememberCoroutineScope()

    var lastTapTime by remember { mutableStateOf(0L) }
    val doubleTapInterval = 300L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        val currentTime = System.currentTimeMillis()
                        val scaleX = zoomState.zoomable.transform.scaleX
                        val isZoomed = scaleX > 1.01f

                        if (isZoomed) {
                            coroutineScope.launch {
                                zoomState.zoomable.scale(1.0f, animated = true)
                            }
                        } else {
                            if (currentTime - lastTapTime < doubleTapInterval) {
                                coroutineScope.launch {
                                    zoomState.zoomable.scale(2.5f, animated = true)
                                }
                            } else {
                                onDismiss()
                            }
                        }
                        lastTapTime = currentTime
                    },
                    onLongPress = { onLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CoilZoomAsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            zoomState = zoomState,
        )
    }
}
