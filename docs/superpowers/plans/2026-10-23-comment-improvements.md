# 评论区完善 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 评论弹层（动态评论、播放页评论共用）补齐八项：置顶评论、输入草稿记忆、翻页与排序修正、楼中楼点赞/回复、发出后直接显示、UP 标识、删除自己的评论、带图评论。

**Architecture:** 网络层补全评论 DTO（游标 / 置顶 / 作者 / 图片），把主评论列表从 `pn/sort` 改成 `mode + pagination_str` 游标翻页（纯函数 `CommentPagePolicy`）；`:app` 侧把列表的合并、点赞、插入、删除写成作用在 `CommentThreadState` 上的纯函数（`CommentListPolicy` / `CommentDraftPolicy`），`CommentThreadController` 只负责调仓库和改状态；界面 `DynamicCommentDialog` 改为直接吃 `CommentThreadState`，由新的 `CommentThreadSheet` 把控制器接到弹层上，替掉 `MainActivity` 里两段一模一样的挂载代码。

**Tech Stack:** Kotlin、kotlinx.serialization、Retrofit、Jetpack Compose（Material3）、JUnit4、Compose UI Test。

## Global Constraints

- **自动化代码绝不请求 B 站服务器**：所有测试只用本地 JSON / 构造数据；真机效果由用户验证。
- 不在评论请求外加任何重试循环；新读接口沿用 `comments()` 现有的限流桶 `FEED` + `refreshingOn101` + 错误分流；新写接口走 `writeRequest`（自动带 csrf、`-111/-101` 只重试一次、`-352` 进冷却）。
- 评论内容（含草稿）**只放内存**，不写 Room / DataStore。
- 新增的 `public` 类型只有 DTO（`ReplyCursor` / `ReplyPaginationReply` / `ReplyTop` / `ReplyUpper` / `ReplyPicture`）和 `BiliRepository.CommentPage` 的新字段、`BiliRepository.deleteReply`；纯函数放 `internal`（`:core:network`）或 `:app` 内。
- 叫法统一：「回复」「点赞」「删除」「置顶」「UP」；提示走 `emitMessage`，不直接 `Toast.makeText`。
- 底部弹层继续用 `CompactDragHandle`。
- 不装机：每个任务只做本地验证（单测 + 编译），用户开口才装到真机。
- 所有提交在分支 `feat/comment-improvements` 上，提交信息结尾带：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `core/network/src/main/java/com/biliseek/core/network/dto/ReplyDto.kt` | 改 | 补 `cursor` / `top_replies` / `top` / `upper` / `content.pictures` |
| `core/network/src/main/java/com/biliseek/core/network/internal/CommentPagePolicy.kt` | 新建 | 主评论请求参数、置顶合并、下一页游标、响应 → `CommentPage` |
| `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt` | 改 | `CommentPage` 加字段；`comments()` 改游标；新增 `deleteReply()` |
| `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt` | 改 | 新增 `x/v2/reply/del` |
| `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt` | 新建 | 翻页合并去重、按 rpid 全局修改、插入刚发的评论、移除、能否删除 |
| `app/src/main/java/com/biliseek/app/comment/CommentDraftPolicy.kt` | 新建 | 关弹层时留不留草稿 |
| `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt` | 改 | 游标翻页、置顶 / 作者状态、草稿、本地插入、删除 |
| `app/src/main/java/com/biliseek/app/dynamic/DynamicFeedController.kt` | 改 | 删除评论后动态卡片评论数 -1 |
| `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt` | 改 | 吃 `CommentThreadState`；楼中楼操作、置顶 / UP 标签、删除确认、评论图片与预览 |
| `app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt` | 新建 | 把 `CommentThreadController` 接到 `DynamicCommentDialog` |
| `app/src/main/java/com/biliseek/app/MainActivity.kt` | 改 | 两处挂载换成 `CommentThreadSheet` |
| `core/network/src/test/.../ReplyDtoTest.kt`、`CommentPagePolicyTest.kt` | 改 / 新建 | 网络层单测 |
| `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`、`CommentDraftPolicyTest.kt` | 新建 | `:app` 纯函数单测 |
| `app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt` | 改 | 弹层 UI 测试（需设备，至少保证编译通过） |
| `docs/05-功能优化篇.md`、`docs/BiliSeek-手动测试指南.md`、`docs/03-架构设计篇.md` | 改 | 记录与手动测试项 |

## 常用命令

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.ReplyDtoTest"
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CommentPagePolicyTest"
./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.*"
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew test
```

---

### Task 0: 建分支

- [ ] **Step 1:** 确认工作区干净并建分支

```bash
git status --short          # 期望：无输出
git switch -c feat/comment-improvements
```

---

### Task 1: 评论 DTO 补字段（游标 / 置顶 / 作者 / 图片）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/ReplyDto.kt`
- Test: `core/network/src/test/java/com/biliseek/core/network/ReplyDtoTest.kt`

**Interfaces:**
- Produces:
  - `ReplyData.cursor: ReplyCursor`、`ReplyData.topReplies: List<ReplyItem>?`、`ReplyData.top: ReplyTop?`、`ReplyData.upper: ReplyUpper?`
  - `ReplyCursor(isEnd: Boolean = false, allCount: Int = 0, paginationReply: ReplyPaginationReply? = null)`
  - `ReplyPaginationReply(nextOffset: String = "")`
  - `ReplyTop(upper: ReplyItem? = null, admin: ReplyItem? = null, vote: ReplyItem? = null)`
  - `ReplyUpper(mid: Long = 0, top: ReplyItem? = null)`
  - `ReplyContent.pictures: List<ReplyPicture>?`、`ReplyPicture(imgSrc: String = "")`

- [ ] **Step 1: 写失败的测试**（追加到 `ReplyDtoTest` 类里）

```kotlin
    @Test
    fun `解析游标分页、置顶、UP 主与评论图片`() {
        val data = json.decodeFromString<ReplyData>(
            """
            {
              "cursor": {
                "is_end": false,
                "all_count": 321,
                "pagination_reply": { "next_offset": "{\"type\":1,\"data\":{\"pn\":2}}" }
              },
              "replies": [{
                "rpid": 2, "mid": 20,
                "content": {
                  "message": "普通",
                  "pictures": [{ "img_src": "https://i0.hdslb.com/a.jpg", "img_width": 100, "img_height": 80 }]
                },
                "member": { "uname": "B" }
              }],
              "top_replies": [{ "rpid": 1, "mid": 10, "content": { "message": "置顶" }, "member": { "uname": "UP" } }],
              "top": { "upper": { "rpid": 1, "content": { "message": "置顶" } }, "admin": null, "vote": null },
              "upper": { "mid": 10, "top": null }
            }
            """.trimIndent(),
        )
        assertEquals(false, data.cursor.isEnd)
        assertEquals(321, data.cursor.allCount)
        assertEquals("{\"type\":1,\"data\":{\"pn\":2}}", data.cursor.paginationReply?.nextOffset)
        assertEquals(1L, data.topReplies?.single()?.rpid)
        assertEquals(1L, data.top?.upper?.rpid)
        assertEquals(10L, data.upper?.mid)
        assertNull(data.upper?.top)
        assertEquals("https://i0.hdslb.com/a.jpg", data.replies?.single()?.content?.pictures?.single()?.imgSrc)
    }

    @Test
    fun `没有新字段的旧响应照常解析`() {
        val data = json.decodeFromString<ReplyData>(
            """{ "page": { "count": 3 }, "replies": [{ "rpid": 1, "content": { "message": "x", "pictures": null } }] }""",
        )
        assertEquals(3, data.page.count)
        assertEquals(false, data.cursor.isEnd)
        assertNull(data.cursor.paginationReply)
        assertNull(data.topReplies)
        assertNull(data.top)
        assertNull(data.upper)
        assertNull(data.replies?.single()?.content?.pictures)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.ReplyDtoTest"`
Expected: 编译失败，`Unresolved reference: cursor` / `pictures` 等。

- [ ] **Step 3: 实现**

`ReplyDto.kt` 里把 `ReplyData` 换成下面这样，并在它后面加四个类：

```kotlin
/** `x/v2/reply/(wbi/)main` —— 评论列表。 */
@Serializable
data class ReplyData(
    /** 旧版 `x/v2/reply` 与楼中楼 `x/v2/reply/reply` 用页码分页。 */
    val page: ReplyPage = ReplyPage(),
    /** `wbi/main` / `main` 用游标分页：`pagination_reply.next_offset` 是下一页的 offset。 */
    val cursor: ReplyCursor = ReplyCursor(),
    val replies: List<ReplyItem>? = null,
    /** 置顶评论（新版字段，只在第一页出现）。 */
    @SerialName("top_replies") val topReplies: List<ReplyItem>? = null,
    /** 置顶：UP 主 / 管理员 / 投票。 */
    val top: ReplyTop? = null,
    /** 作者：`mid` 用来标「UP」，`top` 是 UP 主置顶的那条（旧字段）。 */
    val upper: ReplyUpper? = null,
)

@Serializable
data class ReplyCursor(
    @SerialName("is_end") val isEnd: Boolean = false,
    @SerialName("all_count") val allCount: Int = 0,
    @SerialName("pagination_reply") val paginationReply: ReplyPaginationReply? = null,
)

@Serializable
data class ReplyPaginationReply(
    @SerialName("next_offset") val nextOffset: String = "",
)

@Serializable
data class ReplyTop(
    val upper: ReplyItem? = null,
    val admin: ReplyItem? = null,
    val vote: ReplyItem? = null,
)

@Serializable
data class ReplyUpper(
    val mid: Long = 0,
    val top: ReplyItem? = null,
)
```

`ReplyContent` 末尾（`jumpKeywords` 之后）加：

```kotlin
    /** 带图评论的图片。 */
    val pictures: List<ReplyPicture>? = null,
```

文件末尾加：

