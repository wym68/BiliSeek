package com.biliseek.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主按钮显示「暂停」还是「播放」。
 *
 * 切网缓冲时 `isPlaying` 为假，只看它的话按钮变成「播放」、按下去毫无反应——
 * 这是「播放卡住」体感的一大来源。
 */
class PlaybackStateTest {

    @Test
    fun `在播显示暂停`() {
        assertTrue(PlaybackState(isPlaying = true, playWhenReady = true).showPauseButton)
    }

    @Test
    fun `想播但在缓冲也显示暂停`() {
        assertTrue(PlaybackState(isPlaying = false, isBuffering = true, playWhenReady = true).showPauseButton)
    }

    @Test
    fun `暂停着拖进度条引起的缓冲显示播放`() {
        assertFalse(PlaybackState(isPlaying = false, isBuffering = true, playWhenReady = false).showPauseButton)
    }

    @Test
    fun `出错停住显示播放——按下去就是重试`() {
        assertFalse(PlaybackState(isPlaying = false, isBuffering = false, playWhenReady = true).showPauseButton)
    }
}
