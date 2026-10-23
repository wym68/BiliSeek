package com.biliseek.app.comment

import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.internal.resolveReplyRootParent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 评论回复目标：null=发表新评论；非 null=回复某条评论（[content] 是被回复那条的原文，用于输入框上方的提示）。 */
data class CommentReplyTarget(
    val root: Long,
    val parent: Long,
    val uname: String,
    val content: String = "",
)

/** 一个评论区对话框的状态：列表 + 分页 + 楼中楼 + 回复目标 + 输入框。 */
data class CommentThreadState(
    val oid: Long,
    val type: Int,
    /** 宿主自定的来源标识（动态 = dyn_id_str，播放页 = bvid），回调里用来定位要同步的外部状态。 */
    val key: String = "",
    val content: String = "",
    val isPosting: Boolean = false,
    /** 1=热度 0=时间，对应 [BiliRepository.comments] 的 sort 参数（仓库里换成 mode）。 */
    val sort: Int = 1,
    val comments: RemoteListState<ReplyItem> = RemoteListState.empty(),
    /** 置顶评论（第一页带回来，换排序时重取）。显示在列表最前面，普通列表里已去重。 */
    val topReplies: List<ReplyItem> = emptyList(),
    /** 作者 mid，用来给作者本人的评论标「UP」。0 = 不知道。 */
    val upMid: Long = 0,
    /** 主评论下一页的游标（[BiliRepository.comments] 的 offset）。 */
    val nextOffset: String = "",
    /** 主列表最近一次失败能不能给「重试」（风控拦截时为 false）。 */
    val listErrorRetryable: Boolean = true,
    /** key=一级评论 rpid，value=其楼中楼的分页状态。未展开的评论不在这个 map 里。 */
    val expandedSubReplies: Map<Long, RemoteListState<ReplyItem>> = emptyMap(),
    /** 展开后又收起的楼中楼（rpid）。已加载的仍留在 [expandedSubReplies]，再展开直接复用、不重新请求。 */
    val collapsedSubReplies: Set<Long> = emptySet(),
    val replyTarget: CommentReplyTarget? = null,
    /** 一次性请求：输入框聚焦并弹出键盘（点「回复」时置 true，输入栏处理后回调 [CommentThreadController.onComposerFocused] 清掉）。 */
    val focusComposer: Boolean = false,
    /** 非 null = 弹层暂时藏着（跳去了别的页面），回到 id 为它的页面时再显示；状态全留着。 */
    val hiddenUntilEntry: String? = null,
)

/**
 * 评论区对话框的状态与动作（读列表、翻页、楼中楼、点赞、回复、发表）。
 *
 * 从 AppViewModel 拆出：动态评论与播放页评论原先各有一份几乎逐行相同的实现，现在各持一个实例。
 * 不是 ViewModel，生命周期挂在宿主的 [scope] 上。发表评论前后的外部副作用（如动态卡片评论数的
 * 乐观 +1 / 回滚）通过 [onPostStarted] / [onPostFailed] / [onDeleted] 交给宿主。
 */
