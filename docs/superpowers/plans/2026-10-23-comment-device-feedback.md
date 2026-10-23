# 评论区真机反馈修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修四个真机反馈：评论图片只有占位框；点头像 / 昵称进 UP 空间；楼中楼展开后能收起（开头和末尾都有「收起」）；回复提示带上被回复那条的原文。

**Architecture:** 图片地址走已有的 `normalizeCoverUrl`（`http://` 升 `https://`，Android 9+ 拦明文）。收起不丢已加载的楼中楼，只在 `CommentThreadState` 里记一个 `collapsedSubReplies` 集合，再展开直接复用、不重新请求 B 站。回复原文存在 `CommentReplyTarget.content`，提示文案由纯函数 `replyHintText` 生成，输入栏单行省略。

**Tech Stack:** Kotlin、Jetpack Compose（Material3）、JUnit4、Compose UI Test。

## Global Constraints

- **自动化代码绝不请求 B 站服务器**：所有测试只用本地构造数据；真机效果由用户验证。
- 收起 / 再展开**不得**发新请求：再展开复用已加载的楼中楼状态。
- 评论内容（含草稿、回复原文）只放内存，不写 Room / DataStore。
- 不新增 `:core:network` 公开类型；改动都在 `:app`。
- 叫法统一：「收起」「查看全部 N 条回复」「加载更多回复」「回复 @昵称：原文」。
- 不装机：每个任务只做本地验证（单测 + 编译），用户开口才装到真机。
- 所有提交在分支 `feat/comment-improvements` 上，提交信息结尾带：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt` | 改 | 图片地址归一化；头像 / 昵称可点；楼中楼「收起」；回复提示 |
| `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt` | 改 | `CommentReplyTarget.content`；`collapsedSubReplies`；`collapseSubReplies`；`expandSubReplies` 复用 |
| `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt` | 改 | `visibleSubReplies`、`replyHintText` |
| `app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt` | 改 | 接上 `onCollapseSubReplies` |
| `app/src/main/java/com/biliseek/app/ui/richtext/CommentComposer.kt` | 改 | 回复提示单行省略 |
| `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`、`app/src/test/java/com/biliseek/app/ui/components/CommentPictureUrlsTest.kt` | 改 / 新建 | 单测 |
| `app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt` | 改 | UI 测试（需设备，保证编译） |
| `docs/05-功能优化篇.md`、`docs/BiliSeek-手动测试指南.md` | 改 | 记录与手动测试项 |

---

### Task 1: 评论图片地址升 https

**原因：** 动态图片都经 `normalizeCoverUrl`（`core/network/.../SearchInput.kt`，公开函数）把 `//` 补协议、`http://` 升 `https://`；评论图片的 `pictureUrls()` 原样用 `img_src`，B 站给 `http://` 时 Android 9+ 直接拦掉明文流量，只剩占位框。

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`（文件末尾 `pictureUrls()`）
- Create: `app/src/test/java/com/biliseek/app/ui/components/CommentPictureUrlsTest.kt`

**Interfaces:**
- Produces: `internal fun ReplyContent.pictureUrls(): List<String>` 返回已归一化的地址。

- [ ] **Step 1: 写失败的测试** `app/src/test/java/com/biliseek/app/ui/components/CommentPictureUrlsTest.kt`

```kotlin
package com.biliseek.app.ui.components

