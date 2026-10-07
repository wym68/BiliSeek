package com.biliseek.app.ui

import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsTopBar
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.DayView
import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.core.player.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 某天的播放记录页（M12，仿 BBPlayer `/history/[date]`）。
 *
 * 列表与顶部统计卡出自同一份按视频的收听明细（[DayView]）：这天听过的每个视频一行（「听了 mm:ss」），
 * 顶部是各行之和与行数。10-07 以前没有明细的日子回退到播放历史（行内显示完整时长），时长取旧的按天合计，
 * 也没有就显示「未统计」——不拿条目的完整时长相加充数。点击可播。
 * 通过 [onClose] 返回（系统返回手势由调用方 BackHandler 接管）。
 */
@Composable
fun DayHistoryScreen(
    date: LocalDate,
    view: DayView,
    onClose: () -> Unit,
    onEntryClick: (PlayHistoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        BsTopBar(title = date.format(DateTimeFormatter.ISO_LOCAL_DATE), onNavigate = onClose)

        if (view.rows.isEmpty()) {
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
                    view.totalMs?.let { formatDuration(it) } ?: "未统计",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "共 ${view.rows.size} 首",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth(), contentPadding = miniBarContentPadding()) {
            items(view.rows, key = { it.history.id }) { row ->
                val entry = row.history
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
                                (row.listenedMs?.let { "听了 ${formatDuration(it)}" }
                                    ?: formatDuration(entry.durationSec * 1000)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
