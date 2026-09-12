# 动态卡片转发/评论/点赞交互 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复动态卡片"点赞"取反 bug，把"转发"从未验证的写接口替换为纯本地复制链接，并把"评论"从占位输入框升级为真正的评论列表浏览页（列表 + 楼中楼 + 排序 + 点赞 + 回复）。

**Architecture:** 沿用项目既有分层——`core:network`（`BiliApi`/`BiliRepository`/DTO）只做"oid/type 参数化 + 复用已单测的 `writeRequest`/`runWriteRequest`/`runCommentRead` 编排"，不新增基础设施；`app` 模块的 `AppViewModel` 承载评论列表分页/楼中楼/回复目标状态（复用已有的 `RemoteListState<T>`），UI 层把 `DynamicCommentDialog` 从占位输入框升级为 `ModalBottomSheet` 列表，不新增 NavHost route。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Retrofit + kotlinx.serialization, JUnit4（纯函数单测，**不引入任何 mock 框架**——仓库现状是 mockk/mockito 零依赖），Compose UI Test（`createComposeRule`）。

## Global Constraints

- **禁止自动化代码打真实 B 站服务器**（CLAUDE.md 安全规则）。本计划所有单测/UI 测试只用本地构造的假数据和 lambda 协作者，不发真实网络请求。涉及"接口语义是否与服务端一致"的地方，本计划只做到"结构正确、编译通过、逻辑自洽"，真机验证是人工步骤，不在本计划的自动化任务里。
- **风控错误码分流不可更改**：`-101` 刷新重试一次、`-352` 绝不重试、`-412` 重置凭据。本计划新增/改动的写操作全部继续走已有的 `writeRequest`/`runWriteRequest`（不新写编排代码）。
- **仓库测试哲学**：把决策逻辑抽成纯函数（`*Policy.kt` / 顶层函数），用手写 lambda 协作者 + JUnit4 直接测，**不引入 mockk/mockito/turbine**。`BiliRepository` 的方法本身（如 `comments`/`replyAction`/`addDynamicReply`）在这个仓库里从来不被直接单测——只测它们内部复用的纯函数（如 `resolveWriteOutcome`、`runCommentRead`、`resolveDynamicCommentTargetFromEntry`）。本计划延续这个约定：新增的 `resolveReplyRootParent` 会被单测；`comments`/`replyAction`/`subReplies`/`addDynamicReply` 的签名改动只靠编译 + 现有测试套件回归，不新增假测试凑数。
- **`AppViewModel` 目前零单测覆盖**（`app/src/test/` 下没有任何 `AppViewModel` 相关测试文件），且没有为它搭测试替身的基础设施。本计划新增的 ViewModel 编排方法同样不引入新的测试框架去弥补这个空白（那是与本任务无关的更大改动），而是通过编译 + 现有测试套件回归 + 人工真机验证收尾。
- **UI 文字风格**：项目里的按钮/图标统一用文字而非图标字体（`ActionButton.kt` 注释："文本而非图标"），本计划新增的评论列表 UI 延续这个风格，不引入 `Icons.*`。
- **明确排除的范围**（用户已确认）：不做评论删除/举报/置顶；不新增 NavHost route；不做楼中楼内逐条点赞/回复（楼中楼展开后是只读预览，只有一级评论支持点赞/回复/展开楼中楼）；分页统一用项目已有的"列表末尾 sentinel item + `LaunchedEffect` 触发"模式，不引入滚动位置探测。

---

## Part 1：点赞取反 bug 修复

### Task 1: 修复点赞按钮取反 bug

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:183`
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt:390`

**Interfaces:**
- Consumes：`AppViewModel.toggleDynamicLike(idStr: String, like: Boolean)`（已存在，不改）
- Produces：无新接口，纯行为修复

**背景**：`toggleDynamicLike` 的 `like` 参数语义是"点击后期望达到的目标状态"，但两处调用点把"当前是否已点赞"直接传了进去，导致已点赞状态下点击不会取消。

- [ ] **Step 1: 修复 `DynamicScreen.kt:183`**

把：
```kotlin
onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status == true) },
```
改成：
```kotlin
onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
```

- [ ] **Step 2: 修复 `UpSpaceScreen.kt:390`**

把：
```kotlin
onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status == true) },
```
改成：
```kotlin
onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
```

- [ ] **Step 3: 编译并跑现有测试套件确认无回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（这是一行文字取反，没有可测的分支逻辑，靠编译 + 现有测试回归即可；正确性需要人工真机点击验证——见 Task 15）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt
git commit -m "fix: 动态点赞按钮取反 bug，取消点赞现在生效"
```

---

## Part 2：转发 → 复制链接

### Task 2: 新增 `dynamicUrl`/`notifyLinkCopied`，把转发按钮接到复制链接

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（在 `videoUrl` 附近新增 `dynamicUrl`；在 `emitMessage` 附近新增 `notifyLinkCopied`）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:237`（`DynamicScreen` 的 `onOpenRepost`）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:269`（`UpSpaceScreen` 的 `onOpenRepost`）

**Interfaces:**
- Produces：`AppViewModel.dynamicUrl(idStr: String): String`、`AppViewModel.notifyLinkCopied(): Unit`
- Consumes（已存在）：`MainActivity.copyToClipboard(text: String)`（第 550-553 行）

**关键点**：`DynamicScreen`/`UpSpaceScreen` 的 `onOpenRepost: (String) -> Unit` 参数签名已经是"给一个 idStr"，不需要改这两个 Composable 本身——只改 `MainActivity` 里传进去的 lambda。

- [ ] **Step 1: 在 `AppViewModel.kt` 新增 `dynamicUrl`**

在第 1191 行 `fun videoUrl(video: SearchVideo) = "https://www.bilibili.com/video/${video.bvid}"` 后面新增：

```kotlin
    /** 动态外链（用于「转发=复制链接」，Ch4 转发按钮的新语义）。 */
    fun dynamicUrl(idStr: String) = "https://t.bilibili.com/$idStr"
```

- [ ] **Step 2: 在 `AppViewModel.kt` 新增 `notifyLinkCopied`**

在第 304 行 `private fun emitMessage(text: String) { _transientMessage.tryEmit(text) }` 后面新增：

```kotlin

    /** 复制链接后的一次性提示（Toast），走既有 [transientMessage] 管道。 */
    fun notifyLinkCopied() = emitMessage("链接已复制")
```

- [ ] **Step 3: 改 `MainActivity.kt:237`（`DynamicScreen` 的 `onOpenRepost`）**

把：
```kotlin
                            onOpenRepost = viewModel::openRepostDialog,
```
改成：
```kotlin
                            onOpenRepost = { idStr ->
                                copyToClipboard(viewModel.dynamicUrl(idStr))
                                viewModel.notifyLinkCopied()
                            },
```

- [ ] **Step 4: 改 `MainActivity.kt:269`（`UpSpaceScreen` 的 `onOpenRepost`）**

把：
```kotlin
                                onOpenRepost = viewModel::openRepostDialog,
```
改成：
```kotlin
                                onOpenRepost = { idStr ->
                                    copyToClipboard(viewModel.dynamicUrl(idStr))
                                    viewModel.notifyLinkCopied()
                                },
