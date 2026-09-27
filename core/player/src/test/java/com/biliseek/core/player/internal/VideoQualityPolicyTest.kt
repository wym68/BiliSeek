package com.biliseek.core.player.internal

import com.biliseek.core.player.VIDEO_QUALITY_AUTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityPolicyTest {

    private fun track(qn: Int, codecs: String = "avc1.640032", supported: Boolean = true, height: Int = 0) =
        VideoTrackCandidate(qn, codecs, height, supported)

    // 典型登录用户：1080P / 720P / 480P，每档 AVC + HEVC
    private val typical = listOf(
        track(80, "avc1.640032"), track(80, "hev1.1.6.L150.90"),
        track(64, "avc1.640028"), track(64, "hev1.1.6.L120.90"),
        track(32, "avc1.64001F"), track(32, "hev1.1.6.L120.90"),
    )

    // ── 选轨 ──

    @Test
    fun `自动不锁定`() {
        assertNull(resolveVideoTrackIndex(typical, VIDEO_QUALITY_AUTO))
    }

    @Test
    fun `偏好档位存在时选它且优先 AVC`() {
        assertEquals(2, resolveVideoTrackIndex(typical, 64))
    }

    @Test
    fun `AVC 设备不支持时退到 HEVC`() {
        val tracks = listOf(track(80, "avc1.640032", supported = false), track(80, "hev1.1.6.L150.90"))
        assertEquals(1, resolveVideoTrackIndex(tracks, 80))
    }

    @Test
    fun `偏好档位不存在时往下找最接近的`() {
        // 选了 1080P+（112），账号拿不到，落到 1080P
        assertEquals(0, resolveVideoTrackIndex(typical, 112))
        // 选了 720P60（74），没有，落到 720P（64）
        assertEquals(2, resolveVideoTrackIndex(typical, 74))
    }

    @Test
    fun `比所有档位都低时选能播的最低一档`() {
        assertEquals(4, resolveVideoTrackIndex(typical, 16))
    }

    @Test
    fun `一条能播的都没有时不锁定`() {
        val tracks = listOf(track(80, supported = false), track(64, supported = false))
        assertNull(resolveVideoTrackIndex(tracks, 80))
        assertNull(resolveVideoTrackIndex(emptyList(), 80))
    }

    @Test
    fun `4K 只有 HEVC 时选 HEVC`() {
        val tracks = listOf(track(120, "hev1.2.4.L153.90"), track(80, "avc1.640032"))
        assertEquals(0, resolveVideoTrackIndex(tracks, 120))
    }

    @Test
    fun `编码优先级 AVC HEVC AV1`() {
        assertTrue(codecRank("avc1.640032") < codecRank("hev1.1.6.L150.90"))
        assertTrue(codecRank("hvc1.1.6.L150.90") < codecRank("av01.0.08M.08"))
        assertTrue(codecRank("av01.0.08M.08") < codecRank("vp09"))
    }

    // ── 档位列表 ──

    @Test
    fun `有 accept 列表时包含锁住的档位`() {
        val accepted = listOf(112 to "1080P 高码率", 80 to "1080P 高清", 64 to "720P 准高清", 32 to "480P 清晰")
        val options = resolveVideoQualityOptions(accepted, typical)
        assertEquals(listOf(112, 80, 64, 32), options.map { it.qn })
        assertEquals(listOf(false, true, true, true), options.map { it.available })
        assertEquals("1080P 高码率", options.first().label)
    }

    @Test
    fun `没有 accept 列表时按轨道组装`() {
        val tracks = listOf(track(80, height = 1080), track(64, height = 720), track(64, "hev1", height = 720))
        val options = resolveVideoQualityOptions(emptyList(), tracks)
        assertEquals(listOf(80, 64), options.map { it.qn })
        assertEquals(listOf("1080P", "720P"), options.map { it.label })
    }

    @Test
    fun `能播的不到两档时不给入口`() {
        assertTrue(resolveVideoQualityOptions(emptyList(), listOf(track(80))).isEmpty())
        val accepted = listOf(80 to "1080P", 64 to "720P")
        assertTrue(resolveVideoQualityOptions(accepted, listOf(track(64))).isEmpty())
    }

    @Test
    fun `未知 qn 用高度命名`() {
        assertEquals("1440P", qnLabel(999, 1440))
        assertEquals("qn999", qnLabel(999, 0))
    }

    // ── Representation id ──

    @Test
    fun `Representation id 唯一且能解析回 qn`() {
        val a = videoRepresentationId(80, 0)
        val b = videoRepresentationId(80, 1)
        assertTrue(a != b)
        assertEquals(80, qnOfRepresentationId(a))
        assertEquals(80, qnOfRepresentationId(b))
    }

    @Test
    fun `解析不了的 id 返回 0`() {
        assertEquals(0, qnOfRepresentationId(null))
        assertEquals(0, qnOfRepresentationId("abc"))
    }
}
