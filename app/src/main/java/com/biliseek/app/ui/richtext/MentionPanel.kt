package com.biliseek.app.ui.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.comment.MentionPanelState
import com.biliseek.app.ui.formatFans
import com.biliseek.core.network.dto.MentionUser
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/** @ 候选的宿主：状态 + 按关键词查询（null = 收起）。根部通过 [LocalMentionHost] 提供。 */
data class MentionHost(
    val state: MentionPanelState = MentionPanelState(),
    val search: (String?) -> Unit = {},
)

val LocalMentionHost = compositionLocalOf { MentionHost() }

/** 输入框上方的 @ 候选列表：头像 + 昵称 + 粉丝数，点一下选中。最多占 220dp，多了自己滚。 */
@Composable
internal fun MentionPanel(state: MentionPanelState, onPick: (MentionUser) -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        when {
            state.users.isNotEmpty() -> LazyColumn {
                items(state.users, key = { it.uid }) { user -> MentionRow(user, onClick = { onPick(user) }) }
            }
            state.isLoading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center).padding(BsSpacing.md).size(BsSize.iconSm),
                strokeWidth = 2.dp,
            )
            else -> Text(
                state.errorMessage ?: if (state.keyword.isNullOrEmpty()) "没有可以 @ 的人" else "没有找到「${state.keyword}」",
                Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.md),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MentionRow(user: MentionUser, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BsSpacing.md),
    ) {
        AsyncImage(
            model = normalizeCoverUrl(user.face),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                user.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (user.fans > 0) {
                Text(
                    "粉丝 ${formatFans(user.fans)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
