package com.biliseek.app.message

import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.TrackListId
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.FollowerEntry
import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.dto.PrivateMessage
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.videoBvid
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 消息中心（私信 + 回复与@ + 收到喜欢 + 新增粉丝 + 未读角标）的状态与动作。
 *
 * 从 AppViewModel 拆出：它不是 ViewModel，生命周期挂在宿主 ViewModel 的 [scope] 上，
 * 由 `AppViewModel.messages` 持有。与播放/导航的耦合只通过构造参数里的回调：
 * [play]（点条目播放视频）、[openUpSpace]（点粉丝进 UP 空间）、[emitMessage]（Toast）。
 */
class MessageCenterController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val emitMessage: (String) -> Unit,
    private val play: (PlaybackRequest) -> Unit,
    private val openUpSpace: (SearchUser) -> Unit,
) {

    /** 消息中心主体：私信会话列表。 */
    private val _privateSessions = MutableStateFlow(
        RemoteListState<Session>(id = TrackListId.PrivateSession, title = "私信"),
    )
    val privateSessions: StateFlow<RemoteListState<Session>> = _privateSessions.asStateFlow()

    /** 回复与@ 子页面。 */
    private val _replyFeed = MutableStateFlow(
        RemoteListState<ReplyAtItem>(id = TrackListId.MessageReply, title = "回复与@"),
    )
    val replyFeed: StateFlow<RemoteListState<ReplyAtItem>> = _replyFeed.asStateFlow()

    /** 收到喜欢 子页面。 */
    private val _likeFeed = MutableStateFlow(
        RemoteListState<LikeItem>(id = TrackListId.MessageLike, title = "收到喜欢"),
    )
    val likeFeed: StateFlow<RemoteListState<LikeItem>> = _likeFeed.asStateFlow()

    /** 新增粉丝 子页面。 */
    private val _followerFeed = MutableStateFlow(
        RemoteListState<FollowerEntry>(id = TrackListId.MessageFollower, title = "新增粉丝"),
    )
    val followerFeed: StateFlow<RemoteListState<FollowerEntry>> = _followerFeed.asStateFlow()

    /** 入口未读角标：动态页铃铛 + 消息中心两个图标角标共用同一份数据，避免多发请求。 */
    private val _unreadSummary = MutableStateFlow(BiliRepository.MessageUnreadSummary())
    val unreadSummary: StateFlow<BiliRepository.MessageUnreadSummary> = _unreadSummary.asStateFlow()

    /** 打开消息中心的信号。 */
    private val _openMessagesRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openMessagesRequests: SharedFlow<Unit> = _openMessagesRequests.asSharedFlow()

    /** 打开"回复与@"子页面的信号。 */
    private val _openReplyFeedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openReplyFeedRequests: SharedFlow<Unit> = _openReplyFeedRequests.asSharedFlow()

    /** 打开"收到喜欢"子页面的信号。 */
    private val _openLikeFeedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLikeFeedRequests: SharedFlow<Unit> = _openLikeFeedRequests.asSharedFlow()

    /** 打开"新增粉丝"子页面的信号。 */
    private val _openFollowerFeedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openFollowerFeedRequests: SharedFlow<Unit> = _openFollowerFeedRequests.asSharedFlow()

    /** 进入私信聊天页的信号。 */
    private val _openPrivateChatRequests = MutableSharedFlow<PrivateChatNav>(extraBufferCapacity = 1)
    val openPrivateChatRequests: SharedFlow<PrivateChatNav> = _openPrivateChatRequests.asSharedFlow()

    /** 当前聊天页状态（key = talkerId）。 */
    private val _chatByTalker = MutableStateFlow<Map<Long, PrivateChatState>>(emptyMap())
    val chatByTalker: StateFlow<Map<Long, PrivateChatState>> = _chatByTalker.asStateFlow()

    /**
     * 点铃铛 / 消息中心入口：只发导航信号，不预拉数据——`MessageCenterScreen` 自己的
     * `onInit` 负责加载私信列表（懒加载策略）。**不要**在这里调用 `loadPrivateSessions()`，
     * 会重蹈 v1 的导航死循环（`onInit` 接到同时发信号又加载的函数 → 每次进页面重发信号 →
     * 重新 push → 重新触发 onInit → 无限循环）。
     */
    fun openMessages() { _openMessagesRequests.tryEmit(Unit) }

    /** 点"回复与@"图标：只发导航信号，子页面自己的 onInit 负责加载。 */
    fun openReplyFeed() { _openReplyFeedRequests.tryEmit(Unit) }

    /** 点"收到喜欢"图标：只发导航信号，子页面自己的 onInit 负责加载。 */
    fun openLikeFeed() { _openLikeFeedRequests.tryEmit(Unit) }

    /** 点"新增粉丝"图标：只发导航信号，子页面自己的 onInit 负责加载。 */
    fun openFollowerFeed() { _openFollowerFeedRequests.tryEmit(Unit) }

    /** 消息中心页 onInit / 下拉刷新共用。 */
    fun retryPrivateSessions() = loadPrivateSessions()

    private fun loadPrivateSessions() {
        if (!session.value.isLoggedIn) {
            _privateSessions.value = _privateSessions.value.copy(items = emptyList(), errorMessage = null, isLoading = false)
            return
        }
        _privateSessions.value = _privateSessions.value.copy(isLoading = true, errorMessage = null)
        scope.launch {
            repository.privateSessions()
                .onSuccess { data ->
                    _privateSessions.value = _privateSessions.value.copy(
                        items = data.sessionList,
                        hasMore = data.hasMore == 1,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                .onFailure { e ->
                    _privateSessions.value = _privateSessions.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** "回复与@"子页面 onInit / 下拉刷新共用。 */
    fun retryReplyFeed() = loadReplyFeed()

    private fun loadReplyFeed() {
        _replyFeed.value = _replyFeed.value.copy(isLoading = true, errorMessage = null)
        scope.launch {
            repository.replyFeed()
                .onSuccess { data ->
                    _replyFeed.value = _replyFeed.value.copy(
                        items = data.items,
                        hasMore = data.hasMore,
                        isLoading = false,
                        errorMessage = null,
                    )
                    refreshUnreadCount()
                }
                .onFailure { e ->
                    _replyFeed.value = _replyFeed.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** "收到喜欢"子页面 onInit / 下拉刷新共用。 */
    fun retryLikeFeed() = loadLikeFeed()

    private fun loadLikeFeed() {
        _likeFeed.value = _likeFeed.value.copy(isLoading = true, errorMessage = null)
        scope.launch {
            repository.likeFeed()
                .onSuccess { data ->
                    _likeFeed.value = _likeFeed.value.copy(
                        items = data.items,
                        hasMore = data.hasMore,
                        isLoading = false,
                        errorMessage = null,
                    )
                    refreshUnreadCount()
                }
                .onFailure { e ->
                    _likeFeed.value = _likeFeed.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** "新增粉丝"子页面 onInit / 下拉刷新共用。 */
    fun retryFollowerFeed() = loadFollowerFeed()

    private fun loadFollowerFeed() {
        _followerFeed.value = _followerFeed.value.copy(isLoading = true, errorMessage = null)
        scope.launch {
            repository.followerFeed()
                .onSuccess { data ->
                    _followerFeed.value = _followerFeed.value.copy(
                        items = data.list,
                        hasMore = false,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                .onFailure { e ->
                    _followerFeed.value = _followerFeed.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** 入口未读角标：动态页铃铛 / 消息中心两个图标共用，best-effort，失败不抛。 */
    fun refreshUnreadCount() {
        if (!session.value.isLoggedIn) {
            _unreadSummary.value = BiliRepository.MessageUnreadSummary()
            return
        }
        scope.launch {
            _unreadSummary.value = repository.messageUnread()
        }
    }

    /** 点会话条目：跳到聊天页。 */
    fun openPrivateChat(session: Session) {
        val peerName = session.accountInfo?.name.orEmpty().ifBlank { "用户${session.talkerId}" }
        val peerFace = session.accountInfo?.face.orEmpty()
        _openPrivateChatRequests.tryEmit(
            PrivateChatNav(talkerId = session.talkerId, peerName = peerName, peerFace = peerFace),
        )
    }

    /** 进入聊天页 → 拉首屏 + 标记已读。 */
    fun openChat(talkerId: Long) {
        ensureChatState(talkerId)
        loadChatPage(talkerId, beginSeqno = 0L, isLoadMore = false)
    }

    /** 上滑加载更早消息。 */
    fun loadMoreChat(talkerId: Long) {
        val s = _chatByTalker.value[talkerId] ?: return
        if (s.isLoadingMore || !s.hasMore) return
        val nextBegin = (s.minSeqno - 1).coerceAtLeast(0L)
        loadChatPage(talkerId, beginSeqno = nextBegin, isLoadMore = true)
    }

    private fun loadChatPage(talkerId: Long, beginSeqno: Long, isLoadMore: Boolean) {
        val cur = ensureChatState(talkerId)
        if (!isLoadMore) {
            updateChat(talkerId) { it.copy(isLoading = true, errorMessage = null) }
        } else {
            updateChat(talkerId) { it.copy(isLoadingMore = true, errorMessage = null) }
        }
        scope.launch {
            repository.loadMoreSessionMessages(talkerId, beginSeqno)
                .onSuccess { data ->
                    val newMessages = data.messages.sortedBy { it.msgSeqno }
                    // isLoadMore 时 newMessages 是用 beginSeqno=minSeqno-1 拉到的更早历史，
                    // 时间上在 cur.messages 之前，要拼在前面才是按时间升序（旧→新）。
                    val merged = if (isLoadMore) newMessages + cur.messages else newMessages
                    updateChat(talkerId) {
                        it.copy(
                            messages = merged,
                            isLoading = false,
                            isLoadingMore = false,
                            minSeqno = data.minSeqno.takeIf { v -> v > 0 } ?: it.minSeqno,
                            maxSeqno = data.maxSeqno.takeIf { v -> v > it.maxSeqno } ?: it.maxSeqno,
                            hasMore = data.hasMore == 1,
                            errorMessage = null,
                        )
                    }
                    // 首屏加载完成后立即标记已读
                    if (!isLoadMore && data.maxSeqno > 0L) {
                        repository.markSessionRead(talkerId, data.maxSeqno)
                        refreshUnreadCount()
                    }
                }
                .onFailure { e ->
                    updateChat(talkerId) {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = e.message ?: "加载失败",
                        )
                    }
                }
        }
    }

    fun updateChatDraft(talkerId: Long, draft: String) {
        updateChat(talkerId) { it.copy(draft = draft) }
    }

    fun sendChatMessage(talkerId: Long) {
        val cur = _chatByTalker.value[talkerId] ?: return
        val text = cur.draft.trim()
        if (text.isEmpty() || cur.isSending) return
        updateChat(talkerId) { it.copy(isSending = true, errorMessage = null) }
        scope.launch {
            val session = this@MessageCenterController.session.value
            val devId = session.biliJct.ifBlank { java.util.UUID.randomUUID().toString() }
            repository.sendPrivateMessage(
                receiverId = talkerId,
                content = text,
                devId = devId,
            )
                .onSuccess { seqno ->
                    val now = System.currentTimeMillis() / 1000L
                    val optimistic = PrivateMessage(
                        senderUid = session.dedeUserId.toLongOrNull() ?: 0L,
                        receiverId = talkerId,
                        msgType = 1,
                        content = """{"content":"${escapeForLocal(text)}"}""",
                        timestamp = now,
                        msgSeqno = seqno,
                    )
                    updateChat(talkerId) {
                        it.copy(
                            messages = it.messages + optimistic,
                            draft = "",
                            isSending = false,
                            maxSeqno = maxOf(it.maxSeqno, seqno),
                            errorMessage = null,
                        )
                    }
                    emitMessage("已发送")
                }
                .onFailure { e ->
                    updateChat(talkerId) { it.copy(isSending = false, errorMessage = e.message ?: "发送失败") }
                    emitMessage("发送失败：${e.message ?: ""}")
                }
        }
    }

    fun closeChat(talkerId: Long) {
        _chatByTalker.value = _chatByTalker.value - talkerId
    }

    /** 点回复与@ 条目：评论区在视频下就播该视频；动态/专栏等本期不支持，给提示不乱跳。 */
    fun playReplyAtItem(item: ReplyAtItem) {
        val bvid = item.videoBvid()
        if (bvid == null) {
            emitMessage("暂不支持打开这类内容")
            return
        }
        play(
            PlaybackRequest.Bili(
                bvid = bvid,
                cid = null,
                title = item.title.ifBlank { "评论的视频" },
                coverUrl = "",
                author = "",
                durationSec = 0L,
            ),
        )
    }

    /** 点收到喜欢条目：被赞对象能落到视频上就播；评论/动态等本期不支持。 */
    fun playLikeItem(item: LikeItem) {
        val bvid = item.videoBvid()
        if (bvid == null) {
            emitMessage("暂不支持打开这类内容")
            return
        }
        play(
            PlaybackRequest.Bili(
                bvid = bvid,
                cid = null,
                title = item.title.ifBlank { "收到喜欢的视频" },
                coverUrl = "",
                author = "",
                durationSec = 0L,
            ),
        )
    }

    fun openFollowerSpace(item: FollowerEntry) {
        openUpSpace(
            SearchUser(
                mid = item.mid,
                uname = item.uname,
                upic = item.face,
            ),
        )
        // UP 空间跳转由调用方持有 openUpSpaceRequests 信号触发（与现有约定一致）。
    }

    /** 在站内向一条评论发回复。 */
    fun submitCommentReply(
        aid: Long,
        root: Long,
        parent: Long,
        content: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> },
    ) {
        scope.launch {
            repository.postCommentReply(aid, root, parent, content)
                .onSuccess {
                    emitMessage("已回复")
                    onResult(true, null)
                }
                .onFailure { e ->
                    emitMessage("回复失败：${e.message ?: ""}")
                    onResult(false, e.message)
                }
        }
    }

    private fun ensureChatState(talkerId: Long): PrivateChatState =
        _chatByTalker.value[talkerId] ?: PrivateChatState().also {
            _chatByTalker.value = _chatByTalker.value + (talkerId to it)
        }

    private fun updateChat(talkerId: Long, reduce: (PrivateChatState) -> PrivateChatState) {
        _chatByTalker.value = _chatByTalker.value.toMutableMap().apply {
            val cur = this[talkerId] ?: return
            this[talkerId] = reduce(cur)
        }
    }

    private fun escapeForLocal(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/** 聊天页状态。 */
data class PrivateChatState(
    val messages: List<PrivateMessage> = emptyList(),
    val draft: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSending: Boolean = false,
    val hasMore: Boolean = false,
    val minSeqno: Long = 0L,
    val maxSeqno: Long = 0L,
    val errorMessage: String? = null,
)

/** 私信聊天路由参数。 */
data class PrivateChatNav(
    val talkerId: Long,
    val peerName: String,
    val peerFace: String,
)
