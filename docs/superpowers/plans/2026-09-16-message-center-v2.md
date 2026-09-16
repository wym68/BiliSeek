# 消息中心 v2（图标分页导航 + DTO 修正）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把消息中心的「回复与@/收到喜欢/新增粉丝」从内嵌 TabRow 改成三个独立 push 子页面（对齐真实 B 站导航），改成懒加载，并修正 `ReplyAtItem`/`LikeItem` DTO 的嵌套结构错误（评论正文/跳转字段读到空值的根因）。

**Architecture:** 自底向上：先改 DTO（`core/network/dto/MessageDto.kt`），再改 Repository 的拉取方法（`BiliRepository.kt`），再改 `AppViewModel` 的状态与信号，再接 `AppShell` 的三个新 push destination，再重写/新增 4 个 Compose 屏幕，最后在 `MainActivity.kt` 里把所有东西接起来。每层改完立刻编译/测试验证，不堆到最后一起排错。

**Tech Stack:** Kotlin 2.4.10、Jetpack Compose + Material3、navigation-compose、kotlinx.serialization、Retrofit 3、JUnit 4（`./gradlew :core:network:test` / `:app:testDebugUnitTest`）。

## Global Constraints

- **不引入新依赖**，不改 `core/player`、`design-system` 模块（设计文档 §5）。
- **自动化代码严禁触发真实 B 站网络请求**——本计划里所有可执行的测试步骤都是纯本地 JVM 单测（DTO 反序列化 / 数据类计算属性），不允许写会真的发 HTTP 请求的测试。网络行为最终由人工在真机验证（AGENTS.md §2.3）。
- **图标只能用 `material-icons-core` 里已确认存在的图标**（项目未引入 `material-icons-extended`）：本计划用到的 `Icons.Filled.Email`/`Icons.Filled.ThumbUp`/`Icons.Filled.Person`/`Icons.AutoMirrored.Filled.ArrowBack` 均已在 core 包里核实存在，不要替换成其他未经验证的图标名。
- **子页面的 `onInit` 只能接纯加载函数**，不能接同时"发导航信号 + 加载"的入口函数（`openXxxFeed()`）——这是 v1 已经踩过的导航死循环坑，Task 3 的每个 `open*()` 函数体只允许一行 `tryEmit`，不允许在里面调用对应的 `load*()`。
- 每个 Task 结束都要跑一次对应模块的编译（`:core:network:compileDebugKotlin` 或 `:app:compileDebugKotlin`）确认不破坏其它代码。

---

### Task 1: DTO 层重写（`core/network/dto/MessageDto.kt`）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/MessageDto.kt`
- Test: `core/network/src/test/java/com/biliseek/core/network/MessageDtoParseTest.kt`

**Interfaces:**
- Consumes: 无（最底层）
- Produces（后续任务依赖的确切类型/属性名）：
  - `MsgfeedUser(mid: Long, name: String, face: String)`（`@SerialName` 分别是 `mid`/`nickname`/`avatar`）
  - `ReplyAtItem(id: Long, user: MsgfeedUser?, item: ReplyAtContent?, replyTime: Long)`，计算属性 `content: String`、`businessId: Int`、`sourceId: Long`、`sourceContent: String`、`rootId: Long`、`parentId: Long`
  - `ReplyFeedData(items: List<ReplyAtItem>, cursor: MsgfeedCursor?)`，计算属性 `hasMore: Boolean`
  - `LikeItem(id: Long, users: List<MsgfeedUser>, item: LikeContent?, likeTime: Long)`，计算属性 `firstUser: MsgfeedUser?`、`title: String`、`sourceId: Long`、`businessId: Int`
  - `LikeFeedData(latest: LikeFeedBucket?)`，计算属性 `items: List<LikeItem>`、`hasMore: Boolean`
  - `FollowerEntry`/`FollowersData`/`Session`/`SessionListData`/`PrivateMessage`/`MessageHistoryData`/`SendMessageData`/`MsgfeedUnreadData`/`SessionUnreadData` **保持不变**（字段名、类型都不动）

- [ ] **Step 1: 改测试文件里"回复与@ 列表解析"和"收到喜欢列表解析"两个用例，换成真实嵌套结构的 JSON**

打开 `core/network/src/test/java/com/biliseek/core/network/MessageDtoParseTest.kt`，把这两个 `@Test` 函数整体替换成：

```kotlin
    @Test
    fun `回复与@ 列表解析`() {
        val data = json.decodeFromString<BiliResponse<ReplyFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "items": [
                  {
                    "id": 11,
                    "user": { "mid": 222, "nickname": "用户A", "avatar": "//fa" },
                    "reply_time": 1700000000,
                    "item": {
                      "root_id": 5,
                      "target_id": 6,
                      "source_id": 1000,
                      "business_id": 1,
                      "source_content": "原视频标题",
                      "root_reply_content": "根评论内容",
                      "target_reply_content": "评论内容"
                    }
                  }
                ],
                "cursor": { "id": 11, "time": 1700000000, "is_end": false }
              }
            }
            """.trimIndent(),
        )
        val items = data.data?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals(1000L, items[0].sourceId)
        assertEquals(5L, items[0].rootId)
        assertEquals(6L, items[0].parentId)
        assertEquals("用户A", items[0].user?.name)
        assertEquals("//fa", items[0].user?.face)
        assertEquals("评论内容", items[0].content)
        assertEquals(true, data.data?.hasMore)
    }

    @Test
    fun `回复与@ 正文优先取 target_reply_content，取不到退回 root_reply_content`() {
        val data = json.decodeFromString<BiliResponse<ReplyFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "items": [
                  {
                    "id": 12,
                    "user": { "mid": 223, "nickname": "用户B", "avatar": "//fb" },
                    "reply_time": 1700000001,
                    "item": {
                      "root_id": 1,
                      "target_id": 2,
                      "source_id": 1001,
                      "business_id": 1,
                      "source_content": "",
                      "root_reply_content": "根评论内容",
                      "target_reply_content": ""
                    }
                  }
                ],
                "cursor": { "id": 12, "time": 1700000001, "is_end": true }
              }
            }
            """.trimIndent(),
        )
        assertEquals("根评论内容", data.data?.items?.first()?.content)
        assertEquals(false, data.data?.hasMore)
    }

    @Test
    fun `收到喜欢列表解析`() {
        val data = json.decodeFromString<BiliResponse<LikeFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "latest": {
                  "items": [
                    {
                      "id": 22,
                      "users": [
                        { "mid": 333, "nickname": "用户C", "avatar": "//fc" }
                      ],
                      "item": { "source_id": 2000, "business_id": 1, "title": "被点赞视频" },
                      "like_time": 1700000000
                    }
                  ],
                  "cursor": { "id": 22, "time": 1700000000, "is_end": true }
                }
              }
            }
            """.trimIndent(),
        )
        val items = data.data?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals(2000L, items[0].sourceId)
        assertEquals("被点赞视频", items[0].title)
        assertEquals("用户C", items[0].firstUser?.name)
        assertEquals(false, data.data?.hasMore)
    }
```

同时把文件顶部的 import 列表里加上：

```kotlin
import com.biliseek.core.network.dto.ReplyFeedData
import com.biliseek.core.network.dto.LikeFeedData
```

（`FollowersData`/`MsgfeedUnreadData`/`SessionUnreadData`/`SessionListData`/`MessageHistoryData` 的 import 已经存在，不用动。原来测试里 `ReplyAtItem`/`LikeItem` 是通过 `ReplyFeedData`/`LikeFeedData` 间接用到的，本来就没有单独 import 这两个 item 类型，不用改。)

- [ ] **Step 2: 跑测试，确认新用例编译失败（因为 DTO 还没改）**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.MessageDtoParseTest"`
Expected: 编译错误（`Unresolved reference` 之类），因为 `ReplyAtItem`/`ReplyFeedData`/`LikeItem`/`LikeFeedData` 还是旧的扁平结构，新测试里用到的 `item.sourceId`/`item.parentId`/`data.hasMore`（计算属性）/`item.firstUser` 目前还是普通字段或根本不存在，类型对不上。

- [ ] **Step 3: 重写 `MessageDto.kt` 的互动消息部分**

打开 `core/network/src/main/java/com/biliseek/core/network/dto/MessageDto.kt`，把第 71~154 行（从 `// ───────────────── 互动消息` 注释开始，到文件末尾 `FollowersData` 结束）整体替换成：

