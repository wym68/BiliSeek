package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.AiSummaryEntity
import com.biliseek.app.data.AiSummaryStatus
import com.biliseek.app.summary.FailureAction
import com.biliseek.app.summary.SummaryDisplayPolicy
import com.biliseek.app.summary.SummaryVersionPolicy
import com.biliseek.app.summary.SummaryUiState
import com.biliseek.app.summary.TimestampLinkPolicy
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.component.BsNavIcon
import com.biliseek.designsystem.component.BsTopBar

/**
 * AI 总结阅读页（工单 AI-6）。进行中显示阶段与流式正文；完成后渲染 Markdown，时间戳点击跳播；
 * 失败时顶部横幅给出全部出路。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryReaderScreen(
    state: SummaryUiState?,
    markdown: String?,
    transcript: String?,
    failureActions: List<FailureAction>,
    onClose: () -> Unit,
    onTimestamp: (Long) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    /** 生成新版本：旧版本保留。 */
    onRegenerate: () -> Unit,
    /** 删除当前显示的版本。 */
    onDelete: () -> Unit,
    /** 清除这条视频的全部版本（含原文）。 */
    onDeleteAll: () -> Unit,
    onSelectVersion: (Long) -> Unit,
    onCancel: () -> Unit,
    onFailureAction: (FailureAction) -> Unit,
    onLoadTranscript: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val versions = state?.versions.orEmpty()
    val entity = state?.entity
    val running = entity != null && entity.status != AiSummaryStatus.DONE && entity.status != AiSummaryStatus.FAILED
    val done = entity?.status == AiSummaryStatus.DONE
    // 底部面板：默认 60% 高度，上拉（或点右上角按钮）撑满
    val panel = rememberExpandablePanelState(collapsed = 0.6f)
    val scope = rememberCoroutineScope()

    // 时间戳链接交给播放跳转；其余链接（头部的视频链接）仍走系统默认打开方式
    val defaultUriHandler = LocalUriHandler.current
    val timestampHandler = remember(onTimestamp, defaultUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val ms = TimestampLinkPolicy.parse(uri)
                if (ms != null) onTimestamp(ms) else runCatching { defaultUriHandler.openUri(uri) }
            }
        }
    }

    val body: @Composable ColumnScope.() -> Unit = {
            BsTopBar(
                title = entity?.title ?: "AI 总结",
                subtitle = entity?.pageTitle?.ifBlank { null },
                onNavigate = onClose,
                navIcon = BsNavIcon.Close,
                actions = {
                    IconButton(onClick = { panel.toggle(scope) }) {
                        Icon(
                            if (panel.isExpanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (panel.isExpanded) "退出全屏" else "全屏显示",
                        )
                    }
                    if (done) {
                        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = "分享") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (done) {
                                DropdownMenuItem(text = { Text("另存为…") }, onClick = { menuOpen = false; onExport() })
                            }
                            if (entity?.hasTranscript == true) {
                                DropdownMenuItem(
                                    text = { Text(if (showTranscript) "查看总结" else "查看原文") },
                                    onClick = {
                                        menuOpen = false
                                        showTranscript = !showTranscript
                                        if (showTranscript) onLoadTranscript()
                                    },
                                )
                            }
                            if (running) {
                                DropdownMenuItem(text = { Text("取消") }, onClick = { menuOpen = false; onCancel() })
                            } else {
                                DropdownMenuItem(text = { Text("生成新版本") }, onClick = { menuOpen = false; onRegenerate() })
                            }
                            DropdownMenuItem(
                                text = { Text(if (versions.size > 1) "删除此版本" else "删除") },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                            if (versions.size > 1) {
                                DropdownMenuItem(
                                    text = { Text("删除全部版本") },
                                    onClick = { menuOpen = false; confirmDeleteAll = true },
                                )
                            }
                        }
                    }
                },
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entity == null) {
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                VersionBar(
                    versions = versions,
                    current = entity,
                    showActions = !running,
                    onSelect = onSelectVersion,
                    onNew = onRegenerate,
                    onDelete = { confirmDelete = true },
                )

                if (running) {
                    val live = state?.live
                    Text(
                        live?.stage ?: statusLabel(entity.status),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("summary_stage"),
                    )
                    val fraction = live?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "可以离开这个页面，生成会在后台继续，完成后有通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (entity.status == AiSummaryStatus.FAILED) {
                    FailureBanner(entity.error, failureActions, onFailureAction)
                }

                when {
                    showTranscript -> Text(
                        transcript ?: "原文加载中…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("summary_transcript"),
                    )
                    done && markdown != null -> CompositionLocalProvider(LocalUriHandler provides timestampHandler) {
                        SummaryMarkdownView(
                            content = TimestampLinkPolicy.linkify(SummaryDisplayPolicy.forReader(markdown)),
                            modifier = Modifier.testTag("summary_markdown"),
                        )
                    }
                    running && !state?.live?.streamingText.isNullOrBlank() -> SummaryMarkdownView(
                        content = state?.live?.streamingText.orEmpty(),
                        modifier = Modifier.testTag("summary_streaming"),
                    )
                }
            }
    }

    ExpandableBottomPanel(onDismiss = onClose, state = panel) { body() }

    if (confirmDeleteAll) {
        BsConfirmDialog(
            title = "清除全部 ${versions.size} 个版本？",
            text = "所有版本的总结和保存的原文都会删除，之后需要重新生成。",
            confirmLabel = "全部清除",
            destructive = true,
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAll()
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    if (confirmDelete) {
        BsConfirmDialog(
            title = if (versions.size > 1) "删除这个版本？" else "删除这份总结？",
            text = if (versions.size > 1) {
                "只删除当前这个版本，其他版本保留。"
            } else {
                "总结与保存的原文都会删除，之后需要重新生成。"
            },
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * 版本条：有多个版本时显示「版本 2/3 ▾」切换；不在生成中时给出「生成新版本」和「删除」两个按钮，
 * 让「保留这个、再生成一个」与「清掉」都不必去翻菜单。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionBar(
    versions: List<AiSummaryEntity>,
    current: AiSummaryEntity,
    showActions: Boolean,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: () -> Unit,
) {
    if (versions.size <= 1 && !showActions) return
    var open by remember { mutableStateOf(false) }
    val ids = versions.map { it.id }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("summary_versions"),
    ) {
        if (versions.size > 1) {
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text("版本 ${SummaryVersionPolicy.ordinal(ids, current.id)}/${versions.size} ▾") },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    versions.forEach { v ->
                        val suffix = when (v.status) {
                            AiSummaryStatus.DONE -> ""
                            AiSummaryStatus.FAILED -> " · 失败"
                            else -> " · 生成中"
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (v.id == current.id) "✓ " else "") +
                                        SummaryVersionPolicy.label(v.style, v.sourceKind, v.createdAt) + suffix,
                                )
                            },
                            onClick = {
                                open = false
                                onSelect(v.id)
                            },
                        )
                    }
                }
            }
        }
        if (showActions) {
            OutlinedButton(onClick = onNew, modifier = Modifier.testTag("summary_new_version")) { Text("生成新版本") }
            TextButton(onClick = onDelete) { Text(if (versions.size > 1) "删除此版本" else "删除") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureBanner(
    message: String,
    actions: List<FailureAction>,
    onAction: (FailureAction) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("summary_failure"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("生成失败", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(message.ifBlank { "未知原因" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                OutlinedButton(onClick = { onAction(action) }) { Text(action.label) }
            }
        }
    }
}

private fun statusLabel(status: String) = when (status) {
    AiSummaryStatus.QUEUED -> "排队中"
    AiSummaryStatus.PREPARING -> "准备中"
    AiSummaryStatus.TRANSCRIBING -> "本地识别中"
    AiSummaryStatus.SUMMARIZING -> "总结中"
    AiSummaryStatus.WAITING_NETWORK -> "等待网络后自动继续"
    else -> ""
}
