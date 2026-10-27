package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.collection.CollectionEpisode
import com.biliseek.app.collection.CollectionPolicy
import com.biliseek.app.collection.CollectionSheetRow
import com.biliseek.app.collection.VideoCollection
import com.biliseek.app.ui.components.CompactDragHandle
import com.biliseek.app.ui.components.SettingsSheetSection
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/** 播放页合集弹层的动作。宿主实现（CollectionController）。 */
data class CollectionSheetHandlers(
    val onPlayEpisode: (VideoCollection, Int) -> Unit = { _, _ -> },
    val onSetSubscribed: (VideoCollection, Boolean) -> Unit = { _, _ -> },
    /** 点正在播的那一集：不重新播，把整个合集放进播放列表。 */
    val onQueueCollection: (VideoCollection, Int) -> Unit = { _, _ -> },
)

/**
 * 播放页简介下面的合集入口卡片：封面 +「合集名」/「合集 · 第 3/24 集」+ ›，点开合集弹层。
 * 样式是用户在效果图里选的方案 B（设计稿 docs/superpowers/specs/2026-10-26-collections-design.md §4）。
 */
@Composable
internal fun CollectionEntryRow(
    collection: VideoCollection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(normalizeCoverUrl(collection.cover), width = 64.dp)
        Column(Modifier.weight(1f)) {
            Text(
                collection.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "合集 · ${CollectionPolicy.progressLabel(collection)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 合集弹层：标题区（合集名、共 N 集、订阅按钮）+ 全部集数（多个小节时带小节标题），打开时滚到当前这集。
 * 点某一集：是正在播的就不重新播、把整个合集放进播放列表，否则从这一集起播整个合集；两种都关弹层。点「已订阅」先确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionSheet(
    collection: VideoCollection,
    playingBvid: String,
    subscribed: Boolean,
    /** 已登录才显示订阅按钮。 */
    showSubscribe: Boolean,
    subscribeBusy: Boolean,
    accent: Color,
    onPlayEpisode: (Int) -> Unit,
    onSetSubscribed: (Boolean) -> Unit,
    onQueueCollection: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(collection) { CollectionPolicy.sheetRows(collection) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = CollectionPolicy.currentRowIndex(rows, collection.currentIndex),
    )
    var confirmingUnsubscribe by remember { mutableStateOf(false) }
    if (confirmingUnsubscribe) {
        // 与「列表 → 合集」内容页同一个确认框；两个键是「保留 / 取消订阅」，不用「取消」免得看混
        BsConfirmDialog(
            title = "取消订阅合集",
            text = "确定不再订阅「${collection.title}」？它会从合集列表里移除。",
            confirmLabel = "取消订阅",
            dismissLabel = "保留",
            destructive = true,
            onConfirm = {
                confirmingUnsubscribe = false
                onSetSubscribed(false)
            },
            onDismiss = { confirmingUnsubscribe = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactDragHandle() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    collection.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "共 ${collection.episodes.size} 集",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showSubscribe) {
                Spacer(Modifier.width(BsSpacing.md))
                // 按钮规范同 UP 空间的关注：未订阅 = 主操作（Button）；已订阅 = 次要（FilledTonalButton）
                if (subscribed) {
                    FilledTonalButton(onClick = { confirmingUnsubscribe = true }, enabled = !subscribeBusy) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                        Spacer(Modifier.width(BsSpacing.xs))
                        Text("已订阅")
                    }
                } else {
                    Button(onClick = { onSetSubscribed(true) }, enabled = !subscribeBusy) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                        Spacer(Modifier.width(BsSpacing.xs))
                        Text("订阅")
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(
                rows,
                key = { i, row -> if (row is CollectionSheetRow.Episode) "e${row.index}" else "h$i" },
            ) { _, row ->
                when (row) {
                    is CollectionSheetRow.Header -> SettingsSheetSection(row.title)
                    is CollectionSheetRow.Episode -> CollectionEpisodeRow(
                        episode = row.episode,
                        isCurrent = row.episode.bvid == playingBvid,
                        accent = accent,
                        onClick = {
                            if (CollectionPolicy.shouldPlayEpisode(collection, row.index, playingBvid)) {
                                onPlayEpisode(row.index)
                            } else {
                                onQueueCollection(row.index)
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 弹层里的一集：封面 + 标题 + 时长；正在播的那集用封面取色、加粗，时长前加「正在播放」。 */
@Composable
private fun CollectionEpisodeRow(
    episode: CollectionEpisode,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val duration = formatDuration(episode.durationSec * 1000)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = BsSpacing.xl, vertical = BsSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(normalizeCoverUrl(episode.cover), width = 64.dp)
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isCurrent) "正在播放 · $duration" else duration,
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
