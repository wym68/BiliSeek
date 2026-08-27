package com.biliseek.core.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FLAC 输入缓冲的判定。
 *
 * **这条路径没有真机验收**（撞上它需要大会员账号 + 特定机型的解码器，两样都没有），
 * 因此这些用例就是它唯一的担保。判据不是「抬得对不对」，而是三条边界：
 * 只动 FLAC、不缩小已够大的缓冲、缺省值不导致漏抬。
 */
class FlacBufferPolicyTest {

    @Test
    fun `FLAC 且缓冲不足时抬到 1 MiB`() {
        // 32 KiB 正是出问题的那些机型给的值
        assertEquals(FLAC_MIN_INPUT_SIZE, resolveCodecMaxInputSize("audio/flac", 32 * 1024))
    }

    @Test
    fun `系统没给缓冲值时也要抬`() {
        // MediaFormat 里取不到 KEY_MAX_INPUT_SIZE 时调用方传 0，不能因此漏掉
        assertEquals(FLAC_MIN_INPUT_SIZE, resolveCodecMaxInputSize("audio/flac", 0))
    }

    @Test
    fun `已经足够大就不动`() {
        // 只抬不降：系统给得比我们多时，改写反而是缩小
        assertNull(resolveCodecMaxInputSize("audio/flac", FLAC_MIN_INPUT_SIZE))
        assertNull(resolveCodecMaxInputSize("audio/flac", 4 shl 20))
    }

    @Test
    fun `非 FLAC 一律不动`() {
        // AAC / 杜比没这个毛病，无谓地要大缓冲只是白占内存
        assertNull(resolveCodecMaxInputSize("audio/mp4a-latm", 0))
        assertNull(resolveCodecMaxInputSize("audio/eac3", 1024))
        assertNull(resolveCodecMaxInputSize("video/hevc", 0))
        assertNull(resolveCodecMaxInputSize("", 0))
    }

    @Test
    fun `MIME 大小写不敏感`() {
        // 不同 ROM 给的大小写并不统一
        assertEquals(FLAC_MIN_INPUT_SIZE, resolveCodecMaxInputSize("Audio/FLAC", 1024))
    }
}
