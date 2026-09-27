package com.biliseek.app.ui

import com.biliseek.core.player.VIDEO_QUALITY_AUTO
import com.biliseek.core.player.VideoQualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityLabelPolicyTest {

    private val options = listOf(
        VideoQualityOption(112, "1080P 高码率", available = false),
        VideoQualityOption(80, "1080P 高清", available = true),
        VideoQualityOption(64, "720P 准高清", available = true),
    )

    @Test
    fun `档位名取空格前的短写`() {
        assertEquals("1080P", shortQualityLabel("1080P 高清"))
        assertEquals("4K", shortQualityLabel("4K"))
        assertEquals("720P", shortQualityLabel(" 720P 准高清 "))
    }

    @Test
    fun `自动时按钮写自动`() {
        assertEquals("自动", videoQualityButtonLabel(options, VIDEO_QUALITY_AUTO, null))
    }

    @Test
    fun `锁定时按钮写实际档位`() {
        assertEquals("720P", videoQualityButtonLabel(options, 64, 64))
        // 选了拿不到的 1080P+，实际落在 1080P
        assertEquals("1080P", videoQualityButtonLabel(options, 112, 80))
    }

    @Test
    fun `所选档位生效时不需要解释`() {
        assertNull(videoQualityFallbackLabel(options, 64, 64))
        assertNull(videoQualityFallbackLabel(options, VIDEO_QUALITY_AUTO, null))
    }

    @Test
    fun `所选档位没生效时给出实际档位`() {
        assertEquals("1080P", videoQualityFallbackLabel(options, 112, 80))
    }
}