```kotlin
/** `content.pictures[]`：只取地址，缩略图和预览都用它。 */
@Serializable
data class ReplyPicture(
    @SerialName("img_src") val imgSrc: String = "",
)
```

同时把 `// 置顶、图片评论等仍未建模，以抓包为准逐步补齐。` 这行注释改成 `// 置顶（top / top_replies / upper.top）、图片（content.pictures）已建模。`

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.ReplyDtoTest"`
Expected: PASS（含原有两条）。

- [ ] **Step 5: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/ReplyDto.kt core/network/src/test/java/com/biliseek/core/network/ReplyDtoTest.kt
git commit -m "feat(comment): 评论 DTO 补游标、置顶、作者与图片字段

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 翻页与排序修正（游标分页、mode 排序、去重）

**问题：** `wbi/main` 认 `mode`（3 热度 / 2 时间）和 `pagination_str`，不认 `pn/sort`；返回里没有 `page.count`，所以 `hasMore` 恒为 false（只能看第一页），切排序也不生效。

**Files:**
- Create: `core/network/src/main/java/com/biliseek/core/network/internal/CommentPagePolicy.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt:1030-1086`
- Create: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`
- Test: `core/network/src/test/java/com/biliseek/core/network/CommentPagePolicyTest.kt`、`app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ReplyData.cursor/topReplies/top/upper`。
- Produces:
  - `BiliRepository.CommentPage(items, count, topReplies: List<ReplyItem> = emptyList(), upMid: Long = 0, nextOffset: String? = null)`
  - `BiliRepository.comments(oid: Long, type: Int, sort: Int = 1, offset: String = ""): Result<CommentPage>`（`page` 参数删除）
  - `internal fun resolveCommentMode(sort: Int): Int`、`commentListParams(oid, type, sort, offset): Map<String, String>`、`collectTopReplies(data: ReplyData): List<ReplyItem>`、`resolveNextOffset(cursor: ReplyCursor): String?`、`toCommentPage(data: ReplyData?, firstPage: Boolean): BiliRepository.CommentPage`
  - `:app`：`fun mergeCommentPage(existing: List<ReplyItem>, incoming: List<ReplyItem>, pinned: List<ReplyItem>): List<ReplyItem>`
  - `CommentThreadState` 新字段：`topReplies: List<ReplyItem> = emptyList()`、`upMid: Long = 0`、`nextOffset: String = ""`

- [ ] **Step 1: 写网络层失败的测试** `core/network/src/test/java/com/biliseek/core/network/CommentPagePolicyTest.kt`

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.ReplyCursor
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyPaginationReply
import com.biliseek.core.network.dto.ReplyTop
import com.biliseek.core.network.dto.ReplyUpper
import com.biliseek.core.network.internal.collectTopReplies
import com.biliseek.core.network.internal.commentListParams
import com.biliseek.core.network.internal.resolveCommentMode
import com.biliseek.core.network.internal.resolveNextOffset
import com.biliseek.core.network.internal.toCommentPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主评论列表：mode 排序 + pagination_str 游标翻页 + 置顶合并。 */
class CommentPagePolicyTest {

    @Test
    fun `排序映射到 mode`() {
        assertEquals(3, resolveCommentMode(1))
        assertEquals(2, resolveCommentMode(0))
    }

    @Test
    fun `第一页 pagination_str 的 offset 为空串，不再带 pn 和 sort`() {
        val p = commentListParams(oid = 100, type = 1, sort = 1, offset = "")
        assertEquals("100", p["oid"])
        assertEquals("1", p["type"])
        assertEquals("3", p["mode"])
        assertEquals("20", p["ps"])
        assertEquals("""{"offset":""}""", p["pagination_str"])
        assertFalse(p.containsKey("pn"))
        assertFalse(p.containsKey("sort"))
    }

    @Test
    fun `翻页把 next_offset 原样放进 pagination_str`() {
        val p = commentListParams(oid = 1, type = 17, sort = 0, offset = """{"type":1,"data":{"pn":2}}""")
        assertEquals("2", p["mode"])
        assertEquals("""{"offset":"{\"type\":1,\"data\":{\"pn\":2}}"}""", p["pagination_str"])
    }

    @Test
    fun `置顶合并并按 rpid 去重`() {
        val a = ReplyItem(rpid = 1)
        val b = ReplyItem(rpid = 2)
        val data = ReplyData(topReplies = listOf(a, b), top = ReplyTop(upper = a), upper = ReplyUpper(mid = 9, top = a))
        assertEquals(listOf(1L, 2L), collectTopReplies(data).map { it.rpid })
    }

    @Test
    fun `到底或没有游标就没有下一页`() {
        assertNull(resolveNextOffset(ReplyCursor(isEnd = true, paginationReply = ReplyPaginationReply("x"))))
        assertNull(resolveNextOffset(ReplyCursor(isEnd = false, paginationReply = null)))
        assertNull(resolveNextOffset(ReplyCursor(isEnd = false, paginationReply = ReplyPaginationReply(""))))
        assertEquals("x", resolveNextOffset(ReplyCursor(isEnd = false, paginationReply = ReplyPaginationReply("x"))))
    }

    @Test
    fun `只有第一页带置顶，作者与游标照取`() {
        val data = ReplyData(
            replies = listOf(ReplyItem(rpid = 5)),
            topReplies = listOf(ReplyItem(rpid = 1)),
            upper = ReplyUpper(mid = 9),
            cursor = ReplyCursor(allCount = 50, paginationReply = ReplyPaginationReply("n")),
        )
        val first = toCommentPage(data, firstPage = true)
        assertEquals(listOf(5L), first.items.map { it.rpid })
        assertEquals(listOf(1L), first.topReplies.map { it.rpid })
        assertEquals(9L, first.upMid)
        assertEquals("n", first.nextOffset)
        assertEquals(50, first.count)
        assertTrue(toCommentPage(data, firstPage = false).topReplies.isEmpty())
        assertNull(toCommentPage(null, firstPage = true).nextOffset)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CommentPagePolicyTest"`
Expected: 编译失败，`Unresolved reference: commentListParams` 等。

- [ ] **Step 3: 实现** `core/network/src/main/java/com/biliseek/core/network/internal/CommentPagePolicy.kt`

```kotlin
package com.biliseek.core.network.internal

import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.dto.ReplyCursor
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.dto.ReplyItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 界面排序（1 热度 / 0 时间）→ `wbi/main` 的 mode（3 热度 / 2 时间）。 */
internal fun resolveCommentMode(sort: Int): Int = if (sort == 0) 2 else 3

/**
 * 主评论列表参数（`wbi/main` 与兼容链路 `main` 共用）。`wbi/main` 不认 `pn/sort`：
 * 排序走 `mode`，翻页走 `pagination_str = {"offset": 上一页的 next_offset}`，第一页 offset 为空串。
 */
internal fun commentListParams(oid: Long, type: Int, sort: Int, offset: String): Map<String, String> = mapOf(
    "oid" to oid.toString(),
    "type" to type.toString(),
    "mode" to resolveCommentMode(sort).toString(),
    "ps" to "20",
    "plat" to "1",
    "web_location" to "1315875",
    "pagination_str" to buildJsonObject { put("offset", offset) }.toString(),
)

/** 置顶评论：`top.upper/admin/vote` + `upper.top` + `top_replies`，按 rpid 去重。 */
internal fun collectTopReplies(data: ReplyData): List<ReplyItem> =
    listOfNotNull(data.top?.upper, data.top?.admin, data.top?.vote, data.upper?.top)
        .plus(data.topReplies.orEmpty())
        .filter { it.rpid > 0 }
        .distinctBy { it.rpid }

/** 下一页游标：服务端说到底了、或没给 next_offset 就没有下一页（免得反复拉同一页）。 */
internal fun resolveNextOffset(cursor: ReplyCursor): String? =
    cursor.paginationReply?.nextOffset?.takeIf { !cursor.isEnd && it.isNotBlank() }

/** 响应 → [BiliRepository.CommentPage]。置顶只在第一页取。 */
internal fun toCommentPage(data: ReplyData?, firstPage: Boolean): BiliRepository.CommentPage =
    BiliRepository.CommentPage(
        items = data?.replies.orEmpty(),
        count = data?.cursor?.allCount ?: 0,
        topReplies = if (firstPage && data != null) collectTopReplies(data) else emptyList(),
        upMid = data?.upper?.mid ?: 0L,
        nextOffset = data?.cursor?.let(::resolveNextOffset),
    )
```

`BiliRepository.kt`：
1. 第 1030 行 `CommentPage` 换成：

```kotlin
    data class CommentPage(
        val items: List<ReplyItem>,
        val count: Int,
        /** 只有主评论第一页有：置顶评论。 */
        val topReplies: List<ReplyItem> = emptyList(),
        /** 作者 mid（标「UP」用），拿不到为 0。 */
        val upMid: Long = 0,
        /** 主评论下一页的游标；null = 没有下一页。楼中楼不用它（仍按 count 翻页）。 */
        val nextOffset: String? = null,
    )
```

2. `comments()` 的 KDoc 与签名、参数构造、返回值改成：

```kotlin
    /**
     * 评论列表。先走 WBI 主链路，遇 -111/风控降级兼容链路一次（[runCommentRead]）。
     * `oid`/`type`：视频=用 aid + type=1；动态=按 [com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry] 算出的 oid + type(11/17)。
     * `sort`：0 按时间 / 1 按热度（[commentListParams] 换成 mode）。`offset`：第一页传空串，之后传上一页的 [CommentPage.nextOffset]。
     * 写侧（发评论/点赞/删除）走 [addDynamicReply]/[replyAction]/[deleteReply]。
     */
    suspend fun comments(oid: Long, type: Int, sort: Int = 1, offset: String = ""): Result<CommentPage> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val baseParams = commentListParams(oid, type, sort, offset)
```

   中间的请求、错误分流保持不变；末尾两行

