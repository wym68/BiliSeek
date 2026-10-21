package com.biliseek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.biliseek.app.summary.AiSettings
import com.biliseek.app.summary.AiSettingsPolicy
import com.biliseek.app.summary.ConnectionTestState
import com.biliseek.app.summary.EndpointDraft
import com.biliseek.app.summary.EndpointSlot
import com.biliseek.app.summary.EndpointView
import com.biliseek.app.summary.ModelListState
import com.biliseek.app.summary.StyleChoice
import com.biliseek.app.ui.components.LocalMiniBarInset
import com.biliseek.core.ai.provider.ProviderCatalog
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import com.biliseek.app.ui.components.SettingsChoice
import com.biliseek.app.ui.components.SettingsChoiceSheet
import com.biliseek.app.ui.components.SettingsDivider
import com.biliseek.app.ui.components.SettingsGroup
import com.biliseek.app.ui.components.SettingsRow
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 设置 → AI 总结（工单 AI-3）。总结模型、可选的备用模型、默认风格与自定义提示词；
 * 离线识别模型的管理由 [asrContent] 插槽提供（AI-8）。
 */
@Composable
fun AiSettingsScreen(
    settings: AiSettings,
    tests: Map<EndpointSlot, ConnectionTestState>,
    modelLists: Map<EndpointSlot, ModelListState>,
    onSave: (EndpointSlot, EndpointDraft, (String?) -> Unit) -> Unit,
    onClear: (EndpointSlot) -> Unit,
    onTest: (EndpointSlot, EndpointDraft) -> Unit,
    onFetchModels: (EndpointSlot, EndpointDraft) -> Unit,
    /** 默认选择：(是否 B 站总结, 风格)。 */
    onDefaultChange: (Boolean, StyleChoice) -> Unit,
    onCustomPromptSave: (String) -> Unit,
    onAcceptPrivacy: () -> Unit,
    onClose: () -> Unit,
    asrContent: @Composable () -> Unit = {},
) {
    // 首次保存前说明：字幕 / 识别出的文字会发到第三方 AI 服务
    var pendingSave by remember { mutableStateOf<Pair<EndpointSlot, EndpointDraft>?>(null) }
    var pendingCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }

    fun requestSave(slot: EndpointSlot, draft: EndpointDraft, callback: (String?) -> Unit) {
        if (settings.privacyAccepted) {
            onSave(slot, draft, callback)
        } else {
            pendingSave = slot to draft
            pendingCallback = callback
        }
    }

    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "AI 总结", onNavigate = onClose)
        // 与设置主页同一套外观：组名 + 圆角卡片（ui/components/SettingsComponents.kt）
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BsSpacing.lg)
                // 底部再让出迷你条（它盖在页面最下面）
                .padding(bottom = BsSpacing.xl + LocalMiniBarInset.current),
            verticalArrangement = Arrangement.spacedBy(BsSpacing.lg),
        ) {
            SettingsGroup("总结模型") {
                Column(Modifier.padding(CARD_PADDING)) {
                    EndpointEditor(
                        slot = EndpointSlot.PRIMARY,
                        saved = settings.primary,
                        test = tests[EndpointSlot.PRIMARY] ?: ConnectionTestState.Idle,
                        modelList = modelLists[EndpointSlot.PRIMARY] ?: ModelListState.Idle,
                        onSave = ::requestSave,
                        onClear = onClear,
                        onTest = onTest,
                        onFetchModels = onFetchModels,
                    )
                }
            }

            var backupExpanded by remember { mutableStateOf(settings.backup != null) }
            SettingsGroup("备用模型（可选）") {
                Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "额度用尽或服务故障时，可以在确认后改用它继续；不会自动切换",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { backupExpanded = !backupExpanded }) {
                            Text(if (backupExpanded) "收起" else "展开")
                        }
                    }
                    if (backupExpanded) {
                        EndpointEditor(
                            slot = EndpointSlot.BACKUP,
                            saved = settings.backup,
                            test = tests[EndpointSlot.BACKUP] ?: ConnectionTestState.Idle,
                            modelList = modelLists[EndpointSlot.BACKUP] ?: ModelListState.Idle,
                            onSave = ::requestSave,
                            onClear = onClear,
                            onTest = onTest,
                        onFetchModels = onFetchModels,
                        )
                    }
                }
            }

            StyleSection(settings, onDefaultChange, onCustomPromptSave)

            SettingsGroup("本地语音识别") {
                Column(Modifier.padding(CARD_PADDING)) { asrContent() }
            }

            Text(
                "隐私：视频的字幕或本地识别出的文字会发送到你配置的 AI 服务；音频本身不会上传。" +
                    "API Key 只加密保存在本机，不参与备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = BsSpacing.sm),
            )
        }
    }

    pendingSave?.let { (slot, draft) ->
        BsConfirmDialog(
            title = "发送内容说明",
            text = "生成 AI 总结时，视频的字幕或在本机识别出的文字会发送到你配置的 AI 服务（音频不会上传）。" +
                "API Key 只加密保存在本机。继续保存吗？",
            confirmLabel = "知道了，保存",
            onConfirm = {
                onAcceptPrivacy()
                onSave(slot, draft, pendingCallback ?: {})
                pendingSave = null
                pendingCallback = null
            },
            onDismiss = {
                pendingSave = null
                pendingCallback = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndpointEditor(
    slot: EndpointSlot,
    saved: EndpointView?,
    test: ConnectionTestState,
    modelList: ModelListState,
    onSave: (EndpointSlot, EndpointDraft, (String?) -> Unit) -> Unit,
    onClear: (EndpointSlot) -> Unit,
    onTest: (EndpointSlot, EndpointDraft) -> Unit,
    onFetchModels: (EndpointSlot, EndpointDraft) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var presetId by remember(saved) { mutableStateOf(saved?.presetId ?: ProviderCatalog.MINIMAX_CN.id) }
    var baseUrl by remember(saved) {
        mutableStateOf(saved?.baseUrl ?: ProviderCatalog.MINIMAX_CN.baseUrl)
    }
    var model by remember(saved) { mutableStateOf(saved?.model.orEmpty()) }
    var apiKey by remember(saved) { mutableStateOf("") }
    var problem by remember(saved) { mutableStateOf<String?>(null) }
    val draft = EndpointDraft(presetId, baseUrl, model, apiKey)
    val tagPrefix = "ai_${slot.name.lowercase()}"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderCatalog.chat.forEach { preset ->
                FilterChip(
                    selected = presetId == preset.id,
                    onClick = {
                        presetId = preset.id
                        baseUrl = AiSettingsPolicy.baseUrlForPreset(preset.id, baseUrl)
                    },
                    label = { Text(preset.displayName) },
                )
            }
        }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("接口地址（Base URL）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_base_url"),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = {
                Text(if (saved?.keyConfigured == true) "已保存 ····${saved.keyHint}，留空则不修改" else "粘贴 API Key")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_key"),
        )
        ModelField(
            value = model,
            onValueChange = { model = it },
            choices = AiSettingsPolicy.modelChoices(presetId, baseUrl, modelList),
            openOnLoad = modelList,
            tag = "${tagPrefix}_model",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onFetchModels(slot, draft) },
                enabled = modelList != ModelListState.Loading,
                modifier = Modifier.testTag("${tagPrefix}_fetch_models"),
            ) { Text(if (modelList == ModelListState.Loading) "正在获取…" else "获取模型列表") }
            ProviderCatalog.byId(presetId)?.docsUrl?.takeIf { it.isNotBlank() }?.let { docs ->
                TextButton(onClick = { uriHandler.openUri(docs) }) { Text("去哪拿 API Key") }
            }
        }
        (modelList as? ModelListState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onSave(slot, draft) { problem = it } },
                modifier = Modifier.testTag("${tagPrefix}_save"),
            ) { Text("保存") }
            OutlinedButton(
                onClick = { onTest(slot, draft) },
                enabled = test != ConnectionTestState.Testing,
            ) { Text("测试连接") }
            if (saved != null) {
                TextButton(onClick = { onClear(slot) }) { Text("清除") }
            }
        }
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when (test) {
            ConnectionTestState.Idle -> Unit
            ConnectionTestState.Testing -> Text("正在测试…", style = MaterialTheme.typography.bodySmall)
            ConnectionTestState.Ok -> Text(
                "连接正常",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
            is ConnectionTestState.Failed -> Text(
                test.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 模型名：可直接输入，也可从下拉里选（拉到的列表或预设内置备选）。输入时按包含筛选下拉项。
 * 拉取成功（[openOnLoad] 变成 Loaded）时自动展开下拉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(
    value: String,
    onValueChange: (String) -> Unit,
    choices: List<String>,
    openOnLoad: ModelListState,
    tag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    // 只在这次打开页面期间刚拉取成功时展开；重进页面时已有的结果不自动弹出
    var lastList by remember { mutableStateOf(openOnLoad) }
    LaunchedEffect(openOnLoad) {
        if (openOnLoad is ModelListState.Loaded && openOnLoad != lastList) expanded = true
        lastList = openOnLoad
    }
    val shown = AiSettingsPolicy.filterModels(choices, value)
    ExposedDropdownMenuBox(
        expanded = expanded && shown.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text("模型名（必填）") },
            placeholder = { if (choices.isNotEmpty()) Text("输入或从列表中选择") },
            singleLine = true,
            trailingIcon = {
                if (choices.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && shown.isNotEmpty())
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .testTag(tag),
        )
        ExposedDropdownMenu(
            expanded = expanded && shown.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            shown.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onValueChange(name)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** 默认总结方式：「B 站总结」或某个风格。[SettingsChoiceSheet] 的选项值。 */
private const val OFFICIAL_KEY = "official"

@Composable
private fun StyleSection(
    settings: AiSettings,
    onDefaultChange: (Boolean, StyleChoice) -> Unit,
    onCustomPromptSave: (String) -> Unit,
) {
    var prompt by remember(settings.customPrompt) { mutableStateOf(settings.customPrompt) }
    var showChoices by remember { mutableStateOf(false) }
    val selectedKey = if (settings.defaultOfficial) OFFICIAL_KEY else settings.lastStyle.name
    val choices = remember {
        listOf(SettingsChoice(OFFICIAL_KEY, "B 站总结（免费）", "视频没有官方总结时，改用最近选的风格")) +
            StyleChoice.entries.map { SettingsChoice(it.name, it.label) }
    }

    SettingsGroup("总结风格") {
        SettingsRow(
            title = "默认选择",
            subtitle = "做 AI 总结时弹窗里默认选中这一项，弹窗里仍可改",
            icon = Icons.Outlined.Tune,
            value = choices.first { it.value == selectedKey }.label,
            onClick = { showChoices = true },
        )
        SettingsDivider()
        Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("自定义提示词") },
                placeholder = { Text("例如：按「问题—结论」的形式整理，每条不超过 30 字") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { onCustomPromptSave(prompt) },
                enabled = prompt != settings.customPrompt,
            ) { Text("保存提示词") }
        }
    }

    if (showChoices) {
        SettingsChoiceSheet(
            title = "默认选择",
            note = "查完字幕和 B 站总结后生效。选「B 站总结」时，视频没有官方总结就改用最近选的风格",
            choices = choices,
            selected = selectedKey,
            onSelect = { key ->
                if (key == OFFICIAL_KEY) onDefaultChange(true, settings.lastStyle)
                else onDefaultChange(false, StyleChoice.valueOf(key))
            },
            onDismiss = { showChoices = false },
        )
    }
}

private val CARD_PADDING = androidx.compose.foundation.layout.PaddingValues(14.dp)