```kotlin
// ───────────────── 互动消息（api.bilibili.com x/msgfeed）─────────────────

/** 回复/@/点赞通知里的用户信息。真实字段名是 nickname/avatar，不是 name/face。 */
@Serializable
data class MsgfeedUser(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("nickname") val name: String = "",
    @SerialName("avatar") val face: String = "",
)

/** msgfeed 分页游标：`is_end=true` 表示没有更多了。 */
@Serializable
data class MsgfeedCursor(
    @SerialName("id") val id: Long = 0,
    @SerialName("time") val time: Long = 0,
    @SerialName("is_end") val isEnd: Boolean = false,
)

/** `x/msgfeed/reply` 单条业务内容，真实响应嵌在 item.item 里，不是平铺在顶层。 */
@Serializable
data class ReplyAtContent(
    @SerialName("root_id") val rootId: Long = 0,
    @SerialName("target_id") val targetId: Long = 0,
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("source_content") val sourceContent: String = "",
    @SerialName("root_reply_content") val rootReplyContent: String = "",
    @SerialName("target_reply_content") val targetReplyContent: String = "",
)

/** `x/msgfeed/reply` 单条（回复与@ tab）。 */
@Serializable
data class ReplyAtItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    @SerialName("item") val item: ReplyAtContent? = null,
    @SerialName("reply_time") val replyTime: Long = 0,
) {
    /** 优先取楼中楼回复内容，取不到时退回一级回复内容。 */
    val content: String get() =
        item?.targetReplyContent?.takeIf { it.isNotBlank() } ?: item?.rootReplyContent.orEmpty()
    val businessId: Int get() = item?.businessId ?: 0
    val sourceId: Long get() = item?.sourceId ?: 0
    val sourceContent: String get() = item?.sourceContent.orEmpty()
    val rootId: Long get() = item?.rootId ?: 0
    /** 站内回复评论时用作 `parent`（被回复的具体那条评论）。 */
    val parentId: Long get() = item?.targetId ?: 0
}

@Serializable
data class ReplyFeedData(
    @SerialName("items") val items: List<ReplyAtItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
) {
    val hasMore: Boolean get() = cursor?.isEnd == false
}

/** `x/msgfeed/like` 单条业务内容，嵌在 item.item 里。 */
@Serializable
data class LikeContent(
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("title") val title: String = "",
)

/** `x/msgfeed/like` 单条（收到喜欢 tab）。`users` 是复数——可能多人点赞合并展示，本期只取第一个。 */
@Serializable
data class LikeItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("users") val users: List<MsgfeedUser> = emptyList(),
    @SerialName("item") val item: LikeContent? = null,
    @SerialName("like_time") val likeTime: Long = 0,
) {
    val firstUser: MsgfeedUser? get() = users.firstOrNull()
    val title: String get() = item?.title.orEmpty()
    val sourceId: Long get() = item?.sourceId ?: 0
    val businessId: Int get() = item?.businessId ?: 0
}

@Serializable
data class LikeFeedBucket(
    @SerialName("items") val items: List<LikeItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
)

/** `x/msgfeed/like` 响应按 latest/total 分桶；本期只用 latest（最近点赞），total 桶不解析。 */
@Serializable
data class LikeFeedData(
    @SerialName("latest") val latest: LikeFeedBucket? = null,
) {
    val items: List<LikeItem> get() = latest?.items.orEmpty()
    val hasMore: Boolean get() = latest?.cursor?.isEnd == false
}

/** `x/msgfeed/unread` 返回聚合未读。 */
@Serializable
data class MsgfeedUnreadData(
    @SerialName("at") val at: Int = 0,
    @SerialName("reply") val reply: Int = 0,
    @SerialName("like") val like: Int = 0,
)

/** `session_svr/single_unread` 私信未读聚合。 */
@Serializable
data class SessionUnreadData(
    @SerialName("unfollow_unread") val unfollowUnread: Int = 0,
    @SerialName("follow_unread") val followUnread: Int = 0,
)

// ───────────────── 粉丝列表（x/relation/followers）─────────────────

/** `x/relation/followers` 单条。这一族关系接口是扁平结构，不受本次嵌套修正影响。 */
@Serializable
data class FollowerEntry(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("uname") val uname: String = "",
    @SerialName("face") val face: String = "",
    @SerialName("sign") val sign: String = "",
    @SerialName("mtime") val mtime: Long = 0,
    @SerialName("attribute") val attribute: Int = 0,
)

@Serializable
data class FollowersData(
    @SerialName("list") val list: List<FollowerEntry> = emptyList(),
    @SerialName("total") val total: Int = 0,
)
```

第 1~70 行（私信会话/消息部分：`Session`、`LastMessage`、`SessionPeer`、`SessionListData`、`PrivateMessage`、`MessageHistoryData`、`SendMessageData`）**不动**。

- [ ] **Step 4: 跑测试，确认全部通过**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.MessageDtoParseTest"`
Expected: `BUILD SUCCESSFUL`，7 个测试全绿（原有 5 个 + 新增 2 个）。

- [ ] **Step 5: 编译确认没有破坏其它引用点**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`（此时 `BiliApi.kt`/`BiliRepository.kt` 还没改，但它们只是把 `ReplyFeedData`/`LikeFeedData` 当返回类型传递，不直接访问被改动的内部字段，所以这一步应该照样编译通过）。

- [ ] **Step 6: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/MessageDto.kt \
        core/network/src/test/java/com/biliseek/core/network/MessageDtoParseTest.kt
git commit -m "fix(network): correct msgfeed reply/like DTO nesting to match real API shape

x/msgfeed/reply and x/msgfeed/like responses nest business fields
(content, business_id, source_id...) inside an 'item' sub-object and
use nickname/avatar for user fields, not the flat name/face shape the
DTOs previously assumed. This is why reply text rendered empty on
device despite timestamps parsing correctly."
```

---

### Task 2: Repository 层重写（`BiliRepository.kt`）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt:983-1141`
- Test: `core/network/src/test/java/com/biliseek/core/network/MessageUnreadSummaryTest.kt`（新建）

**Interfaces:**
- Consumes: Task 1 的 `ReplyFeedData`/`LikeFeedData`/`FollowersData`（类型不变，只是内部结构变了，本任务不需要关心内部结构）
- Produces（Task 3 依赖的确切签名）：
  - `suspend fun BiliRepository.privateSessions(): Result<com.biliseek.core.network.dto.SessionListData>`
  - `suspend fun BiliRepository.replyFeed(): Result<com.biliseek.core.network.dto.ReplyFeedData>`
  - `suspend fun BiliRepository.likeFeed(): Result<com.biliseek.core.network.dto.LikeFeedData>`
  - `suspend fun BiliRepository.followerFeed(): Result<com.biliseek.core.network.dto.FollowersData>`
  - `suspend fun BiliRepository.messageUnread(): BiliRepository.MessageUnreadSummary`（原来返回 `Int`，现在返回结构体）
  - `data class BiliRepository.MessageUnreadSummary(reply: Int = 0, at: Int = 0, like: Int = 0, sessionUnread: Int = 0)`，计算属性 `total: Int`、`replyAtBadge: Int`
  - `BiliRepository.messageHub()` 和 `BiliRepository.MessageHub` **被删除**——不要在后续任务里引用它们
  - `loadMoreSessionMessages`/`sendPrivateMessage`/`markSessionRead`/`postCommentReply` **签名不变**

- [ ] **Step 1: 写 `MessageUnreadSummary` 的失败测试**

新建 `core/network/src/test/java/com/biliseek/core/network/MessageUnreadSummaryTest.kt`：

```kotlin
package com.biliseek.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageUnreadSummaryTest {

    @Test
    fun `replyAtBadge 合并 reply 和 at`() {
        val summary = BiliRepository.MessageUnreadSummary(reply = 3, at = 2, like = 5, sessionUnread = 1)
        assertEquals(5, summary.replyAtBadge)
    }

    @Test
    fun `total 是四项之和`() {
        val summary = BiliRepository.MessageUnreadSummary(reply = 3, at = 2, like = 5, sessionUnread = 1)
        assertEquals(11, summary.total)
    }

    @Test
    fun `默认全零`() {
        val summary = BiliRepository.MessageUnreadSummary()
        assertEquals(0, summary.total)
        assertEquals(0, summary.replyAtBadge)
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.MessageUnreadSummaryTest"`
Expected: 编译错误，`BiliRepository.MessageUnreadSummary` 目前还不存在（现在的 `BiliRepository` 里只有 `MessageHub`）。

- [ ] **Step 3: 替换 `messageHub()` 为 4 个独立拉取方法**

打开 `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`，找到第 983~1003 行：