```

- [ ] **Step 5: 编译确认（此时 `openRepostDialog` 还没删，仍能编译）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/biliseek/app/AppViewModel.kt app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat: 转发按钮改为复制动态链接"
```

### Task 3: 删除未验证的"转发到自己动态"死代码

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（删 `RepostDialogState`、`_repostDialog`、`openRepostDialog`/`updateRepostContent`/`dismissRepostDialog`/`submitRepost`）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（删 `repostDialog` collect 与渲染块）
- Delete: `app/src/main/java/com/biliseek/app/ui/components/RepostDialog.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（删 `forwardDynamic`/`jsonWriteRequest`/`buildForwardBody`，删对应未用 import）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`（删 `createDynamic`）

**Interfaces:** 无新增；这一步只删除，Task 2 已经切断了所有对这些代码的调用。

- [ ] **Step 1: 确认 Task 2 之后已无调用点（防止误删还在用的代码）**

Run: `grep -rn "openRepostDialog\|submitRepost\|updateRepostContent\|dismissRepostDialog\|forwardDynamic\|RepostDialog(" app/src/main core/network/src/main`
Expected: 只剩下 `AppViewModel.kt` 里的方法定义本身、`BiliRepository.kt` 里的 `forwardDynamic`/`buildForwardBody` 定义本身、`RepostDialog.kt` 文件——没有任何外部调用点（`MainActivity.kt` 已在 Task 2 改掉）。

- [ ] **Step 2: 删除 `AppViewModel.kt` 里的转发弹窗状态与方法**

删除第 214-219 行：
```kotlin
/** Ch4 动态转发对话框状态。 */
data class RepostDialogState(
    val idStr: String,
    val content: String = "",
    val isPosting: Boolean = false,
)
```

删除第 327-329 行：
```kotlin
    /** 动态转发对话框。null=关闭。 */
    private val _repostDialog = MutableStateFlow<RepostDialogState?>(null)
    val repostDialog: StateFlow<RepostDialogState?> = _repostDialog.asStateFlow()
```

删除第 1668-1710 行（`openRepostDialog`/`updateRepostContent`/`dismissRepostDialog`/`submitRepost` 整块，即原 `/** 打开转发输入对话框。 */` 到 `submitRepost` 函数结束的 `}`）。

- [ ] **Step 3: 删除 `MainActivity.kt` 里的转发弹窗接线**

删除第 112 行：
```kotlin
                val repostDialog by viewModel.repostDialog.collectAsState()
```
（保留同区块第 113 行的 `val commentDialog by viewModel.commentDialog.collectAsState()`，Task 13 会改它）

删除第 487-496 行：
```kotlin
                // Ch4：动态转发 / 评论 / 图片预览
                repostDialog?.let { state ->
                    com.biliseek.app.ui.components.RepostDialog(
                        content = state.content,
                        isPosting = state.isPosting,
                        onContentChange = viewModel::updateRepostContent,
                        onSubmit = viewModel::submitRepost,
                        onDismiss = viewModel::dismissRepostDialog,
                    )
                }
```
（保留紧随其后的 `commentDialog?.let { ... }` 块，Task 13 会改它；但把上面这段的注释 `// Ch4：动态转发 / 评论 / 图片预览` 挪到 `commentDialog?.let` 前面，因为图片预览的渲染块还在后面）

- [ ] **Step 4: 删除 `RepostDialog.kt` 文件**

```bash
git rm app/src/main/java/com/biliseek/app/ui/components/RepostDialog.kt
```

- [ ] **Step 5: 删除 `BiliRepository.kt` 里的 `forwardDynamic`/`jsonWriteRequest`/`buildForwardBody`**

删除第 773-856 行整块（从 `/** * 转发一条动态（纯文字最小集）。` 的文档注释开始，到 `buildForwardBody` 函数结束的 `}.toString()` 为止），保留紧邻的 `private companion object { ... }`（第 858 行起）。

删除后确认第 734-744 行的 `likeDynamic` 完整保留（不受影响，它是动态点赞，不是转发）。

- [ ] **Step 6: 删除 `BiliRepository.kt` 里因上一步变成未用的 import**

删除第 31-38 行里的这 6 行（保留 `put` 那一行，其它写操作还在用它）：
```kotlin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
```
只保留：
```kotlin
import kotlinx.serialization.json.put
```

- [ ] **Step 7: 删除 `BiliApi.kt` 里的 `createDynamic`**

删除第 337-346 行整块：
```kotlin
    /**
     * 转发（动态转发）· WBI 签名 query + JSON body。表单 writeRequest 不适用，走 jsonWriteRequest。
     * ⚠️ **未验证**：dyn_req JSON 结构复杂且易变，以当日抓包为准（B6，先只做纯文字转发最小集）。
     */
    @POST("x/dynamic/feed/create/dyn")
    suspend fun createDynamic(
        @QueryMap wbiParams: Map<String, String>,        // 含 csrf + WBI 签名
        @Body body: RequestBody,                         // JSON：转发内容 / 原动态 id
        @Header("Referer") referer: String = "https://t.bilibili.com/",
    ): BiliResponse<JsonElement>
```
（`RequestBody`/`@Body`/`@Header`/`JsonElement` 的 import 仍被 `activateBuvid`/`likeVideo` 等其它方法使用，不要删 import）

- [ ] **Step 8: 编译并跑全量单测确认无遗留引用**

Run: `./gradlew :core:network:compileDebugKotlin :app:compileDebugKotlin :core:network:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部既有测试通过

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: 删除未验证的「转发到自己动态」写接口链路"
```

---

## Part 3：评论 API 层

### Task 4: 扩展 `ReplyItem` DTO，支持楼中楼预览与点赞态

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/ReplyDto.kt`
- Create: `core/network/src/test/java/com/biliseek/core/network/ReplyDtoTest.kt`

**Interfaces:**
- Produces：`ReplyItem` 新增字段 `rcount: Int`、`action: Int`、`replies: List<ReplyItem>?`（其余字段不变）

- [ ] **Step 1: 写失败的测试**

Create `core/network/src/test/java/com/biliseek/core/network/ReplyDtoTest.kt`:

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.ReplyData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 评论 DTO 解析：楼中楼预览（rcount/replies）与点赞态（action）。
 * B 站评论接口的标准字段，字段缺失时安全降级为 0 / null，不崩。
 */
class ReplyDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `解析带楼中楼预览与点赞态的评论`() {
        val data = json.decodeFromString<ReplyData>(
            """
            {
              "page": { "count": 1, "num": 1, "size": 20 },
              "replies": [{
                "rpid": 100,
                "oid": 200,
                "mid": 300,
                "ctime": 1700000000,
                "like": 5,
                "rcount": 2,
                "action": 1,
                "content": { "message": "一楼" },
                "member": { "mid": "300", "uname": "楼主", "avatar": "//a.jpg" },
                "replies": [
                  { "rpid": 101, "content": { "message": "沙发" }, "member": { "mid": "301", "uname": "小明" } }
                ]
              }]
            }
            """.trimIndent(),
        )
        val reply = data.replies?.single()
        assertEquals(100L, reply?.rpid)
        assertEquals(2, reply?.rcount)
        assertEquals(1, reply?.action)
        assertEquals(1, reply?.replies?.size)
        assertEquals("沙发", reply?.replies?.get(0)?.content?.message)
    }

    @Test
    fun `字段缺失时安全降级`() {
        val data = json.decodeFromString<ReplyData>(
            """{ "replies": [{ "rpid": 1, "content": { "message": "x" }, "member": { "uname": "y" } }] }""",
        )
        val reply = data.replies?.single()
        assertEquals(0, reply?.rcount)
        assertEquals(0, reply?.action)
        assertNull(reply?.replies)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.ReplyDtoTest"`
