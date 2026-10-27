package com.biliseek.app.upspace

import com.biliseek.app.data.RemoteListState
import com.biliseek.app.remote.appendUnseen
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.SpaceVideo

/** UP 空间页的纯函数。无副作用，配单测。 */
object UpSpacePolicy {

    /**
     * 空间页的 UP 是不是当前登录账号本人。
     *
     * 是本人时：不显示关注按钮、不查关注状态、不计入「最常访问」，关注操作直接忽略——
     * B 站不允许关注自己，按钮点了只会报「关注失败」，查关注状态也是白发一次请求。
     * 账号 mid 取本地会话的 `DedeUserID`，不发请求。未登录或 mid 无效一律不算本人。
     */
    fun isSelf(upMid: Long, session: BiliSession): Boolean =
        upMid > 0 && session.isLoggedIn && session.dedeUserId.toLongOrNull() == upMid

    /**
     * 投稿列表拿到一页之后的状态：第 1 页替换、往后的页接在后面，并记下这是第几页。
     *
     * 页码必须记：原先没记，「加载更多」一直去拉第 0 + 1 = 第 1 页，投稿翻不过前 30 条（2026-10-27 修）。
     * 接页时同一视频不重复：翻页期间 UP 发了新视频，后一页会和前一页错开一条，列表按 bvid 当 key，重复会崩。
     */
    fun applyPostsPage(
        base: RemoteListState<SpaceVideo>,
        page: BiliRepository.SpaceArchivePage,
        loadingMore: Boolean,
    ): RemoteListState<SpaceVideo> = base.copy(
        items = appendUnseen(if (loadingMore) base.items else emptyList(), page.items.distinctBy { it.bvid }, emptySet()) {
            it.bvid
        },
        page = page.page,
        hasMore = page.hasMore,
        isLoading = false,
        isLoadingMore = false,
        errorMessage = null,
    )
}
