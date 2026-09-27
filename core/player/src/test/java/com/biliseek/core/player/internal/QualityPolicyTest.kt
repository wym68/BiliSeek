package com.biliseek.core.player.internal

import com.biliseek.core.network.dto.Dash
import com.biliseek.core.network.dto.DashTrack
import com.biliseek.core.network.dto.Dolby
import com.biliseek.core.network.dto.Flac
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.QualityFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音质策略。
 *
 * 这里锁死的是「用户选了却没生效时，界面说的原因对不对」——
 * 悄悄降级而不说明，比不支持更糟。
 */
class QualityPolicyTest {

    private fun aac(id: Int, bandwidth: Long) = DashTrack(
        id = id,
        baseUrl = "https://cdn/$id.m4s",
        bandwidth = bandwidth,
        mimeType = "audio/mp4",
        codecs = "mp4a.40.2",
    )

    private val dash = Dash(
        duration = 100,
        video = emptyList(),
        audio = listOf(aac(30280, 192_000), aac(30232, 132_000), aac(30216, 67_000)),
    )

    // ── 取流前：要不要去要无损 ──────────────────────────

    @Test
    fun `没选无损就不去要`() {
        assertFalse(shouldRequestLossless(AudioQuality.AUTO, speed = 1.0f))
        assertFalse(shouldRequestLossless(AudioQuality.SAVER, speed = 1.0f))
    }

    @Test
    fun `选了无损且倍速不超过阈值才去要`() {
        assertTrue(shouldRequestLossless(AudioQuality.LOSSLESS, speed = 1.0f))
        assertTrue(shouldRequestLossless(AudioQuality.LOSSLESS, speed = 1.5f))
        // 1.5 是包含在内的：常用档还能听无损
        assertFalse(shouldRequestLossless(AudioQuality.LOSSLESS, speed = 1.6f))
        assertFalse(shouldRequestLossless(AudioQuality.LOSSLESS, speed = 2.0f))
    }

    // ── 取流后：到底生没生效 ──────────────────────────

    @Test
    fun `非无损档位不产生回退`() {
        assertEquals(
            EffectiveQuality(AudioQuality.AUTO, QualityFallback.NONE),
            resolveEffectiveQuality(AudioQuality.AUTO, speed = 2.0f, losslessSelected = false),
        )
        assertEquals(
            EffectiveQuality(AudioQuality.SAVER, QualityFallback.NONE),
            resolveEffectiveQuality(AudioQuality.SAVER, speed = 1.0f, losslessSelected = false),
        )
    }

    @Test
    fun `无损生效时如实报告`() {
        assertEquals(
            EffectiveQuality(AudioQuality.LOSSLESS, QualityFallback.NONE),
            resolveEffectiveQuality(AudioQuality.LOSSLESS, speed = 1.0f, losslessSelected = true),
        )
    }

    @Test
    fun `没有无损轨时报不可用`() {
        assertEquals(
            EffectiveQuality(AudioQuality.AUTO, QualityFallback.UNAVAILABLE),
            resolveEffectiveQuality(AudioQuality.LOSSLESS, speed = 1.0f, losslessSelected = false),
        )
    }

    @Test
    fun `高倍速优先报倍速不兼容而不是不可用`() {
        // 倍速是用户刚做的动作，因果最直观；报「本视频无无损轨」会让人以为换个视频就行
        assertEquals(
            EffectiveQuality(AudioQuality.AUTO, QualityFallback.SPEED_INCOMPATIBLE),
            resolveEffectiveQuality(AudioQuality.LOSSLESS, speed = 2.0f, losslessSelected = false),
        )
    }

    // ── 轨道挑选 ────────────────────────────────────

    @Test
    fun `AUTO 保留全部 AAC 轨并按码率降序`() {
        val selection = selectAudioTracks(dash, AudioQuality.AUTO)
        assertEquals(listOf(192_000L, 132_000L, 67_000L), selection.tracks.map { it.bandwidth })
        assertFalse(selection.lossless)
    }

    @Test
    fun `SAVER 只留最低码率一条`() {
        // 多留几条 ExoPlayer 会按带宽自适应升上去，「省流量」就落空了
        val selection = selectAudioTracks(dash, AudioQuality.SAVER)
        assertEquals(listOf(67_000L), selection.tracks.map { it.bandwidth })
    }

