package com.biliseek.app.ui.richtext

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import coil3.compose.AsyncImage
import com.biliseek.app.comment.CommentImage
import com.biliseek.app.comment.MAX_COMMENT_IMAGES
import com.biliseek.app.comment.remainingImageSlots
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
 * - [onAddImages] 非空时多一个配图按钮（系统相册选择器，不要权限）；已选的图在输入框上方排成一行缩略图，
 *   发送中不能增删。[statusText] 是发送中的进度（如「正在上传图片 2/5…」）。
 * - [onCaptureFrame] 非空时多一个截图按钮：截下当前视频画面当一张配图（只有播放页评论区、正在看画面时才传）。
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
    /** 为 true 时聚焦输入框并弹出键盘一次，处理后调 [onFocusRequestHandled]。 */
    requestFocus: Boolean = false,
    onFocusRequestHandled: () -> Unit = {},
    images: List<CommentImage> = emptyList(),
    onAddImages: ((List<Uri>) -> Unit)? = null,
    onRemoveImage: (CommentImage) -> Unit = {},
    statusText: String? = null,
    onCaptureFrame: (() -> Unit)? = null,
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
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            showEmotes = false
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusRequestHandled()
        }
    }
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

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_COMMENT_IMAGES),
    ) { uris -> if (uris.isNotEmpty()) onAddImages?.invoke(uris) }

    Column(modifier.fillMaxWidth()) {
        if (statusText != null) {
            Text(
                statusText,
                Modifier.padding(horizontal = BsSpacing.lg),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (images.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = BsSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
                modifier = Modifier.padding(top = BsSpacing.sm),
            ) {
                items(images, key = { it.path }) { image ->
                    ComposerImageThumb(image, removable = !isPosting, onRemove = { onRemoveImage(image) })
                }
            }
        }
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
                // B 站不收只有图没有字的评论（12066），配了图就提醒一句
                placeholder = { Text(if (images.isNotEmpty()) "配图也要写点文字…" else placeholder) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) showEmotes = false },
                shape = BsShapes.lg,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            // 配图 / 截图 / 表情挨着放、按钮收窄到 36dp（默认 48dp + 4dp 间隔），给输入框多留地方
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onAddImages != null) {
                        IconButton(
                            onClick = {
                                showEmotes = false
                                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = !isPosting && remainingImageSlots(images.size) > 0,
                            modifier = Modifier.size(ComposerActionSize),
                        ) {
                            Icon(Icons.Outlined.Image, contentDescription = "添加图片")
                        }
                    }
                    if (onCaptureFrame != null) {
                        IconButton(
                            onClick = onCaptureFrame,
                            enabled = !isPosting && remainingImageSlots(images.size) > 0,
                            modifier = Modifier.size(ComposerActionSize),
                        ) {
                            Icon(Icons.Outlined.Screenshot, contentDescription = "截取当前画面")
                        }
                    }
                    IconButton(modifier = Modifier.size(ComposerActionSize), onClick = {
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
                }
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

/** 输入框右侧配图 / 截图 / 表情按钮的尺寸（比默认 48dp 窄，给输入框多留地方）。 */
private val ComposerActionSize = 36.dp

/** 输入栏里一张待发图的缩略图，右上角 × 移除。 */
@Composable
private fun ComposerImageThumb(image: CommentImage, removable: Boolean, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp)) {
        AsyncImage(
            model = File(image.path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(BsShapes.sm),
        )
        if (removable) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(BsSpacing.xs)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "移除图片", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}