```kotlin
        val data = response.data
        CommentPage(items = data?.replies.orEmpty(), count = data?.page?.count ?: 0)
    }

    /**
     * 楼中楼
```
   中**只改 `comments()` 的那一处**为：

```kotlin
        toCommentPage(response.data, firstPage = offset.isEmpty())
    }
```

3. 文件顶部 import 区加：

```kotlin
import com.biliseek.core.network.internal.commentListParams
import com.biliseek.core.network.internal.toCommentPage
```

- [ ] **Step 4: 跑网络层测试确认通过**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.CommentPagePolicyTest" --tests "com.biliseek.core.network.CommentReadPolicyTest"`
Expected: PASS。

- [ ] **Step 5: 写 `:app` 失败的测试** `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`

```kotlin
package com.biliseek.app.comment

import com.biliseek.core.network.dto.ReplyItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentListPolicyTest {

    private fun r(id: Long) = ReplyItem(rpid = id)

    @Test
    fun `翻页合并丢掉重复项和置顶里已有的`() {
        val merged = mergeCommentPage(
            existing = listOf(r(2), r(3)),
            incoming = listOf(r(3), r(1), r(4)),
            pinned = listOf(r(1)),
        )
        assertEquals(listOf(2L, 3L, 4L), merged.map { it.rpid })
    }

    @Test
    fun `第一页也要去掉置顶那条`() {
        val merged = mergeCommentPage(existing = emptyList(), incoming = listOf(r(1), r(5)), pinned = listOf(r(1)))
        assertEquals(listOf(5L), merged.map { it.rpid })
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败（`mergeCommentPage` 未定义；控制器调用 `repository.comments(..., nextPage, ...)` 也会因签名变化报错）。

- [ ] **Step 7: 实现** `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`

```kotlin
package com.biliseek.app.comment

import com.biliseek.core.network.dto.ReplyItem

/**
 * 翻页合并：追加新一页，丢掉已经有的、以及置顶里出现过的 rpid
 * （列表按 rpid 当 key，重复会崩；置顶那条服务端可能在普通列表里再给一次）。
 */
fun mergeCommentPage(
    existing: List<ReplyItem>,
    incoming: List<ReplyItem>,
    pinned: List<ReplyItem>,
): List<ReplyItem> {
    val seen = pinned.mapTo(HashSet()) { it.rpid }
    return (existing + incoming).filter { seen.add(it.rpid) }
}
```

`CommentThreadController.kt`：

1. `CommentThreadState` 在 `comments` 字段后加：

```kotlin
    /** 置顶评论（第一页带回来，换排序时重取）。显示在列表最前面，普通列表里已去重。 */
    val topReplies: List<ReplyItem> = emptyList(),
    /** 作者 mid，用来给作者本人的评论标「UP」。0 = 不知道。 */
    val upMid: Long = 0,
    /** 主评论下一页的游标（[BiliRepository.comments] 的 offset）。 */
    val nextOffset: String = "",
```

   并把 `sort` 字段的注释改为 `/** 1=热度 0=时间，对应 [BiliRepository.comments] 的 sort 参数（仓库里换成 mode）。 */`。

2. `setSort` 里同时清掉置顶与游标：

```kotlin
        _state.value = cur.copy(sort = sort, comments = RemoteListState.empty(), topReplies = emptyList(), nextOffset = "")
```

3. `load()` 整个换成：

```kotlin
    /** 加载评论列表。reset=true 重新拉第 1 页；false 用游标翻页追加。 */
    private fun load(reset: Boolean) {
        val cur = _state.value ?: return
        val base = if (reset) RemoteListState.empty<ReplyItem>() else cur.comments
        if (base.isLoading || base.isLoadingMore) return
        val offset = if (reset) "" else cur.nextOffset
        val loadingMore = !reset
        _state.value = cur.copy(
            comments = base.copy(isLoading = !loadingMore, isLoadingMore = loadingMore, errorMessage = null),
        )
        scope.launch {
            repository.comments(cur.oid, cur.type, cur.sort, offset)
                .onSuccess { page ->
                    val dialog = _state.value ?: return@onSuccess
                    // 等结果期间关了、换了评论区或换了排序：这页已经过时，丢掉
                    if (!dialog.sameThreadAs(cur) || dialog.sort != cur.sort) return@onSuccess
                    val pinned = if (reset) page.topReplies else dialog.topReplies
                    _state.value = dialog.copy(
                        topReplies = pinned,
                        upMid = if (page.upMid > 0) page.upMid else dialog.upMid,
                        nextOffset = page.nextOffset.orEmpty(),
                        comments = dialog.comments.copy(
                            items = mergeCommentPage(
                                existing = if (reset) emptyList() else dialog.comments.items,
                                incoming = page.items,
                                pinned = pinned,
                            ),
                            page = if (reset) 1 else dialog.comments.page + 1,
                            hasMore = page.nextOffset != null,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                    )
                }
                .onFailure { err ->
                    val dialog = _state.value ?: return@onFailure
                    if (!dialog.sameThreadAs(cur) || dialog.sort != cur.sort) return@onFailure
                    _state.value = dialog.copy(
                        comments = dialog.comments.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = err.message ?: "评论加载失败",
                        ),
                    )
                }
        }
    }
```

4. 类末尾（`submit()` 之后、类的 `}` 之前）加：

```kotlin
    private fun CommentThreadState.sameThreadAs(other: CommentThreadState) = oid == other.oid && type == other.type
```

- [ ] **Step 8: 跑测试确认通过并编译**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS，编译成功。

- [ ] **Step 9: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/internal/CommentPagePolicy.kt \
  core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt \
  core/network/src/test/java/com/biliseek/core/network/CommentPagePolicyTest.kt \
  app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt \
  app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt \
  app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt
git commit -m "fix(comment): 评论改用 mode + pagination_str 游标翻页，排序生效、能翻到第二页

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 输入草稿记忆（含回复对象）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/comment/CommentDraftPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`（`open` / `dismiss` / `submit`）
- Test: `app/src/test/java/com/biliseek/app/comment/CommentDraftPolicyTest.kt`

**Interfaces:**
- Produces: `data class CommentDraft(val content: String, val replyTarget: CommentReplyTarget?)`、`fun draftOnDismiss(state: CommentThreadState): CommentDraft?`

- [ ] **Step 1: 写失败的测试** `app/src/test/java/com/biliseek/app/comment/CommentDraftPolicyTest.kt`

```kotlin
package com.biliseek.app.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentDraftPolicyTest {

    private val base = CommentThreadState(oid = 1, type = 1)
    private val target = CommentReplyTarget(root = 10, parent = 11, uname = "小明")

    @Test
    fun `有内容就连回复对象一起留`() {
        assertEquals(CommentDraft("没写完", target), draftOnDismiss(base.copy(content = "没写完", replyTarget = target)))
        assertEquals(CommentDraft("一级评论", null), draftOnDismiss(base.copy(content = "一级评论")))
    }

    @Test
    fun `内容为空或只有空白就不留，只点了回复也不算草稿`() {
        assertNull(draftOnDismiss(base))
        assertNull(draftOnDismiss(base.copy(content = "  \n")))
        assertNull(draftOnDismiss(base.copy(replyTarget = target)))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentDraftPolicyTest"`
Expected: 编译失败，`Unresolved reference: draftOnDismiss`。

- [ ] **Step 3: 实现** `app/src/main/java/com/biliseek/app/comment/CommentDraftPolicy.kt`

```kotlin
package com.biliseek.app.comment

/** 一个评论区没发出去的草稿：输入内容 + 当时在回复谁（null = 发一级评论）。 */
data class CommentDraft(val content: String, val replyTarget: CommentReplyTarget?)

/** 关评论区时要不要留草稿：内容为空就不留（只点了「回复」没打字不算草稿）。 */
fun draftOnDismiss(state: CommentThreadState): CommentDraft? =
    state.content.takeIf { it.isNotBlank() }?.let { CommentDraft(it, state.replyTarget) }
```

`CommentThreadController.kt`：

1. `likeRequestGate` 下面加：

```kotlin
    /** 草稿：key = (oid, type)。只在内存里，App 被杀就没了；发送成功后删掉。 */
    private val drafts = mutableMapOf<Pair<Long, Int>, CommentDraft>()
```

2. `open` / `dismiss` 换成：

```kotlin
    /** 打开对话框并立即加载第一页。未登录也可读。上次没发出去的草稿（含回复对象）填回输入框。 */
    fun open(oid: Long, type: Int, key: String = "") {
        val draft = drafts[oid to type]
        _state.value = CommentThreadState(
            oid = oid,
            type = type,
            key = key,
            content = draft?.content.orEmpty(),
            replyTarget = draft?.replyTarget,
        )
        load(reset = true)
    }

    /** 关闭对话框：没发出去的内容按评论区记下来。 */
    fun dismiss() {
        val cur = _state.value ?: return
        val draft = draftOnDismiss(cur)
        if (draft != null) drafts[cur.oid to cur.type] = draft else drafts.remove(cur.oid to cur.type)
        _state.value = null
    }
```

3. `submit()` 的 `.onSuccess { ... }` 换成（发送中关掉弹层也能删对草稿）：

```kotlin
                .onSuccess {
                    drafts.remove(cur.oid to cur.type)
                    val dialog = _state.value
                    if (dialog != null && dialog.sameThreadAs(cur)) {
                        _state.value = dialog.copy(content = "", isPosting = false, replyTarget = null)
                        load(reset = true)
                    }
                    emitMessage("评论已发送")
                }
```

   `.onFailure` 里 `_state.value = _state.value?.copy(isPosting = false)` 改成：

```kotlin
                    _state.value = _state.value?.takeIf { it.sameThreadAs(cur) }?.copy(isPosting = false) ?: _state.value
```

- [ ] **Step 4: 跑测试确认通过并编译**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.*" && ./gradlew :app:compileDebugKotlin`
Expected: PASS，编译成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/CommentDraftPolicy.kt \
  app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt \
  app/src/test/java/com/biliseek/app/comment/CommentDraftPolicyTest.kt
