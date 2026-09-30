package com.biliseek.app.ui

import androidx.compose.material.icons.outlined.DynamicFeed
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.biliseek.designsystem.component.BsTopBar
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.biliseek.app.data.DynamicExpandState
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
 * 右侧三点弹动作菜单（加入列表 / 加入队列 / 下一首播放 / 复制链接 / 下载）。
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
    // 头像/昵称跳 UP 主页；正文原地展开/收起（四级降级拉详情）
    onAuthorClick: (DynamicEntry) -> Unit = {},
    dynamicExpand: Map<String, DynamicExpandState> = emptyMap(),
    onToggleExpand: (DynamicEntry) -> Unit = {},
    // 视频动态长按菜单（与动态 tab 共用同一份动作）
    videoActions: DynamicVideoActions = DynamicVideoActions(),
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
    onPostSummarize: (SpaceVideo) -> Unit = {},
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
                "加入列表" to { actionsFor = null; onPostAddToPlaylist(video) },
                "加入队列" to { actionsFor = null; onPostAppendToQueue(video) },
                "下一首播放" to { actionsFor = null; onPostPlayNext(video) },
                "复制链接" to { actionsFor = null; onPostCopyLink(video) },
                "下载" to { actionsFor = null; onPostDownload(video) },
                "AI 总结" to { actionsFor = null; onPostSummarize(video) },
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
            BsTopBar(title = "UP 主空间", onNavigate = onClose)
            if (up != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = BsSpacing.lg).padding(bottom = BsSpacing.md)) {
                    UpHeader(
                        up = up,
                        following = state.following,
                        followingLoading = state.followingLoading,
                        onToggleFollow = onToggleFollow,
                    )
                }
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
                onAuthorClick = onAuthorClick,
                dynamicExpand = dynamicExpand,
                onToggleExpand = onToggleExpand,
                videoActions = videoActions,
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
                // 粉丝数：从关注列表进入时 fans 暂为 0（userCard 补拉中），先显示占位
                Text(
                    if (up.fans > 0) "粉丝 ${formatFans(up.fans)}" else "粉丝加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 关注按钮：未拉取时一律显示"关注"，避免 UI 闪烁
        // 按钮规范：未关注 = 主操作（Button）；已关注 = 次要（FilledTonalButton），取关同一个按钮
        val isFollowing = following == true
        if (isFollowing) {
            FilledTonalButton(
                onClick = { onToggleFollow(up.mid, false) },
                enabled = !followingLoading,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                Spacer(Modifier.width(BsSpacing.xs))
                Text("已关注")
            }
        } else {
            Button(
                onClick = { onToggleFollow(up.mid, true) },
                enabled = !followingLoading,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                Spacer(Modifier.width(BsSpacing.xs))
                Text("关注")
            }
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
        posts.isLoading && posts.items.isEmpty() -> BsLoadingState()
        posts.errorMessage != null && posts.items.isEmpty() -> BsErrorState(posts.errorMessage, onRetry = onRetry)
        posts.items.isEmpty() -> BsEmptyState("这个 UP 还没有投稿", icon = Icons.Outlined.VideoLibrary)
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
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
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
    onAuthorClick: (DynamicEntry) -> Unit = {},
    dynamicExpand: Map<String, DynamicExpandState> = emptyMap(),
    onToggleExpand: (DynamicEntry) -> Unit = {},
    // 视频动态长按菜单（与动态 tab 共用同一份动作）
    videoActions: DynamicVideoActions = DynamicVideoActions(),
) {
    when {
        dynamic.isLoading && dynamic.items.isEmpty() -> BsLoadingState()
        dynamic.errorMessage != null && dynamic.items.isEmpty() ->
            BsErrorState("${dynamic.errorMessage}\n需登录才能查看 TA 的动态", onRetry = null)
        dynamic.items.isEmpty() -> BsEmptyState("没有动态", icon = Icons.Outlined.DynamicFeed)
        else -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(dynamic.items, key = { it.idStr }) { entry ->
                    DynamicCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                        onAuthorClick = { onAuthorClick(entry) },
                        expandState = dynamicExpand[entry.idStr],
                        onToggleExpand = { onToggleExpand(entry) },
                        onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                        onRepostClick = { onOpenRepost(entry.idStr) },
                        onCommentClick = { onOpenComment(entry) },
                        onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                        videoActions = videoActions,
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
