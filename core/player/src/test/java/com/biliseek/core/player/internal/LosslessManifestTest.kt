package com.biliseek.core.player.internal

import com.biliseek.core.network.dto.Dash
import com.biliseek.core.network.dto.DashTrack
import com.biliseek.core.network.dto.Dolby
import com.biliseek.core.network.dto.Flac
import com.biliseek.core.network.dto.SegmentBase
import com.biliseek.core.player.AudioQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无损 / 杜比一路走到 MPD 的端到端逻辑验证。
 *
 * **为什么要单独写这一层**：正向命中无损需要大会员账号，手上没有，
 * 真机上永远只能看到「回退 AAC」那一支。于是从 `selectAudioTracks` 到
 * `buildLocalDashManifest` 这条链只剩逻辑可验——那就把它验透：
 * 挑对了轨**且真的写进了 manifest**，而不是挑对了却漏在组装那一步。
 *
 * 拿到大会员之后，真机上要补的只剩「声音确实是无损」这一条听感判断。
 */
class LosslessManifestTest {

    private fun track(id: Int, bandwidth: Long, mime: String, codecs: String) = DashTrack(
        id = id,
        baseUrl = "https://cdn.example/$id.m4s",
        bandwidth = bandwidth,
        mimeType = mime,
        codecs = codecs,
        segmentBase = SegmentBase(initialization = "0-100", indexRange = "101-200"),
    )

    private val aacHigh = track(30280, 192_000, "audio/mp4", "mp4a.40.2")
    private val aacLow = track(30216, 67_000, "audio/mp4", "mp4a.40.2")
    private val flac = track(30251, 1_200_000, "audio/mp4", "fLaC")
    private val dolby = track(30250, 400_000, "audio/mp4", "ec-3")
    private val video = track(80, 2_900_000, "video/mp4", "avc1.640028")

    private val dash = Dash(
        duration = 200,
        video = listOf(video),
        audio = listOf(aacHigh, aacLow),
        dolby = Dolby(type = 1, audio = listOf(dolby)),
        flac = Flac(display = true, audio = flac),
    )

    private fun manifestFor(quality: AudioQuality): String {
        val tracks = collectTracks(dash, quality)
        return buildLocalDashManifest(
            durationMs = 200_000,
            minBufferMs = 1500,
            videoTracks = tracks.videos,
            audioTracks = tracks.audios,
        )
    }

    @Test
    fun `无损档位下 flac 轨真的进了 manifest`() {
        val mpd = manifestFor(AudioQuality.LOSSLESS)
        assertTrue("应含 flac Representation", mpd.contains("""id="30251""""))
        assertTrue("应含 flac 的 BaseURL", mpd.contains("30251.m4s"))
        // 无损是独占的：混进 AAC 会让 ExoPlayer 按带宽自适应掉回去
        assertFalse("不应含 AAC 轨", mpd.contains("""id="30280""""))
        assertFalse("不应含 AAC 轨", mpd.contains("""id="30216""""))
    }

    @Test
    fun `没有 flac 时杜比轨进 manifest`() {
        val noFlac = dash.copy(flac = null)
        val tracks = collectTracks(noFlac, AudioQuality.LOSSLESS)
        val mpd = buildLocalDashManifest(200_000, 1500, tracks.videos, tracks.audios)
        assertTrue(tracks.lossless)
        assertTrue("应含杜比 Representation", mpd.contains("""id="30250""""))
        assertTrue("codecs 应保留 ec-3", mpd.contains("""codecs="ec-3""""))
    }

    @Test
    fun `无损档位下视频轨照样在 manifest 里`() {
        // 无缝切看的前提：音视频两个 AdaptationSet 必须同时存在，
        // 换音质不能把视频轨挤掉，否则切看时要重建播放源
        val mpd = manifestFor(AudioQuality.LOSSLESS)
        assertTrue(mpd.contains("""contentType="video""""))
        assertTrue(mpd.contains("""contentType="audio""""))
        assertTrue(mpd.contains("""id="80""""))
    }

    @Test
    fun `省流量档位只写最低码率那条`() {
        val mpd = manifestFor(AudioQuality.SAVER)
        assertTrue(mpd.contains("""id="30216""""))
        assertFalse(mpd.contains("""id="30280""""))
        assertFalse(mpd.contains("""id="30251""""))
    }

    @Test
    fun `自动档位保留全部 AAC 但不碰无损`() {
        val mpd = manifestFor(AudioQuality.AUTO)
        assertTrue(mpd.contains("""id="30280""""))
        assertTrue(mpd.contains("""id="30216""""))
        assertFalse("没选无损就不该拿大会员轨", mpd.contains("""id="30251""""))
    }

    @Test
    fun `无损轨的 URL 里的与号被转义`() {
        // B 站的 CDN URL 带查询参数，& 不转义会让 XML 解析直接崩——
        // 这条在 AAC 上早就成立，换成 flac 走的是同一段组装代码，一并钉住
        val withQuery = flac.copy(baseUrl = "https://cdn.example/f.m4s?a=1&b=2")
        val tracks = collectTracks(dash.copy(flac = Flac(true, withQuery)), AudioQuality.LOSSLESS)
        val mpd = buildLocalDashManifest(200_000, 1500, tracks.videos, tracks.audios)
        assertTrue(mpd.contains("a=1&amp;b=2"))
        assertFalse(mpd.contains("a=1&b=2"))
    }
}
