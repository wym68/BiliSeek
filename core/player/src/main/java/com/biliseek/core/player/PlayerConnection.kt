package com.biliseek.core.player

import android.content.ComponentName
import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.player.internal.PlayUrlResolver
import com.biliseek.core.player.internal.resolveManualNextIndex
import com.biliseek.core.player.internal.resolveStartPosition
import com.biliseek.core.player.internal.resolveNextIndex
import com.biliseek.core.player.internal.resolvePlaybackMode
import com.biliseek.core.player.internal.resolvePreviousIndex
import com.biliseek.core.player.internal.resolveTrackSelectionParameters
import com.biliseek.core.player.internal.shouldEnableVideoTrack
import com.biliseek.core.player.internal.switchLabel
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * `:core:player` 对外的唯一门面。
 *
 * UI 层只与本类交互，不接触 ExoPlayer、MediaController 或任何 Media3 内部类型。
 * 这是《架构设计篇》§2.1「先定边界，再搬代码」纪律的落点——
 * 后续从 BiliPai 移植的取流、MPD 组装、音质策略全部藏在本模块 `internal` 之下。
 */
class PlayerConnection(private val context: Context) {

    private var controller: MediaController? = null
    private var scope: CoroutineScope? = null
    private val resolver = PlayUrlResolver(BiliNetwork.get(context))

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var presentation: PresentationMode = PresentationMode.NONE

    /** 队列。B 站来源需逐条取流，因此不用 ExoPlayer 原生播放列表。 */
    private var queue: List<PlaybackRequest> = emptyList()
    private var queueIndex: Int = -1
    private var playMode: PlayMode = PlayMode.SEQUENTIAL

    /** 起播时定位到的历史位置，由外部提供（续播）。 */
    var resumePositionProvider: (suspend (String, Long) -> Long)? = null

    /** 播放位置回写，用于续播与最近播放。 */
    var positionRecorder: (suspend (String, Long, Long) -> Unit)? = null

