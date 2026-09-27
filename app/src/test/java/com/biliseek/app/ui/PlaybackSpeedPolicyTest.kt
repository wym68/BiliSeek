package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedPolicyTest {

    @Test
    fun `档位覆盖 0点5 到 3 且含 1`() {
        assertEquals(0.5f, PLAYBACK_SPEEDS.first())
        assertEquals(3.0f, PLAYBACK_SPEEDS.last())
        assertTrue(PLAYBACK_SPEEDS.any { isSameSpeed(it, 1.0f) })
        assertEquals(PLAYBACK_SPEEDS.sorted(), PLAYBACK_SPEEDS)
    }

    @Test
    fun `档位文案去掉多余的零`() {
        assertEquals("0.5×", formatSpeed(0.5f))
        assertEquals("0.75×", formatSpeed(0.75f))
        assertEquals("1×", formatSpeed(1.0f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("2×", formatSpeed(2.0f))
    }

    @Test
    fun `1倍时按钮显示入口文案`() {
        assertEquals("倍速", speedButtonLabel(1.0f))
        assertEquals("倍速", speedButtonLabel(1.0001f))
    }

    @Test
    fun `非1倍时按钮显示当前档位`() {
        assertEquals("1.5×", speedButtonLabel(1.5f))
        assertEquals("0.75×", speedButtonLabel(0.75f))
    }

    @Test
    fun `浮点误差内算同一档`() {
        assertTrue(isSameSpeed(1.25f, 1.2500001f))
        assertFalse(isSameSpeed(1.25f, 1.5f))
    }
}
