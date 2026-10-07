package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.hint.HintKey
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 播放列表 sheet（Ch5 §5.1.3，docs/10-播放队列工单.md）。
 *
 * - 固定占屏高 70%、一打开就是完整高度、没有拖动条（标题行有「关闭」，下拉照样能关）；
 *   打开时列表定位到正在播的那一项
 * - 标题行：「播放队列 · N 首」+「清空队列」+ 关闭；下面一行常驻提示（图钉含义 + 右滑删除）
 * - 每行：固定标志（图钉）、序号（01/02…）、标题（单行省略）、时长。
 *   图钉 = 用户用「加入队列」「下一首播放」加的，点击播放不会替换；没有图钉的是点击带进来的临时条目
 * - 当前播放高亮（主色 + 波形图标）
 * - 点击条目：onSkipTo(index)；点当前条目仅关闭 sheet
 * - 向右滑动删除单条；正在播的那一行滑不动
 * - 首次打开时，第一条可删除的行自动右滑一段再弹回，演示一次（见 [SwipeToRemoveRow] 的首次演示）
 * - 队列为空时显示「队列为空」+ 加入方式提示
 *
 * 「清空队列」放在标题行而不是列表下面：列表一长，底部按钮会被挤出屏幕（早期就是这样）。
 * 撤销提示也显示在 sheet 里：sheet 是独立窗口，会挡住主界面的 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQueueSheet(
    state: PlaybackState,
    onSkipTo: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val queue = state.queue
    val currentIndex = state.queueIndex
    // 清空保留正在播的那一项：只剩它时没东西可清
    val canClear = queue.size > 1 || (queue.isNotEmpty() && currentIndex !in queue.indices)
    // 打开时把正在播的那一项滚进视野，上方留两行上下文。只定位这一次，之后不跟着播放跳
    val firstVisible = (currentIndex - 2).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstVisible)
    // 首次演示放在第一条看得见、能删的行上（不是当前播放那一行）
    val hintIndex = queue.indices.firstOrNull { it >= firstVisible && it != currentIndex }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 一打开就是完整高度：半展开状态只露出屏幕最下面一截，手指够起来别扭
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 不要拖动条：它连同上下留白占掉一大块，关闭有标题行的按钮，下拉关闭照样可用
        dragHandle = null,
    ) {
        // 固定高度：标题行永远在同一个位置，不随队列长短忽高忽低
        Box(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT_FRACTION).hintTouchObserver(LocalHints.current)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = BsSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "播放队列 · ${queue.size} 首",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearQueue, enabled = canClear) { Text("清空队列") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (queue.isNotEmpty()) {
                    Row(
                        Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(PIN_SIZE),
                        )
                        Text(
                            " 手动加入，点击播放不会替换 · 右滑删除单条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                if (queue.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("队列为空", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "从搜索结果 ⋮ 菜单 / 复选工具栏「加入队列」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else {
                    // 列表占满面板剩余高度、在内部滚动，不会把标题行挤出屏幕
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                        itemsIndexed(
                            queue,
                            // 同一视频可能在队列里出现多次，key 带上下标才唯一；
                            // 删掉一条后后面的行 key 都变了，滑动状态不会串到别的行上
                            key = { idx, item -> "$idx:${item.keyPart()}" },
                        ) { idx, item ->
                            val isCurrent = idx == currentIndex
                            SwipeToRemoveRow(
                                // 底部弹层的默认底色：前景要不透明才能盖住后面的删除底色
                                background = MaterialTheme.colorScheme.surfaceContainerLow,
                                enabled = !isCurrent,
                                hintKey = HintKey.SWIPE_QUEUE,
                                showHint = idx == hintIndex,
                                onRemove = { onRemove(idx) },
                            ) {
                                QueueRow(
                                    index = idx,
                                    request = item,
                                    isCurrent = isCurrent,
                                    pinned = state.queuePinned.getOrNull(idx) == true,
                                    onClick = {
                                        if (!isCurrent) onSkipTo(idx) else onDismiss()
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            // 与主界面同一位置：导航栏上方固定距离（面板已消费掉导航栏 inset 时这里计为 0）
            SnackbarHost(
                snackbarHostState,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = AppToastDefaults.BottomOffset),
            ) { AppToastSnackbar(it) }
        }
    }
}

private fun PlaybackRequest.keyPart(): String = when (this) {
    is PlaybackRequest.Bili -> "$bvid:${cid ?: 0}"
    is PlaybackRequest.Direct -> uri
}

private const val SHEET_HEIGHT_FRACTION = 0.7f
private val PIN_SIZE = 14.dp

@Composable
private fun QueueRow(
    index: Int,
    request: PlaybackRequest,
    isCurrent: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
) {
    val title = when (request) {
        is PlaybackRequest.Bili -> request.title.ifBlank { request.bvid }
        is PlaybackRequest.Direct -> request.uri
    }
    val durationSec = (request as? PlaybackRequest.Bili)?.durationSec ?: 0L
    val tint = if (isCurrent) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图钉紧贴编号；没有图钉的行留同样宽的空位，编号才对得齐
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "固定",
                    tint = tint,
                    modifier = Modifier.size(PIN_SIZE),
                )
            } else {
                Spacer(Modifier.width(PIN_SIZE))
            }
            Spacer(Modifier.width(BsSpacing.xs))
            Text(
                "%02d".format(index + 1),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
        if (isCurrent) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "当前播放",
                tint = tint,
                modifier = Modifier.size(BsSize.iconSm),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (durationSec > 0) formatDuration(durationSec * 1000) else "",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