Expected: FAIL（`ReplyItem` 目前没有 `rcount`/`action`/`replies` 字段，编译或反序列化会不匹配）

- [ ] **Step 3: 扩展 `ReplyItem`**

在 `core/network/src/main/java/com/biliseek/core/network/dto/ReplyDto.kt` 里，把：
```kotlin
@Serializable
data class ReplyItem(
    val rpid: Long = 0,
    val oid: Long = 0,
    val mid: Long = 0,
    val ctime: Long = 0,
    val like: Int = 0,
    val content: ReplyContent = ReplyContent(),
    val member: ReplyMember = ReplyMember(),
)
```
改成：
```kotlin
@Serializable
data class ReplyItem(
    val rpid: Long = 0,
    val oid: Long = 0,
    val mid: Long = 0,
    val ctime: Long = 0,
    val like: Int = 0,
    /** 楼中楼总数。 */
    val rcount: Int = 0,
    /** 当前用户是否已点赞该评论（1=已赞，0=未赞）。 */
    val action: Int = 0,
    val content: ReplyContent = ReplyContent(),
    val member: ReplyMember = ReplyMember(),
    /** 服务端随主评论内嵌返回的前几条楼中楼（预览用，翻页走 [BiliApi.replyReply]）。 */
    val replies: List<ReplyItem>? = null,
)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.ReplyDtoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/ReplyDto.kt core/network/src/test/java/com/biliseek/core/network/ReplyDtoTest.kt
git commit -m "feat: ReplyItem 支持楼中楼预览与点赞态字段"
```

### Task 5: 泛化 `comments()` 签名，支持动态 oid/type

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt:571-618`

**Interfaces:**
- Produces：`BiliRepository.comments(oid: Long, type: Int, page: Int = 1, sort: Int = 1): Result<CommentPage>`（原来是 `comments(aid: Long, page: Int = 1, sort: Int = 1)`，硬编码 `type="1"`）
- 确认：`grep -rn "\.comments(" app/src/main core/network/src/main` 当前零调用点，改签名不破坏任何现有功能。

- [ ] **Step 1: 确认零调用点**

Run: `grep -rn "\.comments(" app/src/main core/network/src/main`
Expected: 无输出（只有函数定义本身，grep 加 `\.comments(` 不会匹配 `fun comments(`）

- [ ] **Step 2: 改签名与 body**

把（第 567-582 行）：
```kotlin
    /**
     * 评论列表。先走 WBI 主链路，遇 -111/风控降级兼容链路一次（[runCommentRead]）。
     * `sort`：0 按时间 / 1 按热度。写侧（发评论/点赞）走 Phase A [addReply]/[replyAction]。
     */
    suspend fun comments(aid: Long, page: Int = 1, sort: Int = 1): Result<CommentPage> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val baseParams = mapOf(
            "oid" to aid.toString(),
            "type" to "1",
            "pn" to page.toString(),
            "sort" to sort.toString(),
        )
```
改成：
```kotlin
    /**
     * 评论列表。先走 WBI 主链路，遇 -111/风控降级兼容链路一次（[runCommentRead]）。
     * `oid`/`type`：视频=用 aid + type=1；动态=按 [com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry] 算出的 oid + type(11/17)。
     * `sort`：0 按时间 / 1 按热度。写侧（发评论/点赞）走 [addDynamicReply]/[replyAction]。
     */
    suspend fun comments(oid: Long, type: Int, page: Int = 1, sort: Int = 1): Result<CommentPage> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val baseParams = mapOf(
            "oid" to oid.toString(),
            "type" to type.toString(),
            "pn" to page.toString(),
            "sort" to sort.toString(),
        )
```
（其余函数体第 583-618 行不变）

- [ ] **Step 3: 编译确认**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 跑既有测试套件确认无回归（`runCommentRead` 等纯函数测试不受影响）**

Run: `./gradlew :core:network:test`
Expected: BUILD SUCCESSFUL，全部通过

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
git commit -m "refactor: comments() 泛化为 oid/type 参数，供动态与视频共用"
```

### Task 6: 泛化 `replyAction()` 签名，支持动态 oid/type

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt:687-694`

**Interfaces:**
- Produces：`BiliRepository.replyAction(oid: Long, type: Int, rpid: Long, like: Boolean): Result<Unit>`（原来是 `replyAction(aid: Long, rpid: Long, like: Boolean)`，硬编码 `type="1"`）
- 确认：`grep -rn "\.replyAction(" app/src/main core/network/src/main` 当前零调用点。

- [ ] **Step 1: 确认零调用点**

Run: `grep -rn "\.replyAction(" app/src/main core/network/src/main`
Expected: 无输出

- [ ] **Step 2: 改签名与 body**

把：
```kotlin
    /** 评论点赞 / 取消。 */
    suspend fun replyAction(aid: Long, rpid: Long, like: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "oid" to aid.toString(), "type" to "1",
                "rpid" to rpid.toString(), "action" to if (like) "1" else "0",
            ),
        ) { f, _ -> api.replyAction(f) }.map { }
```
改成：
```kotlin
    /** 评论点赞 / 取消。`oid`/`type` 语义同 [comments]。 */
    suspend fun replyAction(oid: Long, type: Int, rpid: Long, like: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "oid" to oid.toString(), "type" to type.toString(),
                "rpid" to rpid.toString(), "action" to if (like) "1" else "0",
            ),
        ) { f, _ -> api.replyAction(f) }.map { }
```

- [ ] **Step 3: 编译并跑现有测试套件**

Run: `./gradlew :core:network:compileDebugKotlin :core:network:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
git commit -m "refactor: replyAction() 泛化为 oid/type 参数"
```

### Task 7: 新增 `subReplies()`，包装楼中楼读接口

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（在 `comments` 后面新增）

**Interfaces:**
- Consumes：`BiliApi.replyReply(params: Map<String, String>): BiliResponse<ReplyData>`（已存在）、`BiliRepository.CommentPage`（已存在）
- Produces：`BiliRepository.subReplies(oid: Long, type: Int, root: Long, page: Int = 1): Result<CommentPage>`

- [ ] **Step 1: 在 `comments()` 函数结束的 `}` 后面（第 618 行之后）新增**

```kotlin

    /**
     * 楼中楼（`x/v2/reply/reply`，不需要 WBI 签名）。`root` 是根评论 rpid，`page` 从 1 起。
     * 错误分流 / 风控退避与 [comments] 一致，但只有单一链路，没有 WBI/兼容双轨。
     */
    suspend fun subReplies(oid: Long, type: Int, root: Long, page: Int = 1): Result<CommentPage> = runCatching {
        try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val params = mapOf(
            "oid" to oid.toString(),
            "type" to type.toString(),
            "root" to root.toString(),
            "pn" to page.toString(),
            "ps" to "10",
        )
        ensureNotCoolingRead()
        val response = try {
            requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                api.replyReply(params)
            }
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
            }
            else -> Unit
        }
        if (response.code != 0) {
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "楼中楼加载失败：code=${response.code} ${response.message}",
            )
        }
        riskCooldown.onSuccess()
        val data = response.data
        CommentPage(items = data?.replies.orEmpty(), count = data?.page?.count ?: 0)
    }