git commit -m "feat(comment): 关掉评论区再进来，没发出去的内容和回复对象还在

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 楼中楼可点赞 / 回复；弹层改吃状态并合并两处挂载

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`（`toggleLike` / 删 `patchLike`）
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt`
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:243-244, 1180-1231`
- Test: `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`、`app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Produces:
  - `fun CommentThreadState.patchReply(rpid: Long, transform: (ReplyItem) -> ReplyItem): CommentThreadState`（置顶、列表、楼中楼预览、已展开楼中楼里 rpid 相同的都改）
  - `fun ReplyItem.withLike(like: Boolean): ReplyItem`
  - `CommentThreadController.toggleLike(reply: ReplyItem)`（去掉 `rootRpid` 参数）
  - `DynamicCommentDialog(state: CommentThreadState, onSortChange, onLoadMore, onLike: (ReplyItem) -> Unit, onReply: (ReplyItem, Long?) -> Unit, onExpandSubReplies, onLoadMoreSubReplies, onContentChange, onClearReplyTarget, onSubmit, onDismiss, onOpenUser)`
  - `@Composable fun CommentThreadSheet(controller: CommentThreadController, onOpenUser: (Long) -> Unit)`

- [ ] **Step 1: 写失败的单测**（追加到 `CommentListPolicyTest`）

```kotlin
    @Test
    fun `patchReply 改到所有出现的位置`() {
        val sub = ReplyItem(rpid = 7, like = 1)
        val state = CommentThreadState(
            oid = 1, type = 1,
            topReplies = listOf(ReplyItem(rpid = 7, like = 1)),
            comments = com.biliseek.app.data.RemoteListState(items = listOf(ReplyItem(rpid = 3, replies = listOf(sub)))),
            expandedSubReplies = mapOf(3L to com.biliseek.app.data.RemoteListState(items = listOf(sub))),
        )
        val out = state.patchReply(7) { it.withLike(true) }
        assertEquals(2, out.topReplies.single().like)
        assertEquals(2, out.comments.items.single().replies!!.single().like)
        assertEquals(1, out.comments.items.single().replies!!.single().action)
        assertEquals(2, out.expandedSubReplies.getValue(3).items.single().like)
        assertEquals(0, out.comments.items.single().like)
    }

    @Test
    fun `取消点赞不会减成负数`() {
        val r = ReplyItem(rpid = 1, like = 0, action = 1).withLike(false)
        assertEquals(0, r.like)
        assertEquals(0, r.action)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败，`Unresolved reference: patchReply`。

- [ ] **Step 3: 实现纯函数**（追加到 `CommentListPolicy.kt`）

```kotlin
/**
 * 对整个评论区里 rpid 相同的那条做同一个修改：同一条可能同时出现在置顶、普通列表、
 * 一级评论自带的楼中楼预览、已展开的楼中楼里。
 */
fun CommentThreadState.patchReply(rpid: Long, transform: (ReplyItem) -> ReplyItem): CommentThreadState {
    fun patch(item: ReplyItem): ReplyItem {
        val self = if (item.rpid == rpid) transform(item) else item
        val subs = self.replies ?: return self
        return self.copy(replies = subs.map { if (it.rpid == rpid) transform(it) else it })
    }
    return copy(
        topReplies = topReplies.map(::patch),
        comments = comments.copy(items = comments.items.map(::patch)),
        expandedSubReplies = expandedSubReplies.mapValues { (_, sub) -> sub.copy(items = sub.items.map(::patch)) },
    )
}

/** 点赞 / 取消后的样子：赞数 ±1（不小于 0），action 1=已赞。 */
fun ReplyItem.withLike(like: Boolean): ReplyItem =
    copy(like = (this.like + if (like) 1 else -1).coerceAtLeast(0), action = if (like) 1 else 0)
```

- [ ] **Step 4: 控制器改用 `patchReply`**

`CommentThreadController.kt` 把 `toggleLike` 换成下面这样，并**删掉整个 `patchLike` 函数**：

```kotlin
    /** 评论点赞 / 取消（一级评论和楼中楼都用它）：500ms 防抖，乐观更新，失败回滚。 */
    fun toggleLike(reply: ReplyItem) {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再点赞")
            return
        }
        val cur = _state.value ?: return
        val like = reply.action != 1
        val now = System.currentTimeMillis()
        val last = likeRequestGate[reply.rpid] ?: 0L
        if (now - last < 500) return
        likeRequestGate[reply.rpid] = now
        _state.value = cur.patchReply(reply.rpid) { it.withLike(like) }
        scope.launch {
            repository.replyAction(cur.oid, cur.type, reply.rpid, like)
                .onFailure { err ->
                    _state.value = _state.value?.takeIf { it.sameThreadAs(cur) }
                        ?.patchReply(reply.rpid) { it.withLike(!like) } ?: _state.value
                    emitMessage("点赞失败：${err.message ?: "未知错误"}")
                }
        }
    }
```

- [ ] **Step 5: 跑单测确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.*"`
Expected: PASS（`:app` 主代码此时会因弹层签名还没改而编译失败的话，先做 Step 6 再跑）。

- [ ] **Step 6: 弹层改写** `DynamicCommentDialog.kt`

1. import 区加 `import com.biliseek.app.comment.CommentThreadState`。
2. KDoc 改为：

```kotlin
/**
 * 评论列表 Sheet（动态评论与播放页评论共用）。列表 + 楼中楼预览 + 排序 + 输入栏（含表情面板）。
 * 一级评论和楼中楼都能点赞 / 回复；一级评论可展开全部楼中楼。
 *
 * 正文走 [RichText]：表情图、@ 高亮（[onOpenUser] 非空时可点进空间）、蓝字关键词、链接。
 */
```

