package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.components.InputDialog

// 本地列表的自定义图标（设计稿：docs/superpowers/specs/2026-10-04-local-list-like-favorites-design.md）。
// key 存进 playlist.icon；空串表示没选过，用默认的播放列表图标。

/** 一个预设图标：[key] 存库，[label] 给读屏用。 */
data class PlaylistIconOption(val key: String, val icon: ImageVector, val label: String)

/** 默认图标的 key（没选过、或存的 key 已经不在预设里时都用它）。 */
const val DEFAULT_PLAYLIST_ICON = "queue_music"

/** 预设图标，顺序就是选择器里的顺序。key 一经发布不要改：改了旧列表会退回默认图标。 */
val PLAYLIST_ICONS: List<PlaylistIconOption> = listOf(
    PlaylistIconOption(DEFAULT_PLAYLIST_ICON, Icons.AutoMirrored.Filled.QueueMusic, "播放列表"),
    PlaylistIconOption("music_note", Icons.Filled.MusicNote, "音符"),
    PlaylistIconOption("headphones", Icons.Filled.Headphones, "耳机"),
    PlaylistIconOption("favorite", Icons.Filled.Favorite, "心形"),
    PlaylistIconOption("star", Icons.Filled.Star, "星星"),
    PlaylistIconOption("bedtime", Icons.Filled.Bedtime, "睡前"),
    PlaylistIconOption("cafe", Icons.Filled.LocalCafe, "咖啡"),
    PlaylistIconOption("fitness", Icons.Filled.FitnessCenter, "健身"),
    PlaylistIconOption("commute", Icons.Filled.Commute, "通勤"),
    PlaylistIconOption("school", Icons.Filled.School, "学习"),
    PlaylistIconOption("work", Icons.Filled.Work, "工作"),
    PlaylistIconOption("games", Icons.Filled.SportsEsports, "游戏"),
    PlaylistIconOption("movie", Icons.Filled.Movie, "电影"),
    PlaylistIconOption("mic", Icons.Filled.Mic, "话筒"),
    PlaylistIconOption("podcasts", Icons.Filled.Podcasts, "播客"),
    PlaylistIconOption("piano", Icons.Filled.Piano, "钢琴"),
    PlaylistIconOption("meditation", Icons.Filled.SelfImprovement, "冥想"),
    PlaylistIconOption("travel", Icons.Filled.Flight, "旅行"),
    PlaylistIconOption("idea", Icons.Filled.Lightbulb, "灵感"),
    PlaylistIconOption("pets", Icons.Filled.Pets, "宠物"),
)

/** 存库的 key → 预设里的 key：空串和不认识的都当默认。 */
fun normalizePlaylistIcon(key: String): String =
    if (PLAYLIST_ICONS.any { it.key == key }) key else DEFAULT_PLAYLIST_ICON

/** 存库的 key → 要画的图标。 */
fun playlistIcon(key: String): ImageVector {
    val normalized = normalizePlaylistIcon(key)
    return PLAYLIST_ICONS.first { it.key == normalized }.icon
}

/** 图标选择器：一排排圆角图标，选中的铺主色底。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistIconPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PLAYLIST_ICONS.forEach { option ->
            val isSelected = option.key == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option.key) }
                    .semantics { contentDescription = option.label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    option.icon,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 本地列表的新建 / 编辑弹窗：名字 + 图标。外壳是 [InputDialog]，名字为空时确认键不可点。
 * 列表页「新建」、列表内页「编辑」、「加入列表」里的新建和编辑都用它。
 */
@Composable
fun PlaylistEditDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, icon: String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialIcon: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(normalizePlaylistIcon(initialIcon)) }
    InputDialog(
        title = title,
        confirmLabel = confirmLabel,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, icon) },
        onDismiss = onDismiss,
    ) { focus ->
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("歌单名") },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Text("图标", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlaylistIconPicker(selected = icon, onSelect = { icon = it })
    }
}
