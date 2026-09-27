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
import com.biliseek.core.player.internal.EffectiveQuality
import com.biliseek.core.player.internal.PlayUrlResolver
import com.biliseek.core.player.internal.SleepTimerSpec
import com.biliseek.core.player.internal.deadlineAfter
import com.biliseek.core.player.internal.hasSleepTimerExpired
import com.biliseek.core.player.internal.loudnessGain
import com.biliseek.core.player.internal.resolveEffectiveQuality
import com.biliseek.core.player.internal.resolveAskedAudioQuality
import com.biliseek.core.player.internal.shouldReloadForAudioQuality
import com.biliseek.core.player.internal.shouldRequestLossless
import com.biliseek.core.player.internal.resolveEnqueueNextIndex
import com.biliseek.core.player.internal.resolveIndexAfterInsert
import com.biliseek.core.player.internal.resolveClearedQueue
import com.biliseek.core.player.internal.resolveManualNextIndex
import com.biliseek.core.player.internal.resolveStartPosition
import com.biliseek.core.player.internal.resolveNextIndex
import com.biliseek.core.player.internal.resolvePlaybackMode
import com.biliseek.core.player.internal.resolvePresentationOnTrackChange
import com.biliseek.core.player.internal.resolvePreviousIndex
import com.biliseek.core.player.internal.resolveTrackSelectionParameters
import com.biliseek.core.player.internal.VideoTrackCandidate
import com.biliseek.core.player.internal.qnOfRepresentationId
import com.biliseek.core.player.internal.resolveVideoQualityOptions
import com.biliseek.core.player.internal.resolveVideoTrackIndex
import com.biliseek.core.player.internal.shouldEnableVideoTrack
import com.biliseek.core.player.internal.shouldStopOnTrackEnd
import com.biliseek.core.player.internal.sleepTimerRemaining
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val connectMutex = Mutex()
    private var scope: CoroutineScope? = null
    private val resolver = PlayUrlResolver(BiliNetwork.get(context))

    init {
        // 离线拦截点接在解析器上：取流第一件事就是查本地下载库（M9 §9.4）
        resolver.offlineLookup = { bvid, cid -> offlineLookup?.invoke(bvid, cid) }
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var presentation: PresentationMode = PresentationMode.NONE

    /** 队列。B 站来源需逐条取流，因此不用 ExoPlayer 原生播放列表。 */
    private var queue: List<PlaybackRequest> = emptyList()
    private var queueIndex: Int = -1
    private var playMode: PlayMode = PlayMode.SEQUENTIAL

    /**
     * 每次 [play] 真正开始加载一条新内容就自增，跟"第几首"完全脱钩。
     *
     * 不能用 [queueIndex] 或 `controller.currentMediaItemIndex` 关联"这份
     * videoSize 是不是当前这首歌解出来的"——单曲起播（非队列翻页）时
     * [queueIndex] 会被无条件写死成 0，两首互不相关的歌 [queueIndex] 可能
     * 完全相同；`currentMediaItemIndex` 则因为切歌走的是 `setMediaItem()`
     * 整体替换单 item，永远是 0。只有这个自增计数器能保证「每次起播都换一个新值」。
     */
    private var trackGeneration = 0

    /** 已从持久化恢复队列（尚未起播）。用于 pushState 中 hasMedia 判断，
     *  以及 togglePlayPause 时改走「取流续播」而非直接 play controller。 */
    private var hasRestoredQueue: Boolean = false

    /** 恢复会话时记下的上次播放位置，供 [resumeFromRestoredPosition] 续播。 */
    private var restoredPositionMs: Long = 0L

    /** 起播时定位到的历史位置，由外部提供（续播）。 */
    var resumePositionProvider: (suspend (String, Long) -> Long)? = null

    /** 播放位置回写，用于续播与最近播放。 */
    var positionRecorder: (suspend (String, Long, Long) -> Unit)? = null

    /**
     * 离线命中检查：查本地下载库，命中返回本地文件。
     *
     * 在 [play] 的取流路径里被调用（取流前、风控凭据前）——
     * 飞行模式下播放已下载的歌不能被自己的风控 gate 挡住。
     */
    var offlineLookup: (suspend (String, Long?) -> OfflineMedia?)? = null

    /**
     * 时长回写：`(bvid, cid, durationSec)`。取流拿到时长后调用一次。
     *
     * 与上面两个回调并列——播放内核不认识数据库，这是本模块既有的解耦惯例。
     */
    var durationRecorder: (suspend (String, Long, Long) -> Unit)? = null

    /**
     * 起播落库。**每一条真正开始播的条目**都会调一次。
     *
     * 落点选在 [play] 而不是 UI 的三个起播入口：队列面板跳转、手动切歌、
     * 播完自动推进全都收敛到 [play]，挂在这里才不会漏掉队列里的第二条及之后。
     *
     * **必须在取流之前调用**——时长回写走的是「更新已有行」的窄接口，
     * 历史行还不存在时那次写会静默丢掉，界面继续显示「/ 00:00」。
     */
    var trackStartRecorder: (suspend (PlaybackRequest.Bili) -> Unit)? = null

    /**
     * 播放偏好（音质、响度均衡开关）。同样走回调——播放内核不认识设置存储。
     *
     * 每次取流时**现读**，因此用户改完设置下一首就生效，不必重启播放器。
     */
    var preferenceProvider: (() -> PlaybackPreferences)? = null

    /**
     * 封面图字节获取回调（**用于系统锁屏 / 通知栏**）。
     *
     * MediaSession 元数据里塞 bitmap bytes，系统就不必再拉——
     * 拉也拉不到，B 站 CDN（`i0.hdslb.com`）有 Referer 防盗链，系统进程不带 Referer 会 403。
     *
     * 返回 null 时跳过（与现状一致：显示默认音符图标）。**绝不应阻塞起播**：
     * 拿不到图就拿不到图，title + artist 已经够锁屏识别曲目。
     *
     * 在 [play] 里取流完成、构造 MediaItem 之前调用一次。有超时保护，不会拖慢起播。
     */
    var coverArtProvider: (suspend (String) -> ByteArray?)? = null

    /** 播放会话保存回调：`(session) -> Unit`。由 AppViewModel 注入。同步调用。 */
    var sessionSaver: ((PersistedSession) -> Unit)? = null

    /** 播放会话恢复回调：`() -> PersistedSession?`。由 AppViewModel 注入。同步调用。 */
    var sessionRestorer: (() -> PersistedSession?)? = null

    /** 睡眠定时器。归本类持有：用户设完就会锁屏走开，状态不能绑 UI 生命周期。 */
    private var sleepTimer: SleepTimerSpec? = null

    /** 上一次取流实际生效的音质。取流时算出，供 UI 如实显示。 */
    private var effective = EffectiveQuality(AudioQuality.AUTO, QualityFallback.NONE)

    /** 上一次取流是否真的挑中了无损轨。换音质但不必重取时，据此重算 [effective]。 */
    private var lastLosslessSelected = false

    /** 当前在播的是本地文件。本地文件没有音质档位可换。 */
    private var currentIsOffline = false

    /**
     * 本次会话里用户在播放页选过的音质，优先于设置页默认。
     * 设置页改了默认值时清掉（[clearAudioQualityOverride]），以设置页为准。
     */
    private var sessionAudioQuality: AudioQuality? = null

    /** 用户要的音质：会话选择优先，其次设置页默认。 */
    private fun requestedAudioQuality(): AudioQuality =
        sessionAudioQuality ?: (preferenceProvider?.invoke() ?: PlaybackPreferences()).audioQuality

    // 切换诊断窗口：setPresentation 期间统计 buffering 与 seek 不连续
    private var recording = false
    private var bufferingEvents = 0
    private var seekDiscontinuities = 0

    // videoSize 只在 onVideoSizeChanged 真正解出新尺寸时更新，绑定当时的
    // trackGeneration——pushState() 靠它判断这份尺寸是不是当前这首歌解出来的，
    // 对不上就是上一首的陈旧值，不能用（详见 [trackGeneration] 的说明）。
    private var lastVideoSizeGeneration = -1
    private var lastVideoAspectRatio: Float? = null

    // ── 画质 ──
    /**
     * 本次会话里用户在播放页选过的画质。选过就一直沿用到下一首、下下首，
     * 直到 App 重启——比每首都退回设置页默认值更符合「我就要看 720P」的本意。
     */
    private var sessionVideoQuality: Int? = null

    /** 当前生效的画质偏好（会话选择优先，其次设置页默认）。 */
    private var videoQualityPreference: Int = VIDEO_QUALITY_AUTO

    /** 当前条目 playurl 给的全部档位（含拿不到的）。 */
    private var acceptQualities: List<Pair<Int, String>> = emptyList()

    /**
     * 新条目已 setMediaItem、画质偏好还没套上。轨道要等 prepare 后才出现，只能在
     * onTracksChanged 里补。在 setMediaItem 那一刻才置位——取流期间上一条的轨道
     * 仍可能触发 onTracksChanged，那时套上去的锁定指向旧轨道组，没有意义。
     */
    private var pendingQualityApply = false

    /** 最近一次从轨道组里读出的档位列表与锁定档位，pushState 直接拿来用。 */
    private var videoQualities: List<VideoQualityOption> = emptyList()
    private var lockedVideoQn: Int? = null

    /** 当前条目取流补全后的 cid（见 [PlaybackState.resolvedCid]）。 */
    private var resolvedCid = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (recording && playbackState == Player.STATE_BUFFERING) bufferingEvents++
            if (playbackState == Player.STATE_ENDED) onTrackEnded()
            // 停止播放时保存会话
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                saveSession()
            }
            pushState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 从播放切换到暂停时保存会话
            if (!isPlaying) saveSession()
            pushState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (recording && reason == Player.DISCONTINUITY_REASON_SEEK) seekDiscontinuities++
            // 切歌时保存会话
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                reason == Player.DISCONTINUITY_REASON_REMOVE) {
                saveSession()
            }
            pushState()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // 新条目的轨道刚出现：把画质偏好套上去。听模式下也要套——
            // 视频轨虽然关着，锁定先生效，切到看时直接按偏好档位出画，不会先低清再跳高清
            if (pendingQualityApply && applyVideoQuality()) {
                pendingQualityApply = false
            }
            refreshVideoQualities()
            pushState()
        }

        // 拿到首帧尺寸后立刻推一次 state，UI 靠它判断按原始比例显示画面、
        // 全屏该转横屏还是保持竖屏——不等下一次 250ms 轮询。
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            lastVideoSizeGeneration = trackGeneration
            lastVideoAspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else {
                null
            }
            pushState()
        }
    }

    /**
     * 绑定播放服务。可重入：并发调用只会真正建立一次连接。
     *
     * 加锁的原因是 [play] 会自己补一次 connect——外部分享进来时，
     * 起播请求与启动时的 connect 是并发的，不锁会建出两个 controller。
     */
    suspend fun connect() = connectMutex.withLock {
        if (controller != null) return@withLock
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

    // ── 播放会话持久化 ──────────────────────────────────

    /** 保存当前播放会话到持久化存储。 */
    fun saveSession() {
        val saver = sessionSaver ?: return
        if (queue.isEmpty() || queueIndex < 0) return
        val c = controller ?: return
        val tracks = queue.filterIsInstance<PlaybackRequest.Bili>().map {
            PersistedTrack.fromBiliRequest(it)
        }
        if (tracks.isEmpty()) return
        val session = PersistedSession(
            queue = tracks,
            queueIndex = queueIndex.coerceIn(0, tracks.size - 1),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            playMode = playMode,
            presentationMode = presentation,
        )
        // 同步保存，确保进程被杀前数据已落盘
        saver(session)
    }

    /**
     * 从持久化恢复上次会话，**只碰内存、不碰播放器**。
     *
     * 恢复后 minibar 立即显示暂停态曲目；用户按下播放才走 [resumeFromRestoredPosition]
     * 真正取流。刻意不给播放器塞媒体项——URI-less 的假 MediaItem `prepare()` 会进
     * error/IDLE，再靠时序窗口去压回调，得不偿失；也避免每次开 App 都向 B 站取流。
     */
    fun restoreSession(): Boolean {
        val restorer = sessionRestorer ?: return false
        val session = restorer() ?: return false
        if (session.queue.isEmpty()) return false

        queue = session.queue.map { it.toBiliRequest() }
        queueIndex = session.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        playMode = session.playMode
        presentation = session.presentationMode
        restoredPositionMs = session.positionMs.coerceAtLeast(0L)
        // 仅在播放器为空时才算「冷恢复」。服务在进程被杀后仍存活时，controller 已连到
        // 有媒体的活会话——若此时也标记，暂停键会误走取流续播（卡顿后重启），而非暂停。
        hasRestoredQueue = (controller?.mediaItemCount ?: 0) == 0

        // 从当前曲目推导 state 元数据，确保 minibar 立即显示正确信息
        val current = session.queue.getOrNull(queueIndex)
        if (current != null) {
            _state.value = _state.value.copy(
                title = current.title,
                bvid = current.bvid,
                cid = current.cid,
                coverUrl = current.coverUrl,
                mode = resolvePlaybackMode(presentation, true),
            )
        }
        return true
    }

    /** 恢复后点击播放时调用：从上次位置取流续播。 */
    fun resumeFromRestoredPosition() {
        val request = queue.getOrNull(queueIndex) ?: return
        val position = restoredPositionMs
        scope?.launch { play(request, fromQueue = true, startPositionOverride = position) }
    }

    /**
     * 起播。B 站来源需要先取流（网络请求），因此是挂起函数。
     *
     * 起播时**先关视频轨再 prepare**——避免起播瞬间就去下载视频分片，
     * 这是「默认听」承诺在时序上的落点。
     */
    suspend fun play(
        request: PlaybackRequest,
        fromQueue: Boolean = false,
        startPositionOverride: Long? = null,
    ) = load(request, fromQueue, startPositionOverride, reload = false)

    /**
     * 同一条内容带进度重新取流：换音质、倍速跨过无损阈值时用。
     *
     * 与切歌不同，呈现态、播放/暂停、画面比例都要原样保留——
     * 用户在全屏看着视频换个音质，不该被踢回听模式。
     */
    private fun reloadCurrent() {
        val c = controller ?: return
        val item = queue.getOrNull(queueIndex) as? PlaybackRequest.Bili ?: return
        val position = c.currentPosition.coerceAtLeast(0L)
        scope?.launch { load(item, fromQueue = true, startPositionOverride = position, reload = true) }
    }

    private suspend fun load(
        request: PlaybackRequest,
        fromQueue: Boolean,
        startPositionOverride: Long?,
        reload: Boolean,
    ) {
        // 起播可能早于绑定完成——外部分享会新建一个 Activity，
        // onCreate 里就把链接喂了进来，而 connect() 还在路上。
        // 早期写法是 `controller ?: return`，请求就这么无声无息地丢了。
        if (controller == null) runCatching { connect() }
        val c = controller ?: run {
            _state.value = _state.value.copy(isResolving = false, error = "播放器未就绪")
            return
        }
        if (!fromQueue) {
            queue = listOf(request)
            queueIndex = 0
        }
        // 新内容开始加载，跟之前解出的 videoSize 划清界限（见 trackGeneration 说明）。
        trackGeneration++
        // 重新取流是同一个视频：比例沿用，不然竖屏视频的全屏会先转横屏再转回来
        if (reload) lastVideoSizeGeneration = trackGeneration
        val resumePlaying = if (reload) c.playWhenReady else true
        // 用户开始播放，清除恢复队列标志
        hasRestoredQueue = false
        _state.value = _state.value.copy(isResolving = true, error = null)

        // 先落库再取流，顺序不能反（见 trackStartRecorder 的说明）
        if (!reload) (request as? PlaybackRequest.Bili)?.let { trackStartRecorder?.invoke(it) }

        val prefs = preferenceProvider?.invoke() ?: PlaybackPreferences()
        val uri: String
        val mimeType: String?
        var isOffline = false
        var media: com.biliseek.core.player.internal.ResolvedMedia? = null
        try {
            when (request) {
                is PlaybackRequest.Direct -> {
                    uri = request.uri
                    mimeType = null
                }
                is PlaybackRequest.Bili -> {
                    // 倍速能在取流前就否掉无损，省掉一次注定要回退的请求
                    val ask = resolveAskedAudioQuality(requestedAudioQuality(), currentSpeed())
                    val resolved = resolver.resolve(request.bvid, request.cid, ask)
                    media = resolved
                    uri = resolved.uri
                    mimeType = resolved.mimeType
                    isOffline = resolved.isOffline
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isResolving = false,
                error = e.message ?: "取流失败",
                isOffline = false,
            )
            return
        }

        // 锁屏 / 通知栏封面。拿不到就拿不到，**绝不阻塞起播**：
        // - 超时保护 3s（正常 ~200ms）
        // - 拿不到 byte 数组 → 跳过 setArtworkData，系统会显示默认音符图标（与现状一致）
        val biliCover = (request as? PlaybackRequest.Bili)?.coverUrl.orEmpty()
        val coverBytes: ByteArray? = if (biliCover.isNotBlank() && coverArtProvider != null) {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(COVER_FETCH_TIMEOUT_MS) {
                    coverArtProvider!!.invoke(biliCover)
                }
            }.getOrNull()
        } else null

        val biliAuthor = (request as? PlaybackRequest.Bili)?.author.orEmpty()
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title.ifBlank { "BiliSeek" })
                    // author 为空时不设 setArtist——让 BiliSeek 作为默认值，
                    // 避免锁屏 / 通知栏出现"未知 UP 主"这种隐去品牌名的结果
                    .apply { if (biliAuthor.isNotBlank()) setArtist(biliAuthor) }
                    .apply {
                        if (coverBytes != null) {
                            setArtworkData(
                                coverBytes,
                                androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                            )
                        }
                    }
                    .build(),
            )
            .build()

        _state.value = _state.value.copy(
            title = request.title,
            bvid = (request as? PlaybackRequest.Bili)?.bvid.orEmpty(),
            cid = (request as? PlaybackRequest.Bili)?.cid ?: 0L,
            coverUrl = (request as? PlaybackRequest.Bili)?.coverUrl.orEmpty(),
        )

        // 时长回写。取流是唯一知道真实时长的地方——播放器要 prepare 完才有 duration，
        // 而用户可能在那之前就退出，界面会一直显示「/ 0s」。
        (request as? PlaybackRequest.Bili)?.let { r ->
            val durationSec = (media?.durationMs ?: 0L) / 1000
            if (durationSec > 0) {
                durationRecorder?.invoke(r.bvid, r.cid ?: 0L, durationSec)
            }
        }
        // 续播位置必须在 setMediaItem 时一并给出。
        // 早期写法是 prepare() 之后再 seekTo()，经 MediaController 异步转发到
        // session 时时序不可靠，seek 会被起播流程覆盖掉——表现为「续播没生效」。
        // 换音质重取时位置由调用方直接给出：走数据库那条路要等 5 秒节流的回写，赶不上
        val startPosition = startPositionOverride ?: (request as? PlaybackRequest.Bili)?.let { r ->
            val saved = resumePositionProvider?.invoke(r.bvid, r.cid ?: 0L) ?: 0L
            resolveStartPosition(saved, media?.durationMs ?: 0L)
        } ?: 0L

        // 上一条的画质锁定指向上一条的轨道组，对新条目无效，先清掉；新轨道出现后在
        // onTracksChanged 里按偏好重新锁定
        resolvedCid = media?.resolvedCid ?: 0L
        videoQualityPreference = sessionVideoQuality ?: prefs.videoQuality
        acceptQualities = media?.acceptQualities.orEmpty()
        videoQualities = emptyList()
        lockedVideoQn = null
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            .build()

        c.setMediaItem(item, startPosition)
        pendingQualityApply = true

        // 换内容即回到听。**禁轨、清 surface、复位呈现态三件事必须一起做**——
        // 早期只做了第一件，字段还停在 INLINE，界面于是渲染一块没有画面的 SurfaceView：
        // 黑屏、不显示封面、按钮还写着「退回听」。
        val nextPresentation = resolvePresentationOnTrackChange(presentation, sameContent = reload)
        applyVideoTrack(enabled = shouldEnableVideoTrack(nextPresentation))
        if (nextPresentation != presentation) {
            presentation = nextPresentation
            c.clearVideoSurface()
        }
        applyLoudness(media, prefs.loudnessNormalization)
        c.prepare()
        c.playWhenReady = resumePlaying

        // 记下这次实际生效的音质，供 UI 如实显示（含回退原因）
        lastLosslessSelected = media?.losslessSelected == true
        currentIsOffline = isOffline
        effective = resolveEffectiveQuality(
            requested = requestedAudioQuality(),
            speed = currentSpeed(),
            losslessSelected = lastLosslessSelected,
        )
        _state.value = _state.value.copy(
            isResolving = false,
            isOffline = isOffline,
            // 本地文件没有音质档位：显示「本地文件」而不是谎报「自动」
            audioQuality = if (isOffline) AudioQuality.AUTO else effective.quality,
            qualityFallback = if (isOffline) QualityFallback.NONE else effective.fallback,
        )
        pushState()
    }

    /**
     * 下载用取流：真实轨道直链。
     *
     * 下载管理器用它拿到 [DownloadSource] 后自行流式写盘。
     * 不走离线拦截（重新下载已下过的内容时不能命中本地文件）。
     */
    suspend fun resolveDownloadSource(
        bvid: String,
        cid: Long?,
        quality: AudioQuality = AudioQuality.AUTO,
    ): DownloadSource {
        val (resolvedCid, media) = resolver.resolveForDownload(bvid, cid, quality)
        require(media.audioUrl.isNotBlank()) { "该内容没有可下载的音频轨" }
        return DownloadSource(
            cid = resolvedCid,
            audioUrl = media.audioUrl,
            videoUrl = media.videoUrl.takeIf { it.isNotBlank() },
            durationMs = media.durationMs,
        )
    }

    /**
     * 套用响度均衡。
     *
     * 关掉开关时要显式恢复 1.0——否则上一首压下去的音量会一直带到下一首，
     * 表现为「关了开关还是小声」。
     */
    private fun applyLoudness(
        media: com.biliseek.core.player.internal.ResolvedMedia?,
        enabled: Boolean,
    ) {
        val c = controller ?: return
        val gain = if (enabled && media != null) {
            loudnessGain(media.measuredLoudness, media.targetLoudness)
        } else {
            1.0f
        }
        c.volume = gain
        // 没有日志就没法验证：增益是听觉上的细微变化，界面上看不见
        android.util.Log.d(
            TAG_LOUDNESS,
            "响度均衡 ${if (enabled) "开" else "关"}：measured=${media?.measuredLoudness} " +
                "target=${media?.targetLoudness} → gain=$gain",
        )
    }

    private fun currentSpeed(): Float = controller?.playbackParameters?.speed ?: 1.0f

    /** 播放一个队列。[startIndex] 指定从哪一首开始。 */
    suspend fun playQueue(items: List<PlaybackRequest>, startIndex: Int = 0) {
        queue = items
        queueIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        items.getOrNull(queueIndex)?.let { play(it, fromQueue = true) }
    }

    /**
     * 重放当前条目。取流失败后的「重试」入口。
     *
     * 取流失败时队列与下标都还在，重放即可——不必让 UI 记住上一次的请求参数。
     */
    fun retry() {
        val request = queue.getOrNull(queueIndex) ?: return
        scope?.launch { play(request, fromQueue = true) }
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        pushState()
    }

    /** 「下一首播放」。插在当前项之后，不打断正在播的这首。 */
    fun enqueueNext(request: PlaybackRequest) {
        if (queue.isEmpty() || queueIndex < 0) {
            scope?.launch { play(request) }
            return
        }
        val at = resolveEnqueueNextIndex(queueIndex, queue.size)
        queue = queue.toMutableList().apply { add(at, request) }
        queueIndex = resolveIndexAfterInsert(queueIndex, at)
        pushState()
    }

    /**
     * 追加到队列末尾。「加入播放列表」用：不打断当前播放、不重排 [queueIndex]、不自动起播。
     *
     * 末尾插入永远在当前项之后，故 [queueIndex] 天然不变（见 [resolveIndexAfterInsert]）。
     * 空队列时仅把它放进队列（[queueIndex] 保持 -1），待用户从队列面板点选才起播。
     */
    fun appendToQueue(request: PlaybackRequest) {
        if (request !is PlaybackRequest.Bili) return
        queue = queue + request
        pushState()
    }

    /**
     * 清空队列：保留当前播放、清掉其余。
     *
     * 不打断当前播放（主流音乐 App 的"清空列表"行为）。queueIndex 归 0；
     * 空队列 / 无当前下标时为 no-op。
     * 实现只动队列结构，不调用 persistQueue——空队列结构简单，会话保存路径
     * 自动跳过（[saveSession] 在 queue 为空时直接 return）。
     */
    fun clearQueue() {
        val (newSize, newIndex) = resolveClearedQueue(queueIndex, queue.size)
        if (newSize == queue.size && newIndex == queueIndex) return
        queue = if (newIndex >= 0 && queueIndex >= 0 && queueIndex < queue.size) {
            listOf(queue[queueIndex])
        } else {
            emptyList()
        }
        queueIndex = newIndex
        pushState()
    }

    /** 队列面板里点某一项直接跳过去。 */
    fun skipToIndex(index: Int) {
        val target = queue.getOrNull(index) ?: return
        queueIndex = index
        scope?.launch { play(target, fromQueue = true) }
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
        // 「播完当前曲目再停」优先于播放模式——单曲循环下也必须真的停下来
        if (shouldStopOnTrackEnd(sleepTimer)) {
            sleepTimer = null
            controller?.pause()
            pushState()
            return
        }
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
        // 进后台时强制保存会话，确保进程被杀前数据已落盘
        saveSession()
        val target = com.biliseek.core.player.internal
            .resolvePresentationOnEnterBackground(presentation)
        if (target != presentation) setPresentation(target)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        // 冷恢复：播放器里没有已加载媒体，但内存有恢复的队列 → 取流续播。
        // 以 controller 实况（mediaItemCount）兜底，杜绝对存活的活会话误发续播。
        if (c.mediaItemCount == 0 && hasRestoredQueue) {
            resumeFromRestoredPosition()
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * 跳转到指定位置。
     *
     * 回写不能等 [recordPositionIfNeeded] 的 5 秒节流：用户拖完进度条随手切后台，
     * 进程被杀时那一次拖动就白拖了。这里归零节流并立刻强制写一次。
     */
    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: 0L
        val target = when {
            duration > 0 -> positionMs.coerceIn(0L, duration)
            else -> positionMs.coerceAtLeast(0L)
        }
        c.seekTo(target)
        lastRecordedAt = 0L
        recordPositionNow(target)
        pushState()
    }

    /** 相对快进 / 快退。供进度条之外的入口（通知栏按钮、耳机键）复用。 */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo(c.currentPosition + deltaMs)
    }

    /** 倍速。音调由 ExoPlayer 的时间伸缩保持不变。 */
    fun setSpeed(speed: Float) {
        val c = controller ?: return
        val before = c.playbackParameters.speed
        c.setPlaybackSpeed(speed)
        pushState()

        // 无损与高倍速不兼容（《架构设计篇》§4.4 边界 1）。跨过阈值必须**真的换一份
        // manifest**——只改状态文案的话，界面说着「已回退 AAC」，放的还是无损轨。
        val requested = requestedAudioQuality()
        if (requested != AudioQuality.LOSSLESS) return
        val wasLossless = shouldRequestLossless(requested, before)
        val nowLossless = shouldRequestLossless(requested, speed)
        if (wasLossless == nowLossless) return

        reloadCurrent()
    }

    /**
     * 播放页换音质：本次会话生效，并对当前这首**带进度重新取流**。
     *
     * 音质档位决定 manifest 里放哪些音轨（省流只放一条最低码率的），
     * 所以换档必须换一份 manifest，会有一次短暂缓冲。实际要的档位没变
     * （如 2× 下无损 ⇄ 自动）时不重取，只更新显示。
     */
    fun setAudioQuality(quality: AudioQuality) {
        val before = requestedAudioQuality()
        sessionAudioQuality = quality
        if (shouldReloadForAudioQuality(before, quality, currentSpeed(), currentIsOffline) &&
            queue.getOrNull(queueIndex) is PlaybackRequest.Bili
        ) {
            reloadCurrent()
        } else {
            effective = resolveEffectiveQuality(quality, currentSpeed(), lastLosslessSelected)
        }
        pushState()
    }

    /** 设置页改了默认音质：以设置页为准，清掉播放页的临时选择。下一首起生效。 */
    fun clearAudioQualityOverride() {
        sessionAudioQuality = null
        pushState()
    }

    /**
     * 切画质。[qn] 为 [VIDEO_QUALITY_AUTO] 时恢复自适应。
     *
     * 只锁定已在 manifest 里的视频轨，**不重新取流**：不 seek、不缓冲，
     * 与听⇄看切换同一套无缝机制（见 VideoQualityPolicy）。
     */
    fun setVideoQuality(qn: Int) {
        sessionVideoQuality = qn
        videoQualityPreference = qn
        applyVideoQuality()
        refreshVideoQualities()
        pushState()
    }

    /** 设倒计时睡眠定时器。[durationMs] 非正视为取消。 */
    fun setSleepTimer(durationMs: Long) {
        sleepTimer = deadlineAfter(System.currentTimeMillis(), durationMs)
        pushState()
    }

    /** 播完当前曲目再停。 */
    fun setSleepTimerEndOfTrack() {
        sleepTimer = SleepTimerSpec.EndOfTrack
        pushState()
    }

    fun cancelSleepTimer() {
        sleepTimer = null
        pushState()
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

    /** 当前视频轨组，以及其中每条轨的最小信息。没有视频轨时返回 null。 */
    private fun videoTrackGroup(): Pair<androidx.media3.common.Tracks.Group, List<VideoTrackCandidate>>? {
        val c = controller ?: return null
        val group = c.currentTracks.groups.firstOrNull {
            it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
        } ?: return null
        val candidates = (0 until group.length).map { i ->
            val format = group.getTrackFormat(i)
            VideoTrackCandidate(
                qn = qnOfRepresentationId(format.id),
                codecs = format.codecs.orEmpty(),
                height = format.height.coerceAtLeast(0),
                supported = group.isTrackSupported(i),
            )
        }
        return group to candidates
    }

    /**
     * 按 [videoQualityPreference] 锁定（或解除锁定）视频轨。
     * 返回是否找到了视频轨组——还没 prepare 完时没有，调用方据此决定要不要稍后再补。
     */
    private fun applyVideoQuality(): Boolean {
        val c = controller ?: return false
        val (group, candidates) = videoTrackGroup() ?: return false
        val index = resolveVideoTrackIndex(candidates, videoQualityPreference)
        val builder = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
        if (index != null) {
            builder.setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index),
            )
        }
        c.trackSelectionParameters = builder.build()
        lockedVideoQn = index?.let { candidates[it].qn }
        return true
    }

    private fun refreshVideoQualities() {
        val candidates = videoTrackGroup()?.second.orEmpty()
        videoQualities = resolveVideoQualityOptions(acceptQualities, candidates)
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
        checkSleepTimer()
        val now = System.currentTimeMillis()
        // hasMedia: player 有媒体项，或已从持久化恢复队列
        val hasMedia = c.mediaItemCount > 0 || hasRestoredQueue
        val videoSelected = c.currentTracks.groups.any { group ->
            group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected
        }
        // 从当前队列条目推导元数据
        val currentTrack = queue.getOrNull(queueIndex) as? PlaybackRequest.Bili
        // 只用 onVideoSizeChanged 当时记下来的值，且要求 trackGeneration 对得上当前
        // 这次起播——c.videoSize 本身是活的、随时可能还没刷新，靠它现算容易在切歌
        // 瞬间读到上一首的陈旧尺寸。
        val videoAspectRatio = if (lastVideoSizeGeneration == trackGeneration) {
            lastVideoAspectRatio
        } else {
            null
        }
        _state.value = _state.value.copy(
            mode = resolvePlaybackMode(presentation, hasMedia),
            presentation = presentation,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.takeIf { it > 0 } ?: 0L,
            videoTrackSelected = videoSelected,
            title = currentTrack?.title.orEmpty(),
            bvid = currentTrack?.bvid.orEmpty(),
            cid = currentTrack?.cid ?: 0L,
            resolvedCid = resolvedCid,
            coverUrl = currentTrack?.coverUrl.orEmpty(),
            queue = queue,
            queueIndex = queueIndex,
            playMode = playMode,
            sleepTimerRemainingMs = sleepTimerRemaining(sleepTimer, now),
            sleepTimerEndOfTrack = sleepTimer is SleepTimerSpec.EndOfTrack,
            speed = c.playbackParameters.speed,
            requestedAudioQuality = requestedAudioQuality(),
            audioQuality = effective.quality,
            qualityFallback = effective.fallback,
            videoAspectRatio = videoAspectRatio,
            videoQualities = videoQualities,
            videoQualityPreference = videoQualityPreference,
            currentVideoQn = lockedVideoQn,
        )
        recordPositionIfNeeded()
    }

    /** 倒计时到点即暂停。250ms 的轮询循环保证了判定粒度。 */
    private fun checkSleepTimer() {
        val c = controller ?: return
        if (!hasSleepTimerExpired(sleepTimer, System.currentTimeMillis())) return
        sleepTimer = null
        if (c.isPlaying) c.pause()
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

    /** 无视节流、无视是否在播，立刻回写一次。位置由调用方给出——`seekTo` 是异步转发的，
     *  紧接着读 `currentPosition` 拿到的可能还是旧值。 */
    private fun recordPositionNow(positionMs: Long) {
        val recorder = positionRecorder ?: return
        val request = queue.getOrNull(queueIndex) as? PlaybackRequest.Bili ?: return
        lastRecordedAt = System.currentTimeMillis()
        scope?.launch {
            recorder(request.bvid, request.cid ?: 0L, positionMs.coerceAtLeast(0L))
        }
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
        const val POSITION_RECORD_INTERVAL_MS = 5_000L
        const val TAG_LOUDNESS = "BiliSeekLoudness"

        /**
         * 锁屏封面拉取超时。正常 ~200ms，3s 已是极宽松裕度。
         *
         * 设长一点是为「冷启动第一次拉大图」留余量——主流程起播不能被拖住。
         */
        const val COVER_FETCH_TIMEOUT_MS = 3_000L
    }
}
