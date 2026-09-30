package com.biliseek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.biliseek.app.interaction.VideoAction
import com.biliseek.app.interaction.VideoActionPolicy
import com.biliseek.app.interaction.VideoInteractionState
import com.biliseek.designsystem.component.BsIconAction
import com.biliseek.designsystem.component.BsIconActionLayout
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/** 视频互动栏的回调集合（播放页 / AppShell 逐层透传用）。 */
data class VideoActionHandlers(
    /** 播放页展开 / 切歌时绑定当前 bvid（触发一次读）。 */
    val onBind: (bvid: String) -> Unit = {},
    val onToggleLike: () -> Unit = {},
    /** 长按点赞 = 一键三连。 */
    val onTriple: () -> Unit = {},
    val onCoin: (count: Int, alsoLike: Boolean) -> Unit = { _, _ -> },
)

/**
 * 播放页视频互动栏：点赞 / 投币 / 收藏 / 评论 / 分享，竖排「图标 + 数字」。
 *
 * 参考官方客户端的互动区：
 * - 已激活的动作用实心图标 + 主题色（官方为粉色，这里跟随莫奈 / 封面 accent）；
 * - **长按点赞 = 一键三连**（官方同款手势），触发时有一次强触感；
 * - 没有数字时显示动作名（「点赞」而不是「0」）；
 * - 收藏走「添加到列表」弹窗（同时可选 B 站收藏夹 / 本地歌单）。
 *
 * [state] 为 null（本地文件、尚未绑定）时整栏按「全 0 未激活」显示，仍可点评论 / 分享。
 */
@Composable
fun VideoActionBar(
    state: VideoInteractionState?,
    handlers: VideoActionHandlers,
    onOpenFavorite: () -> Unit,
    onOpenComments: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    /** AI 总结（工单 AI-6）。 */
    onSummary: () -> Unit = {},
    /** 这条视频已有总结：图标实心 + 主题色，和点赞 / 投币后的形式一致。 */
    hasSummary: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
) {
    val haptic = LocalHapticFeedback.current
    var showCoinSheet by remember { mutableStateOf(false) }
    val busy = state?.busy.orEmpty()

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BsIconAction(
            icon = Icons.Outlined.ThumbUp,
            activeIcon = Icons.Filled.ThumbUp,
            label = "点赞",
            count = state?.likeCount,
            active = state?.liked == true,
            busy = VideoAction.LIKE in busy || VideoAction.TRIPLE in busy,
            enabled = state != null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                handlers.onToggleLike()
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                handlers.onTriple()
            },
            layout = BsIconActionLayout.Vertical,
            activeColor = activeColor,
        )
        BsIconAction(
            icon = Icons.Outlined.MonetizationOn,
            activeIcon = Icons.Filled.MonetizationOn,
            label = "投币",
            count = state?.coinCount,
            active = (state?.coined ?: 0) > 0,
            busy = VideoAction.COIN in busy,
            enabled = state != null,
            onClick = { showCoinSheet = true },
            layout = BsIconActionLayout.Vertical,
            activeColor = activeColor,
        )
        BsIconAction(
            icon = Icons.Outlined.StarBorder,
            activeIcon = Icons.Filled.Star,
            label = "收藏",
            count = state?.favoriteCount,
            active = state?.favored == true,
            onClick = onOpenFavorite,
            layout = BsIconActionLayout.Vertical,
            activeColor = activeColor,
        )
        BsIconAction(
            icon = Icons.Outlined.ChatBubbleOutline,
            label = "评论",
            count = state?.replyCount,
            onClick = onOpenComments,
            layout = BsIconActionLayout.Vertical,
            activeColor = activeColor,
        )
        BsIconAction(
            icon = Icons.Outlined.Share,
            label = "分享",
            count = state?.shareCount,
            onClick = onShare,
            layout = BsIconActionLayout.Vertical,
            activeColor = activeColor,
        )
        BsIconAction(
            icon = Icons.Outlined.AutoAwesome,
            activeIcon = Icons.Filled.AutoAwesome,
            label = "总结",
            active = hasSummary,
            onClick = onSummary,
            layout = BsIconActionLayout.Vertical,
            activeColor = activeColor,
        )
    }

    if (showCoinSheet && state != null) {
        CoinSheet(
            state = state,
            onConfirm = { count, alsoLike ->
                showCoinSheet = false
                handlers.onCoin(count, alsoLike)
            },
            onDismiss = { showCoinSheet = false },
        )
    }
}

/**
 * 投币弹层：选 1 / 2 枚（转载视频只能 1 枚、已投的扣掉），勾选「同时点赞」。
 * 已投满时只显示提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoinSheet(
    state: VideoInteractionState,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = VideoActionPolicy.coinChoices(state)
    var selected by remember { mutableIntStateOf(choices.lastOrNull() ?: 0) }
    var alsoLike by remember { mutableStateOf(!state.liked) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = BsSpacing.xl).padding(bottom = BsSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(BsSpacing.lg),
        ) {
            Text("投币", style = MaterialTheme.typography.titleMedium)
            if (choices.isEmpty()) {
                Text(
                    if (state.maxCoins == 1) "转载视频最多投 1 枚，已经投过啦" else "已经投满 2 枚啦",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(BsSpacing.md)) {
                (1..2).forEach { n ->
                    val available = n in choices
                    CoinOption(
                        count = n,
                        selected = selected == n,
                        enabled = available,
                        onClick = { selected = n },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (state.coined > 0) {
                Text(
                    "已投 ${state.coined} 枚，还能投 ${choices.size} 枚",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.liked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoLike, onCheckedChange = { alsoLike = it })
                    Text("同时点赞", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = { onConfirm(selected, alsoLike && !state.liked) },
                enabled = selected in choices,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("投 $selected 枚") }
            }
        }
    }
}

@Composable
private fun CoinOption(
    count: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) {
        androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        CardDefaults.outlinedCardBorder(enabled)
    }
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        border = border,
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = BsSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row {
                repeat(count) {
                    Icon(
                        Icons.Filled.MonetizationOn,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(BsSize.iconLg),
                    )
                }
            }
            Spacer(Modifier.height(BsSpacing.sm))
            Text("$count 枚", style = MaterialTheme.typography.labelLarge)
        }
    }
}
