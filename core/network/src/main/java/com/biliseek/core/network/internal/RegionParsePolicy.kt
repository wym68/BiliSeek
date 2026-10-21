package com.biliseek.core.network.internal

import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.RegionPage
import com.biliseek.core.network.dto.NewlistRankData
import com.biliseek.core.network.dto.NewlistRankVideo
import com.biliseek.core.network.normalizeCoverUrl

/** `newlist_rank` 的 data → 一页。data / result 为 null 是空页；接口没给页码时用请求的页码。 */
internal fun NewlistRankData?.toRegionPage(requestedPage: Int): RegionPage = RegionPage(
    items = this?.result.orEmpty().mapNotNull { it.toChartItem() },
    page = this?.page?.takeIf { it > 0 } ?: requestedPage,
    totalPages = this?.numPages ?: 0,
)

/** 没有 bvid（已失效）的跳过；播放量字符串不是数字时为 null。 */
internal fun NewlistRankVideo.toChartItem(): ChartItem? =
    if (bvid.isBlank()) {
        null
    } else {
        ChartItem(
            bvid = bvid,
            title = title,
            coverUrl = normalizeCoverUrl(pic),
            author = author,
            durationSec = duration,
            playCount = play.toLongOrNull()?.takeIf { it > 0 },
            rank = null,
            authorMid = mid,
        )
    }
