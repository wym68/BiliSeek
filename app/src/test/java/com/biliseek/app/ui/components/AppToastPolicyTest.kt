package com.biliseek.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** 普通提示显示在哪（设计稿：docs/superpowers/specs/2026-10-04-toast-above-minibar-design.md）。 */
class AppToastPolicyTest {

    @Test
    fun `前台时显示在主界面的提示位`() {
        assertEquals(ToastTarget.MAIN, resolveToastTarget(foreground = true, inPip = false, queueSheetOpen = false, mainWindowFocused = true))
    }

    @Test
    fun `队列面板开着时显示在面板里 否则会被面板挡住`() {
        assertEquals(ToastTarget.QUEUE_SHEET, resolveToastTarget(foreground = true, inPip = false, queueSheetOpen = true, mainWindowFocused = false))
    }

    @Test
    fun `后台或小窗里看不到 App 内的提示 照旧用系统 Toast`() {
        assertEquals(ToastTarget.SYSTEM, resolveToastTarget(foreground = false, inPip = false, queueSheetOpen = false, mainWindowFocused = false))
        assertEquals(ToastTarget.SYSTEM, resolveToastTarget(foreground = false, inPip = false, queueSheetOpen = true, mainWindowFocused = false))
        assertEquals(ToastTarget.SYSTEM, resolveToastTarget(foreground = true, inPip = true, queueSheetOpen = false, mainWindowFocused = true))
    }

    @Test
    fun `别的弹层或弹窗盖着主界面时用系统 Toast 否则 App 内的提示被挡住`() {
        // 底部弹层、居中弹窗都是独立窗口，开着时主窗口没有焦点；它们本来就盖住了迷你条
        assertEquals(ToastTarget.SYSTEM, resolveToastTarget(foreground = true, inPip = false, queueSheetOpen = false, mainWindowFocused = false))
    }
}
