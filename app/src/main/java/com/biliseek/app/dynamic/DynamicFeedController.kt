package com.biliseek.app.dynamic

import com.biliseek.app.DynamicInitState
import com.biliseek.app.DynamicUiState
import com.biliseek.app.comment.CommentThreadController
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.UpVisitEntity
import com.biliseek.app.data.UpVisitPolicy
import com.biliseek.app.data.nextDynamicExpandState
import com.biliseek.app.data.shouldFetchDynamicDetail
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.dto.FollowingUp
import com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 动态：关注流（全部/仅视频、按 UP 筛选、分页）+ 动态卡片互动（点赞、评论对话框、正文展开）。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.dynamics` 持有；生命周期挂在宿主的 [scope] 上。
 * 卡片互动对 UP 空间页里的动态卡片同样生效：那份列表归宿主管，经 [patchOtherEntry] /
 * [findOtherEntry] 同步乐观更新与回滚。点条目播放不在这里（走宿主的 playDynamic）。
 */
class DynamicFeedController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val library: LibraryRepository,
    private val session: StateFlow<BiliSession>,
    private val emitMessage: (String) -> Unit,
    private val patchOtherEntry: (idStr: String, transform: (DynamicEntry) -> DynamicEntry) -> Unit,
    private val findOtherEntry: (idStr: String) -> DynamicEntry?,
) {

    /** 动态评论对话框。发表时对卡片评论数乐观 +1，失败回滚。 */
    val comments = CommentThreadController(
        scope = scope,
        repository = repository,
        session = session,
        emitMessage = emitMessage,
        onPostStarted = { s -> patchCommentCount(s.key, +1) },
        onPostFailed = { s -> patchCommentCount(s.key, -1) },
    )

    /** 点赞防抖：记录每个 dyn_id_str 最近一次发起请求的时间戳。 */
    private val likeRequestGate = ConcurrentHashMap<String, Long>()

    private val _dynamic = MutableStateFlow(DynamicUiState())
    val dynamic: StateFlow<DynamicUiState> = _dynamic.asStateFlow()

    /** 登录后首次进入动态 tab 时调用：拉关注列表 + 第一页动态。 */
    fun initDynamic(forceRefresh: Boolean = false) {
        if (!session.value.isLoggedIn) return
        val cur = _dynamic.value
        if (cur.initState is DynamicInitState.Loading) return
        if (!forceRefresh && (cur.upChips.isNotEmpty() || cur.feed.items.isNotEmpty())) return
        scope.launch {
            _dynamic.value = cur.copy(initState = DynamicInitState.Loading)
            repository.followings()
                .onSuccess { ups ->
                    _dynamic.value = _dynamic.value.copy(
                        upChips = mergeUpChips(ups, library.upVisitAll()),
                        initState = DynamicInitState.Idle,
                    )
                    loadDynamicPage(offset = "")
                }
                .onFailure {
                    _dynamic.value = _dynamic.value.copy(
                        initState = DynamicInitState.Error(it.message ?: "加载失败"),
                    )
                }
        }
    }

    /**
     * 下拉刷新：重拉当前筛选（全部 / 仅视频、选中的 UP）下的第 1 页。
     *
     * 早期直接接在 [initDynamic] 上，而它不带 forceRefresh 时见到已有内容就 return——
     * 列表加载过一次之后，下拉就什么都不做。关注列表不跟着重拉：它另有 [refreshUpChips]，
     * 下拉只为看新动态，多打一个接口只是多一份风控特征。
     */
    fun refreshDynamic() {
        if (_dynamic.value.upChips.isEmpty()) initDynamic(forceRefresh = true) else loadDynamicPage(offset = "")
    }

    /** 合并关注列表与本地访问记录，产出「最常访问」栏的展示顺序。 */
    private suspend fun mergeUpChips(
        followings: List<FollowingUp>,
        visits: List<UpVisitEntity>,
    ): List<FollowingUp> = UpVisitPolicy.mergeUpChips(followings, visits)

    /** 计一次 UP 访问。失败静默：不影响主流程。 */
    private fun recordUpVisit(mid: Long) {
        if (mid <= 0) return
        scope.launch { library.recordUpVisit(mid) }
    }

    /** 「全部 / 仅视频」切换：变化必须重置游标重拉第 1 页。 */
    fun setDynamicTypeFilter(type: String) {
        val cur = _dynamic.value
        if (cur.typeFilter == type) return
        _dynamic.value = cur.copy(typeFilter = type, feed = RemoteListState.empty(), feedOffset = "")
        loadDynamicPage(offset = "")
    }

    /**
     * 按 UP 筛选：null = 回关注流，非 null = 只看该 UP。切换同样重置游标。
     *
     * 再点已选中的 UP = 取消筛选（与 B 站 App 行为一致）。
     */
    fun selectDynamicUp(up: FollowingUp?) {
        val cur = _dynamic.value
        val targetMid = if (up != null && cur.selectedUpMid == up.mid) null else up?.mid
        if (cur.selectedUpMid == targetMid) return
        _dynamic.value = cur.copy(
            selectedUpMid = targetMid,
            feed = RemoteListState.empty(),
            feedOffset = "",
        )
        loadDynamicPage(offset = "")
        // 筛选本身就是一次「访问」：计入最常访问（取消筛选不计）。
        if (up != null && targetMid != null) recordUpVisit(up.mid)
    }

    /** 强制刷新关注列表（无视缓存），关注/取关成功后调用。 */
    fun refreshUpChips() = scope.launch {
        repository.followings()
            .onSuccess { ups ->
                _dynamic.value = _dynamic.value.copy(
                    upChips = mergeUpChips(ups, library.upVisitAll()),
                )
            }
    }

    /** 加载下一页（继续翻）。 */
    fun loadMoreDynamic() {
        val cur = _dynamic.value
        if (!cur.feed.hasMore) return
        loadDynamicPage(offset = cur.feedOffset)
    }

    private fun loadDynamicPage(offset: String) {
        val cur = _dynamic.value
        if (cur.feed.isLoading || cur.feed.isLoadingMore) return
        val loadingMore = offset.isNotBlank()
        scope.launch {
            _dynamic.value = cur.feed.let { feed ->
                cur.copy(
                    feed = feed.copy(
                        isLoading = !loadingMore,
                        isLoadingMore = loadingMore,
                        errorMessage = null,
                    ),
                )
            }
            val result = if (cur.selectedUpMid != null) {
                repository.dynamicSpace(cur.selectedUpMid, offset)
            } else {
                repository.dynamicFeed(cur.typeFilter, offset)
            }
            result
                .onSuccess { pageData ->
                    val base = _dynamic.value.feed
                    _dynamic.value = _dynamic.value.copy(
                        feed = base.copy(
                            items = if (loadingMore) base.items + pageData.entries
                            else pageData.entries,
                            hasMore = pageData.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                        feedOffset = pageData.offset,
                    )
                }
                .onFailure {
                    val base = _dynamic.value.feed
                    _dynamic.value = _dynamic.value.copy(
                        feed = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    // ── 动态卡片互动（点赞 / 评论 / 正文展开），关注流与 UP 空间页共用 ──

    /**
     * 乐观更新一条动态的 stat.like：用于点赞 +1/-1 双状态。
     * 同步更新关注流（`_dynamic.feed`）与宿主持有的其他列表（UP 空间页，经 [patchOtherEntry]）。
     */
    private fun patchEntry(idStr: String, transform: (DynamicEntry) -> DynamicEntry) {
        val dyn = _dynamic.value
        val idx = dyn.feed.items.indexOfFirst { it.idStr == idStr }
        if (idx >= 0) {
            val newItems = dyn.feed.items.toMutableList().also { it[idx] = transform(it[idx]) }
            _dynamic.value = dyn.copy(feed = dyn.feed.copy(items = newItems))
        }
        patchOtherEntry(idStr, transform)
    }

    /** 点赞：500ms 内只发一次请求；乐观更新；失败回滚。 */
    fun toggleDynamicLike(idStr: String, like: Boolean) = scope.launch {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再点赞")
            return@launch
        }
        // 防抖：500ms 闸门
        val now = System.currentTimeMillis()
        val last = likeRequestGate[idStr] ?: 0L
        if (now - last < 500) return@launch
        likeRequestGate[idStr] = now

        val original = snapshotEntry(idStr)
        // 乐观更新：status 反转、count ±1
        patchEntry(idStr) { e ->
            val stat = e.stat ?: return@patchEntry e
            e.copy(stat = stat.copy(like = stat.like.copy(
                count = (stat.like.count + if (like) 1 else -1).coerceAtLeast(0),
                status = like,
            )))
        }
        repository.likeDynamic(idStr, like)
            .onSuccess { emitMessage(if (like) "已点赞" else "已取消点赞") }
            .onFailure { err ->
                // 回滚：把 entry 的 stat 还原
                original?.let { orig -> patchEntry(idStr) { _ -> orig } }
                emitMessage("点赞失败：${err.message ?: "未知错误"}")
            }
    }

    /** 取一份 entry 快照（用于失败回滚）。 */
    private fun snapshotEntry(idStr: String): DynamicEntry? {
        _dynamic.value.feed.items.firstOrNull { it.idStr == idStr }?.let { return it }
        return findOtherEntry(idStr)
    }

    /** 打开评论对话框：根据 dynamic entry 计算 oid/type，并立即加载第一页。未登录也可读。 */
    fun openCommentDialog(entry: DynamicEntry) {
        val (oid, type) = resolveDynamicCommentTargetFromEntry(
            idStr = entry.idStr,
            rawType = entry.rawType,
            drawId = entry.drawId,
            commentOid = entry.commentOid,
            commentType = entry.commentType,
        )
        comments.open(oid = oid, type = type, key = entry.idStr)
    }

    private fun patchCommentCount(idStr: String, delta: Int) {
        patchEntry(idStr) { e ->
            val stat = e.stat ?: return@patchEntry e
            e.copy(stat = stat.copy(comment = stat.comment.copy(
                count = (stat.comment.count + delta).coerceAtLeast(0),
            )))
        }
    }

    // ── 动态卡片正文原地展开/收起（四级降级拉全文）────────────────

    private val _dynamicExpand = MutableStateFlow<Map<String, DynamicExpandState>>(emptyMap())
    val dynamicExpand: StateFlow<Map<String, DynamicExpandState>> = _dynamicExpand.asStateFlow()

    /**
     * 点"展开/收起"：状态转换交给纯函数 [nextDynamicExpandState]，这里只负责按需
     * 发起网络请求（web detail → opus/detail → desktop → rid，与旧版全文弹层同一路径，
     * repository 层有 LRU 缓存）。按 idStr 独立维护，允许多张卡片同时展开。
     */
    fun toggleDynamicExpand(entry: DynamicEntry) {
        val idStr = entry.idStr
        val current = _dynamicExpand.value[idStr]
        val alreadyFetching = current?.expanded == true && current.entry == null && current.error == null
        val next = nextDynamicExpandState(current)
        _dynamicExpand.value = _dynamicExpand.value + (idStr to next)
        if (alreadyFetching || !shouldFetchDynamicDetail(next)) return
        scope.launch {
            repository.dynamicDetail(idStr, seed = entry)
                .onSuccess { detail ->
                    val cur = _dynamicExpand.value[idStr]
                    if (cur?.expanded == true && cur.entry == null) {
                        _dynamicExpand.value = _dynamicExpand.value + (idStr to cur.copy(entry = detail))
                    }
                }
                .onFailure { e ->
                    val cur = _dynamicExpand.value[idStr]
                    if (cur?.expanded == true && cur.entry == null) {
                        _dynamicExpand.value = _dynamicExpand.value + (idStr to cur.copy(error = e.message ?: "加载失败"))
                    }
                }
        }
    }
}
