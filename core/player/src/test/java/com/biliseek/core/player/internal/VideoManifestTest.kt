package com.biliseek.core.player.internal

import com.biliseek.core.network.dto.Dash
import com.biliseek.core.network.dto.DashTrack
import com.biliseek.core.network.dto.SegmentBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频轨写进 MPD 后的 Representation id。
 *
 * 画质锁定靠从 ExoPlayer 的 `Format.id` 解析 qn；同一档的 AVC / HEVC 两条轨
 * B 站给的 id 相同，直接写进去会撞 id。
 */
class VideoManifestTest {

    private fun track(id: Int, mime: String, codecs: String) = DashTrack(
        id = id,
        baseUrl = "https://cdn.example/$id-$codecs.m4s",
        bandwidth = 1_000_000,
        mimeType = mime,
        codecs = codecs,
        segmentBase = SegmentBase(initialization = "0-100", indexRange = "101-200"),
    )

    private val dash = Dash(
        duration = 100,
        video = listOf(
            track(80, "video/mp4", "avc1.640032"),
            track(80, "video/mp4", "hev1.1.6.L150.90"),
            track(64, "video/mp4", "avc1.640028"),
        ),
        audio = listOf(track(30280, "audio/mp4", "mp4a.40.2")),
    )

    private fun videoIds(mpd: String): List<String> {
        val videoSet = mpd.substringAfter("contentType=\"video\"").substringBefore("</AdaptationSet>")
        return Regex("""Representation id="([^"]+)"""").findAll(videoSet).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `同一档多种编码的视频轨 id 不重复`() {
        val tracks = collectTracks(dash)
        val mpd = buildLocalDashManifest(100_000, 1500, tracks.videos, tracks.audios)
        val ids = videoIds(mpd)
        assertEquals(3, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `视频轨 id 能解析回 qn`() {
        val tracks = collectTracks(dash)
        val mpd = buildLocalDashManifest(100_000, 1500, tracks.videos, tracks.audios)
        val qns = videoIds(mpd).map(::qnOfRepresentationId)
        assertEquals(listOf(80, 80, 64), qns)
        assertTrue("音频轨 id 保持原样", mpd.contains("""id="30280""""))
    }
}