import com.biliseek.core.network.dto.ReplyContent
import com.biliseek.core.network.dto.ReplyPicture
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentPictureUrlsTest {

    @Test
    fun `http 升 https，无协议补 https，空地址丢掉`() {
        val content = ReplyContent(
            pictures = listOf(
                ReplyPicture("http://i0.hdslb.com/bfs/a.jpg"),
                ReplyPicture("//i1.hdslb.com/bfs/b.jpg"),
                ReplyPicture("https://i2.hdslb.com/bfs/c.jpg"),
                ReplyPicture(""),
            ),
        )
        assertEquals(
            listOf(
                "https://i0.hdslb.com/bfs/a.jpg",
                "https://i1.hdslb.com/bfs/b.jpg",
                "https://i2.hdslb.com/bfs/c.jpg",
            ),
            content.pictureUrls(),
        )
    }

    @Test
    fun `没有图片返回空`() {
        assertEquals(emptyList<String>(), ReplyContent(pictures = null).pictureUrls())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.ui.components.CommentPictureUrlsTest"`
Expected: FAIL（第一个用例拿到的还是 `http://...` 和 `//...`）。

- [ ] **Step 3: 实现** — `DynamicCommentDialog.kt` 末尾的 `pictureUrls()` 换成：

```kotlin
/** 评论图片地址：与动态图片同样经 [normalizeCoverUrl]（`http://` 会被 Android 9+ 拦成占位框），去掉空串。 */
internal fun com.biliseek.core.network.dto.ReplyContent.pictureUrls(): List<String> =
    pictures.orEmpty().map { normalizeCoverUrl(it.imgSrc) }.filter { it.isNotBlank() }
```

import 区加 `import com.biliseek.core.network.normalizeCoverUrl`。

- [ ] **Step 4: 跑测试确认通过并编译**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.ui.components.CommentPictureUrlsTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS，编译成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/test/java/com/biliseek/app/ui/components/CommentPictureUrlsTest.kt
git commit -m "fix(comment): 评论图片地址升 https，不再只剩占位框

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 点头像 / 昵称进 UP 空间

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`（`CommentItemRow`、`SubReplyRow`、两处 `CommentItemRow(...)` 调用）
- Test: `app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Consumes: `DynamicCommentDialog` 已有参数 `onOpenUser: ((Long) -> Unit)?`（MainActivity 里已接成「关弹层 + 打开 UP 空间」）。
- Produces: `CommentItemRow(..., onOpenUser: ((Long) -> Unit)?)`、`SubReplyRow(..., onOpenUser: ((Long) -> Unit)?)`。

- [ ] **Step 1: 写 UI 测试**（追加到 `DynamicCommentDialogUiTest`；`setDialog` 加参数 `onOpenUser: ((Long) -> Unit)? = null` 并传给 `DynamicCommentDialog(onOpenUser = onOpenUser)`）

```kotlin
    @Test
    fun 点昵称进入对方空间() {
        var opened: Long? = null
        setDialog(state(), onOpenUser = { opened = it })
        compose.onNodeWithText("用户B").performClick()
        check(opened == 20L) { "点昵称应打开 mid=20 的空间" }
    }
```

- [ ] **Step 2: 编译 UI 测试**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: 成功（设备上此时会失败：昵称还不可点）。

- [ ] **Step 3: 实现**

1. `CommentItemRow` 参数末尾加 `onOpenUser: ((Long) -> Unit)?,`；函数体开头（`Row(` 之前）加：

```kotlin
    val openUser: (() -> Unit)? = onOpenUser?.takeIf { reply.mid > 0 }?.let { open -> { open(reply.mid) } }
    val userClick = if (openUser != null) Modifier.clickable(onClick = openUser) else Modifier
```

   头像的 `modifier` 末尾加 `.then(userClick)`：

```kotlin
            modifier = Modifier
                .size(BsSize.avatarSm)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(userClick),
```

   昵称 `Text(reply.member.uname, ...)` 加 `modifier = userClick,`：

```kotlin
                Text(
                    reply.member.uname,
                    modifier = userClick,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
```

   `preview.forEach` 里的 `SubReplyRow(...)` 加 `onOpenUser = onOpenUser,`。

2. `SubReplyRow` 参数末尾加 `onOpenUser: ((Long) -> Unit)?,`；`prefix` 换成（昵称做成可点链接，与 @ 提及同一机制）：

```kotlin
            prefix = {
                val name: AnnotatedString.Builder.() -> Unit = {
                    withStyle(SpanStyle(color = unameColor)) {
                        append(sub.member.uname)
                        if (isUpReply(sub, upMid)) append(" UP")
                    }
                }
                if (onOpenUser != null && sub.mid > 0) {
                    withLink(LinkAnnotation.Clickable(tag = "user:${sub.mid}") { onOpenUser(sub.mid) }) { name() }
                } else {
                    name()
                }
                append("：")
            },
```

3. 两处 `CommentItemRow(...)` 调用（置顶、普通列表）都加 `onOpenUser = onOpenUser,`。
4. import 加：

```kotlin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
```

5. `DynamicCommentDialog` 的 KDoc 第三行改为：` * 正文走 [RichText]：表情图、@ 高亮、蓝字关键词、链接；[onOpenUser] 非空时 @、头像、昵称都可点进空间。`

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin && ./gradlew :app:testDebugUnitTest`
Expected: 全部成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "feat(comment): 点评论的头像或昵称进入对方空间

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 楼中楼展开后可以收起（开头和末尾都有「收起」）

**设计：** 收起时不删已加载的楼中楼，只把 rpid 记进 `collapsedSubReplies`；界面上收起的楼中楼当作「没展开」显示（服务端预览几条 +「查看全部 N 条回复」）。再点「查看全部」：在集合里就移出来、直接复用，不再请求。

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`、`CommentThreadSheet.kt`
- Test: `CommentListPolicyTest.kt`、`DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Produces:
  - `CommentThreadState.collapsedSubReplies: Set<Long> = emptySet()`
  - `fun CommentThreadState.visibleSubReplies(rpid: Long): RemoteListState<ReplyItem>?`
  - `CommentThreadController.collapseSubReplies(rootRpid: Long)`
  - `DynamicCommentDialog(..., onCollapseSubReplies: (Long) -> Unit = {})`

- [ ] **Step 1: 写失败的单测**（追加到 `CommentListPolicyTest`）

```kotlin
    @Test
    fun `收起的楼中楼当作没展开，已加载的还留着`() {
        val loaded = RemoteListState(items = listOf(r(8), r(9)))
        val state = CommentThreadState(oid = 1, type = 1, expandedSubReplies = mapOf(2L to loaded))
        assertEquals(loaded, state.visibleSubReplies(2))
        val collapsed = state.copy(collapsedSubReplies = setOf(2L))
        assertEquals(null, collapsed.visibleSubReplies(2))
        assertEquals(loaded, collapsed.expandedSubReplies[2L])
        assertEquals(null, state.visibleSubReplies(3))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败，`Unresolved reference: collapsedSubReplies` / `visibleSubReplies`。

- [ ] **Step 3: 实现状态与纯函数**

`CommentThreadController.kt` 的 `CommentThreadState` 在 `expandedSubReplies` 字段后加：

```kotlin
    /** 展开后又收起的楼中楼（rpid）。已加载的仍留在 [expandedSubReplies]，再展开直接复用、不重新请求。 */
    val collapsedSubReplies: Set<Long> = emptySet(),
```

`CommentListPolicy.kt` 追加：

```kotlin
/** 界面上该一级评论的楼中楼按「已展开」显示的状态；没展开或已收起返回 null（显示预览 +「查看全部」）。 */
fun CommentThreadState.visibleSubReplies(rpid: Long): RemoteListState<ReplyItem>? =
    expandedSubReplies[rpid]?.takeUnless { rpid in collapsedSubReplies }
```

（import 加 `com.biliseek.app.data.RemoteListState`，若尚未导入。）

- [ ] **Step 4: 控制器**

`expandSubReplies` 的 KDoc 与开头换成（收起过的直接复用）：

```kotlin
    /** 展开楼中楼：第一次点击才发请求；收起过的再展开直接复用已加载的状态，不再请求。 */
    fun expandSubReplies(reply: ReplyItem) {
        val cur = _state.value ?: return
        if (reply.rpid in cur.collapsedSubReplies) {
            _state.value = cur.copy(collapsedSubReplies = cur.collapsedSubReplies - reply.rpid)
            return
        }
        if (cur.expandedSubReplies.containsKey(reply.rpid)) return
```

（后面原有代码不变。）在 `expandSubReplies` 之后加：

```kotlin
    /** 收起楼中楼：只是不显示，已加载的留着。 */
    fun collapseSubReplies(rootRpid: Long) {
        val cur = _state.value ?: return
        if (!cur.expandedSubReplies.containsKey(rootRpid)) return
        _state.value = cur.copy(collapsedSubReplies = cur.collapsedSubReplies + rootRpid)
    }
```

- [ ] **Step 5: 跑单测确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: PASS。

- [ ] **Step 6: 界面**

`DynamicCommentDialog.kt`：

1. 参数在 `onLoadMoreSubReplies` 后加 `onCollapseSubReplies: (Long) -> Unit = {},`。
2. 两处 `CommentItemRow(...)` 调用里 `subReplies = state.expandedSubReplies[reply.rpid],` 都改成 `subReplies = state.visibleSubReplies(reply.rpid),`，并加 `onCollapseSubReplies = { onCollapseSubReplies(reply.rpid) },`。import 加 `com.biliseek.app.comment.visibleSubReplies`。
3. `CommentItemRow` 参数在 `onLoadMoreSubReplies` 后加 `onCollapseSubReplies: () -> Unit,`。楼中楼灰底 `Column(...) { ... }` 的内容换成（展开时开头一个「收起」，末尾一行左边是原来的状态、右边一个「收起」）：

```kotlin
                    if (subReplies != null) {
                        CollapseSubRepliesText(onCollapseSubReplies, Modifier.align(Alignment.End))
                    }
                    preview.forEach { sub ->
                        // （SubReplyRow 调用保持现状）
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            when {
                                // （原来的四个分支原样搬进来：查看全部 / 转圈 / 加载失败 / 加载更多回复）
                            }
                        }
                        if (subReplies != null) CollapseSubRepliesText(onCollapseSubReplies)
                    }
```

   注意：`preview.forEach` 与 `when` 的四个分支内容**不改**，只是外面包了一层。
4. 文件里加：

```kotlin
/** 楼中楼的「收起」（开头、末尾各一个，楼中楼很长时不用滑到底）。 */
@Composable
private fun CollapseSubRepliesText(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "收起",
        modifier.clickable(onClick = onClick),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
```

   import 加 `androidx.compose.foundation.layout.Box`（若尚未导入）。

`CommentThreadSheet.kt` 的 `DynamicCommentDialog(...)` 加 `onCollapseSubReplies = controller::collapseSubReplies,`。

- [ ] **Step 7: UI 测试**（追加到 `DynamicCommentDialogUiTest`；`setDialog` 加参数 `onCollapseSubReplies: (Long) -> Unit = {}` 并传入）

```kotlin
    @Test
    fun 展开的楼中楼开头和末尾都能收起() {
        val sub = ReplyItem(rpid = 11, content = ReplyContent(message = "楼中楼一条"), member = ReplyMember(uname = "用户C"))
        val root = replies[0].copy(rcount = 1, replies = listOf(sub))
        var collapsed: Long? = null
        setDialog(
            state(items = listOf(root)).copy(expandedSubReplies = mapOf(1L to RemoteListState(items = listOf(sub)))),
            onCollapseSubReplies = { collapsed = it },
        )
        val nodes = compose.onAllNodesWithText("收起")
        check(nodes.fetchSemanticsNodes().size == 2) { "展开后开头、末尾各一个「收起」" }
        nodes[0].performClick()
        check(collapsed == 1L) { "收起应回调所属一级评论 rpid" }
    }

    @Test
    fun 收起后显示查看全部() {
        val sub = ReplyItem(rpid = 11, content = ReplyContent(message = "楼中楼一条"), member = ReplyMember(uname = "用户C"))
        val root = replies[0].copy(rcount = 3, replies = listOf(sub))
        setDialog(
            state(items = listOf(root)).copy(
                expandedSubReplies = mapOf(1L to RemoteListState(items = listOf(sub))),
                collapsedSubReplies = setOf(1L),
            ),
        )
        compose.onNodeWithText("查看全部 3 条回复").assertIsDisplayed()
        check(compose.onAllNodesWithText("收起").fetchSemanticsNodes().isEmpty())
    }
```

- [ ] **Step 8: 编译与单测**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: 全部成功。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/ app/src/test/java/com/biliseek/app/comment/ \
  app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt \
  app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "feat(comment): 楼中楼展开后可以收起，开头和末尾都有「收起」，再展开不重新请求

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 回复提示带上原文

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`（`CommentReplyTarget`、`startReplyTo`）
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`（`CommentComposer(...)` 调用）
- Modify: `app/src/main/java/com/biliseek/app/ui/richtext/CommentComposer.kt`（回复提示 `Text`）
- Test: `CommentListPolicyTest.kt`、`DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Produces: `CommentReplyTarget(root, parent, uname, content: String = "")`、`fun replyHintText(target: CommentReplyTarget): String`

- [ ] **Step 1: 写失败的单测**（追加到 `CommentListPolicyTest`）

```kotlin
    @Test
    fun `回复提示带上原文，换行和多余空白压成一个空格`() {
        assertEquals(
            "回复 @小明：这期讲得 太好了",
            replyHintText(CommentReplyTarget(root = 1, parent = 1, uname = "小明", content = " 这期讲得\n\n太好了  ")),
        )
    }

    @Test
    fun `原文为空只写昵称`() {
        assertEquals("回复 @小明", replyHintText(CommentReplyTarget(root = 1, parent = 1, uname = "小明")))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败，`No parameter with name 'content'` / `Unresolved reference: replyHintText`。

- [ ] **Step 3: 实现**

`CommentThreadController.kt`：`CommentReplyTarget` 换成：

```kotlin
/** 评论回复目标：null=发表新评论；非 null=回复某条评论（[content] 是被回复那条的原文，用于输入框上方的提示）。 */
data class CommentReplyTarget(
    val root: Long,
    val parent: Long,
    val uname: String,
    val content: String = "",
)
```

`startReplyTo` 里构造改成：

```kotlin
            replyTarget = CommentReplyTarget(
                root = root,
                parent = parent,
                uname = reply.member.uname,
                content = reply.content.message,
            ),
```

`CommentListPolicy.kt` 追加：

```kotlin
/** 输入框上方的回复提示：「回复 @昵称：原文」，原文里的换行 / 多余空白压成一个空格；原文为空只写昵称。截断交给界面单行省略。 */
fun replyHintText(target: CommentReplyTarget): String {
    val quote = target.content.replace(Regex("\\s+"), " ").trim()
    return if (quote.isEmpty()) "回复 @${target.uname}" else "回复 @${target.uname}：$quote"
}
```

`DynamicCommentDialog.kt`：`CommentComposer(...)` 调用里 `replyHint = replyTargetUname?.let { "回复 @$it" },` 改成 `replyHint = state.replyTarget?.let(::replyHintText),`；import 加 `com.biliseek.app.comment.replyHintText`。

`CommentComposer.kt`：回复提示那个 `Text(replyHint, Modifier.weight(1f), ...)` 加两个参数：

```kotlin
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
```

import 加 `androidx.compose.ui.text.style.TextOverflow`（若尚未导入）。

- [ ] **Step 4: 跑单测确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.*"`
Expected: PASS（`CommentDraftPolicyTest` 里的 `CommentReplyTarget(root, parent, uname)` 仍能编译——`content` 有默认值）。

- [ ] **Step 5: UI 测试**（追加到 `DynamicCommentDialogUiTest`）

```kotlin
    @Test
    fun 回复提示显示被回复那条的原文() {
        setDialog(state(replyTarget = CommentReplyTarget(root = 1, parent = 1, uname = "用户A", content = "第一条评论")))
        compose.onNodeWithText("回复 @用户A：第一条评论").assertIsDisplayed()
    }
```

   已有测试 `回复目标提示与取消` 用的 `CommentReplyTarget(root = 1, parent = 1, uname = "用户A")` 没有原文，仍断言 `回复 @用户A`，不用改。

- [ ] **Step 6: 编译与全量单测**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: 全部成功。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/ app/src/test/java/com/biliseek/app/comment/ \
  app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/main/java/com/biliseek/app/ui/richtext/CommentComposer.kt \
  app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "feat(comment): 回复提示带上被回复那条的原文

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 文档与最终验证

**Files:**
- Modify: `docs/05-功能优化篇.md`（文件末尾，`### 13.77` 之后）
- Modify: `docs/BiliSeek-手动测试指南.md`（「#### 评论区（动态评论与播放页评论共用，2026-10-23）」表格）

- [ ] **Step 1: `docs/05-功能优化篇.md` 末尾追加**

```markdown

### 13.78 评论区真机反馈修正（2026-10-23）

- **图片只有占位框**：评论图片 `img_src` 是 `http://`，Android 9+ 拦明文流量；改为与动态图片同样经 `normalizeCoverUrl` 升 `https://`。
- **点头像 / 昵称进空间**：一级评论的头像、昵称，楼中楼的昵称（与 @ 同一链接机制）都可点，关弹层进 UP 空间。
- **楼中楼收起**：展开后开头、末尾各一个「收起」。收起不丢已加载的楼中楼（`collapsedSubReplies`），再点「查看全部」直接复用，不再请求。
- **回复提示**：输入框上方改为「回复 @昵称：原文」单行省略（`replyHintText`），原文存在 `CommentReplyTarget.content`，草稿恢复时一起回来。
- **验证**：`CommentPictureUrlsTest`、`CommentListPolicyTest` 离线单测；`DynamicCommentDialogUiTest` 已更新（需设备）。**待真机确认图片能显示。**
```

- [ ] **Step 2: 手动测试指南** — 在「#### 评论区（动态评论与播放页评论共用，2026-10-23）」表格里：
  1. 把 `| 楼中楼里点「回复」/「点赞」 | 输入框上方显示「回复 @楼中楼作者」；点赞数 +1 且变色 |` 改为 `| 楼中楼里点「回复」/「点赞」 | 输入框上方显示「回复 @楼中楼作者：那条的原文…」（单行，太长省略）；点赞数 +1 且变色 |`
  2. 表格末尾追加：

```markdown
| 点一级评论的头像 / 昵称，或楼中楼里的昵称 | 弹层关闭，进入这个人的 UP 空间 |
| 展开一条楼中楼后点开头或末尾的「收起」 | 回到只显示几条预览 +「查看全部 N 条回复」；再点「查看全部」立刻展开（不转圈，之前加载的都在） |
```

- [ ] **Step 3: 最终验证**

```bash
./gradlew test assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL；不装机。

- [ ] **Step 4: 提交**

```bash
git add docs/05-功能优化篇.md docs/BiliSeek-手动测试指南.md docs/superpowers/plans/2026-10-23-comment-device-feedback.md
git commit -m "docs(comment): 记录评论区真机反馈修正（§13.78）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
