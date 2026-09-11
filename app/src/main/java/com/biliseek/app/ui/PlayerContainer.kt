package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.QualityFallback
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.theme.AccentDecision
import com.biliseek.designsystem.theme.AdaptiveAccentColorPolicy
import com.biliseek.designsystem.theme.CoverPalette
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 播放器容器的展开程度。
 *
 * [FULLSCREEN] 的入口是**显式按钮**，不是手势。MVP 阶段用过「视频区上滑进全屏」，
 * 因触发判定有缺陷被整个移除（c006079）；重做时换成按钮，把那类误触从根上去掉。
 * 层级见《架构设计篇》§7.2。
 */
enum class PlayerExpansion { COLLAPSED, EXPANDED, FULLSCREEN }

/**
 * 展开播放器控制区的一项。
 *
 * 控制区由**动作列表驱动渲染**，不是写死的 Row。后续「歌词」「音质」「下载」
 * 只需往列表里追加一项，布局代码不动——这是《功能优化篇》§3.5 要求的扩展位。
 */
data class PlayerAction(
    val key: String,
    val label: String,
    val enabled: Boolean = true,
    /** 主要动作用实心按钮，其余用描边按钮。 */
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 常驻播放器容器。
 *
 * 见《架构设计篇》§7.2：**挂在 NavHost 之外**，页面切换时不重建，播放不中断。
 * 从听 → 看 → 全屏全程不动导航栈，「退回听」始终是一步可逆的状态变更。
 */
@Composable
fun PlayerContainer(
    state: PlaybackState,
    expansion: PlayerExpansion,
    surfaceView: SurfaceView,
    onExpansionChange: (PlayerExpansion) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    modifier: Modifier = Modifier,
    onEnterPip: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onCyclePlayMode: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    /** 0 取消、负数表示「播完当前」、正数为倒计时毫秒。 */
    onSetSleepTimer: (Long) -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onQueueSkipTo: (Int) -> Unit = {},
    // Ch5
    onOpenQueue: () -> Unit = {},
    miniBarStyle: com.biliseek.app.data.MiniBarStyle = com.biliseek.app.data.MiniBarStyle.FLOAT,
    /**
     * 点 MiniPlayer 跳到 player route。由 AppShell 提供（调 navController.navigate）。
     * 不在此处设 onExpansionChange(EXPANDED)：M12-A 之后 EXPANDED 不再是 UI 状态，
     * 设了反而会让 AppShell 误判 MiniPlayer 不该渲染。
     */
    onExpand: () -> Unit = {},
) {
    when (expansion) {
        PlayerExpansion.COLLAPSED -> {
            val palette = rememberCoverPalette(state.coverUrl)
            val accent = rememberAccentDecision(palette)
            MiniPlayer(
                state = state,
                onExpand = onExpand,
                onTogglePlayPause = onTogglePlayPause,
                accent = accent,
                onOpenQueue = onOpenQueue,
                onSeek = onSeek,
                miniBarStyle = miniBarStyle,
                modifier = modifier,
            )
        }
        // 全屏时这一份不渲染：SurfaceView 只能有一个父容器，
        // 两处同时挂会抛 "already has a parent"。全屏那份由 AppShell 的 overlay 负责
        PlayerExpansion.FULLSCREEN -> Unit
        PlayerExpansion.EXPANDED -> {
            val palette = rememberCoverPalette(state.coverUrl)
            val accent = rememberAccentDecision(palette)
            // EXPANDED 是临时 alias，仅为兼容旧的本地调用点。
            // AppShell 的 PLAYER destination 改用 [PlayerScreen] 顶层函数。
            PlayerScreen(
                state = state,
                surfaceView = surfaceView,
                onCollapse = { onExpansionChange(PlayerExpansion.COLLAPSED) },
                onTogglePlayPause = onTogglePlayPause,
                onPresentationChange = onPresentationChange,
                onEnterPip = onEnterPip,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onCyclePlayMode = onCyclePlayMode,
                onSeek = onSeek,
                onSetSpeed = onSetSpeed,
                onSetSleepTimer = onSetSleepTimer,
                onRetryPlayback = onRetryPlayback,
                onQueueSkipTo = onQueueSkipTo,
                onEnterFullscreen = { onExpansionChange(PlayerExpansion.FULLSCREEN) },
                accent = accent,
                modifier = modifier,
            )
        }
    }
}

/**
 * 封面取色决策（Compose 状态）。
 *
 * keyed by 主色 + 主题：换歌 / 切亮暗都重算；其余重组直接复用。
 */
@Composable
private fun rememberAccentDecision(palette: CoverPalette): AccentDecision {
    val darkTheme = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    return remember(palette.dominant, darkTheme, surface) {
        AdaptiveAccentColorPolicy.resolve(palette.dominant, darkTheme, surface)
    }
}

/**
 * 全屏播放层。
 *
 * 提升为 Scaffold 之上的 overlay（见《架构设计篇》§7.2），
 * 底部导航与页面栈原样不动，退出即销毁——不必逐个恢复被藏起来的东西。
 */
@Composable
fun FullscreenPlayerOverlay(
    surfaceView: SurfaceView,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 返回键在 overlay 内部处理：Compose 的 BackHandler 后注册者优先，
    // 这里比 AppShell 的那个先收到——全屏下按一次即退出（M9 打磨项）。
    // 早期实现依赖 AppShell 的外层 BackHandler，首次按键被系统栏的
    // 临时显示逻辑吞掉，表现为要按两次。
    androidx.activity.compose.BackHandler { onExit() }

    // 沉浸由 overlay 自己开关：进入时藏系统栏，退出时恢复。
    // 放在这里而不是 Activity 里，是因为「退出即销毁」保证了恢复一定会执行
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    Surface(color = Color.Black, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerSurface(surfaceView, Modifier.fillMaxSize())
            // 退出按钮压在画面上，位置固定——手势退出正是当初出问题的地方
            TextButton(
                onClick = onExit,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Text("退出全屏", color = Color.White)
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    state: PlaybackState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    accent: AccentDecision,
    onOpenQueue: () -> Unit,
    onSeek: (Long) -> Unit,
    miniBarStyle: com.biliseek.app.data.MiniBarStyle,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.mode != com.biliseek.core.player.PlaybackMode.IDLE,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // Ch5 §5.1.4：FLOAT 浮在底部上（圆角 24dp + 左右 16dp + elevation=7）；
        // BOTTOM 贴底（圆角顶 24dp、无阴影）。
        val surfaceModifier = when (miniBarStyle) {
            com.biliseek.app.data.MiniBarStyle.FLOAT -> Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .shadow(elevation = 7.dp, shape = RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
            com.biliseek.app.data.MiniBarStyle.BOTTOM -> Modifier
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        }
        Surface(
            tonalElevation = 3.dp,
            modifier = surfaceModifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // 上滑展开
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -DRAG_THRESHOLD) onExpand()
                    }
                },
        ) {
            Column {
                // Ch5 §5.1.1：minibar 进度条也可拖（24dp 触摸区 + 2dp 视觉条）
                com.biliseek.app.ui.components.DraggableProgressBar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek = onSeek,
                    height = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoverThumbnail(state.coverUrl, width = 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title.ifBlank { "BiliSeek" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatDuration(state.positionMs) + " / " +
                                formatDuration(state.durationMs) +
                                if (state.presentation != PresentationMode.NONE) " · 看" else " · 听",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // Ch5 §5.1.2：minibar 右侧加播放列表按钮
                    IconButton(onClick = onTogglePlayPause) {
                        Text(if (state.isPlaying) "⏸" else "▶")
                    }
                    IconButton(onClick = onOpenQueue) {
                        Text("☰")
                    }
                }
            }
        }
    }
}

@Composable
/**
 * 展开态播放器。
 *
 * 顶层可独立 compose 的函数：M12-A 重构后它被 NavHost 的 PLAYER destination 直接调用，
 * 不再嵌在 [PlayerContainer] 内部。MiniPlayer（COLLAPSED）仍走 [PlayerContainer]，
 * 但 EXPANDED 已经成为 nav 栈的一等公民——back 由 NavController 接管。
 *
 * 为什么拆出来：[PlayerContainer] 同时承担 MiniPlayer / Fullscreen overlay 的渲染，
 * 把 EXPANDED 抽走后三者解耦，单元测试与状态 hoisting 都更干净。
 */
fun PlayerScreen(
    state: PlaybackState,
    surfaceView: SurfaceView,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    onEnterPip: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onRetryPlayback: () -> Unit,
    onQueueSkipTo: (Int) -> Unit,
    onEnterFullscreen: () -> Unit,
    accent: AccentDecision,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            // 展开播放器要避让状态栏（M9 打磨项）。全屏 overlay 保持不吃 insets——
            // 那是有意的沉浸，别一起改了
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // M12-A 莫奈取色：背景为封面主色渐变（无封面时纯表面色，与默认一致）
            .background(
                Brush.verticalGradient(listOf(accent.gradientTop, accent.gradientBottom)),
            ),
    ) {
        // 对比度不足时叠一层向表面色收敛的 scrim，保住文字可读（M12 A-2 判据）
        accent.scrim?.let { scrim ->
            Box(Modifier.fillMaxSize().background(scrim))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) { Text("▾") }
                Text("正在播放", style = MaterialTheme.typography.titleSmall)
                Text("")
            }

            // 封面区原地展开为视频，页面不跳转
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (state.presentation == PresentationMode.NONE) {
                    if (state.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = state.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (state.isResolving) {
                        Text("取流中…", color = Color.White)
                    } else if (state.coverUrl.isBlank()) {
                        Text("纯音频模式", color = Color.White)
                    }
                } else {
                    PlayerSurface(surfaceView, Modifier.fillMaxSize())
                }
            }

            Text(
                state.title.ifBlank { "BiliSeek" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            ProgressBar(state, onSeek)

            PlayerActionRow(
                buildPlayerActions(
                    state = state,
                    surfaceView = surfaceView,
                    onTogglePlayPause = onTogglePlayPause,
                    onPresentationChange = onPresentationChange,
                    onSkipPrevious = onSkipPrevious,
                    onSkipNext = onSkipNext,
                    onCyclePlayMode = onCyclePlayMode,
                    onEnterPip = onEnterPip,
                    onEnterFullscreen = onEnterFullscreen,
                ),
            )

            SpeedRow(state.speed, onSetSpeed)
            QualityLine(state)
            SleepTimerRow(state, onSetSleepTimer)

            if (state.queue.size > 1) {
                QueuePanel(state, onQueueSkipTo)
            }

            // 取流失败不是死路：队列与下标都还在，重放即可
            state.error?.let {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "取流失败：$it",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red,
                    )
                    Button(onClick = onRetryPlayback) { Text("重试") }
                }
            }
        }
    }
}

