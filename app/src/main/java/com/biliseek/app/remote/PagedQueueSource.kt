package com.biliseek.app.remote

import com.biliseek.core.player.PlaybackRequest

/** 分页列表的一页，已换算成播放请求。 */
data class QueuePage(val items: List<PlaybackRequest>, val hasMore: Boolean)

/**
 * 分页列表（合集等）作为播放来源时的续页（docs/10-播放队列工单.md）。
 *
 * 分页状态自己维护，**不复用**界面上的列表状态：用户播着合集 A 又去翻合集 B，
 * 界面状态已经换成 B，续页不能串到 B 上。
 *
 * 每次 [next] 只拉一页。没有更多、请求失败、拿到空页之后一律返回 null 且不再请求——
 * 失败不重试：风控（-352）下重试只会把限制拉长。
 *
 * @param nextPage 下一页页码（界面已加载到第 N 页就传 N+1）
 */
class PagedQueueSource(
    private var nextPage: Int,
    private var hasMore: Boolean,
    private val fetch: suspend (page: Int) -> Result<QueuePage>,
) {
    suspend fun next(): List<PlaybackRequest>? {
        if (!hasMore) return null
        val page = fetch(nextPage).getOrNull()
        if (page == null || page.items.isEmpty()) {
            hasMore = false
            return null
        }
        nextPage++
        hasMore = page.hasMore
        return page.items
    }
}
