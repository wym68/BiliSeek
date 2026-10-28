package com.biliseek.app.ui

import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.VideoLibrary
import com.biliseek.designsystem.component.BsTopBar
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.biliseek.app.PendingAction
import com.biliseek.app.collection.CollectionPolicy
import com.biliseek.app.collection.EpisodeHistory
import com.biliseek.app.collection.EpisodeProgress
import com.biliseek.app.collection.EpisodeProgressPolicy
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.TrackListId
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration

/** 合集内容页多选的状态与动作（宿主实现；默认 = 不支持多选）。 */
data class CollectionSelectionUi(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
    val isLoggedIn: Boolean = false,
    val onEnter: (String) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onAction: (PendingAction) -> Unit = {},
)

/**
 * 合集内容页（M13-N）。分页拉取，滚动到底自动加载下一页。
 *
 * - 「列表」tab 里打开时标题右侧有「取消订阅」（确认后取消订阅，成功后宿主关掉本页）；UP 空间里打开的合集 / 系列不显示。
 * - 顶栏排序图标切正序 / 倒序，副标题写总集数与顺序（合集第三轮 §2）。
 * - 每行按本地播放记录写「已听完 / 听到哪里」，列表顶部有「继续听」横条（§1，样式 B + D）。
 * - 长按进多选，顶栏换成 [SelectionToolbar]，「全选」只选已加载的（§3）。
 */
@Composable
fun SeriesScreen(
    state: RemoteListState<SeriesArchive>,
    onClose: () -> Unit,
    onItemClick: (SeriesArchive) -> Unit,
    onLoadMore: (Int) -> Unit,
    /** 取消订阅这个合集；null = 不显示「取消订阅」（UP 空间里打开的合集 / 系列）。 */
    onUnsubscribe: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** 视频行右侧三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
    /** 切换正序 / 倒序；null = 不显示排序图标。 */
    onToggleSort: (() -> Unit)? = null,
    /** 本地播放记录（按 bvid）。 */
    history: Map<String, EpisodeHistory> = emptyMap(),
    /** 正在播的视频：「继续听」目标是它时不显示横条。 */
    playingBvid: String = "",
    selection: CollectionSelectionUi = CollectionSelectionUi(),
) {
    val reversed = (state.id as? TrackListId.Collection)?.reversed == true
    BackHandler(enabled = selection.active) { selection.onExit() }

    // 三点菜单：当前打开菜单的那一条
    var menuFor by remember { mutableStateOf<SearchVideo?>(null) }
    menuFor?.let { VideoMenuSheet(it, videoActions) { menuFor = null } }

    var confirmingUnsubscribe by remember { mutableStateOf(false) }
    if (confirmingUnsubscribe) {
        UnsubscribeCollectionDialog(
            title = state.title,
            onConfirm = {
                confirmingUnsubscribe = false
                onUnsubscribe?.invoke()
            },
            onDismiss = { confirmingUnsubscribe = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionToolbar(
                selectedCount = selection.selected.size,
                visibleCount = state.items.size,
                allSelected = state.items.isNotEmpty() && state.items.all { it.bvid in selection.selected },
                canAddToPlaylist = selection.isLoggedIn,
                canDownload = selection.isLoggedIn,
                onSelectAll = selection.onSelectAll,
                onClearSelection = selection.onClear,
                onAddToPlaylist = { selection.onAction(PendingAction.AddToPlaylist) },
                onAppendToQueue = { selection.onAction(PendingAction.AppendToQueue) },
                onEnqueueNext = { selection.onAction(PendingAction.EnqueueNext) },
                onDownload = { selection.onAction(PendingAction.Download) },
                onExit = selection.onExit,
            )
        } else {
            BsTopBar(
                title = state.title,
                subtitle = CollectionPolicy.sortSubtitle(state.total, state.items.size, reversed),
                onNavigate = onClose,
                actions = {
                    if (onToggleSort != null) {
                        IconButton(onClick = onToggleSort) {
                            Icon(Icons.Filled.SwapVert, contentDescription = if (reversed) "排序：倒序" else "排序：正序")
                        }
                    }
                    if (onUnsubscribe != null) {
                        TextButton(onClick = { confirmingUnsubscribe = true }) { Text("取消订阅") }
                    }
                },
            )
        }

        when {
            state.isLoading && state.items.isEmpty() -> BsLoadingState()

            state.errorMessage != null && state.items.isEmpty() ->
                BsErrorState(state.errorMessage, onRetry = { onLoadMore(1) })

            state.items.isEmpty() -> BsEmptyState("这个合集是空的", icon = Icons.Outlined.VideoLibrary)

            else -> {
                val bvids = state.items.map { it.bvid }
                val resumeIndex = remember(bvids, history) { EpisodeProgressPolicy.resumeIndex(bvids, history) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                    val target = resumeIndex?.let { state.items[it] }
                    // 横条位固定是第一项，没横条时是空项：播放记录比列表晚到，晚插进来会被挤到屏幕外
                    item(key = "resume") {
                        if (!selection.active && target != null && target.bvid != playingBvid) {
                            CollectionResumeBanner(
                                title = EpisodeProgressPolicy.resumeTitle(
                                    EpisodeProgressPolicy.episodeNumber(resumeIndex, state.total, reversed),
                                    target.title,
                                ),
                                detail = EpisodeProgressPolicy.resumeDetail(
                                    EpisodeProgressPolicy.progressOf(history[target.bvid]),
                                    target.duration,
                                ),
                                onClick = { onItemClick(target) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(state.items, key = { it.bvid }) { archive ->
                        val progress = EpisodeProgressPolicy.progressOf(history[archive.bvid])
                        val checked = archive.bvid in selection.selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selection.active) selection.onToggle(archive.bvid) else onItemClick(archive)
                                    },
                                    onLongClick = { if (!selection.active) selection.onEnter(archive.bvid) },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverThumbnail(normalizeCoverUrl(archive.cover), width = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    archive.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (progress == EpisodeProgress.Finished) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    EpisodeProgressPolicy.statusLine(progress, archive.duration)
                                        ?: (archive.author + "  " + formatDuration(archive.duration * 1000)),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (selection.active) {
                                Checkbox(checked = checked, onCheckedChange = { selection.onToggle(archive.bvid) })
                            } else {
                                VideoMenuButton { menuFor = archive.toSearchVideo() }
                            }
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