```kotlin
    // ── 消息中心（私信 + 互动消息）──────────────────────────

    /**
     * 并发拉取消息中心四块数据（回复与@ / 收到喜欢 / 新增粉丝 / 私信会话）。
     *
     * 任一块失败不影响其他块——返回的 `MessageHub` 里对应块为 null（UI 显示「该区块加载失败」）。
     * 整块由 `RequestThrottle.MESSAGE` 桶串行化（一次只发一块），避免触发 B 站风控。
     */
    suspend fun messageHub(): Result<MessageHub> = runCatching {
        val mid = requireLoggedInMid()
        authGate.awaitReady()
        val reply = safeCall("回复与@") { api.getMsgfeedReply().data }
        val like = safeCall("收到喜欢") { api.getMsgfeedLike().data }
        val follower = safeCall("粉丝") {
            val resp = api.getFollowers(vmid = mid)
            require(resp.code == 0) { "粉丝列表失败：code=${resp.code} ${resp.message}" }
            resp.data
        }
        val sessions = safeCall("私信会话") { api.getSessions().data }
        MessageHub(reply = reply, like = like, follower = follower, sessions = sessions)
    }
```

整段替换成：

```kotlin
    // ── 消息中心（私信 + 互动消息）──────────────────────────

    /** 私信会话列表。消息中心页面主体，懒加载——独立于三个子页面。 */
    suspend fun privateSessions(): Result<com.biliseek.core.network.dto.SessionListData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = requestThrottle.gate(
            ThrottleBucket.MESSAGE,
            MESSAGE_MIN_INTERVAL_MS,
            THROTTLE_JITTER_MS,
        ) { api.getSessions() }
        require(response.code == 0) { "私信会话加载失败：code=${response.code} ${response.message}" }
        response.data ?: com.biliseek.core.network.dto.SessionListData()
    }

    /** 回复与@ 列表。「回复与@」子页面专用，懒加载——只有用户点开这个子页面才请求。 */
    suspend fun replyFeed(): Result<com.biliseek.core.network.dto.ReplyFeedData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = requestThrottle.gate(
            ThrottleBucket.MESSAGE,
            MESSAGE_MIN_INTERVAL_MS,
            THROTTLE_JITTER_MS,
        ) { api.getMsgfeedReply() }
        require(response.code == 0) { "回复与@加载失败：code=${response.code} ${response.message}" }
        response.data ?: com.biliseek.core.network.dto.ReplyFeedData()
    }

    /** 收到喜欢 列表。「收到喜欢」子页面专用，懒加载。 */
    suspend fun likeFeed(): Result<com.biliseek.core.network.dto.LikeFeedData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = requestThrottle.gate(
            ThrottleBucket.MESSAGE,
            MESSAGE_MIN_INTERVAL_MS,
            THROTTLE_JITTER_MS,
        ) { api.getMsgfeedLike() }
        require(response.code == 0) { "收到喜欢加载失败：code=${response.code} ${response.message}" }
        response.data ?: com.biliseek.core.network.dto.LikeFeedData()
    }

    /** 新增粉丝 列表（`x/relation/followers`）。「新增粉丝」子页面专用，懒加载。 */
    suspend fun followerFeed(): Result<com.biliseek.core.network.dto.FollowersData> = runCatching {
        val mid = requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = requestThrottle.gate(
            ThrottleBucket.MESSAGE,
            MESSAGE_MIN_INTERVAL_MS,
            THROTTLE_JITTER_MS,
        ) { api.getFollowers(vmid = mid) }
        require(response.code == 0) { "新增粉丝加载失败：code=${response.code} ${response.message}" }
        response.data ?: com.biliseek.core.network.dto.FollowersData()
    }
```

- [ ] **Step 4: 改 `messageUnread()` 返回结构化类型，删掉 `MessageHub`/`safeCall`**

在同一个文件里找到第 1111~1141 行（`messageUnread()` 的文档注释开始，到 `safeCall` 函数结束）：

```kotlin
    /**
     * 入口未读小红点：互动未读 + 私信未读。
     * `api.bilibili.com x/msgfeed/unread` + `api.vc.bilibili.com session_svr/single_unread`。
     * 失败时返回 0，UI 不报错（小红点是 best-effort）。
     */
    suspend fun messageUnread(): Int = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val msgfeed = runCatching { api.getMsgfeedUnread().data }.getOrNull()
        val session = runCatching { api.getSessionUnread().data }.getOrNull()
        (msgfeed?.let { it.at + it.reply + it.like } ?: 0) +
            (session?.let { it.unfollowUnread + it.followUnread } ?: 0)
    }.getOrDefault(0)

    /** 把会话列表 + 历史消息 + 互动列表都视为「消息中心」，集中放在一个数据类返回。 */
    data class MessageHub(
        val reply: com.biliseek.core.network.dto.ReplyFeedData?,
        val like: com.biliseek.core.network.dto.LikeFeedData?,
        val follower: com.biliseek.core.network.dto.FollowersData?,
        val sessions: com.biliseek.core.network.dto.SessionListData?,
    )

    /** 包裹一个会 throw 的调用，把异常转成 null（局部错误隔离）。
     *  `block` 在调用处展开，可以调用 suspend 函数。 */
    private inline fun <T> safeCall(label: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "$label 失败：${e.message}")
        null
    }
```

整段替换成：

```kotlin
    /**
     * 入口未读角标聚合：互动未读（回复/@/赞）+ 私信未读。
     * `api.bilibili.com x/msgfeed/unread` + `api.vc.bilibili.com session_svr/single_unread`。
     * 失败时返回全零，UI 不报错（角标是 best-effort）。
     */
    suspend fun messageUnread(): MessageUnreadSummary = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val msgfeed = runCatching { api.getMsgfeedUnread().data }.getOrNull()
        val session = runCatching { api.getSessionUnread().data }.getOrNull()
        MessageUnreadSummary(
            reply = msgfeed?.reply ?: 0,
            at = msgfeed?.at ?: 0,
            like = msgfeed?.like ?: 0,
            sessionUnread = (session?.unfollowUnread ?: 0) + (session?.followUnread ?: 0),
        )
    }.getOrDefault(MessageUnreadSummary())

    /**
     * 入口未读角标的结构化聚合。`total` 喂动态页铃铛；`replyAtBadge`/`like`
     * 分别喂消息中心「回复与@」「收到喜欢」两个图标角标（「新增粉丝」没有未读概念，不带角标）。
     */
    data class MessageUnreadSummary(
        val reply: Int = 0,
        val at: Int = 0,
        val like: Int = 0,
        val sessionUnread: Int = 0,
    ) {
        val total: Int get() = reply + at + like + sessionUnread
        val replyAtBadge: Int get() = reply + at
    }
```

`loadMoreSessionMessages`（1005-1022）、`sendPrivateMessage`（1024-1063）、`markSessionRead`（1065-1083）、`postCommentReply`（1090-1109）三段中间代码**不动**。

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.MessageUnreadSummaryTest"`
Expected: `BUILD SUCCESSFUL`，3 个测试全绿。

- [ ] **Step 6: 编译整个模块**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。此时 `:app` 模块还引用着旧的 `messageHub()`/`MessageHub`/`Int` 类型的 `messageUnread()`，**预期 `:app:compileDebugKotlin` 会失败**——这是正常的，Task 3 会修。不要在这一步尝试编译 `:app`。

- [ ] **Step 7: 提交**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt \
        core/network/src/test/java/com/biliseek/core/network/MessageUnreadSummaryTest.kt
git commit -m "refactor(network): split messageHub() into 4 lazy single-block fetches

Replaces the eager all-4-blocks-at-once messageHub() with independent
privateSessions()/replyFeed()/likeFeed()/followerFeed() so each message
center sub-page can fetch only when opened. messageUnread() now returns
a structured MessageUnreadSummary instead of a pre-summed Int so the
same fetch can feed both the dynamic-page bell badge and the two
message-center icon badges."
```

---

### Task 3: `AppViewModel.kt` 状态与函数重构

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt:2584-2678`（消息中心状态+信号+函数）
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt:2875-2884`（删除 `MessageHubState`）
- Modify: `app/src/test/java/com/biliseek/app/data/MessageUiStateMapperTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `BiliRepository.privateSessions()`/`.replyFeed()`/`.likeFeed()`/`.followerFeed()`/`.messageUnread(): MessageUnreadSummary`
- Produces（Task 5/6/7 依赖的确切名字）：
  - `val privateSessions: StateFlow<RemoteListState<com.biliseek.core.network.dto.Session>>`
  - `val replyFeed: StateFlow<RemoteListState<com.biliseek.core.network.dto.ReplyAtItem>>`
  - `val likeFeed: StateFlow<RemoteListState<com.biliseek.core.network.dto.LikeItem>>`
  - `val followerFeed: StateFlow<RemoteListState<com.biliseek.core.network.dto.FollowerEntry>>`
  - `val unreadSummary: StateFlow<com.biliseek.core.network.BiliRepository.MessageUnreadSummary>`（原 `unreadCount: StateFlow<Int>` 被删除）
  - `fun openMessages()`、`fun openReplyFeed()`、`fun openLikeFeed()`、`fun openFollowerFeed()`——**只 emit 导航信号**
  - `fun retryPrivateSessions()`、`fun retryReplyFeed()`、`fun retryLikeFeed()`、`fun retryFollowerFeed()`——纯加载，`onInit`/`onRetry` 共用
  - `fun refreshUnreadCount()`——签名不变，内部改成写 `MessageUnreadSummary`
  - `val openMessagesRequests: SharedFlow<Unit>`（不变）+ 新增 `val openReplyFeedRequests/openLikeFeedRequests/openFollowerFeedRequests: SharedFlow<Unit>`
  - `openMessages()`/`closeMessages()`/`loadMessageHub()`/`retryMessageHub()`/`MessageHubState` **全部删除**——不要在后续任务里引用

