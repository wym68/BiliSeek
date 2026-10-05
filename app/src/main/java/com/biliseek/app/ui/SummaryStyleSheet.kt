package com.biliseek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.summary.AsrPrompt
import com.biliseek.app.summary.LocalAsrPolicy
import com.biliseek.app.summary.NetworkDecision
import com.biliseek.app.summary.OfficialSummary
import com.biliseek.app.summary.SheetChoice
import com.biliseek.app.summary.SummaryDefaultPolicy
import com.biliseek.app.summary.StyleChoice
import com.biliseek.app.summary.StyleSheetState
import com.biliseek.app.summary.SummaryNetworkPolicy
import com.biliseek.app.summary.SummarySource
import com.biliseek.app.ui.components.CompactDragHandle

/**
 * AI 总结风格弹窗（工单 AI-6）：顶部「使用 B 站 AI 总结（免费）」，其下自己生成（风格 + 原文来源说明）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryStyleSheet(
    state: StyleSheetState,
    onConfirm: (SheetChoice) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogin: () -> Unit,
) {
    val officialAvailable = state.official is OfficialSummary.Available
    // 默认跟设置里的「默认选择」；用户手动点过（manualOfficial 非 null）就不再被查询结果改动
    var manualOfficial by remember { mutableStateOf<Boolean?>(null) }
    val useOfficial = SummaryDefaultPolicy.useOfficial(state.defaultOfficial, officialAvailable, manualOfficial)
    var style by remember { mutableStateOf(state.selected) }
    var prompt by remember { mutableStateOf(state.customPrompt) }
    var forceLocalAsr by remember { mutableStateOf(false) }

    val source = state.source
    val needsAudioDownload = (source is SummarySource.UseLocalAsr && !source.hasLocalAudio) || forceLocalAsr
    val durationMs = state.videos.singleOrNull()?.durationMs ?: 0L
    val network = SummaryNetworkPolicy.decide(needsAudioDownload, state.isMetered, durationMs)
    val sourceUsable = state.isBatch || forceLocalAsr ||
        source is SummarySource.UseSubtitle || source is SummarySource.UseLocalAsr
    val canStart = if (useOfficial) {
        officialAvailable
    } else {
        (!style.needsLlm || state.llmReady) && sourceUsable && (style != StyleChoice.CUSTOM || prompt.isNotBlank())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactDragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI 总结", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.isBatch) "${state.videos.size} 个分 P，逐个排队生成" else state.videos.single().title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.hasExisting) {
                Text(
                    "将保存为新版本，已有的版本不会被覆盖；不需要的版本可以在阅读页删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.isBatch) {
                ChoiceRow(
                    selected = useOfficial,
                    enabled = officialAvailable,
                    label = "使用 B 站 AI 总结（免费）",
                    description = when (val o = state.official) {
                        OfficialSummary.Loading -> "查询中…"
                        is OfficialSummary.Available -> o.result.summary.lines().take(2).joinToString(" ").ifBlank { "有分段大纲" }
                        is OfficialSummary.Unavailable -> o.reason.text
                    },
                    onSelect = { manualOfficial = true },
                    tag = "summary_official",
                )
                HorizontalDivider()
            }

            Text("自己生成", style = MaterialTheme.typography.titleSmall)
            if (!state.llmReady) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未配置总结模型，只能生成「原文笔记」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
            StyleChoice.entries.forEach { choice ->
                ChoiceRow(
                    selected = !useOfficial && style == choice,
                    enabled = !choice.needsLlm || state.llmReady,
                    label = choice.label,
                    description = null,
                    onSelect = {
                        manualOfficial = false
                        style = choice
                    },
                    tag = "summary_style_${choice.name.lowercase()}",
                )
            }
            if (!useOfficial && style == StyleChoice.CUSTOM) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("自定义提示词") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!useOfficial && !state.isBatch) {
                SourceLine(
                    source = source,
                    forceLocalAsr = forceLocalAsr,
                    onUseLocalAsr = { forceLocalAsr = true },
                    onLogin = onLogin,
                    onOpenSettings = onOpenSettings,
                )
            }
            if (!useOfficial && state.isBatch) {
                Text(
                    "每个分 P 先找字幕；没有字幕时，若离线识别模型已就绪则本地识别",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val willRunAsr = forceLocalAsr || source is SummarySource.UseLocalAsr
            val asrPrompt = LocalAsrPolicy.decide(durationMs, state.asrRtf)
            if (!useOfficial && !state.isBatch && willRunAsr && asrPrompt is AsrPrompt.ConfirmLong) {
                Text(
                    "视频较长，本地识别预计约 ${asrPrompt.estimatedMinutes} 分钟（会占用较多电量）；可以离开，识别在后台进行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!useOfficial && network is NetworkDecision.ConfirmMetered) {
                Text(
                    "当前是移动网络，需要下载音频约 ${network.estimatedBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    onConfirm(
                        if (useOfficial) {
                            SheetChoice.UseOfficial
                        } else {
                            SheetChoice.Generate(style, prompt, forceLocalAsr)
                        },
                    )
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().testTag("summary_start"),
            ) {
                Text(if (!useOfficial && network is NetworkDecision.ConfirmMetered) "仍然开始" else "开始")
            }
        }
    }
}

@Composable
private fun SourceLine(
    source: SummarySource?,
    forceLocalAsr: Boolean,
    onUseLocalAsr: () -> Unit,
    onLogin: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        forceLocalAsr -> Text("原文：本地语音识别", style = MaterialTheme.typography.bodySmall, color = muted)
        source == null -> Text("正在查找字幕…", style = MaterialTheme.typography.bodySmall, color = muted)
        source is SummarySource.UseSubtitle -> Text(
            "原文：B 站字幕（${source.track.lanDoc.ifBlank { source.track.lan }}）",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        source is SummarySource.UseLocalAsr -> Text(
            if (source.hasLocalAudio) "原文：本地语音识别（使用已下载的音频）" else "原文：本地语音识别（需先下载音频）",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        source is SummarySource.NeedsLoginOrAsr -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("这个视频有字幕，但需要登录才能获取", style = MaterialTheme.typography.bodySmall, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLogin) { Text("去登录") }
                if (source.asrReady) {
                    OutlinedButton(onClick = onUseLocalAsr) { Text("改用本地识别") }
                } else {
                    OutlinedButton(onClick = onOpenSettings) { Text("下载识别模型") }
                }
            }
        }
        source is SummarySource.NeedsAsrModel -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "这个视频没有字幕，需要先下载离线识别模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    description: String?,
    onSelect: () -> Unit,
    tag: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