    @Test
    fun `LOSSLESS 命中 flac 时只放那一条`() {
        val flac = DashTrack(id = 30251, baseUrl = "https://cdn/flac.m4s", bandwidth = 1_000_000)
        val selection = selectAudioTracks(dash.copy(flac = Flac(display = true, audio = flac)), AudioQuality.LOSSLESS)
        assertEquals(listOf(30251), selection.tracks.map { it.id })
        assertTrue(selection.lossless)
    }

    @Test
    fun `LOSSLESS 没有 flac 时退杜比`() {
        val dolbyTrack = DashTrack(id = 30250, baseUrl = "https://cdn/dolby.m4s", codecs = "ec-3")
        val selection = selectAudioTracks(
            dash.copy(dolby = Dolby(type = 1, audio = listOf(dolbyTrack))),
            AudioQuality.LOSSLESS,
        )
        assertEquals(listOf(30250), selection.tracks.map { it.id })
        assertTrue(selection.lossless)
    }

    @Test
    fun `杜比 type 为 0 或编码不对时不算数`() {
        val notDolby = DashTrack(id = 30250, baseUrl = "https://cdn/x.m4s", codecs = "mp4a.40.2")
        val typeZero = dash.copy(dolby = Dolby(type = 0, audio = listOf(notDolby.copy(codecs = "ec-3"))))
        val wrongCodec = dash.copy(dolby = Dolby(type = 1, audio = listOf(notDolby)))

        assertFalse(selectAudioTracks(typeZero, AudioQuality.LOSSLESS).lossless)
        assertFalse(selectAudioTracks(wrongCodec, AudioQuality.LOSSLESS).lossless)
    }

    @Test
    fun `无损都拿不到时回退 AAC 而不是空列表`() {
        // 空列表会让取流抛「无可播放音频流」——选了无损不该变成放不出声
        val selection = selectAudioTracks(dash, AudioQuality.LOSSLESS)
        assertEquals(3, selection.tracks.size)
        assertFalse(selection.lossless)
    }

    @Test
    fun `没有 url 的轨道一律丢掉`() {
        val broken = dash.copy(
            audio = listOf(aac(30280, 192_000).copy(baseUrl = ""), aac(30232, 132_000)),
            flac = Flac(display = true, audio = DashTrack(id = 30251, baseUrl = "")),
        )
        val lossless = selectAudioTracks(broken, AudioQuality.LOSSLESS)
        assertFalse(lossless.lossless)
        assertEquals(listOf(30232), lossless.tracks.map { it.id })
    }

    // ── 播放页换音质 ──

    @Test
    fun `实际要的档位 无损在低倍速下要无损 高倍速下要 AAC`() {
        assertEquals(AudioQuality.LOSSLESS, resolveAskedAudioQuality(AudioQuality.LOSSLESS, 1.0f))
        assertEquals(AudioQuality.AUTO, resolveAskedAudioQuality(AudioQuality.LOSSLESS, 2.0f))
        assertEquals(AudioQuality.SAVER, resolveAskedAudioQuality(AudioQuality.SAVER, 2.0f))
        assertEquals(AudioQuality.AUTO, resolveAskedAudioQuality(AudioQuality.AUTO, 1.0f))
    }

    @Test
    fun `换档导致实际要的档位变化时重新取流`() {
        assertTrue(shouldReloadForAudioQuality(AudioQuality.AUTO, AudioQuality.LOSSLESS, 1.0f, isOffline = false))
        assertTrue(shouldReloadForAudioQuality(AudioQuality.AUTO, AudioQuality.SAVER, 1.0f, isOffline = false))
    }

    @Test
    fun `高倍速下无损与自动互换不重新取流`() {
        assertFalse(shouldReloadForAudioQuality(AudioQuality.AUTO, AudioQuality.LOSSLESS, 2.0f, isOffline = false))
        assertFalse(shouldReloadForAudioQuality(AudioQuality.LOSSLESS, AudioQuality.AUTO, 2.0f, isOffline = false))
    }

    @Test
    fun `本地文件换档不重新取流`() {
        assertFalse(shouldReloadForAudioQuality(AudioQuality.AUTO, AudioQuality.LOSSLESS, 1.0f, isOffline = true))
    }

    @Test
    fun `档位没变不重新取流`() {
        assertFalse(shouldReloadForAudioQuality(AudioQuality.SAVER, AudioQuality.SAVER, 1.0f, isOffline = false))
    }
}