    // 切换诊断窗口：setPresentation 期间统计 buffering 与 seek 不连续
    private var recording = false
    private var bufferingEvents = 0
    private var seekDiscontinuities = 0

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (recording && playbackState == Player.STATE_BUFFERING) bufferingEvents++
            if (playbackState == Player.STATE_ENDED) onTrackEnded()
            pushState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (recording && reason == Player.DISCONTINUITY_REASON_SEEK) seekDiscontinuities++
            pushState()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = pushState()
    }

    suspend fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val mediaController = suspendCancellableCoroutine { cont ->
            future.addListener({ cont.resume(future.get()) }, MoreExecutors.directExecutor())
        }
        mediaController.addListener(listener)
        controller = mediaController

        scope = CoroutineScope(Dispatchers.Main).also { s ->
            s.launch {
                while (true) {
                    pushState()
                    delay(POSITION_POLL_MS)
                }
            }
        }
        pushState()
    }

    /**
     * 起播。B 站来源需要先取流（网络请求），因此是挂起函数。
     *
     * 起播时**先关视频轨再 prepare**——避免起播瞬间就去下载视频分片，
     * 这是「默认听」承诺在时序上的落点。
     */
    suspend fun play(request: PlaybackRequest, fromQueue: Boolean = false) {
        val c = controller ?: return
        if (!fromQueue) {
            queue = listOf(request)
            queueIndex = 0
        }
        _state.value = _state.value.copy(isResolving = true, error = null)

        val uri: String
        val mimeType: String?
        var media: com.biliseek.core.player.internal.ResolvedMedia? = null
        try {
            when (request) {
                is PlaybackRequest.Direct -> {
                    uri = request.uri
                    mimeType = null
                }
                is PlaybackRequest.Bili -> {
                    val resolved = resolver.resolve(request.bvid, request.cid)
                    media = resolved
                    uri = resolved.uri
                    mimeType = resolved.mimeType
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(isResolving = false, error = e.message ?: "取流失败")
            return
        }

        val item = MediaItem.Builder()
            .setUri(uri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title.ifBlank { "BiliSeek" })
                    .build(),
            )
            .build()

        _state.value = _state.value.copy(
            title = request.title,
            bvid = (request as? PlaybackRequest.Bili)?.bvid.orEmpty(),
            cid = (request as? PlaybackRequest.Bili)?.cid ?: 0L,
        )
        // 续播位置必须在 setMediaItem 时一并给出。
        // 早期写法是 prepare() 之后再 seekTo()，经 MediaController 异步转发到
        // session 时时序不可靠，seek 会被起播流程覆盖掉——表现为「续播没生效」。
        val startPosition = (request as? PlaybackRequest.Bili)?.let { r ->
            val saved = resumePositionProvider?.invoke(r.bvid, r.cid ?: 0L) ?: 0L
            resolveStartPosition(saved, media?.durationMs ?: 0L)
        } ?: 0L

        c.setMediaItem(item, startPosition)
        applyVideoTrack(enabled = false)
        c.prepare()
        c.playWhenReady = true
        _state.value = _state.value.copy(isResolving = false)
        pushState()
    }

    /** 播放一个队列。[startIndex] 指定从哪一首开始。 */
    suspend fun playQueue(items: List<PlaybackRequest>, startIndex: Int = 0) {
        queue = items
        queueIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        items.getOrNull(queueIndex)?.let { play(it, fromQueue = true) }
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        pushState()
    }

    fun skipToNext() {
        val next = resolveManualNextIndex(queueIndex, queue.size, playMode) ?: return
        queueIndex = next
        scope?.launch { queue.getOrNull(next)?.let { play(it, fromQueue = true) } }
    }

    fun skipToPrevious() {
        val prev = resolvePreviousIndex(queueIndex, queue.size, playMode) ?: return
        queueIndex = prev
        scope?.launch { queue.getOrNull(prev)?.let { play(it, fromQueue = true) } }
    }

    /** 播完自动推进。与手动切歌不同：单曲循环下这里原地重播。 */
    private fun onTrackEnded() {
        val next = resolveNextIndex(queueIndex, queue.size, playMode) ?: return
        queueIndex = next
        scope?.launch { queue.getOrNull(next)?.let { play(it, fromQueue = true) } }
    }

    /**
     * 生命周期：进后台。
     *
     * PiP 属于可见呈现态，**不回落**——否则 PiP 窗口会黑屏。
     * 这不是特例判断，而是「NONE 才回落」的自然结果。
     */
    fun onEnterBackground() {
        val target = com.biliseek.core.player.internal
            .resolvePresentationOnEnterBackground(presentation)
        if (target != presentation) setPresentation(target)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * 切换呈现态 —— 「一键切看 / 退回听」的唯一入口。
     *
     * 时序严格遵循《架构设计篇》§5.3：
     * - 进看：先 attach surface，再启用视频轨
     * - 退听：先禁用视频轨，再清 surface（顺序不能反，否则会短暂渲染到已销毁的 surface）
     *
     * 全程不 seek、不 prepare、不重建播放源——这是无缝的前提。
     */
    fun setPresentation(target: PresentationMode, surfaceView: SurfaceView? = null) {
        val c = controller ?: return
        val from = presentation
        if (from == target) return

        val wasVideo = shouldEnableVideoTrack(from)
        val willBeVideo = shouldEnableVideoTrack(target)

        val positionBefore = c.currentPosition
        beginRecording()

        if (willBeVideo) {
            if (surfaceView != null) c.setVideoSurfaceView(surfaceView)
            applyVideoTrack(enabled = true)
        } else {
            applyVideoTrack(enabled = false)
            c.clearVideoSurface()
        }

        presentation = target

        if (wasVideo != willBeVideo) {
            endRecording(
                label = switchLabel(from, target),
                positionBefore = positionBefore,
                positionAfter = c.currentPosition,
            )
        } else {
            recording = false
        }
        pushState()
    }

    fun release() {
        scope?.cancel()
        scope = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    // ── 内部 ──────────────────────────────────────────────

    private fun applyVideoTrack(enabled: Boolean) {
        val c = controller ?: return
        c.trackSelectionParameters =
            resolveTrackSelectionParameters(c.trackSelectionParameters, enabled)
    }

    private fun beginRecording() {
        bufferingEvents = 0
        seekDiscontinuities = 0
        recording = true
    }

    private fun endRecording(label: String, positionBefore: Long, positionAfter: Long) {
        val diagnostics = SwitchDiagnostics(
            label = label,
            positionBeforeMs = positionBefore,
            positionAfterMs = positionAfter,
            bufferingEvents = bufferingEvents,
            seekDiscontinuities = seekDiscontinuities,
        )
        recording = false
        _state.value = _state.value.copy(lastSwitch = diagnostics)
    }

    private fun pushState() {
        val c = controller ?: return
        val hasMedia = c.mediaItemCount > 0
        val videoSelected = c.currentTracks.groups.any { group ->
            group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected
        }
        _state.value = _state.value.copy(
            mode = resolvePlaybackMode(presentation, hasMedia),
            presentation = presentation,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.takeIf { it > 0 } ?: 0L,
            videoTrackSelected = videoSelected,
            queue = queue,
            queueIndex = queueIndex,
            playMode = playMode,
        )
        recordPositionIfNeeded()
    }

    private var lastRecordedAt = 0L

    /** 每隔一段时间回写播放位置，供续播与「最近播放」使用。 */
    private fun recordPositionIfNeeded() {
        val c = controller ?: return
        val recorder = positionRecorder ?: return
        val now = System.currentTimeMillis()
        if (now - lastRecordedAt < POSITION_RECORD_INTERVAL_MS) return
        if (!c.isPlaying) return
        val request = queue.getOrNull(queueIndex) as? PlaybackRequest.Bili ?: return
        lastRecordedAt = now
        scope?.launch {
            recorder(request.bvid, request.cid ?: 0L, c.currentPosition.coerceAtLeast(0L))
        }
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
        const val POSITION_RECORD_INTERVAL_MS = 5_000L

    }
}
