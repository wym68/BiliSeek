package com.biliseek.app.interaction

/** 视频互动栏里会发写请求的动作（用于 busy 防连点）。 */
enum class VideoAction { LIKE, COIN, TRIPLE }

/**
 * 播放页视频互动栏的状态：计数 + 当前账号的互动态。
 *
 * 计数来自 `x/web-interface/view` 的 stat，互动态来自 `archive/relation`（需登录）。
 * 简介、播放量、发布时间同出自那次 view 响应，顺带给播放页的简介区用，不另发请求。
 * 写操作全部乐观更新，失败回滚到操作前的快照。
 */
data class VideoInteractionState(
    val bvid: String,
    val aid: Long = 0,
    val likeCount: Long = 0,
    val coinCount: Long = 0,
    val favoriteCount: Long = 0,
    val replyCount: Long = 0,
    val shareCount: Long = 0,
    val liked: Boolean = false,
    /** 当前账号已投币数。 */
    val coined: Int = 0,
    val favored: Boolean = false,
    /** 自制 2 枚、转载 1 枚。 */
    val maxCoins: Int = 2,
    /** 简介原文（未处理；是否显示见 [com.biliseek.app.ui.VideoDescriptionPolicy.shouldShow]）。 */
    val desc: String = "",
    val viewCount: Long = 0,
    /** 发布时间（Unix 秒），0 = 未知。 */
    val pubDate: Long = 0,
    val isLoading: Boolean = false,
    /** 上次拉取失败（没网等），数字是占位的 0。再进播放页时重拉（[VideoActionPolicy.shouldLoad]）。 */
    val loadFailed: Boolean = false,
    val busy: Set<VideoAction> = emptySet(),
)

/**
 * 视频互动的纯函数：投币上限、乐观更新。无副作用，配单测。
 */
object VideoActionPolicy {

    /** copyright：1 自制 → 2 枚；2 转载 → 1 枚；其他值按自制处理。 */
    fun maxCoins(copyright: Int): Int = if (copyright == 2) 1 else 2

    /**
     * 播放页展开 / 切歌时要不要拉这个视频的计数。同一视频已拉到或正在拉就不重拉；
     * 上次失败了要重拉——否则没网时打开过一次，网回来后数字一直是空的，只有切到别的视频才好。
     */
    fun shouldLoad(current: VideoInteractionState?, bvid: String): Boolean =
        current?.bvid != bvid || current.loadFailed

    /** 还能投几枚。 */
    fun remainingCoins(state: VideoInteractionState): Int = (state.maxCoins - state.coined).coerceAtLeast(0)

    /** 投币弹窗可选的枚数（空 = 已投满）。 */
    fun coinChoices(state: VideoInteractionState): List<Int> = (1..remainingCoins(state)).toList()

    fun applyLike(state: VideoInteractionState, like: Boolean): VideoInteractionState {
        if (state.liked == like) return state
        return state.copy(
            liked = like,
            likeCount = (state.likeCount + if (like) 1 else -1).coerceAtLeast(0),
        )
    }

    /** 投 [count] 枚（超过剩余额度时夹紧），[alsoLike] 同时点赞。 */
    fun applyCoin(state: VideoInteractionState, count: Int, alsoLike: Boolean): VideoInteractionState {
        val n = count.coerceIn(0, remainingCoins(state))
        if (n == 0) return state
        val coined = state.copy(coined = state.coined + n, coinCount = state.coinCount + n)
        return if (alsoLike) applyLike(coined, true) else coined
    }

    /** 三连：点赞 + 投满 + 收藏，已完成的部分不重复计数。 */
    fun applyTriple(state: VideoInteractionState): VideoInteractionState {
        val liked = applyLike(state, true)
        val coined = applyCoin(liked, remainingCoins(liked), alsoLike = false)
        return if (coined.favored) coined else coined.copy(favored = true, favoriteCount = coined.favoriteCount + 1)
    }

    /** 三连是否还有意义（已赞、已投满、已收藏 → 长按不再发请求）。 */
    fun isTripleDone(state: VideoInteractionState): Boolean =
        state.liked && remainingCoins(state) == 0 && state.favored

    /** 收藏夹弹窗加入成功后标记已收藏（只在之前未收藏时 +1）。 */
    fun applyFavored(state: VideoInteractionState): VideoInteractionState =
        if (state.favored) state else state.copy(favored = true, favoriteCount = state.favoriteCount + 1)
}
