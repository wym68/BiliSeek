package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetDefaults
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
import com.biliseek.app.collection.EpisodeHistory
import com.biliseek.app.collection.EpisodeProgress
import com.biliseek.app.collection.EpisodeProgressPolicy
import com.biliseek.app.collection.VideoCollection
import com.biliseek.app.ui.components.CompactDragHandle
import com.biliseek.app.ui.components.SettingsSheetSection
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 播放页合集弹层的动作。宿主实现（CollectionController）。 */
data class CollectionSheetHandlers(
    val onPlayEpisode: (VideoCollection, Int) -> Unit = { _, _ -> },
    val onSetSubscribed: (VideoCollection, Boolean) -> Unit = { _, _ -> },
    /** 点正在播的那一集：不重新播，把整个合集放进播放列表。 */
    val onQueueCollection: (VideoCollection, Int) -> Unit = { _, _ -> },
    /** 这些视频的本地播放记录（宿主转给 CollectionController.historyOf）。 */
    val historyOf: (List<String>) -> Flow<Map<String, EpisodeHistory>> = { flowOf(emptyMap()) },
)

/**
 * 播放页互动栏下面、简介行上面的合集入口卡片：封面 +「合集名」/「合集 · 第 3/24 集」+ ›，点开合集弹层。
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
 * 合集弹层：标题区（合集名、共 N 集、订阅按钮）+ 全部集数（多个小节时带吸顶的小节标题），打开时滚到当前这集。
 * 合集名下面固定一条「继续听」横条（不随列表滚），指向「跳到正在播的这集之前听到哪」；
 * 没有这样的记录、或接下来就是正在播的这集时没有横条（[EpisodeProgressPolicy.sheetResumeIndex]）。各集标出已听完 / 听到哪。
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
    /** 各集的本地播放记录（bvid → 最近一条）；异步读，比列表晚到。 */
    history: Map<String, EpisodeHistory> = emptyMap(),
) {
    val rows = remember(collection) { CollectionPolicy.sheetRows(collection) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = CollectionPolicy.sheetInitialItem(rows, collection.currentIndex),
    )
    val bvids = remember(collection) { collection.episodes.map { it.bvid } }
    val resumeIndex = EpisodeProgressPolicy.sheetResumeIndex(bvids, history, playingBvid)
    var confirmingUnsubscribe by remember { mutableStateOf(false) }
    if (confirmingUnsubscribe) {
        UnsubscribeCollectionDialog(
            title = collection.title,
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
        // 「继续听」固定在合集名下面、不随列表滚，打开弹层就看得见（播放记录晚到时整个列表往下让一点）
        if (resumeIndex != null) {
            val target = collection.episodes[resumeIndex]
            CollectionResumeBanner(
                title = EpisodeProgressPolicy.resumeTitle(resumeIndex + 1, target.title),
                detail = EpisodeProgressPolicy.resumeDetail(
                    EpisodeProgressPolicy.progressOf(history[target.bvid]),
                    target.durationSec,
                ),
                onClick = {
                    onPlayEpisode(resumeIndex)
                    onDismiss()
                },
                modifier = Modifier.padding(start = BsSpacing.xl, end = BsSpacing.xl, bottom = BsSpacing.md),
            )
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, row ->
                when (row) {
                    is CollectionSheetRow.Header -> stickyHeader(key = "h$i") {
                        // 吸顶：背景同弹层底色，盖住下面滚上来的行
                        Box(Modifier.fillMaxWidth().background(BottomSheetDefaults.ContainerColor)) {
                            SettingsSheetSection(row.title)
                        }
                    }
                    is CollectionSheetRow.Episode -> item(key = "e${row.index}") {
                        CollectionEpisodeRow(
                            episode = row.episode,
                            isCurrent = row.episode.bvid == playingBvid,
                            progress = EpisodeProgressPolicy.progressOf(history[row.episode.bvid]),
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
}

/**
 * 弹层里的一集：封面 + 标题 + 时长；正在播的那集用封面取色、加粗，时长前加「正在播放」。
 * 其他集按本地播放记录：听完的标题变灰、第二行「✓ 已听完 · 时长」，听到一半的写「听到 …」。
 */
@Composable
private fun CollectionEpisodeRow(
    episode: CollectionEpisode,
    isCurrent: Boolean,
    progress: EpisodeProgress,
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
                color = when {
                    isCurrent -> accent
                    progress == EpisodeProgress.Finished -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isCurrent) {
                    "正在播放 · $duration"
                } else {
                    EpisodeProgressPolicy.statusLine(progress, episode.durationSec) ?: duration
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「取消订阅合集」确认框：合集内容页与合集弹层共用。两个键是「保留 / 取消订阅」，不用「取消」免得和「取消订阅」看混。 */
@Composable
internal fun UnsubscribeCollectionDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BsConfirmDialog(
        title = "取消订阅合集",
        text = "确定不再订阅「$title」？它会从合集列表里移除。",
        confirmLabel = "取消订阅",
        dismissLabel = "保留",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * 「继续听」横条（样式 D）：内容页里是列表第一项，弹层里固定在合集名下面；secondaryContainer、12dp 圆角。
 * 合集弹层与合集内容页共用；第一行「继续听 · 第 N 集 标题」，第二行进度或「从头开始」。
 */
@Composable
internal fun CollectionResumeBanner(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}
