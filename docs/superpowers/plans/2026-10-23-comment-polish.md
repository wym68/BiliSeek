# 评论区第二轮优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修代码审查找出的四个问题（播放页评论区与当前视频不一致、楼中楼错误不清、换排序不回顶部、首屏失败无重试），加友好错误文案、评论读接口 HTTP 412 进冷却，以及长按评论选字复制。

**Architecture:** 判断逻辑都写成 `:app` 里的纯函数（`CommentListPolicy` / 新建 `CommentErrorPolicy`），控制器只调用；网络层只在 `comments()` / `subReplies()` 加一个 `HttpException` 分支；选字复制复用动态长图文的 `SelectionContainer` 做法。

**Tech Stack:** Kotlin、Jetpack Compose（Material3）、Retrofit、JUnit4。

## Global Constraints

- **自动化代码绝不请求 B 站服务器**：测试只用本地构造数据；真机效果由用户验证。
- **`-352` / HTTP 412（风控）不给重试按钮**（`SearchError.retryable` 恒为 false 的规则同样适用于评论区）；冷却期内的请求本地直接失败。
- 不新增重试循环；读请求仍走 `FEED` 桶 + `refreshingOn101`；写请求仍走 `writeRequest`。
- 不新增 `:core:network` 公开类型。
- 用户看到的错误文案不露原始错误码（`code=…`、`(-352)` 之类），服务端给的中文 message 可以保留。
- 叫法统一：「重试」「请求太频繁，请过几分钟再试」「网络不可用」「请先登录」「登录已失效，请重新登录」。
- 不装机：每个任务只做本地验证（单测 + 编译），用户开口才装到真机。
- 所有提交在分支 `feat/comment-improvements` 上，提交信息结尾带：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt` | 改 | `shouldDropPlayerComments` |
| `app/src/main/java/com/biliseek/app/comment/CommentErrorPolicy.kt` | 新建 | 失败 → 文案、是否可重试 |
| `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt` | 改 | 楼中楼错误清除、`retry()`、错误文案、`listErrorRetryable` |
| `app/src/main/java/com/biliseek/app/AppViewModel.kt` | 改 | 切视频时关掉对不上的播放页评论区 |
| `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt` | 改 | 换排序回顶部、首屏「重试」、正文可选字 |
| `app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt` | 改 | 接上 `onRetry` |
| `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt` | 改 | `comments()` / `subReplies()`：HTTP 412 进冷却；Unknown 只带服务端 message |
| `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`、`CommentErrorPolicyTest.kt` | 改 / 新建 | 单测 |
| `docs/05-功能优化篇.md`、`docs/BiliSeek-手动测试指南.md` | 改 | 记录与手动测试项 |

---

### Task 1: 播放页评论区跟着当前视频走

**问题：** 播放页评论区打开时记下 `key = bvid`（`AppViewModel.openPlayerComment`）。之后换了视频（UP 空间里点了别的视频只起播不跳页、或队列自动下一首），弹层仍是旧视频的评论，此时发评论会发到旧视频。

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（`playerComments` 定义之后）
- Test: `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`

**Interfaces:**
- Produces: `fun shouldDropPlayerComments(state: CommentThreadState?, currentBvid: String): Boolean`

- [ ] **Step 1: 写失败的测试**（追加到 `CommentListPolicyTest`）

```kotlin
    @Test
    fun `播放页评论区不是当前视频的就关掉`() {
        val a = CommentThreadState(oid = 1, type = 1, key = "BV1aaa")
        assertEquals(false, shouldDropPlayerComments(null, "BV1bbb"))
        assertEquals(false, shouldDropPlayerComments(a, "BV1aaa"))
        assertEquals(true, shouldDropPlayerComments(a, "BV1bbb"))
        assertEquals(true, shouldDropPlayerComments(a.copy(hiddenUntilEntry = "player-1"), "BV1bbb"))
        assertEquals(true, shouldDropPlayerComments(a, ""))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败，`Unresolved reference: shouldDropPlayerComments`。

- [ ] **Step 3: 实现**

`CommentListPolicy.kt` 追加：

```kotlin
/**
 * 播放页评论区（[CommentThreadState.key] = 打开时的 bvid）是否该关掉：正在播的已经换了视频
 * （UP 空间里点了别的视频、队列切到下一首、停止播放），再留着就会把评论发到上一个视频下面。
 * 藏着的（跳去 UP 空间）也一样处理。
 */
fun shouldDropPlayerComments(state: CommentThreadState?, currentBvid: String): Boolean =
    state != null && state.key != currentBvid
```

`AppViewModel.kt`：紧接 `val playerComments = CommentThreadController(...)` 之后加：

```kotlin
    init {
        // 换了视频就关掉上一个视频的评论区（草稿照常存）：否则返回 / 切歌后会把评论发到上一个视频
        viewModelScope.launch {
            connection.state.map { it.bvid }.distinctUntilChanged().collect { bvid ->
                if (shouldDropPlayerComments(playerComments.state.value, bvid)) playerComments.dismiss()
            }
        }
    }
```

import 按需加 `kotlinx.coroutines.flow.map`、`kotlinx.coroutines.flow.distinctUntilChanged`、`com.biliseek.app.comment.shouldDropPlayerComments`（文件里已有的不重复加；若 `map` 与其他同名导入冲突，用 `kotlinx.coroutines.flow.map` 全限定写法）。

- [ ] **Step 4: 跑测试并编译**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS，编译成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt app/src/main/java/com/biliseek/app/AppViewModel.kt app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt
git commit -m "fix(comment): 换了视频就关掉上一个视频的评论区，评论不会发错地方

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 楼中楼错误能清掉；换排序回到顶部

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`（`loadMoreSubReplies`）
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`（两个 `SortChip`）

- [ ] **Step 1: 楼中楼错误**

`loadMoreSubReplies` 里：
1. 开始加载时 `state.copy(isLoadingMore = true)` 改为 `state.copy(isLoadingMore = true, errorMessage = null)`。
2. `.onSuccess` 里的 `prev.copy(items = merged, page = nextPage, hasMore = ..., isLoadingMore = false)` 加上 `errorMessage = null,`。

（`expandSubReplies` 第一页成功时本来就新建 `RemoteListState`，不用改。）

- [ ] **Step 2: 换排序回顶部**

`DynamicCommentDialog.kt` 两个 `SortChip(...)` 换成（只在真的换排序时回顶；不要用 `LaunchedEffect(sort)`，弹层从 UP 空间返回恢复时它会重跑、冲掉恢复的滚动位置）：

```kotlin
                SortChip("热度", selected = sort == 1) {
                    if (sort != 1) {
                        listState.requestScrollToItem(0)
                        onSortChange(1)
                    }
                }
                SortChip("时间", selected = sort == 0) {
                    if (sort != 0) {
                        listState.requestScrollToItem(0)
                        onSortChange(0)
                    }
                }
```

（`listState` 是 `DynamicCommentDialog` 已有的参数。若 `requestScrollToItem` 在当前 Compose 版本不可用，改为 `rememberCoroutineScope().launch { listState.scrollToItem(0) }`，并在报告里注明。）

- [ ] **Step 3: 编译与单测**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: 全部成功（`DynamicCommentDialogUiTest` 里点「时间」仍会回调 `onSortChange(0)`，因为测试状态 `sort = 1`）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt
git commit -m "fix(comment): 楼中楼加载成功后清掉旧错误；换排序回到列表顶部

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 友好错误文案 + 首屏失败「重试」

**Files:**
- Create: `app/src/main/java/com/biliseek/app/comment/CommentErrorPolicy.kt`
- Create: `app/src/test/java/com/biliseek/app/comment/CommentErrorPolicyTest.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（`comments()` / `subReplies()` 的 `code != 0` 分支）
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`、`CommentThreadSheet.kt`

**Interfaces:**
- Produces: `fun commentErrorText(action: String, error: Throwable): String`、`fun isRetryableCommentError(error: Throwable): Boolean`、`CommentThreadState.listErrorRetryable: Boolean = true`、`CommentThreadController.retry()`、`DynamicCommentDialog(..., onRetry: () -> Unit = {})`

- [ ] **Step 1: 写失败的测试** `app/src/test/java/com/biliseek/app/comment/CommentErrorPolicyTest.kt`

```kotlin
package com.biliseek.app.comment

import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchException
import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class CommentErrorPolicyTest {

    @Test
    fun `风控不露错误码，提示等一会`() {
        assertEquals(
            "评论加载失败：请求太频繁，请过几分钟再试",
            commentErrorText("评论加载", SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")),
        )
        assertEquals(
            "点赞失败：请求太频繁，请过几分钟再试",
            commentErrorText("点赞", WriteException(WriteError.Blocked, "触发风控(-352)，已进入退避")),
        )
    }

    @Test
    fun `服务端中文提示保留，空的给兜底`() {
        assertEquals(
            "评论失败：评论内容包含敏感信息",
            commentErrorText("评论", WriteException(WriteError.Unknown(12015, "评论内容包含敏感信息"), "操作失败：code=12015 评论内容包含敏感信息")),
        )
        assertEquals("评论失败：未知错误", commentErrorText("评论", WriteException(WriteError.Unknown(1, ""), "x")))
        assertEquals(
            "评论加载失败：评论区已关闭",
            commentErrorText("评论加载", SearchException(SearchError.Unknown("评论区已关闭"), "评论加载失败：code=12002 评论区已关闭")),
        )
    }

    @Test
    fun `登录、网络、凭据`() {
        assertEquals("删除失败：请先登录", commentErrorText("删除", WriteException(WriteError.NeedLogin, "未登录(-101)")))
        assertEquals("评论失败：登录已失效，请重新登录", commentErrorText("评论", WriteException(WriteError.CsrfInvalid, "x")))
        assertEquals("评论加载失败：网络不可用", commentErrorText("评论加载", SearchException(SearchError.Network("timeout"), "x")))
        assertEquals("评论加载失败：网络不可用", commentErrorText("评论加载", IOException("reset")))
        assertEquals(
            "评论加载失败：设备凭据已重置，请稍后再试",
            commentErrorText("评论加载", SearchException(SearchError.CredentialsInvalid, "x")),
        )
        assertEquals("评论加载失败：未知错误", commentErrorText("评论加载", IllegalStateException("boom")))
    }

    @Test
    fun `风控不给重试，其他可以`() {
        assertEquals(false, isRetryableCommentError(SearchException(SearchError.Blocked, "x")))
        assertEquals(true, isRetryableCommentError(SearchException(SearchError.Network(""), "x")))
        assertEquals(true, isRetryableCommentError(IOException("x")))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentErrorPolicyTest"`
Expected: 编译失败，`Unresolved reference: commentErrorText`。

- [ ] **Step 3: 实现** `app/src/main/java/com/biliseek/app/comment/CommentErrorPolicy.kt`

```kotlin
package com.biliseek.app.comment

import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchException
import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import com.biliseek.core.network.retryable
import java.io.IOException

/**
 * 评论相关失败 → 给用户看的一句话：「$action失败：原因」。不露原始错误码；
 * 服务端给的中文提示（如「评论内容包含敏感信息」）原样保留。
 */
fun commentErrorText(action: String, error: Throwable): String = "${action}失败：${commentErrorReason(error)}"

private fun commentErrorReason(error: Throwable): String = when (error) {
    is SearchException -> when (val e = error.error) {
        SearchError.Blocked -> BLOCKED
        SearchError.CredentialsInvalid -> CREDENTIALS
        SearchError.NotActivated -> CREDENTIALS
        is SearchError.Network -> NETWORK
        is SearchError.Unknown -> e.message.ifBlank { UNKNOWN }
        else -> UNKNOWN
    }
    is WriteException -> when (val e = error.error) {
        WriteError.NeedLogin -> "请先登录"
        WriteError.CsrfInvalid -> "登录已失效，请重新登录"
        WriteError.Blocked -> BLOCKED
        WriteError.CredentialsInvalid -> CREDENTIALS
        is WriteError.Network -> NETWORK
        is WriteError.Unknown -> e.message.ifBlank { UNKNOWN }
    }
    is IOException -> NETWORK
    else -> UNKNOWN
}

/** 失败后能不能给「重试」：风控拦截（-352 / HTTP 412）不给，免得用户连点把临时限制变长期封禁。 */
fun isRetryableCommentError(error: Throwable): Boolean =
    (error as? SearchException)?.error?.retryable ?: true

private const val BLOCKED = "请求太频繁，请过几分钟再试"
private const val CREDENTIALS = "设备凭据已重置，请稍后再试"
private const val NETWORK = "网络不可用"
private const val UNKNOWN = "未知错误"
```

（`retryable` 是 `core/network/.../SearchError.kt` 里公开的扩展属性；若 import 路径不同按实际调整。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentErrorPolicyTest"`
Expected: PASS。

- [ ] **Step 5: 网络层：Unknown 只带服务端 message**

`BiliRepository.kt` 的 `comments()` 与 `subReplies()` 中 `if (response.code != 0) { throw SearchException(SearchError.Unknown("code=${response.code} ${response.message}"), ...) }` 的 `SearchError.Unknown(...)` 参数都改成 `SearchError.Unknown(response.message)`（异常的第二个参数——日志用的 message——保持带 code 不变）。

- [ ] **Step 6: 控制器用上文案**

`CommentThreadController.kt`：
1. `CommentThreadState` 在 `nextOffset` 后加：
   ```kotlin
       /** 主列表最近一次失败能不能给「重试」（风控拦截时为 false）。 */
       val listErrorRetryable: Boolean = true,
   ```
2. `load()` 的 `.onFailure { err -> ... }`：`errorMessage = err.message ?: "评论加载失败"` 改为 `errorMessage = commentErrorText("评论加载", err)`，并在同一个 `dialog.copy(...)` 里加 `listErrorRetryable = isRetryableCommentError(err),`。
3. `expandSubReplies` 与 `loadMoreSubReplies` 的 `.onFailure`：`errorMessage = err.message ?: "楼中楼加载失败"` 改为 `errorMessage = commentErrorText("楼中楼加载", err)`。
4. `emitMessage("点赞失败：${err.message ?: "未知错误"}")` → `emitMessage(commentErrorText("点赞", err))`；`emitMessage("删除失败：…")` → `emitMessage(commentErrorText("删除", err))`；`emitMessage("评论失败：…")` → `emitMessage(commentErrorText("评论", err))`。
5. `loadMore()` 之后加：
   ```kotlin
       /** 第一页失败后点「重试」：重拉第一页（冷却期内本地直接失败，不会打到服务端）。 */
       fun retry() = load(reset = true)
   ```

- [ ] **Step 7: 界面「重试」**

`DynamicCommentDialog.kt`：
1. 参数在 `onLoadMore` 后加 `onRetry: () -> Unit = {},`。
2. 空状态分支 `comments.items.isEmpty() && state.topReplies.isEmpty() -> BsEmptyState(...)` 加 `action`（只有出错且可重试时才有按钮）：
   ```kotlin
                   comments.items.isEmpty() && state.topReplies.isEmpty() -> BsEmptyState(
                       comments.errorMessage ?: "还没有评论，来说两句吧",
                       Modifier.weight(1f),
                       icon = Icons.Outlined.ChatBubbleOutline,
                       action = if (comments.errorMessage != null && state.listErrorRetryable) {
                           { TextButton(onClick = onRetry) { Text("重试") } }
                       } else null,
                   )
   ```
   import 加 `androidx.compose.material3.TextButton`（若尚未导入）。

`CommentThreadSheet.kt` 的 `DynamicCommentDialog(...)` 加 `onRetry = controller::retry,`。

- [ ] **Step 8: 编译与全量单测**

Run: `./gradlew :core:network:testDebugUnitTest :app:testDebugUnitTest && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: 全部成功。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/ app/src/test/java/com/biliseek/app/comment/ \
  core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt \
  app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt
git commit -m "feat(comment): 错误提示改成好懂的话；评论加载失败可以重试（风控时不给）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 评论读接口 HTTP 412 进冷却

**问题：** `comments()` / `subReplies()` 只 catch `IOException`；B 站 WAF 回 HTTP 412 时 Retrofit 抛 `retrofit2.HttpException`，不进 `RiskCooldown`，自动翻页 / 重试还会继续打。`viewConclusion` 已按「HTTP 412 = 风控」处理（`BiliRepository.kt` 约 947 行）。

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（`comments()`、`subReplies()`）

- [ ] **Step 1: 实现**

`comments()` 与 `subReplies()` 各自的 `try { ... } catch (e: IOException) { ... }` 后面再加一个分支：

```kotlin
        } catch (e: retrofit2.HttpException) {
            // HTTP 412 是 B 站 WAF 的风控拦截：与 -352 同样进冷却、不重试
            if (e.code() == 412) {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（HTTP 412），已进入退避")
            }
            throw e
        }
