package com.biliseek.app.interaction

import com.biliseek.app.collection.CollectionPolicy
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.bvToAv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放页视频互动栏（点赞 / 投币 / 收藏 / 三连）的状态与动作；简介区和合集入口的数据也出自这里的那次 view 请求。
 *
 * 请求纪律（风控）：
 * - **只在播放页展开时** [bind] 当前 bvid（纯听后台、迷你播放器都不拉）；
 * - 同一 bvid 读结果进内存 LRU，切回来不重拉；
 * - 未登录不拉 `archive/relation`（必 -101），只拉计数；
 * - 写操作 busy 期间忽略重复点击，失败回滚乐观更新。
 */
class VideoInteractionController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val emitMessage: (String) -> Unit,
) {
    private val _state = MutableStateFlow<VideoInteractionState?>(null)
    val state: StateFlow<VideoInteractionState?> = _state.asStateFlow()

    private val cache = object : LinkedHashMap<String, VideoInteractionState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoInteractionState>) = size > 16
    }
    private var loadJob: Job? = null

    /** 播放页展开 / 切歌时调用。bvid 为空（本地文件等）时清空。 */
    fun bind(bvid: String) {
        if (bvid.isBlank()) {
            loadJob?.cancel()
            _state.value = null
            return
        }
        if (!VideoActionPolicy.shouldLoad(_state.value, bvid)) return
        cache[bvid]?.let { cached ->
            _state.value = cached
            return
        }
        loadJob?.cancel()
        val fallbackAid = runCatching { bvToAv(bvid) }.getOrDefault(0L)
        _state.value = VideoInteractionState(bvid = bvid, aid = fallbackAid, isLoading = true)
        loadJob = scope.launch {
            val view = repository.videoView(bvid).getOrNull()
            val relation = if (session.value.isLoggedIn) repository.archiveRelation(bvid).getOrNull() else null
            val stat = view?.stat
            val loaded = VideoInteractionState(
                bvid = bvid,
                aid = view?.aid?.takeIf { it > 0 } ?: fallbackAid,
                likeCount = stat?.like ?: 0,
                coinCount = stat?.coin ?: 0,
                favoriteCount = stat?.favorite ?: 0,
                replyCount = stat?.reply ?: 0,
                shareCount = stat?.share ?: 0,
                liked = relation?.like ?: false,
                coined = relation?.coin ?: 0,
                favored = relation?.favorite ?: false,
                maxCoins = VideoActionPolicy.maxCoins(view?.copyright ?: 1),
                desc = view?.desc.orEmpty(),
                viewCount = stat?.view ?: 0,
                pubDate = view?.pubdate ?: 0,
                loadFailed = view == null,
                collection = view?.let(CollectionPolicy::fromView),
                seasonSubscribed = relation?.seasonFav ?: false,
            )
            // 读失败也落一个「全 0 未激活」的态，按钮照样能点；只有成功才进缓存
            if (_state.value?.bvid == bvid) _state.value = loaded
            if (view != null) cache[bvid] = loaded
        }
    }

    fun toggleLike() {
        val cur = requireWritable(VideoAction.LIKE, "点赞") ?: return
        val like = !cur.liked
        runWrite(VideoAction.LIKE, cur, VideoActionPolicy.applyLike(cur, like), failPrefix = "点赞失败") {
            repository.likeVideo(cur.bvid, like)
        }
    }

    fun addCoin(count: Int, alsoLike: Boolean) {
        val cur = requireWritable(VideoAction.COIN, "投币") ?: return
        if (VideoActionPolicy.remainingCoins(cur) == 0) {
            emitMessage("这个视频已经投满啦")
            return
        }
        runWrite(
            VideoAction.COIN, cur, VideoActionPolicy.applyCoin(cur, count, alsoLike),
            failPrefix = "投币失败", successMessage = "投币成功",
        ) {
            repository.addCoin(cur.bvid, count, alsoLike)
        }
    }

    /** 长按点赞 = 一键三连。已全部完成时只提示，不发请求。 */
    fun triple() {
        val cur = requireWritable(VideoAction.TRIPLE, "三连") ?: return
        if (VideoActionPolicy.isTripleDone(cur)) {
            emitMessage("已经三连过啦")
            return
        }
        runWrite(
            VideoAction.TRIPLE, cur, VideoActionPolicy.applyTriple(cur),
            failPrefix = "三连失败", successMessage = "三连成功",
        ) {
            repository.tripleAction(cur.bvid)
        }
    }

    /** 收藏夹弹窗加入成功后回调：把列表里包含当前视频的标记为已收藏。 */
    fun markFavored(bvids: Collection<String>) {
        val cur = _state.value ?: return
        if (cur.bvid !in bvids) return
        update(VideoActionPolicy.applyFavored(cur))
    }

    /** 合集弹层订阅 / 取消订阅成功后（宿主调）：当前视频和缓存里同一合集的视频都跟着改。 */
    fun markSeasonSubscribed(seasonId: Long, subscribed: Boolean) {
        cache.entries.forEach { it.setValue(VideoActionPolicy.applySeasonSubscribed(it.value, seasonId, subscribed)) }
        val cur = _state.value ?: return
        val next = VideoActionPolicy.applySeasonSubscribed(cur, seasonId, subscribed)
        if (next !== cur) _state.value = next
    }

    private fun requireWritable(action: VideoAction, what: String): VideoInteractionState? {
        val cur = _state.value ?: return null
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再$what")
            return null
        }
        if (action in cur.busy) return null
        return cur
    }

    private fun runWrite(
        action: VideoAction,
        before: VideoInteractionState,
        optimistic: VideoInteractionState,
        failPrefix: String,
        successMessage: String? = null,
        request: suspend () -> Result<Unit>,
    ) {
        update(optimistic.copy(busy = before.busy + action))
        scope.launch {
            request()
                .onSuccess {
                    patchBusy(before.bvid, action)
                    successMessage?.let(emitMessage)
                }
                .onFailure { err ->
                    // 回滚到操作前（保留其他进行中的 busy）
                    val now = _state.value
                    if (now?.bvid == before.bvid) update(before.copy(busy = now.busy - action))
                    else cache[before.bvid] = before.copy(busy = emptySet())
                    emitMessage("$failPrefix：${err.message ?: "未知错误"}")
                }
        }
    }

    private fun patchBusy(bvid: String, action: VideoAction) {
        val now = _state.value ?: return
        if (now.bvid != bvid) return
        update(now.copy(busy = now.busy - action))
    }

    private fun update(next: VideoInteractionState) {
        _state.value = next
        if (!next.isLoading) cache[next.bvid] = next.copy(busy = emptySet())
    }
}
