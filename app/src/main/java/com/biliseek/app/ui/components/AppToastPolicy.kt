package com.biliseek.app.ui.components

/**
 * 不带按钮的普通提示显示在哪（设计稿：docs/superpowers/specs/2026-10-04-toast-above-minibar-design.md）。
 *
 * 前台一律用 App 内的提示（[AppToast]）：系统 Toast 的位置挪不动，会压在迷你条上。
 */
enum class ToastTarget {
    /** 主界面的提示位：底部栏（迷你条 + 导航栏）上方。 */
    MAIN,

    /** 队列面板里的提示位：面板是独立窗口，会挡住主界面的提示。 */
    QUEUE_SHEET,

    /**
     * 系统 Toast：App 内的提示看不到的时候——App 在后台或小窗里（如后台下载完成），
     * 或别的底部弹层 / 弹窗（独立窗口）盖着主界面（它们本来就盖住了迷你条）。
     */
    SYSTEM,
}

/** [mainWindowFocused]：主窗口有焦点；底部弹层、居中弹窗开着时它们拿走焦点。 */
fun resolveToastTarget(
    foreground: Boolean,
    inPip: Boolean,
    queueSheetOpen: Boolean,
    mainWindowFocused: Boolean,
): ToastTarget = when {
    !foreground || inPip -> ToastTarget.SYSTEM
    queueSheetOpen -> ToastTarget.QUEUE_SHEET
    !mainWindowFocused -> ToastTarget.SYSTEM
    else -> ToastTarget.MAIN
}