```

（`viewConclusion` 的现有处理不改。）

- [ ] **Step 2: 编译与单测**

Run: `./gradlew :core:network:testDebugUnitTest && ./gradlew :app:compileDebugKotlin`
Expected: 全部成功。（`BiliRepository` 没有可离线构造的测试桩，这一步靠编译 + 真机验证；`commentErrorText` 已覆盖 `SearchError.Blocked` 的文案与不给重试。）

- [ ] **Step 3: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
git commit -m "fix(comment): 评论加载遇 HTTP 412 也按风控进冷却

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 长按评论选字复制

**做法：** 与动态长图文一样（`DynamicScreen.kt` 用 `SelectionContainer` 包 `RichText`，@ / 链接仍可点）。

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`（`CommentItemRow` 的正文 `RichText`、`SubReplyRow` 的 `RichText`）

- [ ] **Step 1: 实现**

1. `CommentItemRow` 里正文那个 `RichText(source = reply.content.toRichSource(), modifier = Modifier.padding(top = BsSpacing.xs), ...)` 包进 `SelectionContainer`（`modifier` 挪到 `SelectionContainer` 上）：
   ```kotlin
               SelectionContainer(Modifier.padding(top = BsSpacing.xs)) {
                   RichText(
                       source = reply.content.toRichSource(),
                       style = MaterialTheme.typography.bodyMedium,
                       actions = actions,
                   )
               }
   ```