```

- [ ] **Step 2: 编译并跑现有测试套件**

Run: `./gradlew :core:network:compileDebugKotlin :core:network:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
git commit -m "feat: 新增 subReplies() 包装楼中楼读接口"
```

### Task 8: 扩展 `addDynamicReply`，支持回复某条评论/楼中楼

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt:746-759`

**Interfaces:**
- Produces：`BiliRepository.addDynamicReply(oid: Long, type: Int, message: String, root: Long = 0, parent: Long = 0): Result<Long>`（原来只有 `oid, type, message`）
- 参考：同文件里 `addReply(aid, message, root: Long? = null, parent: Long? = null)`（第 677-685 行）已经用 `buildMap { ... }` 做过同样的"可选 root/parent"模式，本任务照抄这个风格，只是用 `Long = 0`（不用 `Long? = null`）以匹配 ViewModel 侧 `CommentReplyTarget` 的非空默认值设计（Task 10）。

- [ ] **Step 1: 改签名与 body**

把：
```kotlin
    /**
     * 动态评论。`type` 与 `oid` 由调用方按 [resolveDynamicCommentTarget] 决定
     * ——DRAW=11+drawId，其他=17+id_str。
     */
    suspend fun addDynamicReply(oid: Long, type: Int, message: String): Result<Long> =
        writeRequest(
            payload = mapOf(
                "oid" to oid.toString(),
                "type" to type.toString(),
                "message" to message,
                "plat" to "1",
            ),
            referer = "https://t.bilibili.com/",
        ) { f, _ -> api.addReply(f) }.map { it?.rpid ?: 0L }
```
改成：
```kotlin
    /**
     * 动态评论 / 回复。`type` 与 `oid` 由调用方按 [resolveDynamicCommentTarget] 决定
     * ——DRAW=11+drawId，其他=17+id_str。`root`/`parent` 为 0 表示发表新的一级评论；
     * 非 0 表示回复某条评论或楼中楼（语义同 [addReply]）。
     */
    suspend fun addDynamicReply(
        oid: Long,
        type: Int,
        message: String,
        root: Long = 0,
        parent: Long = 0,
    ): Result<Long> =
        writeRequest(
            payload = buildMap {
                put("oid", oid.toString())
                put("type", type.toString())
                put("message", message)
                put("plat", "1")
                if (root > 0) put("root", root.toString())
                if (parent > 0) put("parent", parent.toString())
            },
            referer = "https://t.bilibili.com/",
        ) { f, _ -> api.addReply(f) }.map { it?.rpid ?: 0L }
```

- [ ] **Step 2: 编译并跑现有测试套件（`submitComment` 目前用 3 参数调用，加了默认值后仍兼容）**

Run: `./gradlew :core:network:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
git commit -m "feat: addDynamicReply 支持 root/parent 回复某条评论"
```

### Task 9: 新增 `resolveReplyRootParent` 纯函数 + 单测

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/internal/DynamicCommentPolicy.kt`
- Create: `core/network/src/test/java/com/biliseek/core/network/ReplyRootParentTest.kt`

**Interfaces:**
- Produces：`resolveReplyRootParent(rpid: Long, rootRpid: Long?): Pair<Long, Long>`（`internal`，与 `resolveDynamicCommentTargetFromEntry` 同文件）
- Consumes（Task 11 会用到）：无

**语义**：回复一级评论时 `rootRpid=null`，此时 `root=parent=该评论 rpid`；回复楼中楼时 `rootRpid` 是其所属根评论的 rpid，此时 `root=rootRpid`，`parent=该楼中楼 rpid`。

- [ ] **Step 1: 写失败的测试**

Create `core/network/src/test/java/com/biliseek/core/network/ReplyRootParentTest.kt`:

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.internal.resolveReplyRootParent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回复目标 root/parent 解析（B 站 x/v2/reply/add 语义）。 */
class ReplyRootParentTest {

    @Test
    fun `回复一级评论时 root 等于 parent`() {
        val (root, parent) = resolveReplyRootParent(rpid = 42, rootRpid = null)
        assertEquals(42L, root)
        assertEquals(42L, parent)
    }

    @Test
    fun `回复楼中楼时 root 是根评论 parent 是该楼中楼`() {
        val (root, parent) = resolveReplyRootParent(rpid = 101, rootRpid = 42)
        assertEquals(42L, root)
        assertEquals(101L, parent)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.ReplyRootParentTest"`
Expected: FAIL（`resolveReplyRootParent` 不存在，编译失败）

- [ ] **Step 3: 在 `DynamicCommentPolicy.kt` 末尾新增函数**

在文件末尾（`resolveDynamicCommentTargetFromEntry` 函数结束的 `}` 之后）新增：

```kotlin

/**
 * 回复目标的 root/parent（B 站 `x/v2/reply/add` 语义）。
 * 回复一级评论：`rootRpid` 为 null，此时 root=parent=该评论 rpid。
 * 回复楼中楼：`rootRpid` 是其所属根评论 rpid，root=rootRpid，parent=该楼中楼 rpid。
 */
fun resolveReplyRootParent(rpid: Long, rootRpid: Long?): Pair<Long, Long> =
    (rootRpid ?: rpid) to rpid
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.ReplyRootParentTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/internal/DynamicCommentPolicy.kt core/network/src/test/java/com/biliseek/core/network/ReplyRootParentTest.kt
git commit -m "feat: 新增 resolveReplyRootParent 纯函数解析回复目标"
```

---

## Part 4：评论 ViewModel 编排

### Task 10: 扩展 `CommentDialogState`，新增列表加载/分页/排序

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`

**Interfaces:**
- Consumes：`BiliRepository.comments(oid, type, page, sort)`（Task 5）、`RemoteListState<T>`（已存在）
- Produces：
  - `data class CommentReplyTarget(val root: Long, val parent: Long, val uname: String)`
  - `CommentDialogState` 新增字段：`sort: Int = 1`、`comments: RemoteListState<ReplyItem> = RemoteListState.empty()`、`expandedSubReplies: Map<Long, RemoteListState<ReplyItem>> = emptyMap()`、`replyTarget: CommentReplyTarget? = null`；删除旧的 `recentReplies` 字段
  - `AppViewModel.loadComments(reset: Boolean = true)`、`loadMoreComments()`、`setCommentSort(sort: Int)`
  - `openCommentDialog(entry)` 不再要求登录（只读列表任何人可看）；`submitComment()` 改为登录门槛 + 支持 `replyTarget`

**注意**：这一步先只做"列表读取"相关的状态与方法；点赞/回复目标/楼中楼放在 Task 11，避免单个任务改动过大。`submitComment`/`openCommentDialog`/`updateCommentContent`/`dismissCommentDialog` 这四个已有方法在本任务里改，Task 11 不再动它们。

- [ ] **Step 1: 替换 `CommentDialogState` 定义（第 221-230 行）**

把：
```kotlin
/** Ch4 动态评论对话框状态。 */
data class CommentDialogState(
    val idStr: String,
    val oid: Long,
    val type: Int,
    val content: String = "",
    val isPosting: Boolean = false,
    /** 评论列表（提交后刷新该列表）。当前实现只展示占位，避免接口泛滥。 */
    val recentReplies: List<String> = emptyList(),
)
```
改成：
```kotlin
/** 评论回复目标：null=发表新评论；非 null=回复某条一级评论。 */
data class CommentReplyTarget(
    val root: Long,
    val parent: Long,
    val uname: String,
)

