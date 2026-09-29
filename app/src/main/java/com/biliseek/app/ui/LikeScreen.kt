package com.biliseek.app.ui

import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.outlined.ThumbUp
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.biliseek.core.network.likeActionText
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
        BsTopBar(title = "收到喜欢", onNavigate = onBack)

        PullRefreshContainer(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            errorMessage = state.errorMessage,
            successText = "收到喜欢已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                when {
                    state.isLoading && state.items.isEmpty() -> item { BsLoadingState() }
                    state.errorMessage != null && state.items.isEmpty() -> item {
                        BsErrorState("加载失败：${state.errorMessage}", onRetry = onRetry)
                    }
                    state.items.isEmpty() -> item { BsEmptyState("还没有收到喜欢", icon = Icons.Outlined.ThumbUp) }
                    else -> items(state.items, key = { it.id }) { entry ->
                        LikeRow(item = entry, onClick = { onPlayItem(entry) })
                        HorizontalDivider()
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
            val name = item.firstUser?.name.orEmpty()
            val who = if (item.counts > 1) "$name 等 ${item.counts} 人" else name
            Text(who, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.likeActionText()}：${item.title}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.likeTime > 0L) formatRelativeTime(item.likeTime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
