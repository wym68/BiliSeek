package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.biliseek.app.ui.components.CompactDragHandle
import com.biliseek.core.player.PlayMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.theme.AccentDecision
import com.biliseek.designsystem.theme.AdaptiveAccentColorPolicy
import com.biliseek.designsystem.theme.CoverPalette

/**
 * 三点菜单 ModalBottomSheet（BBPlayer `PlayerFunctionalMenu` 同款架构）。
 *
 * 调研要点：
 * - 顶部方块按钮（查看UP主 / 加入列表 / 下载）+ Divider + 列表项（画质 / 音质 / 复制链接）
 * - 倍速、定时只从播放页上的胶囊进，菜单里不重复
 * - 一打开就完整展开：内容不到半屏，刚好显示到最后一项
 * - Curried closer：所有菜单项先 onDismiss 再执行动作（避免 sheet 动画冲突）
 * - Haptics：菜单项点击触发 LongPress
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerMoreMenuSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onAddToList: () -> Unit,
    onViewAuthor: () -> Unit,
    onShare: () -> Unit,
    onSetVideoQuality: (Int) -> Unit = {},
    defaultAudioQuality: com.biliseek.core.player.AudioQuality = com.biliseek.core.player.AudioQuality.AUTO,
    onSetAudioQuality: (com.biliseek.core.player.AudioQuality) -> Unit = {},
    onSetDefaultAudioQuality: (com.biliseek.core.player.AudioQuality) -> Unit = {},
) {
    var showAudioSheet by remember { mutableStateOf(false) }
    var showQualitySheet by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    fun closeThen(action: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onDismiss()
        action()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactDragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuQuickButton(
                    icon = Icons.Filled.Person,
                    label = "查看UP主",
                    onClick = { closeThen(onViewAuthor) },
                    modifier = Modifier.weight(1f),
                )
                MenuQuickButton(
                    icon = Icons.Filled.PlaylistAdd,
                    label = "加入列表",
                    onClick = { closeThen(onAddToList) },
                    modifier = Modifier.weight(1f),
                )
                MenuQuickButton(
                    icon = Icons.Filled.Download,
                    label = "下载",
                    onClick = { closeThen(onDownload) },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (state.videoQualities.isNotEmpty()) {
                MenuItemRow(Icons.Filled.HighQuality, "画质") { showQualitySheet = true }
            }
            MenuItemRow(Icons.Filled.GraphicEq, "音质") { showAudioSheet = true }
            MenuItemRow(Icons.Filled.ContentCopy, "复制链接") { closeThen(onShare) }
        }
    }

    if (showQualitySheet) {
        OptionsSheet(title = "画质", onDismiss = { showQualitySheet = false }) {
            VideoQualityOptions(
                options = state.videoQualities,
                preference = state.videoQualityPreference,
                currentQn = state.currentVideoQn,
                onSelect = { onSetVideoQuality(it); showQualitySheet = false },
            )
        }
    }

    if (showAudioSheet) {
        OptionsSheet(title = "音质", onDismiss = { showAudioSheet = false }) {
            AudioQualityOptions(
                state = state,
                defaultQuality = defaultAudioQuality,
                onSelect = { onSetAudioQuality(it); showAudioSheet = false },
                onSetDefault = onSetDefaultAudioQuality,
            )
        }
    }
}

/** 倍速弹层：选完生效并收起。从播放页的倍速胶囊打开。 */
@Composable
internal fun PlayerSpeedSheet(
    state: PlaybackState,
    onSetSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    SpeedSelectionSheet(
        currentSpeed = state.speed,
        speedIncompatibleLossless =
            state.qualityFallback == com.biliseek.core.player.QualityFallback.SPEED_INCOMPATIBLE,
        onSpeedSelected = { onSetSpeed(it); onDismiss() },
        onDismiss = onDismiss,
    )
}

/** 定时关闭弹层：选完生效并收起。从播放页的定时胶囊打开。 */
@Composable
internal fun PlayerTimerSheet(
    state: PlaybackState,
    onSetSleepTimer: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    TimerSelectionSheet(
        isActive = state.sleepTimerActive,
        remainingMs = state.sleepTimerRemainingMs,
        endOfTrack = state.sleepTimerEndOfTrack,
        onSetTimer = { onSetSleepTimer(it); onDismiss() },
        onDismiss = onDismiss,
    )
}