3. `DynamicCommentDialog` 签名与函数体开头换成：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCommentDialog(
    state: CommentThreadState,
    onSortChange: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (ReplyItem) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: (ReplyItem) -> Unit,
    onLoadMoreSubReplies: (Long) -> Unit,
    onContentChange: (String) -> Unit,
    onClearReplyTarget: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUser: ((Long) -> Unit)? = null,
) {
    val comments = state.comments
    val sort = state.sort
    val replyTargetUname = state.replyTarget?.uname
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
```

   函数体其余部分不变，只把 `items(comments.items, ...)` 里的 `CommentItemRow(...)` 调用换成：

```kotlin
                        CommentItemRow(
                            reply = reply,
                            subReplies = state.expandedSubReplies[reply.rpid],
                            actions = actions,
                            onLike = onLike,
                            onReply = onReply,
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                        )
```

   `CommentComposer(...)` 里的 `content = content,` 改成 `content = state.content,`，`isPosting = isPosting,` 改成 `isPosting = state.isPosting,`。

4. `CommentItemRow` 整个换成，并在它后面加 `CommentActionRow`、`SubReplyRow`：

```kotlin
/** 一条一级评论：头像/昵称/时间/内容 + 回复/点赞 + 楼中楼预览与展开。 */
@Composable
private fun CommentItemRow(
    reply: ReplyItem,
    subReplies: RemoteListState<ReplyItem>?,
    actions: RichTextActions,
    onLike: (ReplyItem) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: () -> Unit,
    onLoadMoreSubReplies: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.sm, top = BsSpacing.md, bottom = BsSpacing.xs),
    ) {
        AsyncImage(
            model = reply.member.avatar,
            contentDescription = null,
            modifier = Modifier
                .size(BsSize.avatarSm)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = BsSpacing.sm).weight(1f)) {
            Text(
                reply.member.uname,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RichText(
                source = reply.content.toRichSource(),
                modifier = Modifier.padding(top = BsSpacing.xs),
                style = MaterialTheme.typography.bodyMedium,
                actions = actions,
            )
            CommentActionRow(
                reply = reply,
                onReply = { onReply(reply, null) },
                onLike = { onLike(reply) },
            )
            if (reply.rcount > 0) {
                val preview = subReplies?.items ?: reply.replies.orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = BsSpacing.xs)
                        .clip(BsShapes.sm)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(BsSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BsSpacing.xs),
                ) {
                    preview.forEach { sub ->
                        SubReplyRow(
                            sub = sub,
                            actions = actions,
                            onReply = { onReply(sub, reply.rpid) },
                            onLike = { onLike(sub) },
                        )
                    }
                    when {
                        subReplies == null -> Text(
                            "查看全部 ${reply.rcount} 条回复",
                            Modifier.clickable(onClick = onExpandSubReplies),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        subReplies.isLoading || subReplies.isLoadingMore ->
                            CircularProgressIndicator(Modifier.size(BsSize.iconSm), strokeWidth = 2.dp)
                        subReplies.errorMessage != null -> Text(
                            "加载失败，点击重试",
                            Modifier.clickable(onClick = if (subReplies.items.isEmpty()) onExpandSubReplies else onLoadMoreSubReplies),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        subReplies.hasMore -> Text(
                            "加载更多回复",
                            Modifier.clickable(onClick = onLoadMoreSubReplies),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 时间 + 回复 + 点赞（一级评论和楼中楼共用）。 */
@Composable
private fun CommentActionRow(
    reply: ReplyItem,
    onReply: () -> Unit,
    onLike: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = BsSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatRelativeTime(reply.ctime * 1000),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        BsIconAction(
            icon = Icons.Outlined.ChatBubbleOutline,
            label = "回复",
            onClick = onReply,
            showLabelWhenEmpty = false,
        )
        BsIconAction(
            icon = Icons.Outlined.ThumbUp,
            activeIcon = Icons.Filled.ThumbUp,
            label = "点赞",
            count = reply.like.toLong(),
            active = reply.action == 1,
            onClick = onLike,
            showLabelWhenEmpty = false,
        )
    }
}

/** 楼中楼一条：「昵称：正文」+ 时间、回复、点赞。 */
@Composable
private fun SubReplyRow(
    sub: ReplyItem,
    actions: RichTextActions,
    onReply: () -> Unit,
    onLike: () -> Unit,
) {
    val unameColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        RichText(
            source = sub.content.toRichSource(),
            style = MaterialTheme.typography.bodySmall,
            actions = actions,
            prefix = {
                withStyle(SpanStyle(color = unameColor)) {
                    append(sub.member.uname)
                }
                append("：")
            },
        )
        CommentActionRow(reply = sub, onReply = onReply, onLike = onLike)
    }
}
```

- [ ] **Step 7: 新建** `app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt`

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.biliseek.app.comment.CommentThreadController

/** 把一个 [CommentThreadController] 接到评论弹层上；控制器状态为 null（已关闭）时什么都不画。 */
@Composable
fun CommentThreadSheet(
    controller: CommentThreadController,
    onOpenUser: (Long) -> Unit,
) {
    val state by controller.state.collectAsState()
    val current = state ?: return
    DynamicCommentDialog(
        state = current,
        onSortChange = controller::setSort,
        onLoadMore = controller::loadMore,
        onLike = controller::toggleLike,
        onReply = controller::startReplyTo,
        onExpandSubReplies = controller::expandSubReplies,
        onLoadMoreSubReplies = controller::loadMoreSubReplies,
        onContentChange = controller::updateContent,
        onClearReplyTarget = controller::clearReplyTarget,
        onSubmit = controller::submit,
        onDismiss = controller::dismiss,
        onOpenUser = onOpenUser,
    )
}
```

- [ ] **Step 8: `MainActivity.kt` 换挂载**

1. 删掉第 243–244 行：

```kotlin
                val commentDialog by viewModel.dynamics.comments.state.collectAsState()
                val playerComment by viewModel.playerComments.state.collectAsState()
```

2. 把 `// Ch4：动态评论 / 图片预览` 下面的 `commentDialog?.let { ... }` 和 `// 播放页评论区` 下面的 `playerComment?.let { ... }` 两整段（约 1180–1231 行）换成：

```kotlin
                // Ch4：动态评论 / 播放页评论区
                for (comments in listOf(viewModel.dynamics.comments, viewModel.playerComments)) {
                    com.biliseek.app.ui.components.CommentThreadSheet(
                        controller = comments,
                        onOpenUser = { mid ->
                            comments.dismiss()
                            viewModel.openUpSpace(SearchUser(mid = mid, uname = ""))
                            openUpSpaceRequests.tryEmit(Unit)
                        },
                    )
                }
```

   （循环里两个控制器顺序固定，Compose 按位置区分，不需要 `key`。）

- [ ] **Step 9: 改 UI 测试** — `DynamicCommentDialogUiTest.kt` 整个换成：

```kotlin
package com.biliseek.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.comment.CommentReplyTarget
import com.biliseek.app.comment.CommentThreadState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.components.DynamicCommentDialog
import com.biliseek.core.network.dto.ReplyContent
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyMember
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 评论列表 Sheet 的交互测试：排序切换 / 点赞 / 回复 / 加载更多 / 楼中楼操作回调。
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

    private fun state(
        items: List<ReplyItem> = replies,
        hasMore: Boolean = false,
        replyTarget: CommentReplyTarget? = null,
    ) = CommentThreadState(
        oid = 1, type = 1,
        comments = RemoteListState(items = items, hasMore = hasMore),
        replyTarget = replyTarget,
    )

    private fun setDialog(
        state: CommentThreadState,
        onLike: (ReplyItem) -> Unit = {},
        onReply: (ReplyItem, Long?) -> Unit = { _, _ -> },
        onSortChange: (Int) -> Unit = {},
        onLoadMore: () -> Unit = {},
        onClearReplyTarget: () -> Unit = {},
    ) {
        compose.setContent {
            BiliSeekTheme {
                DynamicCommentDialog(
                    state = state,
                    onSortChange = onSortChange,
                    onLoadMore = onLoadMore,
                    onLike = onLike,
                    onReply = onReply,
                    onExpandSubReplies = {},
                    onLoadMoreSubReplies = {},
                    onContentChange = {},
                    onClearReplyTarget = onClearReplyTarget,
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun 评论列表_点赞回复排序与加载更多回调() {
        var likedReply: ReplyItem? = null
        var replyTarget: ReplyItem? = null
        var replyRoot: Long? = -1
        var sortRequested: Int? = null
        var loadMoreCalled = false

        setDialog(
            state(hasMore = true),
            onLike = { likedReply = it },
            onReply = { reply, root -> replyTarget = reply; replyRoot = root },
            onSortChange = { sortRequested = it },
            onLoadMore = { loadMoreCalled = true },
        )

        compose.onNodeWithText("第一条评论").assertIsDisplayed()
        compose.onNodeWithText("用户B").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("点赞")[0].performClick()
        check(likedReply?.rpid == 1L) { "点赞未回调正确的评论" }

        compose.onAllNodesWithContentDescription("回复")[0].performClick()
        check(replyTarget?.rpid == 1L) { "回复未回调正确的评论" }
        check(replyRoot == null) { "回复一级评论的 root 应为 null" }

        compose.onNodeWithText("时间").performClick()
        check(sortRequested == 0) { "排序切换未回调" }

        compose.waitForIdle()
        check(loadMoreCalled) { "列表末尾 sentinel 未触发加载更多" }
    }

    @Test
    fun 楼中楼可以点赞和回复() {
        val sub = ReplyItem(
            rpid = 11, mid = 30, like = 0,
            content = ReplyContent(message = "楼中楼一条"),
            member = ReplyMember(uname = "用户C"),
        )
        val root = replies[0].copy(rcount = 1, replies = listOf(sub))
        var likedReply: ReplyItem? = null
        var replyTarget: ReplyItem? = null
        var replyRoot: Long? = null

        setDialog(
            state(items = listOf(root)),
            onLike = { likedReply = it },
            onReply = { reply, r -> replyTarget = reply; replyRoot = r },
        )

        // 顺序：一级评论的按钮在前，楼中楼的在后
        compose.onAllNodesWithContentDescription("点赞")[1].performClick()
        check(likedReply?.rpid == 11L) { "楼中楼点赞未回调" }

        compose.onAllNodesWithContentDescription("回复")[1].performClick()
        check(replyTarget?.rpid == 11L) { "楼中楼回复未回调" }
        check(replyRoot == 1L) { "回复楼中楼应带上所属一级评论 rpid" }
    }

    @Test
    fun 回复目标提示与取消() {
        var cleared = false
        setDialog(
            state(replyTarget = CommentReplyTarget(root = 1, parent = 1, uname = "用户A")),
            onClearReplyTarget = { cleared = true },
        )

        compose.onNodeWithText("回复 @用户A").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消回复").performClick()
        check(cleared) { "取消回复目标未回调" }
    }
}
```

- [ ] **Step 10: 编译与单测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.*" && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: PASS，两个编译任务都成功。另外 `grep -n "commentDialog\|playerComment\b" app/src/main/java/com/biliseek/app/MainActivity.kt` 应无输出。
（有设备时再跑 `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.biliseek.app.DynamicCommentDialogUiTest`，由用户决定。）

- [ ] **Step 11: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/ app/src/test/java/com/biliseek/app/comment/ \
  app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt \
  app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt \
  app/src/main/java/com/biliseek/app/MainActivity.kt \
  app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "feat(comment): 楼中楼可点赞和回复；评论弹层两处挂载合成 CommentThreadSheet

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 置顶评论显示 + UP 标识

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`
- Test: `app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Consumes: `CommentThreadState.topReplies`、`CommentThreadState.upMid`（Task 2）。
- Produces: `CommentItemRow(..., pinned: Boolean, upMid: Long, ...)`、`SubReplyRow(..., upMid: Long, ...)`。

- [ ] **Step 1: 写失败的 UI 测试**（追加到 `DynamicCommentDialogUiTest`）

```kotlin
    @Test
    fun 置顶评论排在最前并标出置顶和UP() {
        val pinned = ReplyItem(
            rpid = 99, mid = 10,
            content = ReplyContent(message = "置顶的那条"),
            member = ReplyMember(uname = "作者"),
        )
        setDialog(state().copy(topReplies = listOf(pinned), upMid = 10))

        compose.onNodeWithText("置顶的那条").assertIsDisplayed()
        compose.onNodeWithText("置顶").assertIsDisplayed()
        // 置顶那条和「第一条评论」（mid=10）都是作者本人
        check(compose.onAllNodesWithText("UP").fetchSemanticsNodes().size == 2) { "作者的评论应标 UP" }
    }

    @Test
    fun 只有置顶没有普通评论时不显示空状态() {
        val pinned = ReplyItem(rpid = 99, content = ReplyContent(message = "唯一一条置顶"), member = ReplyMember(uname = "作者"))
        setDialog(state(items = emptyList()).copy(topReplies = listOf(pinned)))
        compose.onNodeWithText("唯一一条置顶").assertIsDisplayed()
    }
```

   import 区加 `import androidx.compose.ui.test.onAllNodesWithText`。

- [ ] **Step 2: 编译 UI 测试**（此时测试能编译，在设备上会失败；没有设备就靠 Step 4 的代码审查）

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: 成功。

- [ ] **Step 3: 实现**

1. 列表：`when` 的分支换成（只有置顶时也显示列表；key 加前缀，置顶和普通列表即使撞 rpid 也不崩）：

```kotlin
            when {
                comments.isLoading && comments.items.isEmpty() && state.topReplies.isEmpty() -> BsLoadingState(Modifier.weight(1f))

                comments.items.isEmpty() && state.topReplies.isEmpty() -> BsEmptyState(
                    comments.errorMessage ?: "还没有评论，来说两句吧",
                    Modifier.weight(1f),
                    icon = Icons.Outlined.ChatBubbleOutline,
                )

                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.topReplies, key = { "top-${it.rpid}" }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            pinned = true,
                            upMid = state.upMid,
                            subReplies = state.expandedSubReplies[reply.rpid],
                            actions = actions,
                            onLike = onLike,
                            onReply = onReply,
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                        )
                        CommentDivider()
                    }
                    items(comments.items, key = { "c-${it.rpid}" }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            pinned = false,
                            upMid = state.upMid,
                            subReplies = state.expandedSubReplies[reply.rpid],
                            actions = actions,
                            onLike = onLike,
                            onReply = onReply,
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                        )
                        CommentDivider()
                    }
                    // 「comment_footer」那个 item 保持原样
```

   原来 `items` 里的 `HorizontalDivider(Modifier.padding(start = ...), color = ...)` 抽成：

```kotlin
@Composable
private fun CommentDivider() {
    HorizontalDivider(
        Modifier.padding(start = BsSpacing.lg + BsSize.avatarSm + BsSpacing.sm),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
```

2. `CommentItemRow` 参数里在 `reply` 后加 `pinned: Boolean,` 和 `upMid: Long,`；昵称那个 `Text(...)` 换成：

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reply.member.uname,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isUpReply(reply, upMid)) CommentTag("UP")
                if (pinned) CommentTag("置顶")
            }
```

   `preview.forEach` 里的 `SubReplyRow(...)` 加 `upMid = upMid,`。

3. `SubReplyRow` 参数加 `upMid: Long,`，`prefix` 换成：

```kotlin
            prefix = {
                withStyle(SpanStyle(color = unameColor)) {
                    append(sub.member.uname)
                    if (isUpReply(sub, upMid)) append(" UP")
                }
                append("：")
            },
```

4. 文件里加：

```kotlin
/** 作者本人的评论（标「UP」）。 */
private fun isUpReply(reply: ReplyItem, upMid: Long): Boolean = upMid > 0 && reply.mid == upMid

/** 昵称后面的小标签：「UP」「置顶」。 */
@Composable
private fun CommentTag(text: String) {
    val color = MaterialTheme.colorScheme.primary
    Text(
        text,
        Modifier
            .padding(start = BsSpacing.xs)
            .border(1.dp, color, BsShapes.xs)
            .padding(horizontal = BsSpacing.xxs),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
```

   import 加 `import androidx.compose.foundation.border`。

   注意：楼中楼里的「UP」拼在昵称文本里（`"用户 UP"`），不是单独节点；Step 1 的测试数的是一级评论的两个 `CommentTag`，不受影响。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: 成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "feat(comment): 置顶评论排在最前并标「置顶」，作者本人的评论标「UP」

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 发出的评论直接显示（不再重拉第一页）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`（`submit`）
- Test: `app/src/test/java/com/biliseek/app/comment/CommentListPolicyTest.kt`

**Interfaces:**
- Consumes: `patchReply`（Task 4）、`CommentReplyTarget`、`BiliSession`。
- Produces: `fun buildPostedReply(rpid: Long, oid: Long, message: String, session: BiliSession, nowSec: Long): ReplyItem`、`fun CommentThreadState.insertPosted(reply: ReplyItem, target: CommentReplyTarget?): CommentThreadState`

- [ ] **Step 1: 写失败的测试**（追加到 `CommentListPolicyTest`；import 加 `com.biliseek.app.data.RemoteListState`、`com.biliseek.core.network.BiliSession`）

```kotlin
    private val me = BiliSession(sessData = "s", biliJct = "j", dedeUserId = "42", refreshToken = "r", uname = "我", face = "//f.jpg")

    @Test
    fun `buildPostedReply 用自己的头像昵称`() {
        val r = buildPostedReply(rpid = 500, oid = 1, message = "你好", session = me, nowSec = 1700)
        assertEquals(500L, r.rpid)
        assertEquals(42L, r.mid)
        assertEquals("我", r.member.uname)
        assertEquals("//f.jpg", r.member.avatar)
        assertEquals("你好", r.content.message)
        assertEquals(1700L, r.ctime)
    }

    @Test
    fun `一级评论插到普通列表最前面`() {
        val state = CommentThreadState(oid = 1, type = 1, comments = RemoteListState(items = listOf(r(2))))
        val out = state.insertPosted(r(500), target = null)
        assertEquals(listOf(500L, 2L), out.comments.items.map { it.rpid })
    }

    @Test
    fun `回复未展开的一级评论：进预览、回复数加一`() {
        val state = CommentThreadState(oid = 1, type = 1, comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 0))))
        val out = state.insertPosted(r(500), CommentReplyTarget(root = 2, parent = 2, uname = "x"))
        val root = out.comments.items.single()
        assertEquals(1, root.rcount)
        assertEquals(listOf(500L), root.replies!!.map { it.rpid })
    }

    @Test
    fun `回复已展开的一级评论：进展开列表末尾、回复数加一`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 1, replies = listOf(r(3))))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(3)))),
        )
        val out = state.insertPosted(r(500), CommentReplyTarget(root = 2, parent = 3, uname = "x"))
        assertEquals(2, out.comments.items.single().rcount)
        assertEquals(listOf(3L), out.comments.items.single().replies!!.map { it.rpid })
        assertEquals(listOf(3L, 500L), out.expandedSubReplies.getValue(2).items.map { it.rpid })
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败，`Unresolved reference: buildPostedReply`。

- [ ] **Step 3: 实现**（追加到 `CommentListPolicy.kt`；import 加 `com.biliseek.core.network.BiliSession`、`com.biliseek.core.network.dto.ReplyContent`、`com.biliseek.core.network.dto.ReplyMember`）

```kotlin
/** 刚发出去的评论在本地的样子：自己的头像昵称 + 输入的原文（表情按 `[xx]` 文本由富文本渲染）。 */
fun buildPostedReply(rpid: Long, oid: Long, message: String, session: BiliSession, nowSec: Long): ReplyItem =
    ReplyItem(
        rpid = rpid,
        oid = oid,
        mid = session.dedeUserId.toLongOrNull() ?: 0L,
        ctime = nowSec,
        content = ReplyContent(message = message),
        member = ReplyMember(mid = session.dedeUserId, uname = session.uname, avatar = session.face),
    )

/**
 * 发送成功后把自己的评论直接放进列表，不重拉（按热度排时新评论多半不在第一页，重拉了也看不到）。
 * 一级评论插到普通列表最前；回复插进所属一级评论：已展开放展开列表末尾，没展开放预览末尾；回复数 +1。
 */
fun CommentThreadState.insertPosted(reply: ReplyItem, target: CommentReplyTarget?): CommentThreadState {
    if (target == null) return copy(comments = comments.copy(items = listOf(reply) + comments.items))
    val rootRpid = target.root
    val expanded = expandedSubReplies[rootRpid]
    val patched = patchReply(rootRpid) { root ->
        root.copy(
            rcount = root.rcount + 1,
            replies = if (expanded != null) root.replies else root.replies.orEmpty() + reply,
        )
    }
    if (expanded == null) return patched
    return patched.copy(
        expandedSubReplies = patched.expandedSubReplies + (rootRpid to expanded.copy(items = expanded.items + reply)),
    )
}
```

`CommentThreadController.kt` 的 `submit()`：`.onSuccess { ... }` 换成（服务端没回 rpid 时退回老办法重拉第一页）：

```kotlin
                .onSuccess { rpid ->
                    drafts.remove(cur.oid to cur.type)
                    emitMessage("评论已发送")
                    val dialog = _state.value?.takeIf { it.sameThreadAs(cur) } ?: return@onSuccess
                    val cleared = dialog.copy(content = "", isPosting = false, replyTarget = null)
                    if (rpid <= 0L) {
                        _state.value = cleared
                        load(reset = true)
                        return@onSuccess
                    }
                    val posted = buildPostedReply(rpid, cur.oid, cur.content, session.value, System.currentTimeMillis() / 1000)
                    _state.value = cleared.insertPosted(posted, target)
                }
```

   并把 `submit()` 的 KDoc 改为：`/** 提交评论：发新评论或回复 [CommentThreadState.replyTarget]；成功后清空输入框与回复目标，把这条直接插进列表。 */`

- [ ] **Step 4: 跑测试确认通过并编译**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.*" && ./gradlew :app:compileDebugKotlin`
Expected: PASS，编译成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/comment/ app/src/test/java/com/biliseek/app/comment/
git commit -m "feat(comment): 发出的评论直接出现在列表里，不再重拉第一页

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 删除自己的评论

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`（`addReply` / `replyAction` 附近）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（`replyAction` 后面）
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentListPolicy.kt`
- Modify: `app/src/main/java/com/biliseek/app/comment/CommentThreadController.kt`
- Modify: `app/src/main/java/com/biliseek/app/dynamic/DynamicFeedController.kt:47-54`
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`、`CommentThreadSheet.kt`、`MainActivity.kt`
- Test: `CommentListPolicyTest.kt`、`DynamicCommentDialogUiTest.kt`

**Interfaces:**
- Produces:
  - `BiliApi.deleteReply(fields: Map<String, String>): BiliResponse<JsonElement>`
  - `BiliRepository.deleteReply(oid: Long, type: Int, rpid: Long): Result<Unit>`
  - `fun canDeleteReply(reply: ReplyItem, myMid: Long): Boolean`、`fun CommentThreadState.removeReply(rpid: Long, rootRpid: Long?): CommentThreadState`
  - `CommentThreadController.delete(reply: ReplyItem, rootRpid: Long?)`；构造参数新增 `onDeleted: (CommentThreadState) -> Unit = {}`
  - `DynamicCommentDialog` 新增参数 `myMid: Long = 0`、`onDelete: (ReplyItem, Long?) -> Unit = { _, _ -> }`
  - `CommentThreadSheet` 新增参数 `myMid: Long`

- [ ] **Step 1: 写失败的单测**（追加到 `CommentListPolicyTest`）

```kotlin
    @Test
    fun `只能删自己的评论，未登录谁的都不能删`() {
        assertEquals(true, canDeleteReply(ReplyItem(rpid = 1, mid = 42), myMid = 42))
        assertEquals(false, canDeleteReply(ReplyItem(rpid = 1, mid = 7), myMid = 42))
        assertEquals(false, canDeleteReply(ReplyItem(rpid = 1, mid = 0), myMid = 0))
    }

    @Test
    fun `删一级评论：从置顶、列表移除，并丢掉它展开的楼中楼`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            topReplies = listOf(r(2)),
            comments = RemoteListState(items = listOf(r(2), r(3))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(9)))),
        )
        val out = state.removeReply(rpid = 2, rootRpid = null)
        assertEquals(emptyList<Long>(), out.topReplies.map { it.rpid })
        assertEquals(listOf(3L), out.comments.items.map { it.rpid })
        assertEquals(false, out.expandedSubReplies.containsKey(2L))
    }

    @Test
    fun `删楼中楼：预览和展开列表都去掉，回复数减一`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 2, replies = listOf(r(8), r(9))))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(8), r(9)))),
        )
        val out = state.removeReply(rpid = 9, rootRpid = 2)
        assertEquals(1, out.comments.items.single().rcount)
        assertEquals(listOf(8L), out.comments.items.single().replies!!.map { it.rpid })
        assertEquals(listOf(8L), out.expandedSubReplies.getValue(2).items.map { it.rpid })
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: 编译失败，`Unresolved reference: canDeleteReply`。

