package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.DynamicDetailState
import com.biliseek.app.ui.formatRelativeTime
import com.biliseek.core.network.DynamicEntry

/**
 * 图文动态详情弹层（点开看全文）。
 *
 * 数据来自 repository 的四级降级（web detail → opus/detail → desktop → rid），
 * 正文取 feed 摘要与 detail 全文的更长者，图片取并集。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicDetailSheet(
    state: DynamicDetailState,
    onImageClick: (List<String>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("动态全文", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()

            val entry = state.entry
            when {
                state.error != null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    Text(state.error, style = MaterialTheme.typography.bodySmall)
                }

                entry == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }

                else -> LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    item(key = "author") {
                        Row(
                            Modifier.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = entry.authorFace,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(entry.authorName, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    formatRelativeTime(entry.pubTs * 1000),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    if (entry.opusTitle.isNotBlank()) {
                        item(key = "title") {
                            Text(entry.opusTitle, style = MaterialTheme.typography.titleSmall)
                        }
                    }

                    if (entry.descText.isNotBlank()) {
                        item(key = "body") {
                            Text(
                                entry.descText,
                                Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    val pics = entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }
                    if (pics.isNotEmpty()) {
                        item(key = "pics") {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                if (pics.size == 1) {
                                    AsyncImage(
                                        model = pics[0],
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(width = 240.dp, height = 160.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { onImageClick(pics, 0) },
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    DrawGrid(
                                        pics = pics,
                                        onImageClick = { idx -> onImageClick(pics, idx) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