/** Ch4 动态评论对话框状态：列表 + 分页 + 楼中楼 + 回复目标。 */
data class CommentDialogState(
    val idStr: String,
    val oid: Long,
    val type: Int,
    val content: String = "",
    val isPosting: Boolean = false,
    /** 1=热度 0=时间，对应 [BiliRepository.comments] 的 sort 参数。 */
    val sort: Int = 1,
    val comments: RemoteListState<ReplyItem> = RemoteListState.empty(),
    /** key=一级评论 rpid，value=其楼中楼的分页状态。未展开的评论不在这个 map 里。 */
    val expandedSubReplies: Map<Long, RemoteListState<ReplyItem>> = emptyMap(),
    val replyTarget: CommentReplyTarget? = null,
)
```

`com.biliseek.app.data.RemoteListState` 已经在文件顶部 import 过（第 54 行，其它列表状态在用），只需要新增一行：
```kotlin
import com.biliseek.core.network.dto.ReplyItem
```
（加在 import 块里 `com.biliseek.core.network.dto.ReplyContent`/`ReplyMember` 之类的旁边，如果没有就按字母顺序插进现有 `com.biliseek.core.network.dto.*` import 组里）

- [ ] **Step 2: 改 `openCommentDialog`（第 1712-1728 行），去掉登录门槛，打开即加载**

把：
```kotlin
    /** 打开评论对话框：根据 dynamic entry 计算 oid/type。 */
    fun openCommentDialog(entry: DynamicEntry) {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再评论")
            return
        }
        val (oid, type) = com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry(
            idStr = entry.idStr,
            rawType = entry.rawType,
            drawId = entry.drawId,
        )
        _commentDialog.value = CommentDialogState(
            idStr = entry.idStr,
            oid = oid,
            type = type,
        )
    }
```
改成：
```kotlin
    /** 打开评论对话框：根据 dynamic entry 计算 oid/type，并立即加载第一页。未登录也可读。 */
    fun openCommentDialog(entry: DynamicEntry) {
        val (oid, type) = com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry(
            idStr = entry.idStr,
            rawType = entry.rawType,
            drawId = entry.drawId,
        )
        _commentDialog.value = CommentDialogState(
            idStr = entry.idStr,
            oid = oid,
            type = type,
        )
        loadComments(reset = true)
    }
```

- [ ] **Step 3: 在 `dismissCommentDialog` 后面（第 1735 行后）新增 `loadComments`/`loadMoreComments`/`setCommentSort`**

```kotlin

    /** 加载评论列表。reset=true 重新拉第 1 页；false 翻页追加。 */
    fun loadComments(reset: Boolean = true) {
        val cur = _commentDialog.value ?: return
        val base = if (reset) RemoteListState.empty<ReplyItem>() else cur.comments
        if (base.isLoading || base.isLoadingMore) return
        val nextPage = if (reset) 1 else base.page + 1
        val loadingMore = !reset
        _commentDialog.value = cur.copy(
            comments = base.copy(isLoading = !loadingMore, isLoadingMore = loadingMore, errorMessage = null),
        )
        viewModelScope.launch {
            network.repository.comments(cur.oid, cur.type, nextPage, cur.sort)
                .onSuccess { page ->
                    val dialog = _commentDialog.value ?: return@onSuccess
                    val mergedItems = if (reset) page.items else dialog.comments.items + page.items
                    _commentDialog.value = dialog.copy(
                        comments = dialog.comments.copy(
                            items = mergedItems,
                            page = nextPage,
                            hasMore = mergedItems.size < page.count,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                    )
                }
                .onFailure { err ->
                    val dialog = _commentDialog.value ?: return@onFailure
                    _commentDialog.value = dialog.copy(
                        comments = dialog.comments.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = err.message ?: "评论加载失败",
                        ),
                    )
                }
        }
    }

    fun loadMoreComments() {
        val cur = _commentDialog.value ?: return
        if (!cur.comments.hasMore) return
        loadComments(reset = false)
    }

    /** 切换热度/时间排序：清空列表重新拉第 1 页。 */
    fun setCommentSort(sort: Int) {
        val cur = _commentDialog.value ?: return
        if (cur.sort == sort) return
        _commentDialog.value = cur.copy(sort = sort, comments = RemoteListState.empty<ReplyItem>())
        loadComments(reset = true)
    }
```

- [ ] **Step 4: 改 `submitComment`（第 1737-1761 行），支持回复目标，成功后刷新列表而非关闭 Sheet**

把整个函数：
```kotlin
    /** 提交评论：乐观更新评论数 +1；失败回滚。 */
    fun submitComment() = viewModelScope.launch {
        val cur = _commentDialog.value ?: return@launch
        if (cur.isPosting || cur.content.isBlank()) return@launch
        _commentDialog.value = cur.copy(isPosting = true)
        patchEntry(cur.idStr) { e ->
            val stat = e.stat ?: return@patchEntry e
            e.copy(stat = stat.copy(comment = stat.comment.copy(count = stat.comment.count + 1)))
        }
        network.repository.addDynamicReply(cur.oid, cur.type, cur.content)
            .onSuccess {
                _commentDialog.value = null
                emitMessage("评论已发送")
            }
            .onFailure { err ->
                _commentDialog.value = cur.copy(isPosting = false)
                patchEntry(cur.idStr) { e ->
                    val stat = e.stat ?: return@patchEntry e
                    e.copy(stat = stat.copy(comment = stat.comment.copy(
                        count = (stat.comment.count - 1).coerceAtLeast(0),
                    )))
                }
                emitMessage("评论失败：${err.message ?: "未知错误"}")
            }
    }