- [ ] **Step 1: 替换消息中心状态与函数主体**

打开 `app/src/main/java/com/biliseek/app/AppViewModel.kt`，找到第 2584~2678 行（从 `// ── 消息中心` 注释开始，到 `refreshUnreadCount()` 函数结束）：

```kotlin
    // ── 消息中心（私信 + 互动消息）──────────────────────────

    /** 消息中心聚合状态：四个 tab 各自的 RemoteListState + 一组会话列表。 */
    private val _messageHub = MutableStateFlow(MessageHubState())
    val messageHub: StateFlow<MessageHubState> = _messageHub.asStateFlow()

    /** 入口未读小红点（best-effort）。 */
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /** 打开消息中心的信号。 */
    private val _openMessagesRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openMessagesRequests: SharedFlow<Unit> = _openMessagesRequests.asSharedFlow()

    /** 进入私信聊天页的信号。 */
    private val _openPrivateChatRequests = MutableSharedFlow<PrivateChatNav>(extraBufferCapacity = 1)
    val openPrivateChatRequests: SharedFlow<PrivateChatNav> = _openPrivateChatRequests.asSharedFlow()

    /** 当前聊天页状态（key = talkerId）。 */
    private val _chatByTalker = MutableStateFlow<Map<Long, PrivateChatState>>(emptyMap())
    val chatByTalker: StateFlow<Map<Long, PrivateChatState>> = _chatByTalker.asStateFlow()

    fun openMessages() {
        if (!session.value.isLoggedIn) {
            _messageHub.value = MessageHubState()
            return
        }
        loadMessageHub()
        _openMessagesRequests.tryEmit(Unit)
    }

    fun closeMessages() { _messageHub.value = MessageHubState() }

    fun retryMessageHub() = loadMessageHub()

    private fun loadMessageHub() {
        _messageHub.value = _messageHub.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            network.repository.messageHub()
                .onSuccess { hub ->
                    val cur = _messageHub.value
                    _messageHub.value = cur.copy(
                        reply = RemoteListState(
                            id = TrackListId.MessageReply,
                            title = "回复与@",
                            items = hub.reply?.items.orEmpty(),
                            hasMore = (hub.reply?.hasMore ?: 0) == 1,
                            isLoading = false,
                            errorMessage = if (hub.reply == null) "回复与@加载失败" else null,
                        ),
                        like = RemoteListState(
                            id = TrackListId.MessageLike,
                            title = "收到喜欢",
                            items = hub.like?.items.orEmpty(),
                            hasMore = (hub.like?.hasMore ?: 0) == 1,
                            isLoading = false,
                            errorMessage = if (hub.like == null) "收到喜欢加载失败" else null,
                        ),
                        follower = RemoteListState(
                            id = TrackListId.MessageFollower,
                            title = "新增粉丝",
                            items = hub.follower?.list.orEmpty(),
                            hasMore = false,
                            isLoading = false,
                            errorMessage = if (hub.follower == null) "新增粉丝加载失败" else null,
                        ),
                        sessions = RemoteListState(
                            id = TrackListId.PrivateSession,
                            title = "私信",
                            items = hub.sessions?.sessionList.orEmpty(),
                            hasMore = (hub.sessions?.hasMore ?: 0) == 1,
                            isLoading = false,
                            errorMessage = if (hub.sessions == null) "私信会话加载失败" else null,
                        ),
                    )
                }
                .onFailure { e ->
                    _messageHub.value = _messageHub.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** 点动态页入口图标 / 列表 tab 切换时调用。best-effort，失败不抛。 */
    fun refreshUnreadCount() {
        if (!session.value.isLoggedIn) {
            _unreadCount.value = 0
            return
        }
        viewModelScope.launch {
            _unreadCount.value = network.repository.messageUnread()
        }
    }
```

整段替换成：

```kotlin
    // ── 消息中心（私信 + 互动消息）──────────────────────────

    /** 消息中心主体：私信会话列表。 */
    private val _privateSessions = MutableStateFlow(
        RemoteListState<com.biliseek.core.network.dto.Session>(id = TrackListId.PrivateSession, title = "私信"),
    )
    val privateSessions: StateFlow<RemoteListState<com.biliseek.core.network.dto.Session>> = _privateSessions.asStateFlow()

    /** 回复与@ 子页面。 */
    private val _replyFeed = MutableStateFlow(
        RemoteListState<com.biliseek.core.network.dto.ReplyAtItem>(id = TrackListId.MessageReply, title = "回复与@"),
    )
    val replyFeed: StateFlow<RemoteListState<com.biliseek.core.network.dto.ReplyAtItem>> = _replyFeed.asStateFlow()

    /** 收到喜欢 子页面。 */
    private val _likeFeed = MutableStateFlow(
        RemoteListState<com.biliseek.core.network.dto.LikeItem>(id = TrackListId.MessageLike, title = "收到喜欢"),
    )
    val likeFeed: StateFlow<RemoteListState<com.biliseek.core.network.dto.LikeItem>> = _likeFeed.asStateFlow()

    /** 新增粉丝 子页面。 */
    private val _followerFeed = MutableStateFlow(
        RemoteListState<com.biliseek.core.network.dto.FollowerEntry>(id = TrackListId.MessageFollower, title = "新增粉丝"),
    )
    val followerFeed: StateFlow<RemoteListState<com.biliseek.core.network.dto.FollowerEntry>> = _followerFeed.asStateFlow()

    /** 入口未读角标：动态页铃铛 + 消息中心两个图标角标共用同一份数据，避免多发请求。 */
    private val _unreadSummary = MutableStateFlow(com.biliseek.core.network.BiliRepository.MessageUnreadSummary())
    val unreadSummary: StateFlow<com.biliseek.core.network.BiliRepository.MessageUnreadSummary> = _unreadSummary.asStateFlow()

    /** 打开消息中心的信号。 */
    private val _openMessagesRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openMessagesRequests: SharedFlow<Unit> = _openMessagesRequests.asSharedFlow()

    /** 打开"回复与@"子页面的信号。 */
    private val _openReplyFeedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openReplyFeedRequests: SharedFlow<Unit> = _openReplyFeedRequests.asSharedFlow()

    /** 打开"收到喜欢"子页面的信号。 */
    private val _openLikeFeedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLikeFeedRequests: SharedFlow<Unit> = _openLikeFeedRequests.asSharedFlow()

    /** 打开"新增粉丝"子页面的信号。 */
    private val _openFollowerFeedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openFollowerFeedRequests: SharedFlow<Unit> = _openFollowerFeedRequests.asSharedFlow()

    /** 进入私信聊天页的信号。 */
    private val _openPrivateChatRequests = MutableSharedFlow<PrivateChatNav>(extraBufferCapacity = 1)
    val openPrivateChatRequests: SharedFlow<PrivateChatNav> = _openPrivateChatRequests.asSharedFlow()

    /** 当前聊天页状态（key = talkerId）。 */
    private val _chatByTalker = MutableStateFlow<Map<Long, PrivateChatState>>(emptyMap())
    val chatByTalker: StateFlow<Map<Long, PrivateChatState>> = _chatByTalker.asStateFlow()

    /**
     * 点铃铛 / 消息中心入口：只发导航信号，不预拉数据——`MessageCenterScreen` 自己的
     * `onInit` 负责加载私信列表（懒加载策略）。**不要**在这里调用 `loadPrivateSessions()`，
     * 会重蹈 v1 的导航死循环（`onInit` 接到同时发信号又加载的函数 → 每次进页面重发信号 →
     * 重新 push → 重新触发 onInit → 无限循环）。
     */
    fun openMessages() { _openMessagesRequests.tryEmit(Unit) }

    /** 点"回复与@"图标：只发导航信号，子页面自己的 onInit 负责加载。 */
    fun openReplyFeed() { _openReplyFeedRequests.tryEmit(Unit) }

    /** 点"收到喜欢"图标：只发导航信号，子页面自己的 onInit 负责加载。 */
    fun openLikeFeed() { _openLikeFeedRequests.tryEmit(Unit) }

    /** 点"新增粉丝"图标：只发导航信号，子页面自己的 onInit 负责加载。 */
    fun openFollowerFeed() { _openFollowerFeedRequests.tryEmit(Unit) }

    /** 消息中心页 onInit / 下拉刷新共用。 */
    fun retryPrivateSessions() = loadPrivateSessions()

    private fun loadPrivateSessions() {
        if (!session.value.isLoggedIn) {
            _privateSessions.value = _privateSessions.value.copy(items = emptyList(), errorMessage = null, isLoading = false)
            return
        }
        _privateSessions.value = _privateSessions.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            network.repository.privateSessions()
                .onSuccess { data ->
                    _privateSessions.value = _privateSessions.value.copy(
                        items = data.sessionList,
                        hasMore = data.hasMore == 1,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                .onFailure { e ->
                    _privateSessions.value = _privateSessions.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** "回复与@"子页面 onInit / 下拉刷新共用。 */
    fun retryReplyFeed() = loadReplyFeed()

    private fun loadReplyFeed() {
        _replyFeed.value = _replyFeed.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            network.repository.replyFeed()
                .onSuccess { data ->
                    _replyFeed.value = _replyFeed.value.copy(
                        items = data.items,
                        hasMore = data.hasMore,
                        isLoading = false,
                        errorMessage = null,
                    )
                    refreshUnreadCount()
                }
                .onFailure { e ->
                    _replyFeed.value = _replyFeed.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** "收到喜欢"子页面 onInit / 下拉刷新共用。 */
    fun retryLikeFeed() = loadLikeFeed()

    private fun loadLikeFeed() {
        _likeFeed.value = _likeFeed.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            network.repository.likeFeed()
                .onSuccess { data ->
                    _likeFeed.value = _likeFeed.value.copy(
                        items = data.items,
                        hasMore = data.hasMore,
                        isLoading = false,
                        errorMessage = null,
                    )
                    refreshUnreadCount()
                }
                .onFailure { e ->
                    _likeFeed.value = _likeFeed.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** "新增粉丝"子页面 onInit / 下拉刷新共用。 */
    fun retryFollowerFeed() = loadFollowerFeed()

    private fun loadFollowerFeed() {
        _followerFeed.value = _followerFeed.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            network.repository.followerFeed()
                .onSuccess { data ->
                    _followerFeed.value = _followerFeed.value.copy(
                        items = data.list,
                        hasMore = false,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                .onFailure { e ->
                    _followerFeed.value = _followerFeed.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载失败",
                    )
                }
        }
    }

    /** 入口未读角标：动态页铃铛 / 消息中心两个图标共用，best-effort，失败不抛。 */
    fun refreshUnreadCount() {
        if (!session.value.isLoggedIn) {
            _unreadSummary.value = com.biliseek.core.network.BiliRepository.MessageUnreadSummary()
            return
        }
        viewModelScope.launch {
            _unreadSummary.value = network.repository.messageUnread()
        }
    }
```

