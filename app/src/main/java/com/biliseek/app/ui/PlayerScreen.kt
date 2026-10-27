package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.AccentDecision
import com.biliseek.designsystem.theme.AdaptiveAccentColorPolicy
import com.biliseek.designsystem.theme.CoverPalette

/**
 * 播放器容器的展开程度。
 *
 * [FULLSCREEN] 的入口是**显式按钮**，不是手势。MVP 阶段用过「视频区上滑进全屏」，
 * 因触发判定有缺陷被整个移除（c006079）；重做时换成按钮，把那类误触从根上去掉。
 * 层级见《架构设计篇》§7.2。
 */
enum class PlayerExpansion { COLLAPSED, EXPANDED, FULLSCREEN }

@Composable
internal fun rememberAccentDecision(palette: CoverPalette): AccentDecision {
    val surface = MaterialTheme.colorScheme.surface
    // 按 App 当前主题的实际深浅算，不读系统：设置里可能选了和系统不一样的深色模式
    val darkTheme = surface.luminance() < 0.5f
    return remember(palette.dominant, darkTheme, surface) {
        AdaptiveAccentColorPolicy.resolve(palette.dominant, darkTheme, surface)
    }
}

/**
 * 展开态播放器。
 *
 * 顶层可独立 compose 的函数：M12-A 重构后它被 NavHost 的 PLAYER destination 直接调用。
 *
 * 音视频模式统一 16:9 CoverArea 容器尺寸，布局完全一致，只有 CoverArea 内容不同。
 */
