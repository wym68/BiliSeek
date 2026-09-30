package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelSnapPolicyTest {
    private val collapsed = 0.6f

    @Test
    fun releaseWithoutFlingSnapsToNearest() {
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(0.6f, 0f, collapsed))
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(0.75f, 0f, collapsed)) // 中点 0.8 以下
        assertEquals(PanelSnap.EXPANDED, PanelSnapPolicy.target(0.85f, 0f, collapsed))
        assertEquals(PanelSnap.EXPANDED, PanelSnapPolicy.target(1f, 0f, collapsed))
    }

    @Test
    fun draggedFarBelowCollapsedDismisses() {
        assertEquals(PanelSnap.DISMISS, PanelSnapPolicy.target(0.4f, 0f, collapsed))
        // 只是稍微往下拉一点，弹回收起高度
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(0.5f, 0f, collapsed))
    }

    @Test
    fun flingUpExpandsFromAnywhere() {
        assertEquals(PanelSnap.EXPANDED, PanelSnapPolicy.target(0.6f, 1.0f, collapsed))
        assertEquals(PanelSnap.EXPANDED, PanelSnapPolicy.target(0.45f, 1.0f, collapsed))
    }

    @Test
    fun flingDownCollapsesWhenExpandedElseDismisses() {
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(1f, -1.0f, collapsed))
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(0.85f, -1.0f, collapsed))
        assertEquals(PanelSnap.DISMISS, PanelSnapPolicy.target(0.6f, -1.0f, collapsed))
        assertEquals(PanelSnap.DISMISS, PanelSnapPolicy.target(0.5f, -1.0f, collapsed))
    }

    @Test
    fun slowVelocityBelowThresholdIsTreatedAsNoFling() {
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(0.7f, PanelSnapPolicy.FLING - 0.01f, collapsed))
        assertEquals(PanelSnap.COLLAPSED, PanelSnapPolicy.target(0.7f, -(PanelSnapPolicy.FLING - 0.01f), collapsed))
    }
}