- [ ] **Step 3: 实现纯函数**（追加到 `CommentListPolicy.kt`）

```kotlin
/** 只能删自己的评论（UP 删别人评论的权限不做）。 */
fun canDeleteReply(reply: ReplyItem, myMid: Long): Boolean = myMid > 0 && reply.mid == myMid

/**
 * 删除成功后从本地移除。`rootRpid == null`：一级评论，置顶 / 列表里去掉，它展开的楼中楼一并丢掉；
 * 否则是楼中楼：所属一级评论的预览和展开列表里去掉，回复数 -1。
 */
fun CommentThreadState.removeReply(rpid: Long, rootRpid: Long?): CommentThreadState {
    if (rootRpid == null) {
        return copy(
            topReplies = topReplies.filterNot { it.rpid == rpid },
            comments = comments.copy(items = comments.items.filterNot { it.rpid == rpid }),
            expandedSubReplies = expandedSubReplies - rpid,
        )
    }
    val patched = patchReply(rootRpid) { root ->
        root.copy(
            rcount = (root.rcount - 1).coerceAtLeast(0),
            replies = root.replies?.filterNot { it.rpid == rpid },
        )
    }
    val expanded = patched.expandedSubReplies[rootRpid] ?: return patched
    return patched.copy(
        expandedSubReplies = patched.expandedSubReplies + (rootRpid to expanded.copy(items = expanded.items.filterNot { it.rpid == rpid })),
    )
}
```

