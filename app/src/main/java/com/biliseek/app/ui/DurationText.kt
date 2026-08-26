package com.biliseek.app.ui

import com.biliseek.core.network.parseDurationText
import com.biliseek.core.player.formatDuration

/**
 * 把搜索接口返回的时长文本规整成全 App 统一的写法。
 *
 * 接口给的是**分钟不进位**的 `mm:ss`——一个 11 小时的合集会显示成 `667:37`，
 * 而歌单与播放页走的是 [formatDuration] 的 `h:mm:ss`。同一个视频三处显示不同，
 * 看着像 bug。
 *
 * **解析不出来时原样返回**：`parseDurationText` 对脏数据返回 0，
 * 若不回退，界面会把「时长未知」显示成一个看似确凿的 `00:00`——
 * 那比不规整更糟。
 */
fun normalizeDurationText(raw: String): String {
    val seconds = parseDurationText(raw)
    return if (seconds > 0) formatDuration(seconds * 1000) else raw
}
