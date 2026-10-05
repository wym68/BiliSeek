package com.biliseek.app.ui

import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.outlined.PersonAdd
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import com.biliseek.core.network.dto.FollowerEntry
import com.biliseek.core.network.normalizeCoverUrl

/** "新增粉丝" 子页面：从消息中心图标进入，没有未读角标（B 站该分类无已读/未读概念）。 */
@Composable
fun FollowerScreen(
    state: RemoteListState<FollowerEntry>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenSpace: (FollowerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "新增粉丝", onNavigate = onBack)

        PullRefreshContainer(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            errorMessage = state.errorMessage,
            successText = "新增粉丝已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding(bottom = 16.dp)) {
                when {
                    state.isLoading && state.items.isEmpty() -> item { BsLoadingState() }
                    state.errorMessage != null && state.items.isEmpty() -> item {
                        BsErrorState("加载失败：${state.errorMessage}", onRetry = onRetry)
                    }
                    state.items.isEmpty() -> item { BsEmptyState("暂无新增粉丝", icon = Icons.Outlined.PersonAdd) }
                    else -> items(state.items, key = { it.mid }) { entry ->
                        FollowerRow(item = entry, onClick = { onOpenSpace(entry) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowerRow(item: FollowerEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(item.face),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.uname, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.sign.ifBlank { "关注了你" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.mtime > 0L) formatRelativeTime(item.mtime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
