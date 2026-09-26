package com.biliseek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.core.network.dto.PrivateMessage
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.privateMessageText

/**
 * 私信聊天页（push），与 WatchLaterScreen 风格一致：
 * - LazyColumn 反向滚动（reverseLayout=true），最新消息在底部
 * - 上滑到底部触发 loadMore
 * - 底部输入栏 + 发送按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatScreen(
    peerName: String,
    peerFace: String,
    currentUid: Long,
    state: com.biliseek.app.AppViewModel.PrivateChatState,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onLoadMore: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onOpen() }

    val listState = rememberLazyListState()
    // reverseLayout=true + items(state.messages.reversed()): 显示下标 0 = 最新消息（贴底部）,
    // 下标越大越旧（贴顶部）。所以"滚到最新"要滚到下标 0，不是 size-1。
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // 用 state.messages.size/hasMore/isLoadingMore 做 key：LaunchedEffect 只在 key 变化时才重启，
    // 若只 key 在 listState（整个会话期间不变）上，协程会永远绑死第一次组合时的 state 快照，
    // hasMore/isLoadingMore 永远读到初始值，onLoadMore 实际上永远不会触发。
    LaunchedEffect(listState, state.messages.size, state.hasMore, state.isLoadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            // 显示下标越大越旧，接近末尾（size-1）= 滚到了最旧的一头，该加载更早的历史了。
            lastVisible >= state.messages.size - 2
        }.collect { nearOldest ->
            if (nearOldest && state.hasMore && !state.isLoadingMore) onLoadMore()
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            AsyncImage(
                model = normalizeCoverUrl(peerFace),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(peerName, style = MaterialTheme.typography.titleMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading && state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                state.errorMessage != null && state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                    }
                }
                state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("开始聊天吧") }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    ) {
                        items(
                            items = state.messages.reversed(),
                            key = { it.msgSeqno },
                        ) { msg ->
                            ChatBubble(message = msg, currentUid = currentUid)
                        }
                    }
                }
            }
            if (state.isLoadingMore) {
                Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.TopCenter) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                placeholder = { Text("输入消息…") },
                modifier = Modifier.weight(1f),
                enabled = !state.isSending,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = !state.isSending && state.draft.isNotBlank(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun ChatBubble(message: PrivateMessage, currentUid: Long) {
    val isMine = message.senderUid == currentUid && currentUid > 0L
    val text = privateMessageText(message.msgType, message.content, full = true)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isMine) 12.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 12.dp,
            ),
            color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