@Composable
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
    onEnterFullscreen: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenComments: () -> Unit,
    onShare: () -> Unit,
    onOpenAddToList: () -> Unit,
    onOpenUpSpace: () -> Unit,
    accent: AccentDecision,
    onDownload: () -> Unit = {},
    onSetVideoQuality: (Int) -> Unit = {},
    defaultAudioQuality: com.biliseek.core.player.AudioQuality = com.biliseek.core.player.AudioQuality.AUTO,
    onSetAudioQuality: (com.biliseek.core.player.AudioQuality) -> Unit = {},
    onSetDefaultAudioQuality: (com.biliseek.core.player.AudioQuality) -> Unit = {},
    modifier: Modifier = Modifier,
    // 全屏 overlay 是否处于激活状态：激活时 CoverArea 让出 SurfaceView 的挂载权，
    // 避免和 FullscreenPlayerOverlay 同时持有同一个 view（见 PlayerSurface.kt 的说明）。
    isFullscreenActive: Boolean = false,
    /** 视频互动栏状态（点赞 / 投币 / 收藏计数与已互动态）。null = 未绑定或本地文件。 */
    interaction: com.biliseek.app.interaction.VideoInteractionState? = null,
    interactionHandlers: VideoActionHandlers = VideoActionHandlers(),
    onSummary: () -> Unit = {},
    /** 当前视频已有总结：「总结」按钮显示为已激活。 */
    hasSummary: Boolean = false,
    /** 简介里点时间点：跳到该处播放。 */
    onDescriptionTimestamp: (Long) -> Unit = {},
    /** 简介里点链接 / 视频号。 */
    onDescriptionLink: (DescLinkTarget) -> Unit = {},
    /** 看模式下离开播放页时截下的最后一帧：撤掉画面后顶替视频，和整页一起淡出（见 AppShell 的 leavePlayerThen）。 */
    frozenFrame: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** 合集弹层的动作（点某一集、订阅 / 取消订阅）。 */
    collectionHandlers: CollectionSheetHandlers = CollectionSheetHandlers(),
    /** 已登录：合集弹层显示「订阅 / 已订阅」。 */
    isLoggedIn: Boolean = false,
    /** 订阅请求进行中：订阅按钮不能点。 */
    subscribeBusy: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    // 长按加速在手势里读最新的播放状态（是否在播、当前速度、音质）
    val currentState = androidx.compose.runtime.rememberUpdatedState(state)
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    // 换视频（切歌 / 自动下一首）时简介弹层跟着收起
    var showDescriptionSheet by remember(state.bvid) { mutableStateOf(false) }
    // 换视频时合集弹层跟着收起
    var showCollectionSheet by remember(state.bvid) { mutableStateOf(false) }
    val isAudio = state.presentation == PresentationMode.NONE
    val boundInteraction = interaction?.takeIf { it.bvid == state.bvid }
    val activeColor = accent.accent ?: MaterialTheme.colorScheme.primary
    // 只有播放页展开时才绑定（= 才可能发互动读请求）；纯听后台不会走到这里
    LaunchedEffect(state.bvid) { interactionHandlers.onBind(state.bvid) }

    Box(
        modifier
            .fillMaxSize()
            // 不再自己加 windowInsetsPadding：AppShell 里的 Scaffold 没有覆盖
            // contentWindowInsets，Material3 默认值是 safeDrawing，NavHost 传给
            // 这里的内容区已经把状态栏高度让出来了。这里如果再加一层，状态栏高度
            // 会被算两遍，顶部空出一大截。
            // 音频/视频模式背景统一用封面取色渐变——视频模式不整页变黑，
            // 只有 CoverArea 内部（视频帧本身的 letterbox）是黑底，其余顶部栏/
            // 标题/进度条/控制条/底栏都应该跟音频模式长一样。
            .background(
                Brush.verticalGradient(listOf(accent.gradientTop, accent.gradientBottom))
            ),
    ) {
        accent.scrim?.let { scrim ->
            Box(Modifier.fillMaxSize().background(scrim))
        }

        // 顶部栏 → CoverArea → 标题/互动栏/简介行 → 进度条/控制行/听看开关，统一放进同一条纵向流里、
        // 始终显示——音频/视频模式布局完全一致，只有 CoverArea 内容不同。
        // 不再各自作为 Box 的平级子项——否则它们会叠在一起画（曾经的重叠 bug）。
        // 中段（标题/互动栏/简介行）按内容高度排，进度条与控制行紧跟其后——简介正文不在页面上占地方（点「简介」弹层看），
        // 控制行因此上提，屏幕下方留空；屏幕矮、放不下时中段被压矮并可滑动，封面与控制行始终在。
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerTopBar(
                onCollapse = onCollapse,
                onOpenMenu = { showMenu = true },
                // 小窗只对看模式有意义：纯听本来就能在后台放
                onEnterPip = if (isAudio) null else onEnterPip,
            )

            CoverArea(
                coverUrl = state.coverUrl,
                presentation = state.presentation,
                surfaceView = if (!isAudio) surfaceView else null,
                isLoading = state.isResolving || state.isBuffering,
                onEnterFullscreen = onEnterFullscreen,
                isFullscreenActive = isFullscreenActive,
                videoAspectRatio = state.videoAspectRatio,
                // 看模式下长按画面：播放中临时加速，松手恢复（同全屏）
                playback = currentState,
                onSetSpeed = onSetSpeed,
                frozenFrame = frozenFrame,
            )

            // 换视频时滚动位置回到顶部
            key(state.bvid) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        state.title.ifBlank { "BiliSeek" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    VideoActionBar(
                        state = boundInteraction,
                        handlers = interactionHandlers,
                        onOpenFavorite = onOpenAddToList,
                        onOpenComments = onOpenComments,
                        onShare = onShare,
                        activeColor = activeColor,
                        onSummary = onSummary,
                        hasSummary = hasSummary,
                    )

                    boundInteraction?.let { info ->
                        VideoDescriptionRow(
                            desc = info.desc,
                            viewCount = info.viewCount,
                            pubDate = info.pubDate,
                            linkColor = activeColor,
                            onOpen = { showDescriptionSheet = true },
                        )
                    }

                    boundInteraction?.collection?.let { collection ->
                        CollectionEntryRow(
                            collection = collection,
                            onOpen = { showCollectionSheet = true },
                        )
                    }
                }
            }

            ProgressBar(state, onSeek)

            PlayerPageControlRow(
                isPlaying = state.showPauseButton,
                playMode = state.playMode,
                onTogglePlayPause = onTogglePlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onCyclePlayMode = onCyclePlayMode,
                onOpenQueue = onOpenQueue,
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
            )

            PlayerSwitchRow(
                state = state,
                isAudioMode = isAudio,
                onSwitchMode = {
                    if (isAudio) {
                        onPresentationChange(PresentationMode.INLINE, surfaceView)
                    } else {
                        onPresentationChange(PresentationMode.NONE, null)
                    }
                },
                onOpenSpeed = { showSpeedSheet = true },
                onOpenTimer = { showTimerSheet = true },
            )

            state.error?.let {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = onRetryPlayback) { Text("重试") }
                }
            }
        }

        if (showMenu) {
            PlayerMoreMenuSheet(
                state = state,
                onDismiss = { showMenu = false },
                onDownload = onDownload,
                onAddToList = onOpenAddToList,
                onViewAuthor = onOpenUpSpace,
                onShare = onShare,
                onSetVideoQuality = onSetVideoQuality,
                defaultAudioQuality = defaultAudioQuality,
                onSetAudioQuality = onSetAudioQuality,
                onSetDefaultAudioQuality = onSetDefaultAudioQuality,
                onOpenSpeed = { showSpeedSheet = true },
                onOpenTimer = { showTimerSheet = true },
            )
        }

        if (showSpeedSheet) {
            PlayerSpeedSheet(state, onSetSpeed, onDismiss = { showSpeedSheet = false })
        }

        if (showTimerSheet) {
            PlayerTimerSheet(state, onSetSleepTimer, onDismiss = { showTimerSheet = false })
        }

        if (showDescriptionSheet && boundInteraction != null) {
            VideoDescriptionSheet(
                desc = boundInteraction.desc,
                durationMs = state.durationMs,
                linkColor = activeColor,
                onTimestamp = onDescriptionTimestamp,
                onLink = onDescriptionLink,
                onDismiss = { showDescriptionSheet = false },
            )
        }

        val collection = boundInteraction?.collection
        if (showCollectionSheet && collection != null) {
            CollectionSheet(
                collection = collection,
                playingBvid = state.bvid,
                subscribed = boundInteraction.seasonSubscribed,
                showSubscribe = isLoggedIn,
                subscribeBusy = subscribeBusy,
                accent = activeColor,
                onPlayEpisode = { index -> collectionHandlers.onPlayEpisode(collection, index) },
                onSetSubscribed = { subscribe -> collectionHandlers.onSetSubscribed(collection, subscribe) },
                onQueueCollection = { index -> collectionHandlers.onQueueCollection(collection, index) },
                onDismiss = { showCollectionSheet = false },
            )
        }
    }
}

/**
 * 播放页顶部栏：收起 + 标题 + （看模式）小窗 + 三点菜单。
 */
@Composable
private fun PlayerTopBar(
    onCollapse: () -> Unit,
    onOpenMenu: () -> Unit,
    onEnterPip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
        }
        Text("正在播放", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.align(Alignment.CenterEnd)) {
            if (onEnterPip != null) {
                IconButton(onClick = onEnterPip) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "小窗播放")
                }
            }
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
            }
        }
    }
}