第 2680 行开始的 `openPrivateChat`/`openChat`/`loadMoreChat`/`loadChatPage`/`updateChatDraft`/`sendChatMessage`/`closeChat`/`playReplyAtItem`/`playLikeItem`/`openFollowerSpace`/`submitCommentReply`/`ensureChatState`/`updateChat`/`escapeForLocal` **全部不动**——它们已经用的是 `ReplyAtItem.businessId`/`.sourceId`/`.rootId`/`.parentId` 这些属性名，Task 1 把这些名字保留成了计算属性，这些函数不需要改一个字。

- [ ] **Step 2: 删除 `MessageHubState` 数据类**

在同一个文件里找到第 2875~2884 行：

```kotlin
    /** 消息中心四个 tab 状态聚合。 */
    data class MessageHubState(
        val reply: RemoteListState<com.biliseek.core.network.dto.ReplyAtItem> = RemoteListState(id = TrackListId.MessageReply, title = "回复与@"),
        val like: RemoteListState<com.biliseek.core.network.dto.LikeItem> = RemoteListState(id = TrackListId.MessageLike, title = "收到喜欢"),
        val follower: RemoteListState<com.biliseek.core.network.dto.FollowerEntry> = RemoteListState(id = TrackListId.MessageFollower, title = "新增粉丝"),
        val sessions: RemoteListState<com.biliseek.core.network.dto.Session> = RemoteListState(id = TrackListId.PrivateSession, title = "私信"),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

```

整段删除（含末尾空行），保留上面的 `// ── 状态数据类 ─────────────────────────────────` 注释和下面的 `/** 聊天页状态。 */ data class PrivateChatState(...)` 不动。

- [ ] **Step 3: 更新单测——删掉测 `MessageHubState` 的用例**

打开 `app/src/test/java/com/biliseek/app/data/MessageUiStateMapperTest.kt`，删掉这个测试函数（`MessageHubState` 已经不存在了）：

```kotlin
    @Test
    fun `MessageHubState 默认四态为空`() {
        val s = AppViewModel.MessageHubState()
        assertEquals(0, s.reply.items.size)
        assertEquals(0, s.like.items.size)
        assertEquals(0, s.follower.items.size)
        assertEquals(0, s.sessions.items.size)
        assertNull(s.errorMessage)
    }

```

`PrivateChatState 默认无草稿无消息` 和 `PrivateChatNav 携带 talker 与昵称` 两个测试保留不动。删除后如果 `assertNull` import 因为没有其它用例用到而变成未使用的 import，一并删掉 `import org.junit.Assert.assertNull` 这一行。

- [ ] **Step 4: 编译 `:app` 模块**

Run: `./gradlew :app:compileDebugKotlin`
Expected: **预期会失败**——`AppShell.kt`/`MainActivity.kt`/`MessageCenterScreen.kt` 还在引用旧的 `viewModel.messageHub`/`viewModel.unreadCount`/`viewModel.retryMessageHub` 等已删除的符号。这一步只是确认 `AppViewModel.kt` 自身没有语法错误，看报错信息应该全部指向 `AppShell.kt`/`MainActivity.kt`/`MessageCenterScreen.kt` 里的引用，不应该有指向 `AppViewModel.kt` 自身的报错。Task 4~7 会依次修完。

- [ ] **Step 5: 跑单测确认新增/保留的用例通过（先只跑不依赖编译整个 UI 层的 module 测试）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.MessageUiStateMapperTest"`
Expected: 因为 Step 4 里 `:app:compileDebugKotlin` 还过不了，这一步现在也会失败（同一个模块）。**这是预期的、暂时的失败**——先不要在这一步纠结，继续做 Task 4~7，等 Task 7 把所有引用点都接上之后回头一次性跑通整个 `:app:testDebugUnitTest`（Task 7 Step 的最后会专门验证这个）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/biliseek/app/AppViewModel.kt \
        app/src/test/java/com/biliseek/app/data/MessageUiStateMapperTest.kt
git commit -m "refactor(app): split AppViewModel message-hub state into per-page lazy state

MessageHubState/openMessages()/loadMessageHub() are replaced by four
independent RemoteListState flows (privateSessions/replyFeed/likeFeed/
followerFeed) each with its own open*()-signals-only and retry*()-loads-only
function pair, matching the fix that already resolved the v1 navigation
loop bug. unreadCount becomes structured unreadSummary.

This intentionally breaks :app compilation until AppShell/MainActivity/
MessageCenterScreen are updated in subsequent commits."
```

---

### Task 4: `AppShell.kt` 三个新 push destination

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/AppShell.kt`

**Interfaces:**
- Consumes: 无新增类型依赖（纯 `SharedFlow<Unit>` 信号 + `@Composable` lambda，不直接碰 DTO/ViewModel）
- Produces（Task 7 依赖）：
  - `Destination.MESSAGE_REPLY`（route `"messageReply"`）、`Destination.MESSAGE_LIKE`（route `"messageLike"`）、`Destination.MESSAGE_FOLLOWER`（route `"messageFollower"`）
  - `AppShell(...)` 新增参数：`openReplyFeedRequests: SharedFlow<Unit> = MutableSharedFlow()`、`openLikeFeedRequests: SharedFlow<Unit> = MutableSharedFlow()`、`openFollowerFeedRequests: SharedFlow<Unit> = MutableSharedFlow()`、`replyFeedContent: @Composable (onBack: () -> Unit) -> Unit = { }`、`likeFeedContent: @Composable (onBack: () -> Unit) -> Unit = { }`、`followerFeedContent: @Composable (onBack: () -> Unit) -> Unit = { }`

