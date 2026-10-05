package com.biliseek.app.ui

import com.biliseek.app.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 设置里的「深色模式」（设计稿：docs/superpowers/specs/2026-10-04-minibar-float-and-theme-design.md）。 */
class ThemePolicyTest {

    @Test
    fun `跟随系统时和系统一致`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `选了浅色或深色就不管系统`() {
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }
}