```
改成：
```kotlin
    /** 提交评论：发新评论或回复 [CommentDialogState.replyTarget]；成功后清空输入框与回复目标并刷新列表首页。 */
    fun submitComment() = viewModelScope.launch {
        val cur = _commentDialog.value ?: return@launch
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再评论")
            return@launch
        }
        if (cur.isPosting || cur.content.isBlank()) return@launch
        _commentDialog.value = cur.copy(isPosting = true)
        val target = cur.replyTarget
        patchEntry(cur.idStr) { e ->
            val stat = e.stat ?: return@patchEntry e
            e.copy(stat = stat.copy(comment = stat.comment.copy(count = stat.comment.count + 1)))
        }
        network.repository.addDynamicReply(
            oid = cur.oid,
            type = cur.type,
            message = cur.content,
            root = target?.root ?: 0L,
            parent = target?.parent ?: 0L,
        )
            .onSuccess {
                val dialog = _commentDialog.value
                if (dialog != null) {
                    _commentDialog.value = dialog.copy(content = "", isPosting = false, replyTarget = null)
                }
                emitMessage("评论已发送")
                loadComments(reset = true)
            }
            .onFailure { err ->
                _commentDialog.value = _commentDialog.value?.copy(isPosting = false)
                patchEntry(cur.idStr) { e ->
                    val stat = e.stat ?: return@patchEntry e
                    e.copy(stat = stat.copy(comment = stat.comment.copy(
                        count = (stat.comment.count - 1).coerceAtLeast(0),
                    )))
                }
                emitMessage("评论失败：${err.message ?: "未知错误"}")
            }
    }
```

- [ ] **Step 5: 编译确认（`toggleCommentLike`/`startReplyTo`/`expandSubReplies` 等 Task 11 才加，本步不引用它们）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/biliseek/app/AppViewModel.kt
git commit -m "feat: 评论对话框状态升级为列表+分页+排序"
```

### Task 11: 新增评论点赞 / 回复目标 / 楼中楼展开

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`

**Interfaces:**
- Consumes：`BiliRepository.replyAction(oid, type, rpid, like)`（Task 6）、`BiliRepository.subReplies(oid, type, root, page)`（Task 7）、`resolveReplyRootParent(rpid, rootRpid)`（Task 9）
- Produces：
  - `AppViewModel.toggleCommentLike(reply: ReplyItem, rootRpid: Long? = null)`
  - `AppViewModel.startReplyTo(reply: ReplyItem, rootRpid: Long? = null)`
  - `AppViewModel.clearReplyTarget()`
  - `AppViewModel.expandSubReplies(reply: ReplyItem)`
  - `AppViewModel.loadMoreSubReplies(rootRpid: Long)`

- [ ] **Step 1: 在 Task 10 新增的 `setCommentSort` 后面追加以下方法**

```kotlin

    /** 设置回复目标：`rootRpid=null` 表示回复一级评论；非 null 表示回复该一级评论下的楼中楼。 */
    fun startReplyTo(reply: ReplyItem, rootRpid: Long? = null) {
        val cur = _commentDialog.value ?: return
        val (root, parent) = com.biliseek.core.network.internal.resolveReplyRootParent(reply.rpid, rootRpid)
        _commentDialog.value = cur.copy(
            replyTarget = CommentReplyTarget(root = root, parent = parent, uname = reply.member.uname),
        )
    }

    fun clearReplyTarget() {
        val cur = _commentDialog.value ?: return
        _commentDialog.value = cur.copy(replyTarget = null)
    }

    /** 评论点赞 / 取消：500ms 防抖，乐观更新，失败回滚。`rootRpid` 非 null 表示这是楼中楼里的评论。 */
    fun toggleCommentLike(reply: ReplyItem, rootRpid: Long? = null) {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再点赞")
            return
        }
        val cur = _commentDialog.value ?: return
        val like = reply.action != 1
        val gateKey = "reply:${reply.rpid}"
        val now = System.currentTimeMillis()
        val last = likeRequestGate[gateKey] ?: 0L
        if (now - last < 500) return
        likeRequestGate[gateKey] = now
        patchCommentLike(reply.rpid, rootRpid, like)
        viewModelScope.launch {
            network.repository.replyAction(cur.oid, cur.type, reply.rpid, like)
                .onFailure { err ->
                    patchCommentLike(reply.rpid, rootRpid, !like)
                    emitMessage("点赞失败：${err.message ?: "未知错误"}")
                }
        }
    }

    /** 乐观更新某条评论（或楼中楼）的点赞态，就地替换列表里的对应条目。 */
    private fun patchCommentLike(rpid: Long, rootRpid: Long?, like: Boolean) {
        val cur = _commentDialog.value ?: return
        fun patchList(items: List<ReplyItem>) = items.map { item ->
            if (item.rpid == rpid) {
                item.copy(
                    like = (item.like + if (like) 1 else -1).coerceAtLeast(0),
                    action = if (like) 1 else 0,
                )
            } else item
        }
        if (rootRpid == null) {
            _commentDialog.value = cur.copy(comments = cur.comments.copy(items = patchList(cur.comments.items)))
        } else {
            val subState = cur.expandedSubReplies[rootRpid] ?: return
            _commentDialog.value = cur.copy(
                expandedSubReplies = cur.expandedSubReplies + (rootRpid to subState.copy(items = patchList(subState.items))),
            )
        }
    }

    /** 展开楼中楼：第一次点击才发请求，之后复用已加载的状态。 */
    fun expandSubReplies(reply: ReplyItem) {
        val cur = _commentDialog.value ?: return
        if (cur.expandedSubReplies.containsKey(reply.rpid)) return
        _commentDialog.value = cur.copy(
            expandedSubReplies = cur.expandedSubReplies + (reply.rpid to RemoteListState<ReplyItem>().copy(isLoading = true)),
        )
        viewModelScope.launch {
            network.repository.subReplies(cur.oid, cur.type, reply.rpid, page = 1)
                .onSuccess { page ->
                    val dialog = _commentDialog.value ?: return@onSuccess
                    _commentDialog.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (reply.rpid to RemoteListState(
                            items = page.items,
                            page = 1,
                            hasMore = page.items.size < page.count,
                            isLoading = false,
                        )),
                    )
                }
                .onFailure { err ->
                    val dialog = _commentDialog.value ?: return@onFailure
                    val prev = dialog.expandedSubReplies[reply.rpid] ?: RemoteListState<ReplyItem>()
                    _commentDialog.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (reply.rpid to prev.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "楼中楼加载失败",
                        )),
                    )
                }
        }
    }

    fun loadMoreSubReplies(rootRpid: Long) {
        val cur = _commentDialog.value ?: return
        val state = cur.expandedSubReplies[rootRpid] ?: return
        if (!state.hasMore || state.isLoading || state.isLoadingMore) return
        val nextPage = state.page + 1
        _commentDialog.value = cur.copy(
            expandedSubReplies = cur.expandedSubReplies + (rootRpid to state.copy(isLoadingMore = true)),
        )
        viewModelScope.launch {
            network.repository.subReplies(cur.oid, cur.type, rootRpid, page = nextPage)
                .onSuccess { page ->
                    val dialog = _commentDialog.value ?: return@onSuccess
                    val prev = dialog.expandedSubReplies[rootRpid] ?: return@onSuccess
                    val merged = prev.items + page.items
                    _commentDialog.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (rootRpid to prev.copy(
                            items = merged,
                            page = nextPage,
                            hasMore = merged.size < page.count,
                            isLoadingMore = false,
                        )),
                    )
                }
                .onFailure { err ->
                    val dialog = _commentDialog.value ?: return@onFailure
                    val prev = dialog.expandedSubReplies[rootRpid] ?: return@onFailure
                    _commentDialog.value = dialog.copy(
                        expandedSubReplies = dialog.expandedSubReplies + (rootRpid to prev.copy(
                            isLoadingMore = false,
                            errorMessage = err.message ?: "楼中楼加载失败",
                        )),
                    )
                }
        }
    }
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/AppViewModel.kt
git commit -m "feat: 评论点赞/回复目标/楼中楼展开"
```

---

## Part 5：评论 UI

### Task 12: 重写 `DynamicCommentDialog.kt` 为列表 Sheet

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`（整体重写）