@Composable
private fun MenuQuickButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MenuItemRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSelectionSheet(
    currentSpeed: Float,
    speedIncompatibleLossless: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            Text("倍速播放", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SpeedOptions(currentSpeed, speedIncompatibleLossless, onSpeedSelected)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 倍速档位选择（不含容器）。竖屏底部面板与全屏侧边面板共用。
 *
 * [speedIncompatibleLossless]：当前倍速下无损已回退 AAC——如实告诉用户，
 * 而不是让「选了无损」在倍速下悄悄失效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeedOptions(
    currentSpeed: Float,
    speedIncompatibleLossless: Boolean,
    onSpeedSelected: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PLAYBACK_SPEEDS.forEach { speed ->
                FilterChip(
                    selected = isSameSpeed(currentSpeed, speed),
                    onClick = { onSpeedSelected(speed) },
                    label = { Text(formatSpeed(speed)) },
                )
            }
        }
        if (speedIncompatibleLossless) {
            Text(
                "当前倍速下无损音质已切换为 AAC，降回 1.5× 及以下自动恢复",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerSelectionSheet(
    isActive: Boolean,
    remainingMs: Long?,
    endOfTrack: Boolean,
    onSetTimer: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            TimerOptions(isActive, remainingMs, endOfTrack, onSetTimer)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 睡眠定时选项（含标题行，不含容器）。竖屏底部面板与全屏侧边面板共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimerOptions(
    isActive: Boolean,
    remainingMs: Long?,
    endOfTrack: Boolean,
    onSetTimer: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("定时关闭", style = MaterialTheme.typography.titleMedium)
            if (isActive) {
                TextButton(onClick = { onSetTimer(0) }) { Text("取消") }
            }
        }
        if (isActive) {
            Text(
                buildString {
                    append("剩余 ")
                    remainingMs?.let { append(formatDuration(it)) }
                    if (endOfTrack) append(" · 播完当前")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SLEEP_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = false,
                    onClick = { onSetTimer(minutes * 60_000L) },
                    label = { Text("$minutes 分钟") },
                )
            }
            FilterChip(
                selected = endOfTrack,
                onClick = { onSetTimer(-1) },
                label = { Text("播完当前") },
            )
        }
    }
}

/** 标题 + 任意选项内容的底部面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 画质档位选择（不含容器）。竖屏底部面板与全屏侧边面板共用。
 *
 * 拿不到的档位（需登录 / 大会员，或设备解不了）也列出来、置灰——
 * 只列能选的会让人以为这个视频最高就这么清楚。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoQualityOptions(
    options: List<com.biliseek.core.player.VideoQualityOption>,
    preference: Int,
    currentQn: Int?,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = preference == com.biliseek.core.player.VIDEO_QUALITY_AUTO,
                onClick = { onSelect(com.biliseek.core.player.VIDEO_QUALITY_AUTO) },
                label = { Text("自动") },
            )
            options.forEach { option ->
                FilterChip(
                    selected = preference == option.qn,
                    onClick = { onSelect(option.qn) },
                    enabled = option.available,
                    label = { Text(option.label) },
                )
            }
        }
        videoQualityFallbackLabel(options, preference, currentQn)?.let { actual ->
            Text(
                "所选档位暂不可用，当前为 $actual",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (options.any { !it.available }) {
            Text(
                "置灰的档位需要登录或大会员，或本机不支持该编码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 音质选择（不含容器）。竖屏底部面板与全屏侧边面板共用。
 *
 * 这里选的只对本次会话生效；「设为默认」才写进设置。换档会带进度重新取流，
 * 有一次短暂缓冲——提前说清楚，免得用户以为卡了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioQualityOptions(
    state: PlaybackState,
    defaultQuality: com.biliseek.core.player.AudioQuality,
    onSelect: (com.biliseek.core.player.AudioQuality) -> Unit,
    onSetDefault: (com.biliseek.core.player.AudioQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.biliseek.core.player.AudioQuality.entries.forEach { quality ->
                FilterChip(
                    selected = state.requestedAudioQuality == quality,
                    onClick = { onSelect(quality) },
                    enabled = !state.isOffline,
                    label = { Text(audioQualityLabel(quality)) },
                )
            }
        }
        audioQualityStatusText(state.qualityFallback, state.isOffline)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "切换后当前视频会重新加载音频，可能短暂缓冲",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.requestedAudioQuality != defaultQuality) {
            TextButton(onClick = { onSetDefault(state.requestedAudioQuality) }) {
                Text("将「${audioQualityLabel(state.requestedAudioQuality)}」设为默认")
            }
        }
    }
}

private val SLEEP_MINUTES = listOf(15, 30, 60)
