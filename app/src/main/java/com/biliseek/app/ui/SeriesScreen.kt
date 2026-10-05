package com.biliseek.app.ui

import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.RemoteListState
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration

/**
 * 合集内容页（M13-N）。分页拉取，滚动到底自动加载下一页。
 *
 * 结构与 [FavoriteDetail] 一致。标题右侧「取消订阅」：确认后取消订阅这个合集（成功后宿主关掉本页）。
 */
@Composable
fun SeriesScreen(
    state: RemoteListState<SeriesArchive>,
    onClose: () -> Unit,
    onItemClick: (SeriesArchive) -> Unit,
    onLoadMore: (Int) -> Unit,
    onUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
    /** 视频行右侧三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
) {
    // 三点菜单：当前打开菜单的那一条
    var menuFor by remember { mutableStateOf<SearchVideo?>(null) }
    menuFor?.let { VideoMenuSheet(it, videoActions) { menuFor = null } }

    var confirmingUnsubscribe by remember { mutableStateOf(false) }
    if (confirmingUnsubscribe) {
        // 两个键是「保留 / 取消订阅」：不用「取消」，免得和「取消订阅」看混
        BsConfirmDialog(
            title = "取消订阅合集",
            text = "确定不再订阅「${state.title}」？它会从合集列表里移除。",
            confirmLabel = "取消订阅",
            dismissLabel = "保留",
            destructive = true,
            onConfirm = {
                confirmingUnsubscribe = false
                onUnsubscribe()
            },
            onDismiss = { confirmingUnsubscribe = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        BsTopBar(
            title = state.title,
            subtitle = "${state.items.size} 首",
            onNavigate = onClose,
            actions = {
                TextButton(onClick = { confirmingUnsubscribe = true }) { Text("取消订阅") }
            },
        )

        when {
            state.isLoading && state.items.isEmpty() -> BsLoadingState()

            state.errorMessage != null && state.items.isEmpty() ->
                BsErrorState(state.errorMessage, onRetry = { onLoadMore(1) })

            state.items.isEmpty() -> BsEmptyState("这个合集是空的", icon = Icons.Outlined.VideoLibrary)

            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                    items(state.items, key = { it.bvid }) { archive ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(archive) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverThumbnail(normalizeCoverUrl(archive.cover), width = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    archive.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    archive.author + "  " +
                                        formatDuration(archive.duration * 1000),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            VideoMenuButton { menuFor = archive.toSearchVideo() }
                        }
                        HorizontalDivider()
                    }

                    if (state.hasMore || state.isLoadingMore) {
                        item(key = "footer") {
                            // 失败后不自动再来（否则会一直重打 B 站），等用户点「重试」
                            if (shouldAutoLoadMore(state.hasMore, state.isLoadingMore, state.errorMessage)) {
                                LaunchedEffect(state.page) { onLoadMore(state.page + 1) }
                            }
                            Box(
                                Modifier.fillMaxWidth().padding(12.dp),
                                Alignment.Center,
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    state.errorMessage?.let { msg ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                msg,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                            TextButton(onClick = { onLoadMore(state.page + 1) }) {
                                                Text("重试")
                                            }
                                        }
                                    } ?: Text(
                                        "加载中…",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}