/**
 * 当前**实际生效**的音质，以及没按用户所选生效时的原因。
 *
 * 显示生效值而不是设置值：用户选了无损却拿不到时，界面若还写着「无损」，
 * 那是在骗人；而悄悄降级不吭声，用户会以为无损功能坏了。
 */
@Composable
private fun QualityLine(state: PlaybackState) {
    if (state.mode == com.biliseek.core.player.PlaybackMode.IDLE) return
    // 本地文件没有音质档位：如实说「本地文件」，不谎报「自动」
    if (state.isOffline) {
        Text("音质：本地文件", style = MaterialTheme.typography.labelSmall)
        return
    }
    val quality = when (state.audioQuality) {
        AudioQuality.AUTO -> "自动"
        AudioQuality.LOSSLESS -> "无损"
        AudioQuality.SAVER -> "省流量"
    }
    val reason = when (state.qualityFallback) {
        QualityFallback.NONE -> ""
        QualityFallback.UNAVAILABLE -> "（本视频无无损音轨，或账号无权限）"
        QualityFallback.SPEED_INCOMPATIBLE -> "（倍速超过 1.5×，已回退）"
    }
    Text(
        "音质：$quality$reason",
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * 可拖动的进度条。
 *
 * **拖动期间必须用本地状态。** 250ms 的 `pushState()` 轮询会不停推来播放器的真实位置，
 * 若 `Slider` 直接绑 `state.positionMs`，手指还没松开就会被拽回去。
 */
@Composable
private fun ProgressBar(state: PlaybackState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs
    val max = duration.coerceAtLeast(1L).toFloat()
    val value = (if (dragging) dragValue else state.positionMs.toFloat()).coerceIn(0f, max)

    Column {
        Slider(
            value = value,
            onValueChange = {
                // 只改本地，不下发 seek——否则每一帧都是一次跳转
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..max,
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(value.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun buildPlayerActions(
    state: PlaybackState,
    surfaceView: SurfaceView,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onEnterPip: () -> Unit,
    onEnterFullscreen: () -> Unit,
): List<PlayerAction> = buildList {
    add(
        PlayerAction(
            key = "play",
            label = if (state.isPlaying) "暂停" else "播放",
            onClick = onTogglePlayPause,
        ),
    )
    add(
        if (state.presentation == PresentationMode.NONE) {
            PlayerAction("presentation", "切「看」", primary = true) {
                onPresentationChange(PresentationMode.INLINE, surfaceView)
            }
        } else {
            PlayerAction("presentation", "退回「听」", primary = true) {
                onPresentationChange(PresentationMode.NONE, null)
            }
        },
    )
    add(PlayerAction("previous", "上一首", enabled = state.hasPrevious, onClick = onSkipPrevious))
    add(PlayerAction("next", "下一首", enabled = state.hasNext, onClick = onSkipNext))
    add(
        PlayerAction(
            key = "playMode",
            label = when (state.playMode) {
                com.biliseek.core.player.PlayMode.SEQUENTIAL -> "顺序"
                com.biliseek.core.player.PlayMode.REPEAT_ALL -> "列表循环"
                com.biliseek.core.player.PlayMode.REPEAT_ONE -> "单曲"
                com.biliseek.core.player.PlayMode.SHUFFLE -> "随机"
            },
            onClick = onCyclePlayMode,
        ),
    )
    if (state.presentation != PresentationMode.NONE) {
        add(PlayerAction("fullscreen", "全屏", onClick = onEnterFullscreen))
        add(PlayerAction("pip", "画中画", onClick = onEnterPip))
    }
    // 后续「歌词」「下载」在此追加即可，无需改动布局
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerActionRow(actions: List<PlayerAction>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            if (action.primary) {
                Button(onClick = action.onClick, enabled = action.enabled) { Text(action.label) }
            } else {
                OutlinedButton(onClick = action.onClick, enabled = action.enabled) {
                    Text(action.label)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedRow(current: Float, onSetSpeed: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("倍速", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEEDS.forEach { speed ->
                FilterChip(
                    // 浮点相等不可靠，比差值
                    selected = kotlin.math.abs(current - speed) < 0.01f,
                    onClick = { onSetSpeed(speed) },
                    label = { Text("${speed}×") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerRow(state: PlaybackState, onSetSleepTimer: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("睡眠定时")
                    state.sleepTimerRemainingMs?.let { append(" · 剩余 ${formatDuration(it)}") }
                    if (state.sleepTimerEndOfTrack) append(" · 播完当前")
                },
                style = MaterialTheme.typography.labelMedium,
            )
            if (state.sleepTimerActive) {
                TextButton(onClick = { onSetSleepTimer(0) }) { Text("取消") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SLEEP_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = false,
                    onClick = { onSetSleepTimer(minutes * 60_000L) },
                    label = { Text("$minutes 分钟") },
                )
            }
            FilterChip(
                selected = state.sleepTimerEndOfTrack,
                onClick = { onSetSleepTimer(-1) },
                label = { Text("播完当前") },
            )
        }
    }
}

/**
 * 队列面板。
 *
 * 早期只有一行「队列 N / M」——知道自己在队列里，却看不见队列有什么、也跳不过去。
 * 默认收起：多 P 视频动辄几十上百条，展开后会把控制区顶出屏幕。
 */
@Composable
private fun QueuePanel(state: PlaybackState, onSkipTo: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "队列 ${state.queueIndex + 1} / ${state.queue.size}",
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        if (!expanded) return@Column

        state.queue.forEachIndexed { index, request ->
            val current = index == state.queueIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSkipTo(index) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    request.title.ifBlank { "未命名" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (current) {
                    Text("正在播放", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_MINUTES = listOf(15, 30, 60)

private const val DRAG_THRESHOLD = 8f

/** Compose 里拿 Activity：主题包装后 context 往往是 ContextWrapper，得一层层剥。 */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