- [ ] **Step 4: 跑单测确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.comment.CommentListPolicyTest"`
Expected: PASS。

- [ ] **Step 5: 网络层接口**

`BiliApi.kt` 在 `replyAction` 声明后面加：

```kotlin
    @FormUrlEncoded
    @POST("x/v2/reply/del")
    suspend fun deleteReply(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>  // oid, type, rpid, csrf
```

`BiliRepository.kt` 在 `replyAction()` 后面加：

```kotlin
    /** 删除自己的评论。`oid`/`type` 语义同 [comments]。 */
    suspend fun deleteReply(oid: Long, type: Int, rpid: Long): Result<Unit> =
        writeRequest(
            payload = mapOf("oid" to oid.toString(), "type" to type.toString(), "rpid" to rpid.toString()),
        ) { f, _ -> api.deleteReply(f) }.map { }
```

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: PASS（若有反射检查写接口签名的测试，如 `SendMessageFieldBuildTest`，一并通过）。

- [ ] **Step 6: 控制器**

`CommentThreadController` 构造参数在 `onPostFailed` 后加：

```kotlin
    private val onDeleted: (CommentThreadState) -> Unit = {},
```

   KDoc 里「通过 [onPostStarted] / [onPostFailed] 交给宿主」改成「通过 [onPostStarted] / [onPostFailed] / [onDeleted] 交给宿主」。

   `likeRequestGate` 下面加：

```kotlin
    /** 正在删除的 rpid，防连点重复请求。 */
    private val deleting = mutableSetOf<Long>()
```

   `submit()` 前面加：

```kotlin
    /** 删除自己的评论（界面上已二次确认）。成功后本地移除，不重拉。`rootRpid` 非 null 表示楼中楼。 */
    fun delete(reply: ReplyItem, rootRpid: Long?) {
        val cur = _state.value ?: return
        if (!session.value.isLoggedIn || !deleting.add(reply.rpid)) return
        scope.launch {
            repository.deleteReply(cur.oid, cur.type, reply.rpid)
                .onSuccess {
                    _state.value = _state.value?.takeIf { it.sameThreadAs(cur) }
                        ?.removeReply(reply.rpid, rootRpid) ?: _state.value
                    onDeleted(cur)
                    emitMessage("已删除")
                }
                .onFailure { err -> emitMessage("删除失败：${err.message ?: "未知错误"}") }
            deleting.remove(reply.rpid)
        }
    }
```

`DynamicFeedController.kt` 第 52–53 行之后加一行：

```kotlin
        onDeleted = { s -> patchCommentCount(s.key, -1) },
```

- [ ] **Step 7: 界面**

`DynamicCommentDialog.kt`：

1. `DynamicCommentDialog` 参数在 `onOpenUser` 前加：

```kotlin
    /** 当前登录用户 mid；0 = 未登录（不显示删除）。 */
    myMid: Long = 0,
    onDelete: (ReplyItem, Long?) -> Unit = { _, _ -> },
```

2. 函数体 `val actions = ...` 下面加确认弹窗状态；在 `ModalBottomSheet { Column { ... } }` 的 `Column` 结束后（仍在 `ModalBottomSheet` 内容里）加弹窗：

```kotlin
    var pendingDelete by remember { mutableStateOf<Pair<ReplyItem, Long?>?>(null) }
```

```kotlin
        pendingDelete?.let { (reply, root) ->
            BsConfirmDialog(
                title = "删除评论",
                text = "删除后不能恢复。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    pendingDelete = null
                    onDelete(reply, root)
                },
                onDismiss = { pendingDelete = null },
            )
        }
```

   两处 `CommentItemRow(...)` 调用都加：

```kotlin
                            myMid = myMid,
                            onDelete = { r, root -> pendingDelete = r to root },
```

3. `CommentItemRow` 参数加 `myMid: Long,`、`onDelete: (ReplyItem, Long?) -> Unit,`；其中 `CommentActionRow(...)` 调用加 `onDelete = if (canDeleteReply(reply, myMid)) ({ onDelete(reply, null) }) else null,`；`SubReplyRow(...)` 调用加 `onDelete = if (canDeleteReply(sub, myMid)) ({ onDelete(sub, reply.rpid) }) else null,`。
4. `SubReplyRow` 参数加 `onDelete: (() -> Unit)?,`，内部 `CommentActionRow(reply = sub, onReply = onReply, onLike = onLike, onDelete = onDelete)`。
5. `CommentActionRow` 参数加 `onDelete: (() -> Unit)? = null,`，在「回复」按钮前加：

```kotlin
        if (onDelete != null) {
            BsIconAction(
                icon = Icons.Outlined.Delete,
                label = "删除",
                onClick = onDelete,
                showLabelWhenEmpty = false,
            )
        }
```

6. import 加：

```kotlin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.biliseek.app.comment.canDeleteReply
import com.biliseek.designsystem.component.BsConfirmDialog
```

`CommentThreadSheet.kt`：参数加 `myMid: Long,`，`DynamicCommentDialog(...)` 里加 `myMid = myMid,` 和 `onDelete = controller::delete,`。

`MainActivity.kt` 的 `CommentThreadSheet(...)` 调用加：

```kotlin
                        myMid = session.dedeUserId.toLongOrNull() ?: 0L,
```

（`session` 已在第 209 行 `val session by viewModel.session.collectAsState()`。）

- [ ] **Step 8: UI 测试**（追加到 `DynamicCommentDialogUiTest`；`setDialog` 加参数 `myMid: Long = 0, onDelete: (ReplyItem, Long?) -> Unit = { _, _ -> }` 并传给 `DynamicCommentDialog`）

   「删除」既是行内按钮的 contentDescription，又是确认按钮的文字，所以点 `onAllNodesWithText("删除")` 的最后一个（确认按钮）：

```kotlin
    @Test
    fun 只有自己的评论能删且要先确认() {
        var deleted: ReplyItem? = null
        setDialog(state(), myMid = 20, onDelete = { r, _ -> deleted = r })

        // 只有「用户B」（mid=20）那条有删除
        check(compose.onAllNodesWithContentDescription("删除").fetchSemanticsNodes().size == 1)
        compose.onNodeWithContentDescription("删除").performClick()
        check(deleted == null) { "未确认前不应删除" }
        compose.onNodeWithText("删除后不能恢复。").assertIsDisplayed()
        val confirm = compose.onAllNodesWithText("删除")
        confirm[confirm.fetchSemanticsNodes().lastIndex].performClick()
        check(deleted?.rpid == 2L) { "确认后应回调删除" }
    }
```

- [ ] **Step 9: 编译与单测**

Run: `./gradlew :core:network:testDebugUnitTest :app:testDebugUnitTest && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: 全部通过。

- [ ] **Step 10: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliApi.kt core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt \
  app/src/main/java/com/biliseek/app/comment/ app/src/test/java/com/biliseek/app/comment/ \
  app/src/main/java/com/biliseek/app/dynamic/DynamicFeedController.kt \
  app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt \
  app/src/main/java/com/biliseek/app/MainActivity.kt app/src/androidTest/java/com/biliseek/app/DynamicCommentDialogUiTest.kt
git commit -m "feat(comment): 可以删除自己的评论（二次确认，成功后本地移除）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 带图评论（缩略图 + 弹层内全屏预览）

**要点：** 评论弹层是 `ModalBottomSheet`，有自己的窗口；`MainActivity` 里那个全局 `ImagePreviewDialog` 画在主窗口，会被弹层挡住。所以评论里的图在弹层内部用 `Dialog` 窗口打开预览（盖在弹层上面），复用 `ImagePreviewDialog` 本体。

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt`、`app/src/main/java/com/biliseek/app/MainActivity.kt`

