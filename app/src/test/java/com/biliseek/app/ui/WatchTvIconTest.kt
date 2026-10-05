package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 小电视图标的路径是手写的字符串：写错了会在播放页首次绘制时崩，所以在单测里先构建一遍。
 */
class WatchTvIconTest {

    @Test
    fun `能构建出来，24 的画布，机身和天线两条路径`() {
        assertEquals(24f, WatchTvIcon.viewportWidth)
        assertEquals(24f, WatchTvIcon.viewportHeight)
        assertEquals(2, WatchTvIcon.root.size)
    }
}
