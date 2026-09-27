package com.biliseek.app.ui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.emote.EmoteDictionaryPolicy
import com.biliseek.app.emote.EmotePanelState
import com.biliseek.core.network.dto.Emote
import com.biliseek.core.network.dto.EmotePackage
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSpacing

/** 表情面板的宿主：状态 + 触发加载。根部通过 [LocalEmotePanelHost] 提供。 */
data class EmotePanelHost(
    val state: EmotePanelState = EmotePanelState(),
    val ensureLoaded: () -> Unit = {},
)

val LocalEmotePanelHost = compositionLocalOf { EmotePanelHost() }

/**
 * 表情面板：底部一排表情包 tab + 上方表情网格 + 右下退格。
 *
 * - 小表情 7 列、大表情 4 列、颜文字（无图）3 列文字；
 * - 首次显示时才触发加载（[EmotePanelHost.ensureLoaded]），进程内只请求一次。
 */
@Composable
fun EmotePanel(
    onPick: (Emote) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    host: EmotePanelHost = LocalEmotePanelHost.current,
) {
    LaunchedEffect(Unit) { host.ensureLoaded() }
    val state = host.state
    var selected by remember { mutableIntStateOf(0) }

    Column(
        modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.packages.isEmpty() && state.isLoading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.packages.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BsSpacing.sm),
                ) {
                    Text(
                        state.errorMessage ?: "暂无表情",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.errorMessage != null) {
                        FilledTonalButton(onClick = host.ensureLoaded) { Text("重试") }
                    }
                }

                else -> {
                    val pkg = state.packages[selected.coerceIn(0, state.packages.lastIndex)]
                    EmoteGrid(pkg, onPick)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs)) {
                itemsIndexed(state.packages) { index, pkg ->
                    PackageTab(pkg, selected = index == selected, onClick = { selected = index })
                }
            }
            IconButton(onClick = onBackspace) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun EmoteGrid(pkg: EmotePackage, onPick: (Emote) -> Unit) {
    val emotes = pkg.emote.orEmpty()
    val textOnly = emotes.isNotEmpty() && emotes.all(EmoteDictionaryPolicy::isTextOnly)
    val large = !textOnly && emotes.any { it.isLarge }
    val columns = when {
        textOnly -> 3
        large -> 4
        else -> 7
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxWidth().padding(horizontal = BsSpacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = BsSpacing.sm),
    ) {
        items(emotes) { emote ->
            Box(
                Modifier
                    .clip(BsShapes.sm)
                    .clickable { onPick(emote) }
                    .semantics { contentDescription = emote.meta.alias.ifBlank { emote.text } }
                    .padding(BsSpacing.xs)
                    .then(if (textOnly) Modifier else Modifier.aspectRatio(1f)),
                contentAlignment = Alignment.Center,
            ) {
                if (EmoteDictionaryPolicy.isTextOnly(emote)) {
                    Text(
                        emote.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    AsyncImage(
                        model = emote.url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(if (large) 56.dp else 30.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageTab(pkg: EmotePackage, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = BsSpacing.xs)
            .size(width = 48.dp, height = 36.dp)
            .clip(BsShapes.sm)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .semantics { contentDescription = pkg.text },
        contentAlignment = Alignment.Center,
    ) {
        if (pkg.url.isNotBlank()) {
            AsyncImage(model = pkg.url, contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            Text(pkg.text.take(2), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val PANEL_HEIGHT = 280.dp
