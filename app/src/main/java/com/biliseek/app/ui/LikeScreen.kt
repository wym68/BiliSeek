package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.normalizeCoverUrl

/** "收到喜欢" 子页面：从消息中心图标进入。 */
@Composable
fun LikeScreen(
    state: RemoteListState<LikeItem>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlayItem: (LikeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "收到喜欢",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    when {
                        state.isLoading && state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.errorMessage != null && state.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无内容")
                            }
                        }
                        else -> items(state.items, key = { it.id }) { entry ->
                            LikeRow(item = entry, onClick = { onPlayItem(entry) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LikeRow(item: LikeItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(item.firstUser?.face.orEmpty()),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.firstUser?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("赞了你的视频：${item.title}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.likeTime > 0L) formatRelativeTime(item.likeTime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
