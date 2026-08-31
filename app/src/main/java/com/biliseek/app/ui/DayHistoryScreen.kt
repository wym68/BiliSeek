package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.core.player.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 某天的播放记录页（M12，仿 BBPlayer `/history/[date]`）。
 *
 * 顶部统计卡：当日去重曲目数 + 总时长；下方当天播放列表，点击可播。
 * 通过 [onClose] 返回（系统返回手势由调用方 BackHandler 接管）。
 */
@Composable
fun DayHistoryScreen(
    date: LocalDate,
    entries: List<PlayHistoryEntity>,
    onClose: () -> Unit,
    onEntryClick: (PlayHistoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalDurationSec = entries.sumOf { it.durationSec }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onClose) { Text("返回") }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("这天没有播放记录", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        // 当日统计卡
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("当日听歌时长", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatDuration(totalDurationSec * 1000),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "共 ${entries.size} 首",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEntryClick(entry) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverThumbnail(entry.coverUrl, width = 72.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (entry.author.ifBlank { "" }) +
                                (if (entry.author.isNotBlank()) " · " else "") +
                                formatDuration(entry.durationSec * 1000),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