**Interfaces:**
- Consumes：`RemoteListState<ReplyItem>`、`ReplyItem`（`com.biliseek.core.network.dto.ReplyItem`）、`com.biliseek.app.ui.formatRelativeTime(ts: Long): String`（已存在，`RelativeTime.kt`）
- Produces：`DynamicCommentDialog(...)` 新签名（见下），私有 `CommentItemRow(...)`

**范围说明（明确的简化，供审阅）**：楼中楼展开后是**只读预览**——不支持对楼中楼本身再点赞/回复（只有一级评论支持点赞/回复/展开）。这是为了避免"评论卡片"组件递归嵌套自身，把 UI 复杂度控制在合理范围；如果后续需要楼中楼也能点赞/回复，可以在这个组件基础上加，不影响本次改动的接口形状。

- [ ] **Step 1: 用以下内容整体替换 `DynamicCommentDialog.kt`**

```kotlin
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.formatRelativeTime
import com.biliseek.core.network.dto.ReplyItem

/**
 * 动态评论列表 Sheet（Ch4 §4.1.4 评论）。列表 + 楼中楼预览 + 排序 + 输入框。
 * 楼中楼展开后只读（不支持对楼中楼再点赞/回复），一级评论支持点赞/回复/展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCommentDialog(
    comments: RemoteListState<ReplyItem>,
    sort: Int,
    replyTargetUname: String?,
    content: String,
    isPosting: Boolean,
    expandedSubReplies: Map<Long, RemoteListState<ReplyItem>>,
    onSortChange: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (ReplyItem, Long?) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: (ReplyItem) -> Unit,
    onLoadMoreSubReplies: (Long) -> Unit,
    onContentChange: (String) -> Unit,
    onClearReplyTarget: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("💬 评论", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "热度",
                        Modifier.clickable { onSortChange(1) },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sort == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "时间",
                        Modifier.clickable { onSortChange(0) },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sort == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "关闭",
                        Modifier.clickable(onClick = onDismiss),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            HorizontalDivider()

            when {
                comments.isLoading && comments.items.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    Alignment.Center,
                ) { CircularProgressIndicator() }

                comments.items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        comments.errorMessage ?: "还没有评论，来说两句吧",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(comments.items, key = { it.rpid }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            subReplies = expandedSubReplies[reply.rpid],
                            onLike = { onLike(reply, null) },
                            onReply = { onReply(reply, null) },
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                        )
                        HorizontalDivider()
                    }
                    if (comments.hasMore || comments.isLoadingMore) {
                        item(key = "comment_footer") {
                            if (comments.hasMore && !comments.isLoadingMore) {
                                LaunchedEffect(comments.items.size) { onLoadMore() }
                            }
                            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                if (comments.isLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("加载中…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            if (replyTargetUname != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回复 @$replyTargetUname", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "取消",
                        Modifier.clickable(onClick = onClearReplyTarget),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text(if (replyTargetUname != null) "回复…" else "写评论…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                minLines = 2,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSubmit, enabled = !isPosting && content.isNotBlank()) {
                    Text(if (isPosting) "发送中…" else "发送")
                }
            }
        }
    }
}

/** 一条一级评论：头像/昵称/时间/内容 + 回复/点赞 + 楼中楼预览与展开。 */
@Composable
private fun CommentItemRow(
    reply: ReplyItem,
    subReplies: RemoteListState<ReplyItem>?,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onExpandSubReplies: () -> Unit,
    onLoadMoreSubReplies: () -> Unit,
) {
    val liked = reply.action == 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = reply.member.avatar,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(reply.member.uname, style = MaterialTheme.typography.labelLarge)
                Text(formatRelativeTime(reply.ctime * 1000), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(reply.content.message, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "回复",
                Modifier.clickable(onClick = onReply),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${reply.like} 赞",
                Modifier.clickable(onClick = onLike),
                style = MaterialTheme.typography.labelSmall,
                color = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reply.rcount > 0) {
            val preview = subReplies?.items ?: reply.replies.orEmpty()
            if (preview.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp),
                ) {
                    preview.forEach { sub ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${sub.member.uname}: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(sub.content.message, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (subReplies?.hasMore == true) {
                        Text(
                            if (subReplies.isLoadingMore) "加载中…" else "加载更多回复",
                            Modifier.clickable(enabled = !subReplies.isLoadingMore, onClick = onLoadMoreSubReplies),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(
                "查看全部回复(${reply.rcount})",
                Modifier
                    .padding(top = 4.dp)
                    .clickable(enabled = subReplies?.isLoading != true, onClick = onExpandSubReplies),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
```

- [ ] **Step 2: 编译确认（此时 `MainActivity.kt` 还没改调用点，先只确认组件本身编译通过）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译失败——`MainActivity.kt` 里旧的 `DynamicCommentDialog(content=..., isPosting=..., onContentChange=..., onSubmit=..., onDismiss=...)` 调用点参数不匹配新签名。这是预期的，Task 13 会修。记录这一步的报错信息用于核对 Task 13 改的位置是否正确即可，不需要现在修复。

- [ ] **Step 3: Commit（先不 add MainActivity.kt，只提交组件本身；如果编译失败导致 CI 卡住，也可以把 Step 3 合并到 Task 13 一起提交——推荐这样做，见下）**

本任务不单独 commit，直接进入 Task 13，两个任务的改动一起编译通过后再 commit 一次（因为 `DynamicCommentDialog` 和它在 `MainActivity` 里的调用点是同一个"接口契约"，拆开提交会让中间状态编译不过，不符合"每个 commit 都能编译"的习惯）。

### Task 13: 重接 `MainActivity.kt` 的评论对话框调用点

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:497-505`

**Interfaces:**
- Consumes：Task 12 的 `DynamicCommentDialog(...)` 新签名；Task 10/11 的 `AppViewModel.loadMoreComments/setCommentSort/toggleCommentLike/startReplyTo/clearReplyTarget/expandSubReplies/loadMoreSubReplies/updateCommentContent/submitComment/dismissCommentDialog`

- [ ] **Step 1: 改 `MainActivity.kt` 里的评论对话框渲染块**

把（第 497-505 行，Task 3 Step 3 里已经把上面那行注释挪到了这里）：
```kotlin
                commentDialog?.let { state ->
                    com.biliseek.app.ui.components.DynamicCommentDialog(
                        content = state.content,
                        isPosting = state.isPosting,
                        onContentChange = viewModel::updateCommentContent,
                        onSubmit = viewModel::submitComment,
                        onDismiss = viewModel::dismissCommentDialog,
                    )
                }
