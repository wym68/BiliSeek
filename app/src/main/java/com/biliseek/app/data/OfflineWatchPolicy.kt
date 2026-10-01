package com.biliseek.app.data

/** 要切到「看」时，当前条目是只下了音频的本地文件，该怎么办。 */
enum class OfflineWatch {
    /** 不是这种情况：照常切（之后照常过流量闸门）。 */
    NORMAL,

    /** 联网：从当前位置改为在线播放，音视频都有（之后照常过流量闸门）。 */
    ONLINE,

    /** 「记住上次在看」自动进看模式：不为它联网，留在听。 */
    STAY_LISTENING,

    /** 没网：留在听，并提示只下载了音频。 */
    NO_NETWORK,
}

/**
 * 只下了音频的本地文件没有视频轨，「看」只会是一块黑屏。用户主动点「看」时改为联网播放；
 * 起播时按「记住上次在看」自动进看模式的，不为它悄悄联网（用户下载就是为了离线听）。纯函数，配单测。
 *
 * @param audioOnlyOffline 当前在播的是本地文件且没有视频轨
 */
fun resolveOfflineWatch(audioOnlyOffline: Boolean, hasNetwork: Boolean, automatic: Boolean): OfflineWatch = when {
    !audioOnlyOffline -> OfflineWatch.NORMAL
    automatic -> OfflineWatch.STAY_LISTENING
    !hasNetwork -> OfflineWatch.NO_NETWORK
    else -> OfflineWatch.ONLINE
}
