package com.biliseek.core.player.internal

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/**
 * 视频轨开关 —— 无缝切看的底层机制。
 *
 * 关闭时同时设置 `maxVideoSize(0,0)`：仅 `setTrackTypeDisabled` 在部分场景下
 * 仍可能选中视频轨，加上尺寸约束才能确保 ExoPlayer 不请求视频分片。
 * 这一步是「听模式省流量」承诺的实际兑现点。
 */
internal fun resolveTrackSelectionParameters(
    current: TrackSelectionParameters,
    videoEnabled: Boolean,
): TrackSelectionParameters {
    val builder = current.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled)
    return if (videoEnabled) {
        builder.clearVideoSizeConstraints().build()
    } else {
        builder.setMaxVideoSize(0, 0).build()
    }
}