2. `SubReplyRow` 里的 `RichText(..., prefix = { ... })` 整个包进 `SelectionContainer { ... }`。
3. import 加 `androidx.compose.foundation.text.selection.SelectionContainer`。

- [ ] **Step 2: 编译与单测**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin && ./gradlew :app:testDebugUnitTest`
Expected: 全部成功（UI 测试里按文字找节点、点 @ / 昵称的方式不受 `SelectionContainer` 影响）。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt
git commit -m "feat(comment): 长按评论正文可以选字复制

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 文档与最终验证

**Files:**
- Modify: `docs/05-功能优化篇.md`（文件末尾，`### 13.78` 之后）
- Modify: `docs/BiliSeek-手动测试指南.md`（「#### 评论区（动态评论与播放页评论共用，2026-10-23）」表格末尾）

- [ ] **Step 1: `docs/05-功能优化篇.md` 末尾追加**

```markdown

### 13.79 评论区第二轮优化（2026-10-23）

- **播放页评论区跟着当前视频走**：换了视频（UP 空间里点别的视频只起播不跳页、队列切到下一首、停止播放）就关掉上一个视频的评论区，草稿照常存（`shouldDropPlayerComments`）。以前返回后弹层还是旧视频的评论，发出去会落到旧视频下面。
- **楼中楼错误能清掉**：「加载更多回复」失败一次后，以前成功了也一直显示「加载失败，点击重试」；现在开始加载与成功时都清掉错误。
- **换排序回到顶部**：点「热度 / 时间」时先滚回第一条，不再从中间开始、也不会因为停在底部多翻一页。
- **首屏失败可以重试**：空状态带「重试」；风控拦截（-352 / HTTP 412）时不给按钮，提示「请过几分钟再试」。
- **错误文案**：`CommentErrorPolicy` 把失败转成「X失败：原因」，不再露 `code=…` / `(-352)`；服务端给的中文提示保留。
- **HTTP 412**：`comments()` / `subReplies()` 遇 HTTP 412 按风控进冷却（以前只 catch `IOException`，412 不进冷却）。
- **长按选字复制**：评论正文、楼中楼可长按选字，@ / 昵称 / 链接仍可点。
- **验证**：`CommentListPolicyTest`、`CommentErrorPolicyTest` 离线单测。**HTTP 412 与选字交互待真机确认。**
```

- [ ] **Step 2: 手动测试指南** — 评论区表格末尾追加：

```markdown
| 播放页打开评论区，点人进 UP 空间，在那里点另一个视频播放，再返回 | 回到播放页时评论区已关掉（不会显示上一个视频的评论）；重新打开是新视频的评论 |
| 播放页评论区开着时，队列自动切到下一首 | 评论区自动关掉 |
| 先滑到列表中间，再点「时间」/「热度」 | 新排序从第一条开始显示 |
| 断网后打开评论区 | 显示「评论加载失败：网络不可用」和「重试」；恢复网络点「重试」能加载 |
| 长按一条评论或楼中楼的正文 | 出现选字手柄和「复制」，可以选一部分复制；@ 和昵称仍能点 |
```

- [ ] **Step 3: 最终验证**

```bash
./gradlew test assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL；不装机。

- [ ] **Step 4: 提交**

```bash
git add docs/05-功能优化篇.md docs/BiliSeek-手动测试指南.md docs/superpowers/plans/2026-10-23-comment-polish.md
git commit -m "docs(comment): 记录评论区第二轮优化（§13.79）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