- [ ] **Step 1: 新增 3 个 Destination 枚举值**

打开 `app/src/main/java/com/biliseek/app/ui/AppShell.kt`，找到第 48~51 行：

```kotlin
    /** 消息中心（push，不入底部导航）。 */
    MESSAGES("messages", "消息"),
    /** 私信聊天页（push，参数化 talkerId/peerName/peerFace）。 */
    PRIVATE_CHAT("privateChat/{talkerId}/{peerName}/{peerFace}", "私信"),
```

替换成：

```kotlin
    /** 消息中心（push，不入底部导航）。 */
    MESSAGES("messages", "消息"),
    /** 回复与@ 子页面（push，从消息中心图标进入）。 */
    MESSAGE_REPLY("messageReply", "回复与@"),
    /** 收到喜欢 子页面（push）。 */
    MESSAGE_LIKE("messageLike", "收到喜欢"),
    /** 新增粉丝 子页面（push）。 */
    MESSAGE_FOLLOWER("messageFollower", "新增粉丝"),
    /** 私信聊天页（push，参数化 talkerId/peerName/peerFace）。 */
    PRIVATE_CHAT("privateChat/{talkerId}/{peerName}/{peerFace}", "私信"),
```

- [ ] **Step 2: 新增 3 个信号参数**

找到第 118~121 行：

```kotlin
    /** 打开消息中心的信号。AppShell 订阅后 push MESSAGES destination。 */
    openMessagesRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 进入私信聊天页的信号（参数化）。AppShell 订阅后 push PRIVATE_CHAT destination。 */
    openPrivateChatRequests: SharedFlow<com.biliseek.app.AppViewModel.PrivateChatNav> = MutableSharedFlow(),
```

替换成：

```kotlin
    /** 打开消息中心的信号。AppShell 订阅后 push MESSAGES destination。 */
    openMessagesRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开"回复与@"子页面的信号。AppShell 订阅后 push MESSAGE_REPLY destination。 */
    openReplyFeedRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开"收到喜欢"子页面的信号。AppShell 订阅后 push MESSAGE_LIKE destination。 */
    openLikeFeedRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开"新增粉丝"子页面的信号。AppShell 订阅后 push MESSAGE_FOLLOWER destination。 */
    openFollowerFeedRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 进入私信聊天页的信号（参数化）。AppShell 订阅后 push PRIVATE_CHAT destination。 */
    openPrivateChatRequests: SharedFlow<com.biliseek.app.AppViewModel.PrivateChatNav> = MutableSharedFlow(),
```

- [ ] **Step 3: 新增 3 个子页面内容参数**

找到第 134~137 行：

```kotlin
    /** 消息中心页内容。onBack 由 AppShell 传入。 */
    messagesContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 私信聊天页内容。onBack 由 AppShell 传入。 */
    privateChatContent: @Composable (talkerId: Long, peerName: String, peerFace: String, onBack: () -> Unit) -> Unit = { _, _, _, _ -> },
```

替换成：

```kotlin
    /** 消息中心页内容。onBack 由 AppShell 传入。 */
    messagesContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 回复与@ 子页面内容。onBack 由 AppShell 传入。 */
    replyFeedContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 收到喜欢 子页面内容。onBack 由 AppShell 传入。 */
    likeFeedContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 新增粉丝 子页面内容。onBack 由 AppShell 传入。 */
    followerFeedContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 私信聊天页内容。onBack 由 AppShell 传入。 */
    privateChatContent: @Composable (talkerId: Long, peerName: String, peerFace: String, onBack: () -> Unit) -> Unit = { _, _, _, _ -> },
```

- [ ] **Step 4: 新增 3 个 `LaunchedEffect` 导航订阅**

找到第 186~193 行：

```kotlin
    // 消息中心导航：收到信号后 push MESSAGES destination（launchSingleTop 防重复压栈）。
    LaunchedEffect(openMessagesRequests) {
        openMessagesRequests.collect {
            navController.navigate(Destination.MESSAGES.route) {
                launchSingleTop = true
            }
        }
    }
```

在这段**后面**（`LaunchedEffect(openPrivateChatRequests)` 之前）插入：

```kotlin

    // "回复与@" 子页面导航：收到信号后 push MESSAGE_REPLY destination。
    LaunchedEffect(openReplyFeedRequests) {
        openReplyFeedRequests.collect {
            navController.navigate(Destination.MESSAGE_REPLY.route) {
                launchSingleTop = true
            }
        }
    }

    // "收到喜欢" 子页面导航：收到信号后 push MESSAGE_LIKE destination。
    LaunchedEffect(openLikeFeedRequests) {
        openLikeFeedRequests.collect {
            navController.navigate(Destination.MESSAGE_LIKE.route) {
                launchSingleTop = true
            }
        }
    }

    // "新增粉丝" 子页面导航：收到信号后 push MESSAGE_FOLLOWER destination。
    LaunchedEffect(openFollowerFeedRequests) {
        openFollowerFeedRequests.collect {
            navController.navigate(Destination.MESSAGE_FOLLOWER.route) {
                launchSingleTop = true
            }
        }
    }
```

- [ ] **Step 5: 把三个新 destination 排除在底部导航之外**

找到第 232~235 行：

```kotlin
                            if (dest == Destination.PLAYER || dest == Destination.UP_SPACE ||
                                dest == Destination.FOLLOWING_LIST || dest == Destination.MESSAGES ||
                                dest == Destination.PRIVATE_CHAT
                            ) return@forEach
```

替换成：

```kotlin
                            if (dest == Destination.PLAYER || dest == Destination.UP_SPACE ||
                                dest == Destination.FOLLOWING_LIST || dest == Destination.MESSAGES ||
                                dest == Destination.PRIVATE_CHAT || dest == Destination.MESSAGE_REPLY ||
                                dest == Destination.MESSAGE_LIKE || dest == Destination.MESSAGE_FOLLOWER
                            ) return@forEach
```

- [ ] **Step 6: 注册 3 个 `composable{}` 路由**

找到第 297~299 行：

```kotlin
                composable(Destination.MESSAGES.route) {
                    messagesContent({ navController.popBackStack() })
                }
```

在这段**后面**（`composable(Destination.PRIVATE_CHAT.route, ...)` 之前）插入：

```kotlin
                composable(Destination.MESSAGE_REPLY.route) {
                    replyFeedContent({ navController.popBackStack() })
                }
                composable(Destination.MESSAGE_LIKE.route) {
                    likeFeedContent({ navController.popBackStack() })
                }
                composable(Destination.MESSAGE_FOLLOWER.route) {
                    followerFeedContent({ navController.popBackStack() })
                }
```

- [ ] **Step 7: 编译确认 `AppShell.kt` 自身没问题**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 因为所有新参数都给了默认值（`MutableSharedFlow()`/`{ }`），`AppShell.kt` 这个文件本身语法应该是对的；但整个模块编译仍然会因为 Task 3 遗留的 `MessageCenterScreen.kt`/`MainActivity.kt` 引用旧符号而失败。看报错信息确认**没有**指向 `AppShell.kt` 的新报错（比如找不到 `Destination.MESSAGE_REPLY` 之类）就算这一步通过。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/AppShell.kt
git commit -m "feat(nav): add MESSAGE_REPLY/MESSAGE_LIKE/MESSAGE_FOLLOWER push destinations

Same wiring pattern as the existing UP_SPACE/FOLLOWING_LIST destinations:
one SharedFlow<Unit> signal + one LaunchedEffect + one composable{} each."
```

---

### Task 5: `MessageCenterScreen.kt` 重写

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/MessageCenterScreen.kt`（整体重写）

