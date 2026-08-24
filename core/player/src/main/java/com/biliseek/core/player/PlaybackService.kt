package com.biliseek.core.player

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.biliseek.core.network.BiliNetwork

/**
 * 播放服务：**唯一 ExoPlayer 实例的持有者**。
 *
 * 见《架构设计篇》§5.1。播放器生命周期绑 Service 而非任何 UI 组件——
 * 「默认只听」意味着用户绝大多数时间在别的 App 里、锁着屏。
 *
 * **硬性约束：不得声明 `android:process`。** UI 通过 [MediaController] 下发的
 * `setVideoSurfaceView()` 与 `setTrackSelectionParameters()` 只在同进程下才能
 * 转发到真正的 player，而这两个调用正是「原地展开 + 无缝切轨」的全部依赖。
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // B 站 CDN 校验 Referer，取流请求不带就 403。
        // 这一步必须作用在播放器的 DataSource 上，而不只是 API 请求上。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(BiliNetwork.get(this).mediaRequestHeaders)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机 / 断蓝牙自动暂停
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 验收依赖：EventLogger 会打印轨道选择与缓冲事件。
        // 「画面消失」不能证明视频轨被禁用，只有这里的日志能证明。
        if (BuildConfig.DEBUG) {
            exoPlayer.addAnalyticsListener(EventLogger(TAG_EVENTS))
        }

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val exoPlayer = player
        if (exoPlayer == null || !exoPlayer.playWhenReady || exoPlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player?.run {
            release()
            player = null
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG_EVENTS = "BiliSeekPlayer"
    }
}
