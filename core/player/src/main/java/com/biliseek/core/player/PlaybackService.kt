package com.biliseek.core.player

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.player.internal.CdnHostHealth
import com.biliseek.core.player.internal.StreamLoadErrorPolicy
import java.io.IOException

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

        // 加载失败怎么重试：没网就一直等网回来，有网却连不上就换线路、再不行上浮去重新取流（见该类的说明）
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val deviceOnline = {
            connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        val loadErrorPolicy = StreamLoadErrorPolicy(deviceOnline)

        val exoPlayer = ExoPlayer.Builder(this)
            // Hi-Res FLAC 在部分机型上会因输入缓冲太小而崩，见该类的说明
            .setRenderersFactory(
                com.biliseek.core.player.internal.HiResCompatibleRenderersFactory(this),
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorPolicy),
            )
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

        // 有网却连不上的主机记下来：重新取流组装 manifest 时排到最后，自动恢复才不会再撞同一个坏节点。
        // 没网时不记——那时所有主机都会失败，记了只会把好节点也打入冷宫
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                if (error !is HttpDataSource.HttpDataSourceException || !deviceOnline()) return
                loadEventInfo.uri.host?.let(CdnHostHealth::recordFailure)
            }
        })

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
