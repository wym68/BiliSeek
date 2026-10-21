package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.biliseek.app.region.RegionCatalog
import com.biliseek.app.region.RegionGroup
import com.biliseek.app.region.RegionSettings
import com.biliseek.app.region.formatSpanLabel
import com.biliseek.app.region.span
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsTopBar
import java.time.LocalDate

/**
 * 分区总览（时光姬式；设计稿 2026-10-21-region-entries）：顶部时间范围卡片（点开时间设置），
 * 下面每个大类一张卡片——标题行（图标 + 名字，点了进第一个子分区）+ 子分区标签（点了进对应子分区）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RegionOverviewScreen(
    settings: RegionSettings,
    onSettingsChange: (RegionSettings) -> Unit,
    onOpenSubRegion: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var showTime by remember { mutableStateOf(false) }
    if (showTime) RegionTimeSheet(settings, today, onApply = onSettingsChange, onDismiss = { showTime = false })

    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "分区", onNavigate = onClose)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = miniBarContentPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "time") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showTime = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(formatSpanLabel(settings.span(today), today), style = MaterialTheme.typography.titleMedium)
                        Text("按${settings.order.label}排序 · 点这里修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(RegionGroup.entries, key = { it.name }) { group ->
                val subs = RegionCatalog.inGroup(group)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenSubRegion(subs.first().tid) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(regionGroupIcon(group), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(group.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    FlowRow(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        subs.forEach { sub -> AssistChip(onClick = { onOpenSubRegion(sub.tid) }, label = { Text(sub.name) }) }
                    }
                }
            }
        }
    }
}

/** 大类图标；快捷入口里的子分区卡片也用它。 */
internal fun regionGroupIcon(group: RegionGroup): ImageVector = when (group) {
    RegionGroup.MUSIC -> Icons.Outlined.LibraryMusic
    RegionGroup.KNOWLEDGE -> Icons.Outlined.School
    RegionGroup.DOCUMENTARY -> Icons.Outlined.Movie
    RegionGroup.TALK -> Icons.Outlined.Forum
    RegionGroup.VOICE -> Icons.Outlined.RecordVoiceOver
}
