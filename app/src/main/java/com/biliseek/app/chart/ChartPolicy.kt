package com.biliseek.app.chart

import com.biliseek.app.ui.formatPlayCount
import com.biliseek.core.network.ChartItem
import com.biliseek.core.player.formatDuration

/** 榜单行的副行：「作者 · 时长 · 播放量」，没有的项省略（热歌榜只有歌手）。 */
internal fun chartRowSubtitle(item: ChartItem): String = listOfNotNull(
    item.author.takeIf { it.isNotBlank() },
    item.durationSec.takeIf { it > 0 }?.let { formatDuration(it * 1000) },
    item.playCount?.takeIf { it > 0 }?.let { "${formatPlayCount(it)} 播放" },
).joinToString(" · ")
