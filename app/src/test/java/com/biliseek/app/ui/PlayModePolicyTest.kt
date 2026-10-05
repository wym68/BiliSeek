package com.biliseek.app.ui

import com.biliseek.core.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayModePolicyTest {

    @Test
    fun `点一下按顺序 列表循环 单曲循环 随机 的顺序循环`() {
        assertEquals(PlayMode.REPEAT_ALL, nextPlayMode(PlayMode.SEQUENTIAL))
        assertEquals(PlayMode.REPEAT_ONE, nextPlayMode(PlayMode.REPEAT_ALL))
        assertEquals(PlayMode.SHUFFLE, nextPlayMode(PlayMode.REPEAT_ONE))
        assertEquals(PlayMode.SEQUENTIAL, nextPlayMode(PlayMode.SHUFFLE))
    }

    @Test
    fun `连点四次回到原来的模式`() {
        PlayMode.entries.forEach { start ->
            var mode = start
            repeat(PlayMode.entries.size) { mode = nextPlayMode(mode) }
            assertEquals(start, mode)
        }
    }

    @Test
    fun `四种模式的名字`() {
        assertEquals("顺序播放", playModeLabel(PlayMode.SEQUENTIAL))
        assertEquals("列表循环", playModeLabel(PlayMode.REPEAT_ALL))
        assertEquals("单曲循环", playModeLabel(PlayMode.REPEAT_ONE))
        assertEquals("随机播放", playModeLabel(PlayMode.SHUFFLE))
    }

    @Test
    fun `名字互不相同——切换提示靠它分辨`() {
        val labels = PlayMode.entries.map(::playModeLabel)
        assertEquals(labels.size, labels.toSet().size)
    }
}
