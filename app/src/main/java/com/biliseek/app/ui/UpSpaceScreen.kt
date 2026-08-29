package com.biliseek.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.UpSpaceUiState
import com.biliseek.app.UpSpaceView
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration

/**
 * UP 空间页（M11-D）。
 *
 * 点 UP 搜索结果进入。双视图：**投稿**（arc/search，全可播）与 **动态**
 * （feed/space，复用动态 tab 的卡片组件）。两视图分页状态独立。
 */
@Composable
fun UpSpaceScreen(
    state: UpSpaceUiState,
    onClose: () -> Unit,
    onViewChange: (UpSpaceView) -> Unit,
    onPostClick: (SpaceVideo) -> Unit,
    onLoadMorePosts: () -> Unit,
    onRetryPosts: () -> Unit = {},
    onDynamicClick: (DynamicEntry) -> Unit,
    onLoadMoreDynamic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val up = state.up
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (up != null) {
                UpHeader(up)
            }
            TextButton(onClick = onClose) { Text("返回") }
        }
        HorizontalDivider()

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = state.view == UpSpaceView.POSTS,
                onClick = { onViewChange(UpSpaceView.POSTS) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("投稿") }
            SegmentedButton(
                selected = state.view == UpSpaceView.DYNAMIC,
                onClick = { onViewChange(UpSpaceView.DYNAMIC) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("动态") }
        }

        when (state.view) {
            UpSpaceView.POSTS -> PostsList(
                posts = state.posts,
                onPostClick = onPostClick,
                onLoadMore = onLoadMorePosts,
                onRetry = onRetryPosts,
            )
            UpSpaceView.DYNAMIC -> UpDynamicList(
                dynamic = state.dynamic,
                onEntryClick = onDynamicClick,
                onLoadMore = onLoadMoreDynamic,
            )
        }
    }
}

@Composable
private fun UpHeader(up: SearchUser) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(up.upic),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column {
            Text(
                up.uname,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                up.usign,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PostsList(
    posts: com.biliseek.app.data.RemoteListState<SpaceVideo>,
    onPostClick: (SpaceVideo) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        posts.isLoading && posts.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        posts.errorMessage != null && posts.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(posts.errorMessage, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onRetry) { Text("重试") }
            }
        }
        posts.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("这个 UP 还没有投稿", style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(posts.items, key = { it.bvid }) { video ->
                    PostRow(video, onClick = { onPostClick(video) })
                    HorizontalDivider()
                }
                if (posts.hasMore || posts.isLoadingMore) {
                    item(key = "footer") {
                        if (posts.hasMore && !posts.isLoadingMore) {
                            LaunchedEffect(posts.items.size) { onLoadMore() }
                        }
                        Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                            if (posts.isLoadingMore) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("加载中…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostRow(video: SpaceVideo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(normalizeCoverUrl(video.pic), width = 96.dp)
        Column(Modifier.weight(1f)) {
            Text(
                com.biliseek.core.network.stripHighlight(video.title),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                video.author + "  " + formatDuration(
                    com.biliseek.core.network.parseDurationText(video.length) * 1000,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun UpDynamicList(
    dynamic: com.biliseek.app.data.RemoteListState<DynamicEntry>,
    onEntryClick: (DynamicEntry) -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        dynamic.isLoading && dynamic.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        dynamic.errorMessage != null && dynamic.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dynamic.errorMessage, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                Text("需登录才能查看 TA 的动态", style = MaterialTheme.typography.labelSmall)
            }
        }
        dynamic.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("没有动态", style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(dynamic.items, key = { it.idStr }) { entry ->
                    DynamicCard(entry, onClick = { onEntryClick(entry) })
                    HorizontalDivider()
                }
                if (dynamic.hasMore || dynamic.isLoadingMore) {
                    item(key = "footer") {
                        if (dynamic.hasMore && !dynamic.isLoadingMore) {
                            LaunchedEffect(dynamic.items.size) { onLoadMore() }
                        }
                        Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                            if (dynamic.isLoadingMore) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("加载中…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
