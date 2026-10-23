package com.biliseek.app.ui.richtext

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 统一的评论 / 回复 / 私信输入栏：输入框 + 表情切换 + 发送，下方可展开表情面板。
 *
 * - 文本真源仍是调用方的 [content]（ViewModel 状态），这里只额外持有光标位置，
 *   以便在光标处插入表情；外部清空（发送成功）时同步重置。
 * - 表情按钮与键盘互斥：打开面板收起键盘，点输入框收起面板。
 * - [replyHint] 非空时在输入框上方显示「回复 @xxx ×」。
 */
@Composable
fun CommentComposer(
    content: String,
    onContentChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "写评论…",
    isPosting: Boolean = false,
    replyHint: String? = null,
    onClearReply: (() -> Unit)? = null,
    maxLines: Int = 4,
) {
    var value by remember { mutableStateOf(TextFieldValue(content, TextRange(content.length))) }
    // 本组件发出去、还没回流的文本（按发出顺序）。ViewModel 状态经 collectAsState 回流有延迟，
    // 回流的旧值不能当成「外部修改」去重置输入框（否则光标跳到末尾、输入法组合被打断）。
    // 回流按顺序到达：收到其中一个就把它及之前的都出队；不在队列里的才是真正的外部修改。
    val pendingEchoes = remember { ArrayDeque<String>() }
    var lastSeenContent by remember { mutableStateOf(content) }
    if (content != lastSeenContent) {
        lastSeenContent = content
        val echoIndex = pendingEchoes.indexOf(content)
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingEchoes.removeFirst() }
        } else if (content != value.text) {
            // 真正的外部修改（发送成功清空 / 切换回复目标）→ 光标放末尾
            pendingEchoes.clear()
            value = TextFieldValue(content, TextRange(content.length))
        }
    }
    var showEmotes by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val host = LocalEmotePanelHost.current
    val known = remember(host.state.packages) {
        host.state.packages.flatMap { p -> p.emote.orEmpty().map { it.text } }.toSet()
    }

    fun update(newValue: TextFieldValue) {
        val changed = newValue.text != value.text
        value = newValue
        if (changed) {
            pendingEchoes.addLast(newValue.text)
            // 兜底：回流丢失时不让队列无限增长
            while (pendingEchoes.size > 32) pendingEchoes.removeFirst()
            onContentChange(newValue.text)
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (replyHint != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    replyHint,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onClearReply != null) {
                    IconButton(onClick = onClearReply) {
                        Icon(Icons.Filled.Close, contentDescription = "取消回复", modifier = Modifier.size(BsSize.iconSm))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BsSpacing.md, vertical = BsSpacing.sm),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = ::update,
                placeholder = { Text(placeholder) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) showEmotes = false },
                shape = BsShapes.lg,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = {
                if (showEmotes) {
                    showEmotes = false
                    focusRequester.requestFocus()
                    keyboard?.show()
                } else {
                    keyboard?.hide()
                    showEmotes = true
                }
            }) {
                Icon(
                    if (showEmotes) Icons.Filled.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = if (showEmotes) "键盘" else "表情",
                )
            }
            FilledIconButton(
                onClick = {
                    showEmotes = false
                    onSubmit()
                },
                enabled = !isPosting && value.text.isNotBlank(),
            ) {
                if (isPosting) {
                    CircularProgressIndicator(Modifier.size(BsSize.iconSm), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
        AnimatedVisibility(visible = showEmotes) {
            EmotePanel(
                onPick = { emote ->
                    val (text, cursor) = com.biliseek.app.emote.EmoteDictionaryPolicy.insert(
                        value.text, value.selection.start, value.selection.end, emote.text,
                    )
                    update(TextFieldValue(text, TextRange(cursor)))
                },
                onBackspace = {
                    val (text, cursor) = com.biliseek.app.emote.EmoteDictionaryPolicy.backspace(
                        value.text, value.selection.start, known,
                    )
                    update(TextFieldValue(text, TextRange(cursor)))
                },
            )
        }
    }
}