**Interfaces:**
- Consumes: Task 2 的 `BiliRepository.MessageUnreadSummary`（`.replyAtBadge`/`.like`），`com.biliseek.core.network.dto.Session`（不变）
- Produces（Task 7 依赖的确切签名）：
```kotlin
@Composable
fun MessageCenterScreen(
    sessions: RemoteListState<Session>,
    unreadSummary: BiliRepository.MessageUnreadSummary,
    session: BiliSession,
    loginState: LoginState,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onOpenChat: (Session) -> Unit,
    onOpenReplyFeed: () -> Unit,
    onOpenLikeFeed: () -> Unit,
    onOpenFollowerFeed: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: 整体重写文件内容**

用下面的内容**整体覆盖** `app/src/main/java/com/biliseek/app/ui/MessageCenterScreen.kt`：

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.LoginPanel
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.normalizeCoverUrl

/**
 * 消息中心页。三个入口图标（回复与@/收到喜欢/新增粉丝，前两个带未读角标）+
 * 私信会话列表主体。图标点击跳到各自独立的子页面，对齐真实 B 站的导航结构
 * （不是内嵌 TabRow 切换）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageCenterScreen(
    sessions: RemoteListState<Session>,
    unreadSummary: BiliRepository.MessageUnreadSummary,
    session: BiliSession,
    loginState: LoginState,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onOpenChat: (Session) -> Unit,
    onOpenReplyFeed: () -> Unit,
    onOpenLikeFeed: () -> Unit,
    onOpenFollowerFeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "消息",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }

        if (!session.isLoggedIn) {
            LoginPanel(
                loginState = loginState,
                session = session,
                onStart = onStartLogin,
                onCancel = onCancelLogin,
                onLogout = {},
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MessageEntryIcon(
                icon = Icons.Filled.Email,
                label = "回复与@",
                badgeCount = unreadSummary.replyAtBadge,
                onClick = onOpenReplyFeed,
            )
            MessageEntryIcon(
                icon = Icons.Filled.ThumbUp,
                label = "收到喜欢",
                badgeCount = unreadSummary.like,
                onClick = onOpenLikeFeed,
            )
            MessageEntryIcon(
                icon = Icons.Filled.Person,
                label = "新增粉丝",
                badgeCount = 0,
                onClick = onOpenFollowerFeed,
            )
        }
        HorizontalDivider()

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = sessions.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    when {
                        sessions.isLoading && sessions.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        sessions.errorMessage != null && sessions.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${sessions.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        sessions.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无私信")
                            }
                        }
                        else -> items(sessions.items, key = { it.talkerId }) { s ->
                            SessionRow(session = s, onClick = { onOpenChat(s) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageEntryIcon(
    icon: ImageVector,
    label: String,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            BadgedBox(
                badge = {
                    if (badgeCount > 0) {
                        Badge { Text(minOf(badgeCount, 99).toString()) }
                    }
                },
            ) {
                Icon(imageVector = icon, contentDescription = label)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    val peerName = session.accountInfo?.name.orEmpty().ifBlank { "用户${session.talkerId}" }
    val peerFace = session.accountInfo?.face.orEmpty()
    val lastText = session.lastMsg?.content?.let { extractTextPreview(it) }.orEmpty()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(peerFace),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peerName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lastText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (session.lastMsg?.timestamp ?: 0L > 0L) formatRelativeTime(session.lastMsg!!.timestamp * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun extractTextPreview(rawJson: String): String {
    val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    return regex.find(rawJson)?.groupValues?.get(1)
        ?.replace("\\\\", "\\")
        ?.replace("\\\"", "\"")
        ?.replace("\\n", "\n")
        ?: rawJson
}
```

（`formatRelativeTime` 定义在同包的 `RelativeTime.kt` 里，同包不需要 import。）

- [ ] **Step 2: 编译确认没有语法错误**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | grep -i "MessageCenterScreen"`
Expected: 无输出，或者只有来自 `MainActivity.kt`（还没改）调用这个函数时参数不匹配的报错，不应该有 `MessageCenterScreen.kt` **自身**的语法/类型错误。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/MessageCenterScreen.kt
git commit -m "feat(ui): rewrite MessageCenterScreen as icon row + private session list

Drops the inline TabRow and MessageHubState; the three interaction-feed
tabs are now entry icons (first two badged) that push to standalone
pages built in the next commit. Private session list is now the page's
sole scrollable body, matching real Bilibili's layout."
```

---

### Task 6: 三个互动消息子页面（新建）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/ReplyAtScreen.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/LikeScreen.kt`
- Create: `app/src/main/java/com/biliseek/app/ui/FollowerScreen.kt`

**Interfaces:**
- Consumes: Task 1 的 `ReplyAtItem`/`LikeItem`/`FollowerEntry`（含计算属性）
- Produces（Task 7 依赖的确切签名）：
```kotlin
@Composable
fun ReplyAtScreen(
    state: RemoteListState<ReplyAtItem>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlayItem: (ReplyAtItem) -> Unit,
    onSubmitReply: (aid: Long, root: Long, parent: Long, content: String, onResult: (Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun LikeScreen(
    state: RemoteListState<LikeItem>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlayItem: (LikeItem) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun FollowerScreen(
    state: RemoteListState<FollowerEntry>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenSpace: (FollowerEntry) -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: 新建 `ReplyAtScreen.kt`**（含长按弹回复框——修正 v1 里 `onLongClick` 参数从未真正接上手势检测的遗留 bug，这次用 `combinedClickable` 正确接上）

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.normalizeCoverUrl

/** "回复与@" 子页面：从消息中心图标进入。长按条目可站内直接回复。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyAtScreen(
    state: RemoteListState<ReplyAtItem>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlayItem: (ReplyAtItem) -> Unit,
    onSubmitReply: (aid: Long, root: Long, parent: Long, content: String, onResult: (Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }
    var pendingReply by remember { mutableStateOf<ReplyAtItem?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "回复与@",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    when {
                        state.isLoading && state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.errorMessage != null && state.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无内容")
                            }
                        }
                        else -> items(state.items, key = { it.id }) { entry ->
                            ReplyAtRow(
                                item = entry,
                                onClick = { onPlayItem(entry) },
                                onLongClick = {
                                    pendingReply = entry
                                    replyDraft = ""
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (pendingReply != null) {
        ModalBottomSheet(onDismissRequest = { pendingReply = null }) {
            Column(Modifier.padding(16.dp)) {
                Text("回复 ${pendingReply?.user?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("「${pendingReply?.content.orEmpty()}」", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { replyDraft = it },
                    placeholder = { Text("写下你的回复…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { pendingReply = null }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val target = pendingReply ?: return@TextButton
                            replySubmitting = true
                            onSubmitReply(
                                target.sourceId,
                                target.rootId,
                                target.parentId,
                                replyDraft,
                            ) { ok, _ ->
                                replySubmitting = false
                                if (ok) {
                                    pendingReply = null
                                    replyDraft = ""
                                }
                            }
                        },
                        enabled = !replySubmitting && replyDraft.isNotBlank(),
                    ) { Text(if (replySubmitting) "发送中…" else "发送") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyAtRow(
    item: ReplyAtItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(item.user?.face.orEmpty()),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.user?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("评论：${item.content}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.replyTime > 0L) formatRelativeTime(item.replyTime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
```

- [ ] **Step 2: 新建 `LikeScreen.kt`**

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.normalizeCoverUrl

