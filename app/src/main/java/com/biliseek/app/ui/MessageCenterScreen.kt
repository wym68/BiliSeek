package com.biliseek.app.ui

import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.outlined.Email
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.LoginPanel
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.privateMessageText

/**
 * 消息中心页。三个入口图标（回复与@/收到喜欢/新增粉丝，前两个带未读角标）+
 * 私信会话列表主体。图标点击跳到各自独立的子页面，对齐真实 B 站的导航结构
 * （不是内嵌 TabRow 切换）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageCenterScreen(
    sessions: RemoteListState<Session>,
    unreadSummary: BiliRepository.MessageUnreadSummary,
    session: BiliSession,
    loginState: LoginState,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onOpenChat: (Session) -> Unit,
    onOpenReplyFeed: () -> Unit,
    onOpenLikeFeed: () -> Unit,
    onOpenFollowerFeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "消息", onNavigate = onClose)

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

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MessageEntryIcon(
                icon = Icons.Filled.Email,
                label = "回复与@",
                badgeCount = unreadSummary.replyAtBadge,
                onClick = onOpenReplyFeed,
            )
            MessageEntryIcon(
                icon = Icons.Filled.ThumbUp,
                label = "收到喜欢",
                badgeCount = unreadSummary.like,
                onClick = onOpenLikeFeed,
            )
            MessageEntryIcon(
                icon = Icons.Filled.Person,
                label = "新增粉丝",
                badgeCount = 0,
                onClick = onOpenFollowerFeed,
            )
        }
        HorizontalDivider()

        PullRefreshContainer(
            isRefreshing = sessions.isLoading && sessions.items.isNotEmpty(),
            errorMessage = sessions.errorMessage,
            successText = "私信已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding(bottom = 16.dp)) {
                when {
                    sessions.isLoading && sessions.items.isEmpty() -> item { BsLoadingState() }
                    sessions.errorMessage != null && sessions.items.isEmpty() -> item {
                        BsErrorState("加载失败：${sessions.errorMessage}", onRetry = onRetry)
                    }
                    sessions.items.isEmpty() -> item { BsEmptyState("暂无私信", icon = Icons.Outlined.Email) }
                    else -> items(sessions.items, key = { it.talkerId }) { s ->
                        SessionRow(session = s, onClick = { onOpenChat(s) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageEntryIcon(
    icon: ImageVector,
    label: String,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            BadgedBox(
                badge = {
                    if (badgeCount > 0) {
                        Badge { Text(minOf(badgeCount, 99).toString()) }
                    }
                },
            ) {
                Icon(imageVector = icon, contentDescription = label)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    val peerName = session.accountInfo?.name.orEmpty().ifBlank { "用户${session.talkerId}" }
    val peerFace = session.accountInfo?.face.orEmpty()
    val lastText = session.lastMsg?.let { privateMessageText(it.msgType, it.content) }.orEmpty()
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