**Interfaces:**
- Consumes: `ReplyContent.pictures`（Task 1）、`DrawGrid(pics: List<String>, onImageClick: (Int) -> Unit, modifier)`、`ImagePreviewDialog(pics, initialIndex, onDismiss, onSaveImage)`。
- Produces: `DynamicCommentDialog` 新增参数 `onSaveImage: (String) -> Unit = {}`；`CommentThreadSheet` 新增参数 `onSaveImage: (String) -> Unit`；`internal fun ReplyContent.pictureUrls(): List<String>`。

- [ ] **Step 1: 实现**

`DynamicCommentDialog.kt`：

1. 参数在 `onOpenUser` 前加 `onSaveImage: (String) -> Unit = {},`。
2. `pendingDelete` 下面加：

```kotlin
    var preview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val openImages: (List<String>, Int) -> Unit = { pics, index -> preview = pics to index }
```

   在 `pendingDelete?.let { ... }` 后面（仍在 `ModalBottomSheet` 内容里）加：

```kotlin
        // 弹层有自己的窗口，主窗口里的全局图片预览会被挡住；这里另开一个 Dialog 窗口盖在弹层上
        preview?.let { (pics, index) ->
            Dialog(
                onDismissRequest = { preview = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                ImagePreviewDialog(
                    pics = pics,
                    initialIndex = index,
                    onDismiss = { preview = null },
                    onSaveImage = onSaveImage,
                )
            }
        }
```

3. 两处 `CommentItemRow(...)` 调用加 `onOpenImages = openImages,`；`CommentItemRow` 参数加 `onOpenImages: (List<String>, Int) -> Unit,`，在一级评论 `RichText(...)` 之后加：

```kotlin
            CommentPictures(reply, onOpenImages)
```

   `SubReplyRow(...)` 调用加 `onOpenImages = onOpenImages,`；`SubReplyRow` 参数加 `onOpenImages: (List<String>, Int) -> Unit,`，在它的 `RichText(...)` 之后加 `CommentPictures(sub, onOpenImages)`。

4. 文件里加：

```kotlin
/** 评论图片：用动态同款九宫格，点开全屏预览。没有图什么都不画。 */
@Composable
private fun CommentPictures(reply: ReplyItem, onOpenImages: (List<String>, Int) -> Unit) {
    val pics = reply.content.pictureUrls()
    if (pics.isEmpty()) return
    DrawGrid(
        pics = pics,
        onImageClick = { index -> onOpenImages(pics, index) },
        modifier = Modifier.padding(top = BsSpacing.xs),
    )
}

/** 评论图片地址（去掉空串）。 */
internal fun com.biliseek.core.network.dto.ReplyContent.pictureUrls(): List<String> =
    pictures.orEmpty().map { it.imgSrc }.filter { it.isNotBlank() }
```

5. import 加：

```kotlin
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
```

`CommentThreadSheet.kt`：参数加 `onSaveImage: (String) -> Unit,`，`DynamicCommentDialog(...)` 加 `onSaveImage = onSaveImage,`。

`MainActivity.kt` 的 `CommentThreadSheet(...)` 调用加：

```kotlin
                        onSaveImage = viewModel::saveImageToAlbum,
```

- [ ] **Step 2: 编译与全量单测**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin && ./gradlew test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt \
  app/src/main/java/com/biliseek/app/ui/components/CommentThreadSheet.kt app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(comment): 显示评论里的图片，点开在弹层上全屏预览

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 文档与最终验证

**Files:**
- Modify: `docs/05-功能优化篇.md`（§13 末尾）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 29「动态」表格之后加一节）
- Modify: `docs/03-架构设计篇.md:112`

- [ ] **Step 1: `docs/05-功能优化篇.md` 末尾追加**

```markdown
### 13.76 评论区完善：置顶、草稿、翻页排序、楼中楼操作、删除、图片（2026-10-23）

- **置顶**：`ReplyData` 原先只解析 `page` / `replies`，置顶评论在 `top_replies` / `top.upper|admin|vote` / `upper.top` 里被丢掉。现在合并去重后放在列表最前，标「置顶」；普通列表里同一条去掉（`CommentPagePolicy.collectTopReplies` / `mergeCommentPage`）。
- **翻页与排序**：`wbi/main` 不认 `pn/sort`、也不返回 `page.count`，以前只能看到第一页、热度/时间切换不生效。改成 `mode`（3 热度 / 2 时间）+ `pagination_str` 游标，`cursor.is_end` 或缺 `next_offset` 即到底（`CommentPagePolicy`）。等结果期间换排序 / 换评论区，旧结果丢弃。楼中楼仍按页码。
- **草稿**：关掉评论弹层时，没发出去的内容连同「回复 @xxx」按评论区（oid+type）记在内存里，再进来填回；发送成功删除；App 被杀即清空（`CommentDraftPolicy`）。
- **楼中楼**：每条也有回复 / 点赞；点赞按 rpid 同步到置顶、列表、预览、展开列表里所有出现的位置（`CommentListPolicy.patchReply`）。
- **发出后直接显示**：不再重拉第一页，用接口回的 rpid 在本地插一条（一级评论放最前，回复放进所属楼中楼并回复数 +1）；没回 rpid 时退回重拉。
- **UP 标识**：作者本人（`upper.mid`）的评论昵称后标「UP」。
- **删除**：自己的评论有「删除」，二次确认后调 `x/v2/reply/del`（`writeRequest`），成功本地移除；动态卡片评论数 -1。
- **图片**：解析 `content.pictures`，用 `DrawGrid` 显示；点开在弹层内另开 `Dialog` 窗口预览（主窗口的全局预览会被弹层挡住）。
- **结构**：`DynamicCommentDialog` 改为直接吃 `CommentThreadState`；新增 `CommentThreadSheet` 把控制器接到弹层，`MainActivity` 里两段相同的挂载合成一处。
- **验证**：`ReplyDtoTest` / `CommentPagePolicyTest` / `CommentListPolicyTest` / `CommentDraftPolicyTest` 离线单测；`DynamicCommentDialogUiTest` 已更新（需设备）。**翻页、排序、置顶字段、删除接口待真机确认。**
```

- [ ] **Step 2: `docs/BiliSeek-手动测试指南.md`**：在「步骤 29 — 动态」那一节的表格之后加：

```markdown
#### 评论区（动态评论与播放页评论共用，2026-10-23）

| 操作 | 期望 |
|---|---|
| 打开有 UP 置顶评论的视频评论区 | 第一条是置顶评论，昵称后有「置顶」；下面普通列表里不再重复出现这条 |
| 评论很多的视频，一直往下滑 | 能持续加载出新评论，没有重复；到底后不再转圈 |
| 切「时间」/「热度」 | 列表顺序明显变化（时间：最新在前） |
| 输入一半关掉弹层，再打开同一个评论区 | 输入框里还是刚才的内容；若当时在回复某人，「回复 @xxx」也还在 |
| 换到另一个视频 / 动态的评论区 | 看不到上一个评论区的草稿 |
| 发送成功后再关掉重开 | 输入框是空的 |
| 楼中楼里点「回复」/「点赞」 | 输入框上方显示「回复 @楼中楼作者」；点赞数 +1 且变色 |
| 发一条一级评论（热度排序下） | 发出后立刻出现在置顶下面第一条，不用刷新 |
| 回复一条评论 | 回复出现在该评论的楼中楼里，「查看全部 N 条回复」的 N +1 |
| 作者本人的评论 / 楼中楼 | 昵称后有「UP」 |
| 自己的评论点「删除」 | 先弹「删除评论 / 删除后不能恢复。」；确认后这条消失，提示「已删除」；别人的评论没有删除按钮 |
| 带图评论 | 正文下有缩略图；点开全屏预览盖在评论弹层上面，可左右滑、保存；返回回到评论弹层 |
```

- [ ] **Step 3: `docs/03-架构设计篇.md` 第 112 行**

`- 评论读取降级（`CommentReadPolicy`）、凭据来源选择（`CredentialSourcePolicy`）。` 改成：

```markdown
- 评论读取降级（`CommentReadPolicy`）、评论游标翻页与置顶合并（`CommentPagePolicy`）、凭据来源选择（`CredentialSourcePolicy`）。
```

- [ ] **Step 4: 最终验证**

```bash
./gradlew test assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
grep -rn "repository.comments(" app/src/main --include='*.kt'   # 只剩控制器里 (oid, type, sort, offset) 一处
```

Expected: 全部 BUILD SUCCESSFUL；不装机。

- [ ] **Step 5: 提交**

```bash
git add docs/05-功能优化篇.md docs/BiliSeek-手动测试指南.md docs/03-架构设计篇.md docs/superpowers/plans/2026-10-23-comment-improvements.md
git commit -m "docs(comment): 记录评论区完善（§13.76）与手动测试项

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 真机验证清单（交给用户，自动化不碰 B 站）

1. 翻页 / 排序：`wbi/main` 用 `mode + pagination_str` 后能否翻到第二页、时间排序是否生效（若不行，看 `BiliSeekReadDebug` 日志里的回码）。
2. 置顶：`top_replies` / `upper.top` 至少一个有值，置顶评论出现。
3. 删除：`x/v2/reply/del` 回 0；动态评论区删除后卡片评论数 -1。
4. 图片预览：`Dialog` 盖在弹层上，返回键先关预览再关弹层；「已保存到相册」提示可见。