/** "收到喜欢" 子页面：从消息中心图标进入。 */
@Composable
fun LikeScreen(
    state: RemoteListState<LikeItem>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlayItem: (LikeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "收到喜欢",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    when {
                        state.isLoading && state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.errorMessage != null && state.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无内容")
                            }
                        }
                        else -> items(state.items, key = { it.id }) { entry ->
                            LikeRow(item = entry, onClick = { onPlayItem(entry) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LikeRow(item: LikeItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(item.firstUser?.face.orEmpty()),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.firstUser?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("赞了你的视频：${item.title}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.likeTime > 0L) formatRelativeTime(item.likeTime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
```

- [ ] **Step 3: 新建 `FollowerScreen.kt`**

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.dto.FollowerEntry
import com.biliseek.core.network.normalizeCoverUrl

/** "新增粉丝" 子页面：从消息中心图标进入，没有未读角标（B 站该分类无已读/未读概念）。 */
@Composable
fun FollowerScreen(
    state: RemoteListState<FollowerEntry>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenSpace: (FollowerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "新增粉丝",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    when {
                        state.isLoading && state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.errorMessage != null && state.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无新增粉丝")
                            }
                        }
                        else -> items(state.items, key = { it.mid }) { entry ->
                            FollowerRow(item = entry, onClick = { onOpenSpace(entry) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowerRow(item: FollowerEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(item.face),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.uname, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.sign.ifBlank { "关注了你" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.mtime > 0L) formatRelativeTime(item.mtime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
```

- [ ] **Step 4: 编译确认三个新文件没有语法错误**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | grep -iE "ReplyAtScreen|LikeScreen|FollowerScreen"`
Expected: 无输出（这三个文件自身编译通过；整体模块仍会因 `MainActivity.kt` 未接线而失败，Task 7 处理）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/biliseek/app/ui/ReplyAtScreen.kt \
        app/src/main/java/com/biliseek/app/ui/LikeScreen.kt \
        app/src/main/java/com/biliseek/app/ui/FollowerScreen.kt
git commit -m "feat(ui): add ReplyAtScreen/LikeScreen/FollowerScreen standalone pages

Extracted from the old MessageCenterScreen TabRow content. Also fixes a
latent bug: the v1 long-press-to-reply row accepted an onLongClick
callback but never attached it to a gesture detector (plain .clickable,
no combinedClickable), so the reply bottom sheet could never actually
open. ReplyAtRow now wires combinedClickable correctly."
```

---

### Task 7: `MainActivity.kt` 接线 + 全量构建验证

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`

**Interfaces:**
- Consumes: 全部前 6 个 Task 产出的类型/函数签名
- Produces: 无（叶子任务，整合收尾）

- [ ] **Step 1: 新增 import**

打开 `app/src/main/java/com/biliseek/app/MainActivity.kt`，找到第 37 行：

```kotlin
import com.biliseek.app.ui.MessageCenterScreen
```

替换成：

```kotlin
import com.biliseek.app.ui.FollowerScreen
import com.biliseek.app.ui.LikeScreen
import com.biliseek.app.ui.MessageCenterScreen
import com.biliseek.app.ui.ReplyAtScreen
```

- [ ] **Step 2: `unreadCount` 改成 `unreadSummary`**

找到第 128 行：

```kotlin
                val unreadCount by viewModel.unreadCount.collectAsState()
```

替换成：

```kotlin
                val unreadSummary by viewModel.unreadSummary.collectAsState()
```

- [ ] **Step 3: `AppShell(...)` 调用里加 3 个新信号参数**

找到第 217 行：

```kotlin
                    openMessagesRequests = viewModel.openMessagesRequests,
                    openPrivateChatRequests = viewModel.openPrivateChatRequests,
```

替换成：

```kotlin
                    openMessagesRequests = viewModel.openMessagesRequests,
                    openReplyFeedRequests = viewModel.openReplyFeedRequests,
                    openLikeFeedRequests = viewModel.openLikeFeedRequests,
                    openFollowerFeedRequests = viewModel.openFollowerFeedRequests,
                    openPrivateChatRequests = viewModel.openPrivateChatRequests,
```

- [ ] **Step 4: `dynamicContent` 里的 `unreadCount` 改传 `unreadSummary.total`**

找到第 279 行：

```kotlin
                            unreadCount = unreadCount,
```

替换成：

```kotlin
                            unreadCount = unreadSummary.total,
```

（`DynamicScreen` 自己的参数名还是 `unreadCount: Int`，不用改 `DynamicScreen.kt`，只是这里传入的表达式变了。）

- [ ] **Step 5: 重写 `messagesContent`，新增 3 个 `xxxFeedContent`**

找到第 546~574 行：

```kotlin
                    messagesContent = { onBack ->
                        val hub by viewModel.messageHub.collectAsState()
                        MessageCenterScreen(
                            hub = hub,
                            session = session,
                            loginState = loginState,
                            unreadCount = unreadCount,
                            onInit = viewModel::retryMessageHub,
                            onRetry = viewModel::retryMessageHub,
                            onClose = {
                                viewModel.closeMessages()
                                onBack()
                            },
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                            onOpenChat = { s ->
                                viewModel.openPrivateChat(s)
                            },
                            onPlayReplyAtItem = viewModel::playReplyAtItem,
                            onPlayLikeItem = viewModel::playLikeItem,
                            onOpenFollower = { f ->
                                viewModel.openFollowerSpace(f)
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                            onSubmitReply = { aid, root, parent, content, onResult ->
                                viewModel.submitCommentReply(aid, root, parent, content, onResult)
                            },
                        )
                    },
```

替换成：

```kotlin
                    messagesContent = { onBack ->
                        val sessions by viewModel.privateSessions.collectAsState()
                        MessageCenterScreen(
                            sessions = sessions,
                            unreadSummary = unreadSummary,
                            session = session,
                            loginState = loginState,
                            onInit = viewModel::retryPrivateSessions,
                            onRetry = viewModel::retryPrivateSessions,
                            onClose = onBack,
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                            onOpenChat = { s -> viewModel.openPrivateChat(s) },
                            onOpenReplyFeed = viewModel::openReplyFeed,
                            onOpenLikeFeed = viewModel::openLikeFeed,
                            onOpenFollowerFeed = viewModel::openFollowerFeed,
                        )
                    },
                    replyFeedContent = { onBack ->
                        val replyState by viewModel.replyFeed.collectAsState()
                        ReplyAtScreen(
                            state = replyState,
                            onInit = viewModel::retryReplyFeed,
                            onRetry = viewModel::retryReplyFeed,
                            onBack = onBack,
                            onPlayItem = viewModel::playReplyAtItem,
                            onSubmitReply = { aid, root, parent, content, onResult ->
                                viewModel.submitCommentReply(aid, root, parent, content, onResult)
                            },
                        )
                    },
                    likeFeedContent = { onBack ->
                        val likeState by viewModel.likeFeed.collectAsState()
                        LikeScreen(
                            state = likeState,
                            onInit = viewModel::retryLikeFeed,
                            onRetry = viewModel::retryLikeFeed,
                            onBack = onBack,
                            onPlayItem = viewModel::playLikeItem,
                        )
                    },
                    followerFeedContent = { onBack ->
                        val followerState by viewModel.followerFeed.collectAsState()
                        FollowerScreen(
                            state = followerState,
                            onInit = viewModel::retryFollowerFeed,
                            onRetry = viewModel::retryFollowerFeed,
                            onBack = onBack,
                            onOpenSpace = { f ->
                                viewModel.openFollowerSpace(f)
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                        )
                    },
```

- [ ] **Step 6: 编译整个 `:app` 模块**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。如果还有报错，大概率是某个 import 漏加或者某处参数名/类型没对齐——对照报错信息里的文件名回到对应 Task 检查。

- [ ] **Step 7: 跑全部相关单测**

Run: `./gradlew :core:network:test :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`。重点确认：
- `MessageDtoParseTest`（Task 1）7 个用例全绿
- `MessageUnreadSummaryTest`（Task 2）3 个用例全绿
- `MessageUiStateMapperTest`（Task 3）剩下的 2 个用例全绿
- 没有其它模块的测试因为这次改动被带崩（`RequestThrottleTest` 等历史测试应该完全不受影响）

- [ ] **Step 8: 构建 + 安装到真机做最终验证**

Run: `./gradlew installDebug`
Expected: `BUILD SUCCESSFUL`，`Installed on 1 device.`

安装完成后需要**人工**在真机上验证（这一步涉及真实 B 站网络请求，不能自动化，AGENTS.md §2.3）：
- 点动态页铃铛 → 消息中心页正常打开，不闪烁，能正常返回。
- 消息中心页只有 3 个图标 + 私信列表，没有 TabRow。
- 点「回复与@」图标 → 独立页面，下拉刷新指示器加载完之后会自动停止（不再卡死转圈）；评论正文非空；长按条目能弹出回复输入框并发送成功；点条目能跳到正确视频；返回键回消息中心。
- 点「收到喜欢」图标 → 独立页面，用户名/头像/视频标题正常显示；点条目跳转正确视频。
- 点「新增粉丝」图标 → 独立页面，数据正常显示；确认图标本身没有角标数字。
- 「回复与@」「收到喜欢」图标的角标数字与实际未读一致，进入对应页面后角标消失；「新增粉丝」始终无角标。
- 私信会话列表点击进入聊天页流程不受影响（v1 功能，本次未改动这部分）。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(app): wire MessageCenter/ReplyAt/Like/Follower screens into MainActivity

Completes the v2 message center: MainActivity now passes the four
independent RemoteListState flows and three new navigation signals
through to AppShell, and the dynamic-page bell reads unreadSummary.total
instead of the old flat unreadCount."
```

---

## Self-Review Notes

- **Spec 覆盖**：v2 设计文档 §2.1（导航）→ Task 4/7；§2.2（懒加载）→ Task 3；§2.3（角标来源）→ Task 2/3/5；§2.4（DTO 修正）→ Task 1；§2.5（AppViewModel 状态拆分）→ Task 3；§3.1（MessageCenterScreen UI）→ Task 5；§3.2（子页面 UI）→ Task 6；§4（测试）→ 每个 Task 内置。全部覆盖，无遗漏项。
- **占位符检查**：全文没有 TBD/TODO，每个 Step 的代码都是可以直接落盘的完整内容。
- **类型一致性检查**：`retryPrivateSessions`/`retryReplyFeed`/`retryLikeFeed`/`retryFollowerFeed`/`openReplyFeed`/`openLikeFeed`/`openFollowerFeed` 这几个函数名在 Task 3（定义）、Task 5/6（作为回调类型出现在 Composable 签名里）、Task 7（作为方法引用传入）三处贴了一遍，命名完全一致。`BiliRepository.MessageUnreadSummary` 的字段名（`reply`/`at`/`like`/`sessionUnread`/`total`/`replyAtBadge`）在 Task 2（定义+测试）、Task 3（消费）、Task 5（消费）三处一致。