class CommentThreadController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val emitMessage: (String) -> Unit,
    private val onPostStarted: (CommentThreadState) -> Unit = {},
    private val onPostFailed: (CommentThreadState) -> Unit = {},
    private val onDeleted: (CommentThreadState) -> Unit = {},
) {
    private val _state = MutableStateFlow<CommentThreadState?>(null)
    /** null = 对话框关闭。 */
    val state: StateFlow<CommentThreadState?> = _state.asStateFlow()

    /** 点赞防抖：记录每条评论最近一次发起请求的时间戳。 */
    private val likeRequestGate = ConcurrentHashMap<Long, Long>()

    /** 正在删除的 rpid，防连点重复请求。 */
    private val deleting = mutableSetOf<Long>()

    /** 草稿：key = (oid, type)。只在内存里，App 被杀就没了；发送成功后删掉。 */
    private val drafts = mutableMapOf<Pair<Long, Int>, CommentDraft>()

    /** 打开对话框并立即加载第一页。未登录也可读。上次没发出去的草稿（含回复对象）填回输入框。 */
    fun open(oid: Long, type: Int, key: String = "") {
        _state.value?.let { prev -> saveDraft(prev) }
        val draft = drafts[oid to type]
        _state.value = CommentThreadState(
            oid = oid,
            type = type,
            key = key,
            content = draft?.content.orEmpty(),
            replyTarget = draft?.replyTarget,
        )
        load(reset = true)
    }

    /** 关闭对话框：没发出去的内容按评论区记下来。 */
    fun dismiss() {
        val cur = _state.value ?: return
        saveDraft(cur)
        _state.value = null
    }

    private fun saveDraft(state: CommentThreadState) {
        val draft = draftOnDismiss(state)
        if (draft != null) drafts[state.oid to state.type] = draft else drafts.remove(state.oid to state.type)
    }

    /** 跳去别的页面（点人进 UP 空间）：弹层先藏起来，状态全留着；回到 [entryId] 这个页面时由 [resumeOn] 再显示。 */
    fun hideUntilBackOn(entryId: String) {
        val cur = _state.value ?: return
        _state.value = cur.copy(hiddenUntilEntry = entryId, focusComposer = false)
    }

    /** 当前页面变了：是藏起弹层时的那个页面就再显示。 */
    fun resumeOn(entryId: String?) {
        val cur = _state.value ?: return
        _state.value = cur.resumedOn(entryId)
    }

    fun updateContent(text: String) {
        val cur = _state.value ?: return
        _state.value = cur.copy(content = text)
    }

    /** 切换热度/时间排序：清空列表重新拉第 1 页。 */
    fun setSort(sort: Int) {
        val cur = _state.value ?: return
        if (cur.sort == sort) return
        _state.value = cur.copy(sort = sort, comments = RemoteListState.empty(), topReplies = emptyList(), nextOffset = "")
        load(reset = true)
    }

    fun loadMore() {
        val cur = _state.value ?: return
        if (!cur.comments.hasMore) return
        load(reset = false)
    }

    /** 第一页失败后点「重试」：重拉第一页（冷却期内本地直接失败，不会打到服务端）。 */
    fun retry() = load(reset = true)

    /** 加载评论列表。reset=true 重新拉第 1 页；false 用游标翻页追加。 */
    private fun load(reset: Boolean) {
        val cur = _state.value ?: return
        val base = if (reset) RemoteListState.empty<ReplyItem>() else cur.comments
        if (base.isLoading || base.isLoadingMore) return
        val offset = if (reset) "" else cur.nextOffset
        val loadingMore = !reset
        _state.value = cur.copy(
            comments = base.copy(isLoading = !loadingMore, isLoadingMore = loadingMore, errorMessage = null),
        )
        scope.launch {
            repository.comments(cur.oid, cur.type, cur.sort, offset)
                .onSuccess { page ->
                    val dialog = _state.value ?: return@onSuccess
                    // 等结果期间关了、换了评论区或换了排序：这页已经过时，丢掉
                    if (!dialog.sameThreadAs(cur) || dialog.sort != cur.sort) return@onSuccess
                    val pinned = if (reset) page.topReplies else dialog.topReplies
                    val before = if (reset) emptyList() else dialog.comments.items
                    val merged = mergeCommentPage(existing = before, incoming = page.items, pinned = pinned)
                    _state.value = dialog.copy(
                        topReplies = pinned,
                        upMid = if (page.upMid > 0) page.upMid else dialog.upMid,
                        nextOffset = page.nextOffset.orEmpty(),
                        comments = dialog.comments.copy(
                            items = merged,
                            page = if (reset) 1 else dialog.comments.page + 1,
                            hasMore = resolveCommentHasMore(reset, before.size, merged.size, page.nextOffset),
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                    )
                }
                .onFailure { err ->
                    val dialog = _state.value ?: return@onFailure
                    if (!dialog.sameThreadAs(cur) || dialog.sort != cur.sort) return@onFailure
                    _state.value = dialog.copy(
                        comments = dialog.comments.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = commentErrorText("评论加载", err),
                        ),
                        listErrorRetryable = isRetryableCommentError(err),
                    )
                }
        }
    }

    /** 设置回复目标：`rootRpid=null` 表示回复一级评论；非 null 表示回复该一级评论下的楼中楼。 */
    fun startReplyTo(reply: ReplyItem, rootRpid: Long? = null) {
        val cur = _state.value ?: return
        val (root, parent) = resolveReplyRootParent(reply.rpid, rootRpid)
        _state.value = cur.copy(
            focusComposer = true,
            replyTarget = CommentReplyTarget(
                root = root,
                parent = parent,
                uname = reply.member.uname,
                content = reply.content.message,
            ),
        )
    }

    fun clearReplyTarget() {
        val cur = _state.value ?: return
        _state.value = cur.copy(replyTarget = null)
    }

    /** 输入栏已聚焦：清掉一次性请求，免得恢复弹层时又弹键盘。 */
    fun onComposerFocused() {
        val cur = _state.value ?: return
        if (cur.focusComposer) _state.value = cur.copy(focusComposer = false)
    }

    /** 评论点赞 / 取消（一级评论和楼中楼都用它）：500ms 防抖，乐观更新，失败回滚。 */
    fun toggleLike(reply: ReplyItem) {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再点赞")
            return
        }
        val cur = _state.value ?: return
        val like = reply.action != 1
        val now = System.currentTimeMillis()
        val last = likeRequestGate[reply.rpid] ?: 0L
        if (now - last < 500) return
        likeRequestGate[reply.rpid] = now
        _state.value = cur.patchReply(reply.rpid) { it.withLike(like) }
        scope.launch {
            repository.replyAction(cur.oid, cur.type, reply.rpid, like)
                .onFailure { err ->
                    _state.value = _state.value?.takeIf { it.sameThreadAs(cur) }
                        ?.patchReply(reply.rpid) { it.withLike(!like) } ?: _state.value
                    emitMessage(commentErrorText("点赞", err))
                }
        }
    }

    /** 展开楼中楼：第一次点击才发请求；第一页失败后点「点击重试」再拉一次；收起过的再展开直接复用已加载的状态，不再请求。 */
    fun expandSubReplies(reply: ReplyItem) {
        val cur = _state.value ?: return
        if (reply.rpid in cur.collapsedSubReplies) {
            _state.value = cur.copy(collapsedSubReplies = cur.collapsedSubReplies - reply.rpid)
            return
        }
        if (!shouldLoadFirstSubReplyPage(cur.expandedSubReplies[reply.rpid])) return
        _state.value = cur.copy(
            expandedSubReplies = cur.expandedSubReplies + (reply.rpid to RemoteListState<ReplyItem>().copy(isLoading = true)),
        )
        scope.launch {
            repository.subReplies(cur.oid, cur.type, reply.rpid, page = 1)
                .onSuccess { page ->
                    val dialog = _state.value ?: return@onSuccess
                    _state.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (reply.rpid to RemoteListState(
                            items = page.items,
                            page = 1,
                            hasMore = page.items.size < page.count,
                            isLoading = false,
                        )),
                    )
                }
                .onFailure { err ->
                    val dialog = _state.value ?: return@onFailure
                    val prev = dialog.expandedSubReplies[reply.rpid] ?: RemoteListState<ReplyItem>()
                    _state.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (reply.rpid to prev.copy(
                            isLoading = false,
                            errorMessage = commentErrorText("楼中楼加载", err),
                        )),
                    )
                }
        }
    }

    /** 收起楼中楼：只是不显示，已加载的留着。 */
    fun collapseSubReplies(rootRpid: Long) {
        val cur = _state.value ?: return
        if (!cur.expandedSubReplies.containsKey(rootRpid)) return
        _state.value = cur.copy(collapsedSubReplies = cur.collapsedSubReplies + rootRpid)
    }

    fun loadMoreSubReplies(rootRpid: Long) {
        val cur = _state.value ?: return
        val state = cur.expandedSubReplies[rootRpid] ?: return
        if (!state.hasMore || state.isLoading || state.isLoadingMore) return
        val nextPage = state.page + 1
        _state.value = cur.copy(
            expandedSubReplies = cur.expandedSubReplies + (rootRpid to state.copy(isLoadingMore = true, errorMessage = null)),
        )
        scope.launch {
            repository.subReplies(cur.oid, cur.type, rootRpid, page = nextPage)
                .onSuccess { page ->
                    val dialog = _state.value ?: return@onSuccess
                    val prev = dialog.expandedSubReplies[rootRpid] ?: return@onSuccess
                    val merged = mergeCommentPage(existing = prev.items, incoming = page.items, pinned = emptyList())
                    _state.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (rootRpid to prev.copy(
                            items = merged,
                            page = nextPage,
                            hasMore = merged.size < page.count,
                            isLoadingMore = false,
                            errorMessage = null,
                        )),
                    )
                }
                .onFailure { err ->
                    val dialog = _state.value ?: return@onFailure
                    val prev = dialog.expandedSubReplies[rootRpid] ?: return@onFailure
                    _state.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (rootRpid to prev.copy(
                            isLoadingMore = false,
                            errorMessage = commentErrorText("楼中楼加载", err),
                        )),
                    )
                }
        }
    }

    /** 删除自己的评论（界面上已二次确认）。成功后本地移除，不重拉。`rootRpid` 非 null 表示楼中楼。 */
    fun delete(reply: ReplyItem, rootRpid: Long?) {
        val cur = _state.value ?: return
        if (!session.value.isLoggedIn || !deleting.add(reply.rpid)) return
        scope.launch {
            repository.deleteReply(cur.oid, cur.type, reply.rpid)
                .onSuccess {
                    _state.value = _state.value?.takeIf { it.sameThreadAs(cur) }
                        ?.removeReply(reply.rpid, rootRpid) ?: _state.value
                    onDeleted(cur)
                    emitMessage("已删除")
                }
                .onFailure { err -> emitMessage(commentErrorText("删除", err)) }
            deleting.remove(reply.rpid)
        }
    }

    /** 提交评论：发新评论或回复 [CommentThreadState.replyTarget]；成功后清空输入框与回复目标，把这条直接插进列表。 */
    fun submit() {
        val cur = _state.value ?: return
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再评论")
            return
        }
        if (cur.isPosting || cur.content.isBlank()) return
        _state.value = cur.copy(isPosting = true)
        val target = cur.replyTarget
        onPostStarted(cur)
        scope.launch {
            repository.addDynamicReply(
                oid = cur.oid,
                type = cur.type,
                message = cur.content,
                root = target?.root ?: 0L,
                parent = target?.parent ?: 0L,
            )
                .onSuccess { rpid ->
                    drafts.remove(cur.oid to cur.type)
                    emitMessage("评论已发送")
                    val dialog = _state.value?.takeIf { it.sameThreadAs(cur) } ?: return@onSuccess
                    val cleared = dialog.copy(content = "", isPosting = false, replyTarget = null)
                    if (rpid <= 0L) {
                        _state.value = cleared
                        load(reset = true)
                        return@onSuccess
                    }
                    val posted = buildPostedReply(rpid, cur.oid, cur.content, session.value, System.currentTimeMillis() / 1000)
                    _state.value = cleared.insertPosted(posted, target)
                }
                .onFailure { err ->
                    _state.value = _state.value?.takeIf { it.sameThreadAs(cur) }?.copy(isPosting = false) ?: _state.value
                    onPostFailed(cur)
                    emitMessage(commentErrorText("评论", err))
                }
        }
    }

    private fun CommentThreadState.sameThreadAs(other: CommentThreadState) = oid == other.oid && type == other.type
}