```
改成：
```kotlin
                // Ch4：动态评论 / 图片预览
                commentDialog?.let { state ->
                    com.biliseek.app.ui.components.DynamicCommentDialog(
                        comments = state.comments,
                        sort = state.sort,
                        replyTargetUname = state.replyTarget?.uname,
                        content = state.content,
                        isPosting = state.isPosting,
                        expandedSubReplies = state.expandedSubReplies,
                        onSortChange = viewModel::setCommentSort,
                        onLoadMore = viewModel::loadMoreComments,
                        onLike = { reply, root -> viewModel.toggleCommentLike(reply, root) },
                        onReply = { reply, root -> viewModel.startReplyTo(reply, root) },
                        onExpandSubReplies = viewModel::expandSubReplies,
                        onLoadMoreSubReplies = viewModel::loadMoreSubReplies,
                        onContentChange = viewModel::updateCommentContent,
                        onClearReplyTarget = viewModel::clearReplyTarget,
                        onSubmit = viewModel::submitComment,
                        onDismiss = viewModel::dismissCommentDialog,
                    )
                }
```

（如果 Task 3 Step 3 已经把 `// Ch4：动态转发 / 评论 / 图片预览` 这行注释挪到这里，这一步改成 `// Ch4：动态评论 / 图片预览` 即可，不要重复两行注释）

- [ ] **Step 2: 编译确认整条链路打通**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 跑全量单测确认无回归**

Run: `./gradlew :core:network:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit（把 Task 12 + Task 13 一起提交）**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat: 评论对话框升级为列表 Sheet（排序/楼中楼/回复/点赞）"
```

### Task 14: `DynamicCommentDialog` 的 Compose UI 测试

**Files:**
- Create: `app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Consumes：`DynamicCommentDialog(...)`（Task 12）、`RemoteListState`、`ReplyItem`/`ReplyContent`/`ReplyMember`（`com.biliseek.core.network.dto`）、`BiliSeekTheme`（`com.biliseek.designsystem.theme`，与 `DialogsUiTest.kt` 一致）

- [ ] **Step 1: 写测试**

Create `app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt`:

```kotlin
package com.biliseek.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.components.DynamicCommentDialog
import com.biliseek.core.network.dto.ReplyContent
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyMember
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 评论列表 Sheet 的交互测试：排序切换 / 点赞 / 回复 / 加载更多回调。
 */
class DynamicCommentDialogUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val replies = listOf(
        ReplyItem(
            rpid = 1, mid = 10, ctime = 0, like = 5,
            content = ReplyContent(message = "第一条评论"),
            member = ReplyMember(uname = "用户A"),
        ),
        ReplyItem(
            rpid = 2, mid = 20, ctime = 0, like = 2,
            content = ReplyContent(message = "第二条评论"),
            member = ReplyMember(uname = "用户B"),
        ),
    )

    @Test
    fun 评论列表_点赞回复排序与加载更多回调() {
        var likedReply: ReplyItem? = null
        var likedRoot: Long? = null
        var replyTarget: ReplyItem? = null
        var sortRequested: Int? = null
        var loadMoreCalled = false

        compose.setContent {
            BiliSeekTheme {
                DynamicCommentDialog(
                    comments = RemoteListState(items = replies, hasMore = true),
                    sort = 1,
                    replyTargetUname = null,
                    content = "",
                    isPosting = false,
                    expandedSubReplies = emptyMap(),
                    onSortChange = { sortRequested = it },
                    onLoadMore = { loadMoreCalled = true },
                    onLike = { reply, root -> likedReply = reply; likedRoot = root },
                    onReply = { reply, _ -> replyTarget = reply },
                    onExpandSubReplies = {},
                    onLoadMoreSubReplies = {},
                    onContentChange = {},
                    onClearReplyTarget = {},
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("第一条评论").assertIsDisplayed()
        compose.onNodeWithText("用户B").assertIsDisplayed()

        compose.onNodeWithText("5 赞").performClick()
        check(likedReply?.rpid == 1L) { "点赞未回调正确的评论" }
        check(likedRoot == null) { "一级评论点赞的 root 应为 null" }

        compose.onAllNodesWithText("回复")[0].performClick()
        check(replyTarget?.rpid == 1L) { "回复未回调正确的评论" }

        compose.onNodeWithText("时间").performClick()
        check(sortRequested == 0) { "排序切换未回调" }

        compose.waitForIdle()
        check(loadMoreCalled) { "列表末尾 sentinel 未触发加载更多" }
    }

    @Test
    fun 回复目标提示与取消() {
        var cleared = false
        compose.setContent {
            BiliSeekTheme {
                DynamicCommentDialog(
                    comments = RemoteListState(items = replies),
                    sort = 1,
                    replyTargetUname = "用户A",
                    content = "",
                    isPosting = false,
                    expandedSubReplies = emptyMap(),
                    onSortChange = {},
                    onLoadMore = {},
                    onLike = { _, _ -> },
                    onReply = { _, _ -> },
                    onExpandSubReplies = {},
                    onLoadMoreSubReplies = {},
                    onContentChange = {},
                    onClearReplyTarget = { cleared = true },
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("回复 @用户A").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        check(cleared) { "取消回复目标未回调" }
    }
}
```

- [ ] **Step 2: 跑测试（需要连接设备/模拟器，AGENTS.md 允许——这是本地 UI 断言，不发真实网络请求）**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.biliseek.app.DynamicCommentDialogUiTest"`
Expected: 2 个测试全部 PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "test: 评论列表 Sheet 的 Compose UI 测试"
```

---

## Part 6：收尾

### Task 15: 全量回归 + 人工真机验证清单

**Files:** 无代码改动，只验证。

- [ ] **Step 1: 全量单测 + 编译**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL，`core:network` 与 `app` 两个模块全部测试通过

- [ ] **Step 2: 连接设备跑 Compose UI 测试全集**

Run: `./gradlew connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL（含 Task 14 新增的测试与既有的 `DialogsUiTest` 等）

- [ ] **Step 3: 交给人工在真机上验证（CLAUDE.md 安全规则：自动化代码不能碰真实服务器，以下必须人工操作）**

列出验证清单（不是自动化步骤，供人工核对）：
- 点赞一条动态 → 数字 +1 且按钮变主题色；再点一次 → 数字 -1 且恢复未激活色（验证 Task 1 的取反修复）。
- 点击转发 → 系统提示"链接已复制"，粘贴到其它 App 验证是 `https://t.bilibili.com/<idStr>` 格式且能打开对应动态（验证 Task 2）。
- 点击评论 → 列表能正常加载（未登录也能看），下拉到底能翻页；切换"热度/时间"列表刷新；某条评论有楼中楼时点"查看全部回复"能展开，能"加载更多回复"；登录后点评论的"赞"能生效且再点取消；点"回复"后输入框上方出现"回复 @xxx"，发送后能在列表里看到新评论/新回复（验证 Task 4-14 整条链路，尤其确认 `oid`/`type`/`root`/`parent` 与真实 B 站接口语义一致——这是本计划里"以当日抓包为准"的部分，无法靠自动化测试保证）。

- [ ] **Step 4: 记录验证结果**

如果人工验证发现某个字段/参数与真实接口不符（例如 `subReplies` 的 `ps`/`pn` 参数名，或 `addDynamicReply` 的 `root`/`parent` 实际字段名），在 `docs/验收记录/`（仓库现有目录约定，见 CLAUDE.md 文档地图）或对应 PR 描述里记录下来，作为后续修正的依据——不在本计划范围内自动修正。
