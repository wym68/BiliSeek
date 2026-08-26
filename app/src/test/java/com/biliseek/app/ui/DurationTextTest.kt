package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索结果行的时长规整。
 *
 * 真正的判据不是「好看」，而是**脏数据不能被伪装成确凿的 `00:00`**——
 * 那会让用户以为这是个零长度的视频。
 */
class DurationTextTest {

    @Test
    fun `分钟不进位的接口原样转成 h_mm_ss`() {
        // B 站给的是 667 分 37 秒，不是 667 小时
        assertEquals("11:07:37", normalizeDurationText("667:37"))
    }

    @Test
    fun `一小时以内保持 mm_ss`() {
        assertEquals("04:32", normalizeDurationText("4:32"))
        assertEquals("55:42", normalizeDurationText("55:42"))
    }

    @Test
    fun `已经是三段式的也能规整`() {
        assertEquals("1:02:03", normalizeDurationText("1:02:03"))
    }

    @Test
    fun `解析不出来时原样返回而不是显示 00_00`() {
        assertEquals("--:--", normalizeDurationText("--:--"))
        assertEquals("", normalizeDurationText(""))
        assertEquals("直播中", normalizeDurationText("直播中"))
    }

    @Test
    fun `零时长原样返回`() {
        // 解析得到 0 与「解析失败」不可区分，一律回退原文
        assertEquals("0:00", normalizeDurationText("0:00"))
    }
}
