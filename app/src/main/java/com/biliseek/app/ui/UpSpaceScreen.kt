package com.biliseek.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.biliseek.app.ui.formatPlayCount
import com.biliseek.app.ui.formatPubDate
import com.biliseek.core.player.formatDuration

/**
 * UP 空间页（M11-D）。
 *
 * 点 UP 搜索结果进入。双视图：**投稿**（arc/search，全可播）与 **动态**
 * （feed/space，复用动态 tab 的卡片组件）。两视图分页状态独立。
 * Ch4：头部加关注按钮，动态视图接入互动按钮。
 *
 * 投稿视图与搜索结果同一套交互：长按进复选（工具栏批量操作），
 * 右侧三点弹动作菜单（加入歌单 / 加入队列 / 下一首播放 / 复制链接 / 下载）。
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
    // Ch4
    onToggleFollow: (Long, Boolean) -> Unit = { _, _ -> },
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    // 投稿复选与动作
    isSelection: Boolean = false,
    selectedBvids: Set<String> = emptySet(),
    isLoggedIn: Boolean = false,
    onEnterSelection: (SpaceVideo) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    onAddSelectionToPlaylist: () -> Unit = {},
    onAppendSelectionToQueue: () -> Unit = {},
    onEnqueueSelectionNext: () -> Unit = {},
    onDownloadSelection: () -> Unit = {},
    onPostAddToPlaylist: (SpaceVideo) -> Unit = {},
    onPostAppendToQueue: (SpaceVideo) -> Unit = {},
    onPostPlayNext: (SpaceVideo) -> Unit = {},
    onPostCopyLink: (SpaceVideo) -> Unit = {},
    onPostDownload: (SpaceVideo) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val up = state.up
    // 复选态系统返回先退复选，再退页面（外层 closeUpSpace 的 BackHandler 兜底）
    BackHandler(enabled = isSelection) { onExitSelection() }
    // 单条三点菜单
    var actionsFor by remember { mutableStateOf<SpaceVideo?>(null) }
    actionsFor?.let { video ->
        ActionListSheet(
            title = com.biliseek.core.network.stripHighlight(video.title),
            items = listOf(
                "加入歌单" to { actionsFor = null; onPostAddToPlaylist(video) },
                "加入队列" to { actionsFor = null; onPostAppendToQueue(video) },
                "下一首播放" to { actionsFor = null; onPostPlayNext(video) },
                "复制链接" to { actionsFor = null; onPostCopyLink(video) },
                "下载" to { actionsFor = null; onPostDownload(video) },
            ),
            onDismiss = { actionsFor = null },
        )
    }
    Column(modifier.fillMaxSize()) {
        if (isSelection) {
            // 复选态整栏换工具栏（与搜索结果 / 多 P 面板同一组件）
            SelectionToolbar(
                selectedCount = selectedBvids.size,
                visibleCount = state.posts.items.size,
                allSelected = selectedBvids.size == state.posts.items.size && state.posts.items.isNotEmpty(),
                canAddToPlaylist = isLoggedIn,
                canDownload = isLoggedIn,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onAddToPlaylist = onAddSelectionToPlaylist,
                onAppendToQueue = onAppendSelectionToQueue,
                onEnqueueNext = onEnqueueSelectionNext,
                onDownload = onDownloadSelection,
                onExit = onExitSelection,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (up != null) {
                    UpHeader(
                        up = up,
                        following = state.following,
                        followingLoading = state.followingLoading,
                        onToggleFollow = onToggleFollow,
                    )
                }
                TextButton(onClick = onClose) { Text("返回") }
            }
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
                isSelection = isSelection,
                selectedBvids = selectedBvids,
                onPostClick = onPostClick,
                onLoadMore = onLoadMorePosts,
                onRetry = onRetryPosts,
                onEnterSelection = onEnterSelection,
                onToggleSelection = onToggleSelection,
                onOpenActions = { actionsFor = it },
            )
            UpSpaceView.DYNAMIC -> UpDynamicList(
                dynamic = state.dynamic,
                onEntryClick = onDynamicClick,
                onLoadMore = onLoadMoreDynamic,
                onOpenImagePreview = onOpenImagePreview,
                onOpenRepost = onOpenRepost,
                onOpenComment = onOpenComment,
                onToggleLike = onToggleLike,
            )
        }
    }
}

@Composable
private fun UpHeader(
    up: SearchUser,
    following: Boolean?,
    followingLoading: Boolean,
    onToggleFollow: (Long, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            AsyncImage(
                model = normalizeCoverUrl(up.upic),
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f)) {
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
        // 关注按钮：未拉取时一律显示"关注"，避免 UI 闪烁
        val isFollowing = following == true
        Button(
            onClick = { onToggleFollow(up.mid, !isFollowing) },
            enabled = !followingLoading,
        ) {
            Text(if (isFollowing) "✓ 已关注" else "关注")
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PostsList(
    posts: com.biliseek.app.data.RemoteListState<SpaceVideo>,
    isSelection: Boolean,
    selectedBvids: Set<String>,
    onPostClick: (SpaceVideo) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEnterSelection: (SpaceVideo) -> Unit,
    onToggleSelection: (String) -> Unit,
    onOpenActions: (SpaceVideo) -> Unit,
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
                    PostRow(
                        video = video,
                        selected = video.bvid in selectedBvids,
                        selectionMode = isSelection,
                        onClick = { if (isSelection) onToggleSelection(video.bvid) else onPostClick(video) },
                        onLongClick = { if (!isSelection) onEnterSelection(video) },
                        onToggle = { onToggleSelection(video.bvid) },
                        onOpenActions = { onOpenActions(video) },
                    )
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
private fun PostRow(
    video: SpaceVideo,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 长按进复选；复选态点击切换勾选，正常态点击播放
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面 64dp 宽（16:9），与搜索结果条目一致（M11 真机反馈）
        CoverThumbnail(normalizeCoverUrl(video.pic), width = 64.dp)
        Column(Modifier.weight(1f)) {
            Text(
                com.biliseek.core.network.stripHighlight(video.title),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 元信息 FlowRow：与搜索结果同款，空间不够时整个词条换行不拆字
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(video.author, style = MaterialTheme.typography.bodySmall)
                Text(
                    formatDuration(
                        com.biliseek.core.network.parseDurationText(video.length) * 1000,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${formatPlayCount(video.play)} 播放",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 发布时间：与搜索页同一格式化（当天显示时间、一周内 X天前……）
                val pubDateText = formatPubDate(video.created)
                if (pubDateText.isNotEmpty()) {
                    Text(pubDateText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (selectionMode) {
            androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onToggle() })
        } else {
            IconButton(onClick = onOpenActions) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun UpDynamicList(
    dynamic: com.biliseek.app.data.RemoteListState<DynamicEntry>,
    onEntryClick: (DynamicEntry) -> Unit,
    onLoadMore: () -> Unit,
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
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
                    DynamicCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                        onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                        onRepostClick = { onOpenRepost(entry.idStr) },
                        onCommentClick = { onOpenComment(entry) },
                        onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status == true) },
                    )
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
