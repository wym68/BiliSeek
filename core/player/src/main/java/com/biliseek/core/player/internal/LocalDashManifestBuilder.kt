package com.biliseek.core.player.internal

import com.biliseek.core.network.dto.Dash
import com.biliseek.core.network.dto.DashTrack
import com.biliseek.core.player.AudioQuality
import java.util.Locale

/**
 * 把 B 站的 DASH JSON 拼装成标准 MPD。
 *
 * B 站 playurl 返回的是自定义 JSON 而非标准 MPD，Media3 无法直接吃。
 *
 * **硬性要求：manifest 必须同时含音、视频两个 AdaptationSet，无论当前是听还是看模式。**
 * 这是无缝切轨的前提——两条轨在同一个 MediaSource 里，切换才不需要重建播放源。
 * 省流量靠的是禁用视频轨后 ExoPlayer 不请求视频分片，不是靠 manifest 里不放视频。
 */
internal fun buildLocalDashManifest(
    durationMs: Long,
    minBufferMs: Long,
    videoTracks: List<DashTrack>,
    audioTracks: List<DashTrack>,
): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append(
        """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" """ +
            """profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" """ +
            """mediaPresentationDuration="${durationMs.toIsoDuration()}" """ +
            """minBufferTime="${minBufferMs.toIsoDuration()}">""",
    ).append('\n')
    append("  <Period>").append('\n')

    if (videoTracks.isNotEmpty()) {
        append("""    <AdaptationSet contentType="video" mimeType="${videoTracks.first().mimeType.esc()}">""")
        append('\n')
        videoTracks.forEach { appendVideo(it) }
        append("    </AdaptationSet>").append('\n')
    }
    if (audioTracks.isNotEmpty()) {
        append("""    <AdaptationSet contentType="audio" mimeType="${audioTracks.first().mimeType.esc()}">""")
        append('\n')
        audioTracks.forEach { appendAudio(it) }
        append("    </AdaptationSet>").append('\n')
    }

    append("  </Period>").append('\n')
    append("</MPD>").append('\n')
}

/** manifest 的输入：视频轨、音频轨，以及这批音频轨是不是无损 / 杜比。 */
internal data class CollectedTracks(
    val videos: List<DashTrack>,
    val audios: List<DashTrack>,
    val lossless: Boolean,
)

/**
 * 从 dash 数据里挑出要放进 manifest 的轨道。
 *
 * 音频轨的挑选规则归 [selectAudioTracks]——那是策略，配了单测；这里只负责组装。
 */
internal fun collectTracks(
    dash: Dash,
    quality: AudioQuality = AudioQuality.AUTO,
): CollectedTracks {
    val videos = dash.video.filter { it.url().isNotBlank() }.sortedByDescending { it.id }
    val audio = selectAudioTracks(dash, quality)
    return CollectedTracks(videos, audio.tracks, audio.lossless)
}

private fun StringBuilder.appendVideo(t: DashTrack) {
    append(
        """      <Representation id="${t.id}" bandwidth="${t.bandwidth}" """ +
            """codecs="${t.codecs.esc()}" width="${t.width}" height="${t.height}" """ +
            """frameRate="${t.frameRate.ifBlank { "25" }.esc()}">""",
    ).append('\n')
    appendCommon(t)
}

private fun StringBuilder.appendAudio(t: DashTrack) {
    append(
        """      <Representation id="${t.id}" bandwidth="${t.bandwidth}" codecs="${t.codecs.esc()}">""",
    ).append('\n')
    append("""        <AudioChannelConfiguration """)
    append("""schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>""")
    append('\n')
    appendCommon(t)
}

private fun StringBuilder.appendCommon(t: DashTrack) {
    // B 站的 URL 里带 & 查询参数，不转义会让 XML 解析直接崩
    append("        <BaseURL>${t.url().esc()}</BaseURL>").append('\n')
    t.segment()?.let { seg ->
        val range = seg.range()
        if (range.isNotBlank()) {
            append("""        <SegmentBase indexRange="${range.esc()}">""").append('\n')
            if (seg.initialization.isNotBlank()) {
                append("""          <Initialization range="${seg.initialization.esc()}"/>""").append('\n')
            }
            append("        </SegmentBase>").append('\n')
        }
    }
    append("      </Representation>").append('\n')
}

private fun Long.toIsoDuration(): String =
    String.format(Locale.US, "PT%.3fS", coerceAtLeast(0L) / 1000.0)

private fun String.esc(): String = this
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
