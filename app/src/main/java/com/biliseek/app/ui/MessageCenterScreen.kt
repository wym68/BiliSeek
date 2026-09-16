package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.LoginPanel
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.FollowerEntry
import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.normalizeCoverUrl

/**
 * 消息中心页。登录后展示三 tab 互动消息 + 私信会话列表。
 * 模板仿 WatchLaterScreen / FollowingListScreen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageCenterScreen(
    hub: com.biliseek.app.AppViewModel.MessageHubState,
    session: BiliSession,
    loginState: LoginState,
    unreadCount: Int,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onOpenChat: (Session) -> Unit,
    onPlayReplyAtItem: (ReplyAtItem) -> Unit,
    onPlayLikeItem: (LikeItem) -> Unit,
    onOpenFollower: (FollowerEntry) -> Unit,
    onSubmitReply: (aid: Long, root: Long, parent: Long, content: String, onResult: (Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }
    var selectedTab by remember { mutableStateOf(MessageTab.REPLY) }
    var pendingReply by remember { mutableStateOf<ReplyAtItem?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "消息",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }

        if (!session.isLoggedIn) {
            LoginPanel(
                loginState = loginState,
                session = session,
                onStart = onStartLogin,
                onCancel = onCancelLogin,
                onLogout = {},
            )
            return@Column
        }

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            MessageTab.entries.forEachIndexed { idx, tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) },
                )
            }
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = hub.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    val current = when (selectedTab) {
                        MessageTab.REPLY -> hub.reply
                        MessageTab.LIKE -> hub.like
                        MessageTab.FOLLOWER -> hub.follower
                    }
                    when {
                        current.isLoading && current.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        current.errorMessage != null && current.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${current.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        current.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无内容")
                            }
                        }
                        else -> {
                            items(current.items, key = { itemKey(it) }) { item ->
                                val typed = item
                                MessageItemRow(
                                    item = typed,
                                    onClick = when (selectedTab) {
                                        MessageTab.REPLY -> {
                                            { onPlayReplyAtItem(typed as ReplyAtItem) }
                                        }
                                        MessageTab.LIKE -> {
                                            { onPlayLikeItem(typed as LikeItem) }
                                        }
                                        MessageTab.FOLLOWER -> {
                                            { onOpenFollower(typed as FollowerEntry) }
                                        }
                                    },
                                    onLongClick = if (selectedTab == MessageTab.REPLY) {
                                        {
                                            (typed as ReplyAtItem).let { r ->
                                                pendingReply = r
                                                replyDraft = ""
                                            }
                                        }
                                    } else null,
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    item {
                        Text(
                            "私信",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                        )
                    }
                    when {
                        hub.sessions.isLoading && hub.sessions.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        hub.sessions.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无私信")
                            }
                        }
                        else -> items(hub.sessions.items, key = { it.talkerId }) { session ->
                            SessionRow(session = session, onClick = { onOpenChat(session) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (pendingReply != null) {
        ModalBottomSheet(onDismissRequest = { pendingReply = null }) {
            Column(Modifier.padding(16.dp)) {
                Text("回复 ${pendingReply?.user?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("「${pendingReply?.content.orEmpty()}」", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { replyDraft = it },
                    placeholder = { Text("写下你的回复…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { pendingReply = null }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val target = pendingReply ?: return@TextButton
                            replySubmitting = true
                            onSubmitReply(
                                target.sourceId,
                                target.rootId,
                                target.parentId,
                                replyDraft,
                            ) { ok, _ ->
                                replySubmitting = false
                                if (ok) {
                                    pendingReply = null
                                    replyDraft = ""
                                }
                            }
                        },
                        enabled = !replySubmitting && replyDraft.isNotBlank(),
                    ) { Text(if (replySubmitting) "发送中…" else "发送") }
                }
            }
        }
    }
}

@Composable
private fun MessageItemRow(
    item: Any,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val avatar = when (item) {
        is ReplyAtItem -> item.user?.face.orEmpty()
        is LikeItem -> item.user?.face.orEmpty()
        is FollowerEntry -> item.face
        else -> ""
    }
    val name = when (item) {
        is ReplyAtItem -> item.user?.name.orEmpty()
        is LikeItem -> item.user?.name.orEmpty()
        is FollowerEntry -> item.uname
        else -> ""
    }
    val body = when (item) {
        is ReplyAtItem -> "评论：${item.content}"
        is LikeItem -> "赞了你的视频：${item.title}"
        is FollowerEntry -> item.sign.ifBlank { "关注了你" }
        else -> ""
    }
    val time = when (item) {
        is ReplyAtItem -> item.replyTime
        is LikeItem -> item.likeTime
        is FollowerEntry -> item.mtime
        else -> 0L
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (time > 0L) formatRelativeTime(time * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    val peerName = session.accountInfo?.name.orEmpty().ifBlank { "用户${session.talkerId}" }
    val peerFace = session.accountInfo?.face.orEmpty()
    val lastText = session.lastMsg?.content?.let { extractTextPreview(it) }.orEmpty()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(peerFace),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peerName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lastText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (session.lastMsg?.timestamp ?: 0L > 0L) formatRelativeTime(session.lastMsg!!.timestamp * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private enum class MessageTab(val label: String) {
    REPLY("回复与@"),
    LIKE("收到喜欢"),
    FOLLOWER("新增粉丝"),
}

private fun itemKey(item: Any): String = when (item) {
    is ReplyAtItem -> "reply-${item.id}"
    is LikeItem -> "like-${item.id}"
    is FollowerEntry -> "follower-${item.mid}"
    else -> item.hashCode().toString()
}

private fun extractTextPreview(rawJson: String): String {
    val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    return regex.find(rawJson)?.groupValues?.get(1)
        ?.replace("\\\\", "\\")
        ?.replace("\\\"", "\"")
        ?.replace("\\n", "\n")
        ?: rawJson
}
