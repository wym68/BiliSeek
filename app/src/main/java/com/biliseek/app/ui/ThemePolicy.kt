package com.biliseek.app.ui

import com.biliseek.app.data.ThemeMode

/**
 * 设置里的「深色模式」落到主题上：跟随系统就看 [systemDark]，选了浅色 / 深色就不管系统
 * （设计稿：docs/superpowers/specs/2026-10-04-minibar-float-and-theme-design.md）。
 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
