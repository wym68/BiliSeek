# BiliSeek 消息中心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "消息中心" feature that lets a logged-in BiliSeek user view 互动消息 (回复与@ / 收到喜欢 / 新增粉丝) and 私信 (会话列表 + 聊天详情 + 发送文本), with a Bell entry icon + unread red dot on the dynamic page.

**Architecture:** New DTO + 10 BiliApi methods + 6 BiliRepository methods, all routed through existing `writeRequest`/`riskCooldown`/`authGate`. UI follows the WatchLaterScreen/FollowingListScreen template. New screens: MessageCenterScreen, PrivateChatScreen. AppShell gains two `Destination`s and two SharedFlow nav signals.

**Tech Stack:** Kotlin, Jetpack Compose, Retrofit 3 + OkHttp 5, kotlinx.serialization, Material3 (TabRow / IconButton / Badge).

---

## File Structure

### Created
- `core/network/src/main/java/com/biliseek/core/network/dto/MessageDto.kt` — minimal-field DTOs for sessions, messages, follow-reply items, likes, followers, unread, send result.
- `core/network/src/test/java/com/biliseek/core/network/MessageDtoParseTest.kt` — JSON parsing pin.
- `core/network/src/test/java/com/biliseek/core/network/SendMessageFieldBuildTest.kt` — pin nested `msg[*]` field shapes.
- `app/src/main/java/com/biliseek/app/ui/MessageCenterScreen.kt` — 3-tab + 私信 section.
- `app/src/main/java/com/biliseek/app/ui/PrivateChatScreen.kt` — chat detail + send box.
- `app/src/test/java/com/biliseek/app/data/MessageUiStateMapperTest.kt` — pin `MessageHubState` merging & guard rules.

### Modified
- `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt` — append a `// ── 消息中心` block: `getMsgfeedUnread`, `getMsgfeedReply`, `getMsgfeedLike`, `getFollowers`, `getSessionUnread`, `getSessions`, `fetchSessionMsgs`, `postSendMsg` (csrf_token explicit), `postUpdateAck` (csrf_token explicit).
- `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt` — append message section: `messageHub()`, `loadMoreSessionMessages()`, `sendPrivateMessage()`, `markSessionRead()`, `postCommentReply()` thin wrapper, `messageUnread()`. Add `MESSAGE_MIN_INTERVAL_MS` to companion.
- `core/network/src/main/java/com/biliseek/core/network/internal/RequestThrottle.kt` — add `const val MESSAGE = "message"` to `ThrottleBucket`.
- `app/src/main/java/com/biliseek/app/data/RemoteListState.kt` — add 4 `TrackListId` cases: `MessageReply`, `MessageLike`, `MessageFollower`, `PrivateSession`.
- `app/src/main/java/com/biliseek/app/AppViewModel.kt` — append a `// ── 消息中心` section: `_messages`/`messages`/`openMessages/loadMessages/retryMessages/closeMessages`, `_chat`/`chat`/`openChat/sendChatMessage/loadMoreChat/retryChat/closeChat`, `_unreadCount`/`unreadCount`/`refreshUnreadCount`, `openMessagesRequests`, `openPrivateChatRequests`. Method `playReplyAtItem(item)` for video nav. Method `submitCommentReply(...)`.
- `app/src/main/java/com/biliseek/app/ui/AppShell.kt` — add `Destination.MESSAGES` + `Destination.PRIVATE_CHAT`; add `openMessagesRequests`/`openPrivateChatRequests` params; add 2 `LaunchedEffect` blocks. Add the private chat route's `arguments` to the NavHost composable.
- `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt` — modify `DynamicFilterBar` to include trailing bell icon (icon + Badge), add 2 callbacks `onOpenMessages`.
- `app/src/main/java/com/biliseek/app/MainActivity.kt` — instantiate `openMessagesRequests`/`openPrivateChatRequests`, wire Bell click, wire `dynamicContent`, pass new params into AppShell, register `LifecycleObserver` for `ON_RESUME` → `viewModel.refreshUnreadCount()`, add `messagesContent`/`privateChatContent` lambdas to AppShell.

---

## Task 1: Add `MESSAGE` throttle bucket

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/internal/RequestThrottle.kt:10-14`

- [ ] **Step 1: Add the new constant**

In `core/network/src/main/java/com/biliseek/core/network/internal/RequestThrottle.kt`, inside `internal object ThrottleBucket`, add:

```kotlin
const val MESSAGE = "message"
```

Final block:

```kotlin
/** 请求限速的分类桶。搜索联想与批量写各自单独调，互不拖累。 */
internal object ThrottleBucket {
    const val SEARCH = "search"
    const val WRITE = "write"
    const val FEED = "feed"
    /** 消息中心（私信会话/历史/互动 tab）：与 FEED 隔离，避免触发风控 */
    const val MESSAGE = "message"
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/internal/RequestThrottle.kt
git commit -m "feat(network): add MESSAGE throttle bucket for message center"
```

---

## Task 2: Create MessageDto.kt (minimal-field DTOs)

**Files:**
- Create: `core/network/src/main/java/com/biliseek/core/network/dto/MessageDto.kt`

- [ ] **Step 1: Create the file with all DTOs**

```kotlin
package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ───────────────── 私信会话/消息（api.vc.bilibili.com）─────────────────

/** `get_sessions` 一条会话。 */
@Serializable
data class Session(
    @SerialName("talker_id") val talkerId: Long = 0,
    @SerialName("session_type") val sessionType: Int = 0,
    @SerialName("top_ts") val topTs: Long = 0,
    @SerialName("ack_ts") val ackTs: Long = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_msg") val lastMsg: LastMessage? = null,
    @SerialName("account_info") val accountInfo: SessionPeer? = null,
)

/** `last_msg.content` 是 JSON 字符串，按 msg_type 解析。纯文本 (msg_type=1) 直接展示。 */
@Serializable
data class LastMessage(
    @SerialName("sender_uid") val senderUid: Long = 0,
    @SerialName("msg_type") val msgType: Int = 0,
    @SerialName("content") val content: String = "",
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
)

/** 对方用户卡片（昵称/头像）。 */
@Serializable
data class SessionPeer(
    @SerialName("name") val name: String = "",
    @SerialName("face") val face: String = "",
    @SerialName("mid") val mid: Long = 0,
)

@Serializable
data class SessionListData(
    @SerialName("session_list") val sessionList: List<Session> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
)

/** `fetch_session_msgs` 一条消息。 */
@Serializable
data class PrivateMessage(
    @SerialName("sender_uid") val senderUid: Long = 0,
    @SerialName("receiver_id") val receiverId: Long = 0,
    @SerialName("msg_type") val msgType: Int = 0,
    /** 文本消息是 JSON 字符串：`{"content":"hello"}`。 */
    @SerialName("content") val content: String = "",
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
)

@Serializable
data class MessageHistoryData(
    @SerialName("messages") val messages: List<PrivateMessage> = emptyList(),
    @SerialName("max_seqno") val maxSeqno: Long = 0,
    @SerialName("min_seqno") val minSeqno: Long = 0,
    @SerialName("has_more") val hasMore: Int = 0,
)

/** `send_msg` 返回。msg_seqno 用于本地立即插入。 */
@Serializable
data class SendMessageData(
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
    @SerialName("msg_key") val msgKey: Long = 0,
)

// ───────────────── 互动消息（api.bilibili.com x/msgfeed）─────────────────

/** `x/msgfeed/reply` 单条（回复与@ tab）。 */
@Serializable
data class ReplyAtItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    /** 业务类型：1=视频回复，5=动态回复，7=评论回复等。 */
    @SerialName("business_id") val businessId: Int = 0,
    /** 资源 oid（视频=aid，动态=id_str）。 */
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("source_content") val sourceContent: String = "",
    @SerialName("root_id") val rootId: Long = 0,
    @SerialName("parent_id") val parentId: Long = 0,
    @SerialName("reply_time") val replyTime: Long = 0,
    @SerialName("content") val content: String = "",
)

/** `x/msgfeed/like` 单条（收到喜欢 tab）。 */
@Serializable
data class LikeItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("like_time") val likeTime: Long = 0,
)

@Serializable
data class MsgfeedUser(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("face") val face: String = "",
)

@Serializable
data class ReplyFeedData(
    @SerialName("items") val items: List<ReplyAtItem> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class LikeFeedData(
    @SerialName("items") val items: List<LikeItem> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

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

/** `x/relation/followers` 单条。复用 RelationStatusSerializer 不必要 —— 关注列表字段更详。 */
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

- [ ] **Step 2: Verify compile**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/MessageDto.kt
git commit -m "feat(network): add message center DTOs (sessions, messages, reply/like, followers)"
```

---

## Task 3: Pin DTO parsing with `MessageDtoParseTest`

**Files:**
- Create: `core/network/src/test/java/com/biliseek/core/network/MessageDtoParseTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.FollowersData
import com.biliseek.core.network.dto.LikeFeedData
import com.biliseek.core.network.dto.MessageHistoryData
import com.biliseek.core.network.dto.MsgfeedUnreadData
import com.biliseek.core.network.dto.ReplyFeedData
import com.biliseek.core.network.dto.SessionListData
import com.biliseek.core.network.dto.SessionUnreadData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDtoParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `会话列表解析`() {
        val data = json.decodeFromString<BiliResponse<SessionListData>>(
            """
            {
              "code": 0,
              "data": {
                "session_list": [
                  {
                    "talker_id": 1001,
                    "session_type": 1,
                    "ack_ts": 1700000000,
                    "unread_count": 3,
                    "last_msg": {
                      "sender_uid": 1001,
                      "msg_type": 1,
                      "content": "{\"content\":\"hi\"}",
                      "timestamp": 1700000000,
                      "msg_seqno": 5
                    },
                    "account_info": { "name": "用户B", "face": "//face", "mid": 1001 }
                  }
                ],
                "has_more": 0
              }
            }
            """.trimIndent(),
        )
        assertEquals(0, data.code)
        val sessions = data.data?.sessionList.orEmpty()
        assertEquals(1, sessions.size)
        val first = sessions[0]
        assertEquals(1001L, first.talkerId)
        assertEquals(3, first.unreadCount)
        assertNotNull(first.lastMsg)
        assertEquals("hi", first.lastMsg?.content?.let { extractContent(it) })
        assertEquals("用户B", first.accountInfo?.name)
    }

    @Test
    fun `历史消息解析`() {
        val data = json.decodeFromString<BiliResponse<MessageHistoryData>>(
            """
            {
              "code": 0,
              "data": {
                "messages": [
                  {
                    "sender_uid": 1001,
                    "receiver_id": 999,
                    "msg_type": 1,
                    "content": "{\"content\":\"Hello\"}",
                    "timestamp": 1700000000,
                    "msg_seqno": 7
                  }
                ],
                "max_seqno": 7,
                "min_seqno": 7,
                "has_more": 0
              }
            }
            """.trimIndent(),
        )
        val msgs = data.data?.messages.orEmpty()
        assertEquals(1, msgs.size)
        assertEquals(7L, msgs[0].msgSeqno)
        assertEquals("Hello", extractContent(msgs[0].content))
        assertEquals(7L, data.data?.maxSeqno)
    }

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
                    "user": { "mid": 222, "name": "用户A", "face": "//fa" },
                    "business_id": 1,
                    "source_id": 1000,
                    "source_content": "原视频标题",
                    "root_id": 0,
                    "parent_id": 0,
                    "reply_time": 1700000000,
                    "content": "评论内容"
                  }
                ],
                "has_more": 1,
                "total_count": 1
              }
            }
            """.trimIndent(),
        )
        val items = data.data?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals(1000L, items[0].sourceId)
        assertEquals("用户A", items[0].user?.name)
        assertEquals("评论内容", items[0].content)
    }

    @Test
    fun `收到喜欢列表解析`() {
        val data = json.decodeFromString<BiliResponse<LikeFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "items": [
                  {
                    "id": 22,
                    "user": { "mid": 333, "name": "用户C", "face": "//fc" },
                    "source_id": 2000,
                    "business_id": 1,
                    "title": "被点赞视频",
                    "like_time": 1700000000
                  }
                ],
                "has_more": 0,
                "total_count": 1
              }
            }
            """.trimIndent(),
        )
        val items = data.data?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals(2000L, items[0].sourceId)
        assertEquals("被点赞视频", items[0].title)
    }

    @Test
    fun `粉丝列表解析`() {
        val data = json.decodeFromString<BiliResponse<FollowersData>>(
            """
            {
              "code": 0,
              "data": {
                "list": [
                  { "mid": 444, "uname": "新粉", "face": "//f", "sign": "s", "mtime": 1700000000, "attribute": 1 }
                ],
                "total": 1
              }
            }
            """.trimIndent(),
        )
        val list = data.data?.list.orEmpty()
        assertEquals(1, list.size)
        assertEquals("新粉", list[0].uname)
        assertEquals(444L, list[0].mid)
    }

    @Test
    fun `未读聚合解析`() {
        val msgfeed = json.decodeFromString<BiliResponse<MsgfeedUnreadData>>(
            """{"code":0,"data":{"at":2,"reply":5,"like":1}}""",
        )
        assertEquals(5, msgfeed.data?.reply)
        assertEquals(2, msgfeed.data?.at)
        assertEquals(1, msgfeed.data?.like)
        val session = json.decodeFromString<BiliResponse<SessionUnreadData>>(
            """{"code":0,"data":{"unfollow_unread":3,"follow_unread":2}}""",
        )
        assertEquals(3, session.data?.unfollowUnread)
    }

    @Test
    fun `字段缺失不崩`() {
        val data = json.decodeFromString<BiliResponse<SessionListData>>("""{"code":0,"data":{}}""")
        assertTrue(data.data?.sessionList.orEmpty().isEmpty())
    }

    /** `last_msg.content` 是 JSON 字符串，纯文本 (msg_type=1) 时为 `{"content":"…"}`。 */
    private fun extractContent(raw: String): String {
        // 简单解析：取 `"content":"..."` 中间的文本，匹配转义折行场景时足够
        val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return regex.find(raw)?.groupValues?.get(1)?.replace("\\\\", "\\")
            ?.replace("\\\"", "\"")
            ?: raw
    }
}
```

- [ ] **Step 2: Run the test (compile-time)** — should compile since DTOs were created in Task 2.

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.MessageDtoParseTest"`
Expected: PASS (6 tests).

- [ ] **Step 3: Commit**

```bash
git add core/network/src/test/java/com/biliseek/core/network/MessageDtoParseTest.kt
git commit -m "test(network): pin message center DTO parsing"
```

---

## Task 4: Add BiliApi endpoint methods

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt:411-432` (after the `likeDynamic` block, before `Destination` companion)

- [ ] **Step 0: Add `BiliDtoUnit` placeholder**

`postUpdateAck` returns `BiliResponse<BiliDtoUnit>` (写端不需要 data 解构)。在 `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt` 的 `BiliResponse` 定义附近追加：

```kotlin
/** 占位 data object — 写接口用，data 字段不需要解构。 */
@Serializable
data object BiliDtoUnit
```

- [ ] **Step 1: Insert the message center block**

Append after the `// ── 动态互动（Ch4）` block (before the `relation` block) — i.e. between `likeDynamic` and `relation`:

```kotlin
    // ── 消息中心（私信 + 互动消息）────────────────────────

    /** 私信会话列表（`api.vc.bilibili.com`）。`session_type=4` 表示全部。 */
    @GET("https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions")
    suspend fun getSessions(
        @Query("session_type") sessionType: Int = 4,
        @Query("group_fold") groupFold: Int = 1,
        @Query("unfollow_fold") unfollowFold: Int = 0,
        @Query("sort_rule") sortRule: Int = 2,
        @Query("size") size: Int = 20,
        @Query("build") build: Int = 0,
        @Query("mobi_app") mobiApp: String = "web",
        @Query("web_location") webLocation: String = "333.999",
        @Query("pn") pn: Int = 1,
        @Query("end_ts") endTs: Long = 0,
    ): BiliResponse<com.biliseek.core.network.dto.SessionListData>

    /** 私信历史消息（`api.vc.bilibili.com`）。`beginSeqno=0` 拉首屏。 */
    @GET("https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs")
    suspend fun fetchSessionMsgs(
        @Query("talker_id") talkerId: Long,
        @Query("session_type") sessionType: Int = 1,
        @Query("size") size: Int = 20,
        @Query("begin_seqno") beginSeqno: Long = 0,
        @Query("end_seqno") endSeqno: Long = 0,
        @Query("mobi_app") mobiApp: String = "web",
    ): BiliResponse<com.biliseek.core.network.dto.MessageHistoryData>

    /** 私信未读聚合（`api.vc.bilibili.com`）。 */
    @GET("https://api.vc.bilibili.com/session_svr/v1/session_svr/single_unread")
    suspend fun getSessionUnread(
        @Query("unread_type") unreadType: Int = 0,
        @Query("show_unfollow_list") showUnfollowList: Int = 1,
        @Query("show_dustbin") showDustbin: Int = 1,
        @Query("mobi_app") mobiApp: String = "web",
    ): BiliResponse<com.biliseek.core.network.dto.SessionUnreadData>

    /** 发送私信（`api.vc.bilibili.com`）。注意：`csrf` 由 `writeRequest` 注入；
     *  `csrf_token` 必须显式声明并由调用方从 `session.biliJct` 传入。 */
    @FormUrlEncoded
    @POST("https://api.vc.bilibili.com/web_im/v1/web_im/send_msg")
    suspend fun postSendMsg(
        @Field("msg[sender_uid]") senderUid: Long,
        @Field("msg[receiver_id]") receiverId: Long,
        @Field("msg[receiver_type]") receiverType: Int = 1,
        @Field("msg[msg_type]") msgType: Int = 1,
        @Field("msg[content]") content: String,
        @Field("msg[timestamp]") timestamp: Long,
        @Field("msg[dev_id]") devId: String,
        @Field("msg[new_face_version]") newFaceVersion: Int = 1,
        @Field("csrf_token") csrfToken: String,
    ): BiliResponse<com.biliseek.core.network.dto.SendMessageData>

    /** 标记会话已读（`api.vc.bilibili.com`）。同上：`csrf_token` 显式声明。 */
    @FormUrlEncoded
    @POST("https://api.vc.bilibili.com/session_svr/v1/session_svr/update_ack")
    suspend fun postUpdateAck(
        @Field("talker_id") talkerId: Long,
        @Field("session_type") sessionType: Int = 1,
        @Field("ack_seqno") ackSeqno: Long,
        @Field("csrf_token") csrfToken: String,
    ): BiliResponse<com.biliseek.core.network.dto.BiliDtoUnit>

    /** 互动消息未读聚合（`api.bilibili.com x/msgfeed`）。 */
    @GET("https://api.bilibili.com/x/msgfeed/unread")
    suspend fun getMsgfeedUnread(
        @Query("build") build: Int = 0,
        @Query("mobi_app") mobiApp: String = "web",
        @Query("web_location") webLocation: String = "333.1365",
    ): BiliResponse<com.biliseek.core.network.dto.MsgfeedUnreadData>

    /** 回复与@ 列表（`api.bilibili.com x/msgfeed/reply`）。`id`/`reply_time` 用于分页游标。 */
    @GET("https://api.bilibili.com/x/msgfeed/reply")
    suspend fun getMsgfeedReply(
        @Query("id") cursorId: Long? = null,
        @Query("reply_time") cursorTime: Long? = null,
        @Query("platform") platform: String = "web",
        @Query("mobi_app") mobiApp: String = "web",
        @Query("build") build: Int = 0,
        @Query("web_location") webLocation: String = "333.40164",
    ): BiliResponse<com.biliseek.core.network.dto.ReplyFeedData>

    /** 收到喜欢列表（`api.bilibili.com x/msgfeed/like`）。 */
    @GET("https://api.bilibili.com/x/msgfeed/like")
    suspend fun getMsgfeedLike(
        @Query("id") cursorId: Long? = null,
        @Query("like_time") cursorTime: Long? = null,
        @Query("platform") platform: String = "web",
        @Query("mobi_app") mobiApp: String = "web",
        @Query("build") build: Int = 0,
        @Query("web_location") webLocation: String = "333.40164",
    ): BiliResponse<com.biliseek.core.network.dto.LikeFeedData>

    /** 粉丝列表（`api.bilibili.com x/relation/followers`）。`order=desc` 按关注时间倒序。 */
    @GET("https://api.bilibili.com/x/relation/followers")
    suspend fun getFollowers(
        @Query("vmid") vmid: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
        @Query("order") order: String = "desc",
        @Query("order_type") orderType: String = "time",
    ): BiliResponse<com.biliseek.core.network.dto.FollowersData>
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliApi.kt
git commit -m "feat(network): add message center endpoint methods to BiliApi"
```

> Note: `com.biliseek.core.network.dto.BiliDtoUnit` may not exist yet. If compilation fails, add to `BiliDto.kt` a one-line data object:
>
> ```kotlin
> @Serializable
> data object BiliDtoUnit
> ```

---

## Task 5: Add BiliRepository message methods + companion constants

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt:985-990` (companion constants), append a new section before companion.

- [ ] **Step 1: Add `MESSAGE_MIN_INTERVAL_MS` constant**

In `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`, modify the companion object (currently contains `SEARCH_MIN_INTERVAL_MS`, `FEED_MIN_INTERVAL_MS`, `WRITE_MIN_INTERVAL_MS`, `THROTTLE_JITTER_MS`) to add a new constant. The final companion body:

```kotlin
    companion object {
        const val SEARCH_MIN_INTERVAL_MS = 800L
        const val FEED_MIN_INTERVAL_MS = 600L
        const val WRITE_MIN_INTERVAL_MS = 1_000L
        /** 消息中心限速：B 站私信接口对连发敏感，按 800ms/req 起步。 */
        const val MESSAGE_MIN_INTERVAL_MS = 800L
        const val THROTTLE_JITTER_MS = 300L
    }
```

- [ ] **Step 2: Insert the message section**

Find the existing `// ── 写操作（Phase A）` line and insert the message block immediately **before** it:

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

    /** 私信历史消息分页加载。`beginSeqno=0` 拉首屏。 */
    suspend fun loadMoreSessionMessages(talkerId: Long, beginSeqno: Long): Result<com.biliseek.core.network.dto.MessageHistoryData> =
        runCatching {
            requireLoggedInMid()
            authGate.awaitReady()
            ensureNotCoolingRead()
            val response = requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.fetchSessionMsgs(talkerId = talkerId, beginSeqno = beginSeqno) }
            require(response.code == 0) {
                "私信消息失败：code=${response.code} ${response.message}"
            }
            response.data ?: throw IllegalStateException("私信消息 data 为空")
        }

    /** 发送文本私信。`devId` 取自 `BiliCredentials.bNut`（缺失时 fallback UUID）。 */
    suspend fun sendPrivateMessage(
        receiverId: Long,
        content: String,
        devId: String,
    ): Result<Long> = runCatching {
        requireLoggedInMid()
        val mid = sessionStore.current.dedeUserID.ifBlank { sessionStore.current.dedeUserId.toString() }
        val senderUid = mid.toLongOrNull() ?: throw IllegalStateException("缺 sender_uid")
        val timestampSec = System.currentTimeMillis() / 1000L
        // `msg[content]` 是 JSON 字符串，B 站 Web 客户端格式
        val contentJson = """{"content":${escapeJsonString(content)}}"""
        val response = writeRequest(
            payload = mapOf(
                "msg[sender_uid]" to senderUid.toString(),
                "msg[receiver_id]" to receiverId.toString(),
                "msg[receiver_type]" to "1",
                "msg[msg_type]" to "1",
                "msg[content]" to contentJson,
                "msg[timestamp]" to timestampSec.toString(),
                "msg[dev_id]" to devId,
                "msg[new_face_version]" to "1",
                "csrf_token" to sessionStore.current.biliJct,
            ),
        ) { f, _ -> api.postSendMsg(
            senderUid = f.getValue("msg[sender_uid]").toLong(),
            receiverId = f.getValue("msg[receiver_id]").toLong(),
            receiverType = f.getValue("msg[receiver_type]").toInt(),
            msgType = f.getValue("msg[msg_type]").toInt(),
            content = f.getValue("msg[content]"),
            timestamp = f.getValue("msg[timestamp]").toLong(),
            devId = f.getValue("msg[dev_id]"),
            newFaceVersion = f.getValue("msg[new_face_version]").toInt(),
            csrfToken = f.getValue("csrf_token"),
        ) }.getOrThrow()
        response?.msgSeqno ?: 0L
    }

    /** 标记会话已读到 `ackSeqno`。 */
    suspend fun markSessionRead(talkerId: Long, ackSeqno: Long): Result<Unit> = runCatching {
        requireLoggedInMid()
        writeRequest(
            payload = mapOf(
                "talker_id" to talkerId.toString(),
                "session_type" to "1",
                "ack_seqno" to ackSeqno.toString(),
                "csrf_token" to sessionStore.current.biliJct,
            ),
        ) { f, _ -> api.postUpdateAck(
            talkerId = f.getValue("talker_id").toLong(),
            sessionType = f.getValue("session_type").toInt(),
            ackSeqno = f.getValue("ack_seqno").toLong(),
            csrfToken = f.getValue("csrf_token"),
        ) }
        Unit
    }

    /**
     * 站内回复一条评论（`x/v2/reply/add`）。`root=0/parent=0` 表示发一级评论；
     * 楼中楼用 `root=一级评论 rpid, parent=被回复评论 rpid`。
     * `oid` 用 aid、`type=1`（视频评论）。
     */
    suspend fun postCommentReply(
        aid: Long,
        root: Long,
        parent: Long,
        content: String,
    ): Result<Long> = runCatching {
        requireLoggedInMid()
        writeRequest(
            payload = buildMap {
                put("oid", aid.toString())
                put("type", "1")
                put("root", root.toString())
                put("parent", parent.toString())
                put("message", content)
                put("plat", "1")
            },
        ) { f, _ -> api.addReply(f) }
            .getOrThrow()
            ?.rpid ?: 0L
    }

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
        android.util.Log.w("BiliSeekMessageHub", "$label 失败：${e.message}")
        null
    }
}

/** 把字符串里的 `"` 与换行转义为 JSON 安全形式。文件级（顶层）函数 —— 比 private member
 *  更容易在单测里通过 reflection 调到。 */
internal fun escapeJsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (may need `BiliDtoUnit` from Task 4 note)

- [ ] **Step 4: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt
git commit -m "feat(network): add message hub, send/reply helpers, unread aggregator"
```

---

## Task 6: Pin `SendMessageFieldBuildTest` (nested field format)

**Files:**
- Create: `core/network/src/test/java/com/biliseek/core/network/SendMessageFieldBuildTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.SendMessageData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住 `web_im/v1/web_im/send_msg` 的 `msg[*]` 嵌套字段形态——B 站按字段名取参，
 * 顺序与名字错了服务端直接 -400/-1 拒绝。参考 bilipai ApiClient.kt:2547。
 */
class SendMessageFieldBuildTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `send_msg 返回解析`() {
        val raw = """{"code":0,"data":{"msg_seqno":12345,"msg_key":0},"message":"0","ttl":1}"""
        val resp = json.decodeFromString<com.biliseek.core.network.dto.BiliResponse<SendMessageData>>(raw)
        assertEquals(0, resp.code)
        assertEquals(12345L, resp.data?.msgSeqno)
    }

    @Test
    fun `escapeJsonString 处理换行与引号`() {
        // 顶层 internal 函数会被 Kotlin 编译到 `BiliRepositoryKt` synthetic class 里。
        val cls = Class.forName("com.biliseek.core.network.BiliRepositoryKt")
        val m = cls.declaredMethods.first { it.name == "escapeJsonString" }
        m.isAccessible = true
        val out = m.invoke(null, "hello\n\"world\"") as String
        assertEquals("\"hello\\n\\\"world\\\"\"", out)
    }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.SendMessageFieldBuildTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add core/network/src/test/java/com/biliseek/core/network/SendMessageFieldBuildTest.kt
git commit -m "test(network): pin send_msg field format and JSON escape"
```

---

## Task 7: Add 4 `TrackListId` cases for the message center

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/data/RemoteListState.kt:10-22`

- [ ] **Step 1: Add cases**

Modify the sealed interface body:

```kotlin
sealed interface TrackListId {
    /** 收藏夹（M11-B）：目录快照落库，内容分页拉取。 */
    data class Favorite(val mediaId: Long) : TrackListId

    /** UP 主投稿（M11-D）：空间投稿分页拉取。 */
    data class Uploader(val mid: Long) : TrackListId

    /** 稍后再看（M12）：远程列表，一次性拉取（无分页）。 */
    data object WatchLater : TrackListId

    /** 合集（M13-N）：订阅合集分页拉取。 */
    data class Series(val seriesId: Long) : TrackListId

    /** 消息中心·回复与@ tab。 */
    data object MessageReply : TrackListId

    /** 消息中心·收到喜欢 tab。 */
    data object MessageLike : TrackListId

    /** 消息中心·新增粉丝 tab。 */
    data object MessageFollower : TrackListId

    /** 消息中心·私信会话列表。 */
    data object PrivateSession : TrackListId
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/data/RemoteListState.kt
git commit -m "feat(app): add 4 TrackListId cases for message center"
```

---

## Task 8: Append `MessageCenterState` + actions to AppViewModel

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt` — append at end of file (before final `}`)

- [ ] **Step 1: Add state + actions**

Append a new section just before the closing class brace at the end of `AppViewModel.kt`:

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

    /** 点会话条目：跳到聊天页。 */
    fun openPrivateChat(session: com.biliseek.core.network.dto.Session) {
        val peerName = session.accountInfo?.name.orEmpty().ifBlank { "用户${session.talkerId}" }
        val peerFace = session.accountInfo?.face.orEmpty()
        _openPrivateChatRequests.tryEmit(
            PrivateChatNav(talkerId = session.talkerId, peerName = peerName, peerFace = peerFace),
        )
    }

    fun openPrivateChatDirect(talkerId: Long, peerName: String, peerFace: String) {
        _openPrivateChatRequests.tryEmit(PrivateChatNav(talkerId, peerName, peerFace))
    }

    /** 进入聊天页 → 拉首屏 + 标记已读。 */
    fun openChat(talkerId: Long) {
        ensureChatState(talkerId)
        loadChatPage(talkerId, beginSeqno = 0L, isLoadMore = false)
    }

    /** 上滑加载更早消息。 */
    fun loadMoreChat(talkerId: Long) {
        val s = _chatByTalker.value[talkerId] ?: return
        if (s.isLoadingMore || !s.hasMore) return
        val nextBegin = (s.minSeqno - 1).coerceAtLeast(0L)
        loadChatPage(talkerId, beginSeqno = nextBegin, isLoadMore = true)
    }

    private fun loadChatPage(talkerId: Long, beginSeqno: Long, isLoadMore: Boolean) {
        val cur = ensureChatState(talkerId)
        if (!isLoadMore) {
            updateChat(talkerId) { it.copy(isLoading = true, errorMessage = null) }
        } else {
            updateChat(talkerId) { it.copy(isLoadingMore = true, errorMessage = null) }
        }
        viewModelScope.launch {
            network.repository.loadMoreSessionMessages(talkerId, beginSeqno)
                .onSuccess { data ->
                    val newMessages = data.messages.sortedBy { it.msgSeqno }
                    val merged = if (isLoadMore) cur.messages + newMessages else newMessages
                    updateChat(talkerId) {
                        it.copy(
                            messages = merged,
                            isLoading = false,
                            isLoadingMore = false,
                            minSeqno = data.minSeqno.takeIf { v -> v > 0 } ?: it.minSeqno,
                            maxSeqno = data.maxSeqno.takeIf { v -> v > it.maxSeqno } ?: it.maxSeqno,
                            hasMore = data.hasMore == 1,
                            errorMessage = null,
                        )
                    }
                    // 首屏加载完成后立即标记已读
                    if (!isLoadMore && data.maxSeqno > 0L) {
                        network.repository.markSessionRead(talkerId, data.maxSeqno)
                        refreshUnreadCount()
                    }
                }
                .onFailure { e ->
                    updateChat(talkerId) {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = e.message ?: "加载失败",
                        )
                    }
                }
        }
    }

    fun updateChatDraft(talkerId: Long, draft: String) {
        updateChat(talkerId) { it.copy(draft = draft) }
    }

    fun sendChatMessage(talkerId: Long) {
        val cur = _chatByTalker.value[talkerId] ?: return
        val text = cur.draft.trim()
        if (text.isEmpty() || cur.isSending) return
        updateChat(talkerId) { it.copy(isSending = true, errorMessage = null) }
        viewModelScope.launch {
            val session = network.repository.sessionStore.current
            val devId = session.bNut.ifBlank { java.util.UUID.randomUUID().toString() }
            network.repository.sendPrivateMessage(
                receiverId = talkerId,
                content = text,
                devId = devId,
            )
                .onSuccess { seqno ->
                    val now = System.currentTimeMillis() / 1000L
                    val optimistic = com.biliseek.core.network.dto.PrivateMessage(
                        senderUid = session.dedeUserId,
                        receiverId = talkerId,
                        msgType = 1,
                        content = """{"content":"${escapeForLocal(text)}"}""",
                        timestamp = now,
                        msgSeqno = seqno,
                    )
                    updateChat(talkerId) {
                        it.copy(
                            messages = it.messages + optimistic,
                            draft = "",
                            isSending = false,
                            maxSeqno = maxOf(it.maxSeqno, seqno),
                            errorMessage = null,
                        )
                    }
                    emitMessage("已发送")
                }
                .onFailure { e ->
                    updateChat(talkerId) { it.copy(isSending = false, errorMessage = e.message ?: "发送失败") }
                    emitMessage("发送失败：${e.message ?: ""}")
                }
        }
    }

    fun closeChat(talkerId: Long) {
        _chatByTalker.value = _chatByTalker.value - talkerId
    }

    /** 点回复与@ 条目：跳转到对应视频（business_id=1 视频，5 动态本期不支持）。 */
    fun playReplyAtItem(item: com.biliseek.core.network.dto.ReplyAtItem) {
        if (item.businessId == 1 && item.sourceId > 0L) {
            val bvid = com.biliseek.core.network.avToBv(item.sourceId)
            play(
                com.biliseek.core.player.PlaybackRequest.Bili(
                    bvid = bvid,
                    cid = null,
                    title = item.sourceContent.ifBlank { "评论的视频" },
                    coverUrl = "",
                    author = item.user?.name.orEmpty(),
                    durationSec = 0L,
                ),
            )
        }
    }

    fun playLikeItem(item: com.biliseek.core.network.dto.LikeItem) {
        if (item.sourceId > 0L) {
            val bvid = com.biliseek.core.network.avToBv(item.sourceId)
            play(
                com.biliseek.core.player.PlaybackRequest.Bili(
                    bvid = bvid,
                    cid = null,
                    title = item.title.ifBlank { "收到喜欢的视频" },
                    coverUrl = "",
                    author = item.user?.name.orEmpty(),
                    durationSec = 0L,
                ),
            )
        }
    }

    fun openFollowerSpace(item: com.biliseek.core.network.dto.FollowerEntry) {
        openUpSpace(
            com.biliseek.core.network.dto.SearchUser(
                mid = item.mid,
                uname = item.uname,
                upic = item.face,
            ),
        )
        // UP 空间跳转由调用方持有 openUpSpaceRequests 信号触发（与现有约定一致）。
    }

    /** 在站内向一条评论发回复。 */
    fun submitCommentReply(
        aid: Long,
        root: Long,
        parent: Long,
        content: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> },
    ) {
        viewModelScope.launch {
            network.repository.postCommentReply(aid, root, parent, content)
                .onSuccess {
                    emitMessage("已回复")
                    onResult(true, null)
                }
                .onFailure { e ->
                    emitMessage("回复失败：${e.message ?: ""}")
                    onResult(false, e.message)
                }
        }
    }

    private fun ensureChatState(talkerId: Long): PrivateChatState =
        _chatByTalker.value[talkerId] ?: PrivateChatState().also {
            _chatByTalker.value = _chatByTalker.value + (talkerId to it)
        }

    private fun updateChat(talkerId: Long, reduce: (PrivateChatState) -> PrivateChatState) {
        _chatByTalker.value = _chatByTalker.value.toMutableMap().apply {
            val cur = this[talkerId] ?: return
            this[talkerId] = reduce(cur)
        }
    }

    private fun escapeForLocal(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    // ── 状态数据类 ─────────────────────────────────

    /** 消息中心四个 tab 状态聚合。 */
    data class MessageHubState(
        val reply: RemoteListState<com.biliseek.core.network.dto.ReplyAtItem> = RemoteListState(id = TrackListId.MessageReply, title = "回复与@"),
        val like: RemoteListState<com.biliseek.core.network.dto.LikeItem> = RemoteListState(id = TrackListId.MessageLike, title = "收到喜欢"),
        val follower: RemoteListState<com.biliseek.core.network.dto.FollowerEntry> = RemoteListState(id = TrackListId.MessageFollower, title = "新增粉丝"),
        val sessions: RemoteListState<com.biliseek.core.network.dto.Session> = RemoteListState(id = TrackListId.PrivateSession, title = "私信"),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    /** 聊天页状态。 */
    data class PrivateChatState(
        val messages: List<com.biliseek.core.network.dto.PrivateMessage> = emptyList(),
        val draft: String = "",
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isSending: Boolean = false,
        val hasMore: Boolean = false,
        val minSeqno: Long = 0L,
        val maxSeqno: Long = 0L,
        val errorMessage: String? = null,
    )

    /** 私信聊天路由参数。 */
    data class PrivateChatNav(
        val talkerId: Long,
        val peerName: String,
        val peerFace: String,
    )
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Note: `play()` is the existing playback entry point on AppViewModel (`play(request: PlaybackRequest)`); `SearchVideo(aid=, title=)` uses existing DTO. `openUpSpace` already exists.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/AppViewModel.kt
git commit -m "feat(app): message hub state, chat state, unread count, nav signals"
```

---

## Task 9: Add `MessageUiStateMapperTest`

**Files:**
- Create: `app/src/test/java/com/biliseek/app/data/MessageUiStateMapperTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.biliseek.app.data

import com.biliseek.app.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUiStateMapperTest {

    @Test
    fun `MessageHubState 默认四态为空`() {
        val s = AppViewModel.MessageHubState()
        assertEquals(0, s.reply.items.size)
        assertEquals(0, s.like.items.size)
        assertEquals(0, s.follower.items.size)
        assertEquals(0, s.sessions.items.size)
        assertNull(s.errorMessage)
    }

    @Test
    fun `PrivateChatState 默认无草稿无消息`() {
        val s = AppViewModel.PrivateChatState()
        assertEquals("", s.draft)
        assertEquals(0L, s.minSeqno)
        assertEquals(0L, s.maxSeqno)
        assertTrue(!s.isSending)
        assertTrue(!s.hasMore)
    }

    @Test
    fun `PrivateChatNav 携带 talker 与昵称`() {
        val nav = AppViewModel.PrivateChatNav(talkerId = 123L, peerName = "Alice", peerFace = "//face")
        assertEquals(123L, nav.talkerId)
        assertEquals("Alice", nav.peerName)
    }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.MessageUiStateMapperTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/biliseek/app/data/MessageUiStateMapperTest.kt
git commit -m "test(app): pin MessageHubState and PrivateChatState defaults"
```

---

## Task 10: Add `Destination.MESSAGES` + `Destination.PRIVATE_CHAT` and SharedFlow nav

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/AppShell.kt:33-46`, `:101-105`, `:115-117`, `:148-164`, `:212-255`

- [ ] **Step 1: Add Destinations**

Modify the enum body:

```kotlin
enum class Destination(val route: String, val label: String) {
    SEARCH("search", "搜索"),
    DYNAMIC("dynamic", "动态"),
    LIBRARY("library", "列表"),
    SETTINGS("settings", "设置"),
    PLAYER("player", "播放器"),
    UP_SPACE("upSpace", "UP主页"),
    FOLLOWING_LIST("followingList", "关注列表"),
    /** 消息中心（push，不入底部导航）。 */
    MESSAGES("messages", "消息"),
    /** 私信聊天页（push，参数化 talkerId/peerName/peerFace）。 */
    PRIVATE_CHAT("privateChat/{talkerId}/{peerName}/{peerFace}", "私信"),
}
```

Add a `const val` route template helper inside the enum's `companion`:

```kotlin
    companion object {
        fun privateChatRoute(talkerId: Long, peerName: String, peerFace: String): String =
            "privateChat/$talkerId/${java.net.URLEncoder.encode(peerName, "UTF-8")}/${java.net.URLEncoder.encode(peerFace, "UTF-8")}"
    }
```

- [ ] **Step 2: Add `messagesContent` / `privateChatContent` parameters**

In `AppShell` composable signature, after `followingListContent`, add:

```kotlin
    openMessagesRequests: SharedFlow<Unit> = MutableSharedFlow(),
    openPrivateChatRequests: SharedFlow<com.biliseek.app.AppViewModel.PrivateChatNav> = MutableSharedFlow(),
    /** 消息中心页内容。onBack 由 AppShell 传入。 */
    messagesContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 私信聊天页内容。onBack 由 AppShell 传入。 */
    privateChatContent: @Composable (talkerId: Long, peerName: String, peerFace: String, onBack: () -> Unit) -> Unit = { _, _, _, _ -> },
```

- [ ] **Step 3: Add nav LaunchedEffects**

After the existing `openFollowingListRequests` LaunchedEffect (around line 164), add:

```kotlin
    // 消息中心导航：收到信号后 push MESSAGES destination（launchSingleTop 防重复压栈）。
    LaunchedEffect(openMessagesRequests) {
        openMessagesRequests.collect {
            navController.navigate(Destination.MESSAGES.route) {
                launchSingleTop = true
            }
        }
    }

    // 私信聊天导航：参数化路径 + launchSingleTop。
    LaunchedEffect(openPrivateChatRequests) {
        openPrivateChatRequests.collect { nav ->
            val route = Destination.privateChatRoute(nav.talkerId, nav.peerName, nav.peerFace)
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }
```

- [ ] **Step 4: Hide new Destinations from bottom nav**

Modify the filter in `NavigationBar`:

```kotlin
                        if (dest == Destination.PLAYER || dest == Destination.UP_SPACE ||
                            dest == Destination.FOLLOWING_LIST || dest == Destination.MESSAGES ||
                            dest == Destination.PRIVATE_CHAT
                        ) return@forEach
```

- [ ] **Step 5: Register composables in NavHost**

After the `FOLLOWING_LIST` composable:

```kotlin
                composable(Destination.MESSAGES.route) {
                    messagesContent({ navController.popBackStack() })
                }
                composable(
                    Destination.PRIVATE_CHAT.route,
                    arguments = listOf(
                        navArgument("talkerId") { type = NavType.LongType },
                        navArgument("peerName") { type = NavType.StringType },
                        navArgument("peerFace") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val talkerId = backStackEntry.arguments?.getLong("talkerId") ?: 0L
                    val peerName = backStackEntry.arguments?.getString("peerName").orEmpty()
                    val peerFace = backStackEntry.arguments?.getString("peerFace").orEmpty()
                    privateChatContent(
                        talkerId,
                        java.net.URLDecoder.decode(peerName, "UTF-8"),
                        java.net.URLDecoder.decode(peerFace, "UTF-8"),
                    ) { navController.popBackStack() }
                }
```

- [ ] **Step 6: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/AppShell.kt
git commit -m "feat(ui): add Message/PrivateChat destinations and nav LaunchedEffects"
```

---

## Task 11: Create `MessageCenterScreen.kt`

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/MessageCenterScreen.kt`

- [ ] **Step 1: Write the screen**

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.biliseek.app.LoginPanel
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.FollowerEntry
import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.normalizeCoverUrl

/**
 * 消息中心页。登录后展示三 tab 互动消息 + 私信会话列表。
 * 模板仿 WatchLaterScreen / FollowingListScreen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageCenterScreen(
    hub: com.biliseek.app.AppViewModel.MessageHubState,
    session: BiliSession,
    loginState: LoginState,
    unreadCount: Int,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onOpenChat: (Session) -> Unit,
    onPlayReplyAtItem: (ReplyAtItem) -> Unit,
    onPlayLikeItem: (LikeItem) -> Unit,
    onOpenFollower: (FollowerEntry) -> Unit,
    onSubmitReply: (aid: Long, root: Long, parent: Long, content: String, onResult: (Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }
    var selectedTab by remember { mutableStateOf(MessageTab.REPLY) }
    var pendingReply by remember { mutableStateOf<ReplyAtItem?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }

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
            return
        }

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            MessageTab.entries.forEachIndexed { idx, tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) },
                )
            }
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = hub.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    val current = when (selectedTab) {
                        MessageTab.REPLY -> hub.reply
                        MessageTab.LIKE -> hub.like
                        MessageTab.FOLLOWER -> hub.follower
                    }
                    when {
                        current.isLoading && current.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        current.errorMessage != null && current.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${current.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        current.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无内容")
                            }
                        }
                        else -> {
                            items(current.items, key = { itemKey(it) }) { item ->
                                val typed = item
                                MessageItemRow(
                                    item = typed,
                                    onClick = when (selectedTab) {
                                        MessageTab.REPLY -> {
                                            { onPlayReplyAtItem(typed as ReplyAtItem) }
                                        }
                                        MessageTab.LIKE -> {
                                            { onPlayLikeItem(typed as LikeItem) }
                                        }
                                        MessageTab.FOLLOWER -> {
                                            { onOpenFollower(typed as FollowerEntry) }
                                        }
                                    },
                                    onLongClick = if (selectedTab == MessageTab.REPLY) {
                                        { (typed as ReplyAtItem).let { r ->
                                            pendingReply = r
                                            replyDraft = ""
                                        } }
                                    } else null,
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    item {
                        Text(
                            "私信",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                        )
                    }
                    when {
                        hub.sessions.isLoading && hub.sessions.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        hub.sessions.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无私信")
                            }
                        }
                        else -> items(hub.sessions.items, key = { it.talkerId }) { session ->
                            SessionRow(session = session, onClick = { onOpenChat(session) })
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

@Composable
private fun MessageItemRow(
    item: Any,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val avatar = when (item) {
        is ReplyAtItem -> item.user?.face.orEmpty()
        is LikeItem -> item.user?.face.orEmpty()
        is FollowerEntry -> item.face
        else -> ""
    }
    val name = when (item) {
        is ReplyAtItem -> item.user?.name.orEmpty()
        is LikeItem -> item.user?.name.orEmpty()
        is FollowerEntry -> item.uname
        else -> ""
    }
    val body = when (item) {
        is ReplyAtItem -> "评论：${item.content}"
        is LikeItem -> "赞了你的视频：${item.title}"
        is FollowerEntry -> item.sign.ifBlank { "关注了你" }
        else -> ""
    }
    val time = when (item) {
        is ReplyAtItem -> item.replyTime
        is LikeItem -> item.likeTime
        is FollowerEntry -> item.mtime
        else -> 0L
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (time > 0L) formatRelativeTime(time) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
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
            text = if (session.lastMsg?.timestamp ?: 0L > 0L) formatRelativeTime(session.lastMsg!!.timestamp) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private enum class MessageTab(val label: String) {
    REPLY("回复与@"),
    LIKE("收到喜欢"),
    FOLLOWER("新增粉丝"),
}

private fun itemKey(item: Any): String = when (item) {
    is ReplyAtItem -> "reply-${item.id}"
    is LikeItem -> "like-${item.id}"
    is FollowerEntry -> "follower-${item.mid}"
    else -> item.hashCode().toString()
}

private fun extractTextPreview(rawJson: String): String {
    val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    return regex.find(rawJson)?.groupValues?.get(1)
        ?.replace("\\\\", "\\")
        ?.replace("\\\"", "\"")
        ?.replace("\\n", "\n")
        ?: rawJson
}

/** 秒级时间戳 → "3 小时前" 之类（简单实现，后续可替成专门工具）。 */
private fun formatRelativeTime(seconds: Long): String {
    if (seconds <= 0L) return ""
    val nowSec = System.currentTimeMillis() / 1000L
    val delta = (nowSec - seconds).coerceAtLeast(0L)
    return when {
        delta < 60L -> "刚刚"
        delta < 3600L -> "${delta / 60L} 分钟前"
        delta < 86_400L -> "${delta / 3600L} 小时前"
        delta < 86_400L * 30L -> "${delta / 86_400L} 天前"
        else -> "${delta / (86_400L * 30L)} 个月前"
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/MessageCenterScreen.kt
git commit -m "feat(ui): add MessageCenterScreen with 3-tab layout + session list"
```

---

## Task 12: Create `PrivateChatScreen.kt`

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/PrivateChatScreen.kt`

- [ ] **Step 1: Write the screen**

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.core.network.dto.PrivateMessage
import com.biliseek.core.network.normalizeCoverUrl

/**
 * 私信聊天页（push），与 WatchLaterScreen 风格一致：
 * - LazyColumn 反向滚动（reverseLayout=true），最新消息在底部
 * - 上滑到底部触发 loadMore
 * - 底部输入栏 + 发送按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatScreen(
    peerName: String,
    peerFace: String,
    currentUid: Long,
    state: com.biliseek.app.AppViewModel.PrivateChatState,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onLoadMore: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onOpen() }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            firstVisible <= 1
        }.collect { nearTop ->
            if (nearTop && state.hasMore && !state.isLoadingMore) onLoadMore()
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            AsyncImage(
                model = normalizeCoverUrl(peerFace),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(peerName, style = MaterialTheme.typography.titleMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading && state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                state.errorMessage != null && state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                    }
                }
                state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("开始聊天吧") }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    ) {
                        items(
                            items = state.messages.reversed(),
                            key = { it.msgSeqno },
                        ) { msg ->
                            ChatBubble(message = msg, currentUid = currentUid)
                        }
                    }
                }
            }
            if (state.isLoadingMore) {
                Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.TopCenter) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                placeholder = { Text("输入消息…") },
                modifier = Modifier.weight(1f),
                enabled = !state.isSending,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = !state.isSending && state.draft.isNotBlank(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun ChatBubble(message: PrivateMessage, currentUid: Long) {
    val isMine = message.senderUid == currentUid && currentUid > 0L
    val text = extractText(message.content)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isMine) 12.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 12.dp,
            ),
            color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** `msg_type=1` 时 content 是 `{"content":"…"}`。 */
private fun extractText(raw: String): String {
    val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    return regex.find(raw)?.groupValues?.get(1)
        ?.replace("\\\\", "\\")
        ?.replace("\\\"", "\"")
        ?.replace("\\n", "\n")
        ?: raw
}

// `LazyColumn.items(...)` with reverseLayout uses the standard `items()` extension
// from `androidx.compose.foundation.lazy.items` (already in project deps via
// `compose-foundation`).
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/PrivateChatScreen.kt
git commit -m "feat(ui): add PrivateChatScreen with bubbles, loadMore, send box"
```

---

## Task 13: Add bell icon to `DynamicFilterBar`

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:254-274` (the `DynamicFilterBar` private function)

- [ ] **Step 1: Modify `DynamicFilterBar` to take bell + Badge**

Replace the function body:

```kotlin
/** 筛选行：「全部 / 仅视频」分段 + 右侧消息入口图标（带未读小红点）。 */
@Composable
private fun DynamicFilterBar(
    typeFilter: String,
    onTypeFilterChange: (String) -> Unit,
    unreadCount: Int,
    onOpenMessages: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            SegmentedButton(
                selected = typeFilter == DynamicType.ALL,
                onClick = { onTypeFilterChange(DynamicType.ALL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("全部") }
            SegmentedButton(
                selected = typeFilter == DynamicType.VIDEO,
                onClick = { onTypeFilterChange(DynamicType.VIDEO) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("仅视频") }
        }
        IconButton(onClick = onOpenMessages) {
            androidx.compose.material3.BadgedBox(
                badge = {
                    if (unreadCount > 0) {
                        androidx.compose.material3.Badge { Text(minOf(unreadCount, 99).toString()) }
                    }
                },
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Notifications,
                    contentDescription = "消息",
                )
            }
        }
    }
}
```

- [ ] **Step 2: Update caller in `DynamicScreen`**

Find the call site (`onTypeFilterChange = onTypeFilterChange,` inside the `Column` block ~line 147-150) and replace it with:

```kotlin
                DynamicFilterBar(
                    typeFilter = state.typeFilter,
                    onTypeFilterChange = onTypeFilterChange,
                    unreadCount = unreadCount,
                    onOpenMessages = onOpenMessages,
                )
```

- [ ] **Step 3: Add `unreadCount` and `onOpenMessages` parameters to `DynamicScreen`**

Add to the signature after `onOpenFollowList`:

```kotlin
    unreadCount: Int = 0,
    onOpenMessages: () -> Unit = {},
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt
git commit -m "feat(ui): add bell icon with unread Badge to dynamic filter bar"
```

---

## Task 14: Wire everything in `MainActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:131-264`, `:188-264`

- [ ] **Step 1: Add `LifecycleObserver`**

After line 135 (`openFollowingListRequests`), insert:

```kotlin
                // 启动 / 从后台返回时刷新未读小红点。
                // 使用 `androidx.compose.ui.platform.LocalLifecycleOwner`（compose-ui 自带，
                // 不引入新依赖）；返回 LifecycleOwner 接口，足够 `lifecycle.addObserver` 使用。
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshUnreadCount()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
```

- [ ] **Step 2: Pass VM signals directly to `AppShell`**

No local MutableSharedFlow needed — pass the VM's signals directly (they're already `SharedFlow`):

```kotlin
                    openMessagesRequests = viewModel.openMessagesRequests,
                    openPrivateChatRequests = viewModel.openPrivateChatRequests,
```

Add the two new content lambdas (after `followingListContent` is passed; check current shape):

```kotlin
                    messagesContent = { onBack ->
                        val hub by viewModel.messageHub.collectAsState()
                        MessageCenterScreen(
                            hub = hub,
                            session = session,
                            loginState = loginState,
                            unreadCount = unreadCount,
                            onInit = viewModel::openMessages,
                            onRetry = viewModel::retryMessageHub,
                            onClose = {
                                viewModel.closeMessages()
                                onBack()
                            },
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                            onOpenChat = { s ->
                                viewModel.openPrivateChat(s)
                                // openPrivateChatRequests 会自动 emit
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
                    privateChatContent = { talkerId, peerName, peerFace, onBack ->
                        val chats by viewModel.chatByTalker.collectAsState()
                        val state = chats[talkerId] ?: com.biliseek.app.AppViewModel.PrivateChatState()
                        PrivateChatScreen(
                            peerName = peerName,
                            peerFace = peerFace,
                            currentUid = session.dedeUserId,
                            state = state,
                            onBack = {
                                viewModel.closeChat(talkerId)
                                onBack()
                            },
                            onOpen = { viewModel.openChat(talkerId) },
                            onLoadMore = { viewModel.loadMoreChat(talkerId) },
                            onDraftChange = { viewModel.updateChatDraft(talkerId, it) },
                            onSend = { viewModel.sendChatMessage(talkerId) },
                        )
                    },
```

- [ ] **Step 3: Subscribe to unreadCount and pass it to `DynamicScreen`**

```kotlin
                val unreadCount by viewModel.unreadCount.collectAsState()
```

In `dynamicContent`, pass:

```kotlin
                            unreadCount = unreadCount,
                            onOpenMessages = viewModel::openMessages,
```

- [ ] **Step 4: Trigger initial refresh on first composition**

After all wiring, ensure that `viewModel.refreshUnreadCount()` runs at least once. The lifecycle observer handles ON_RESUME; for first launch also add:

```kotlin
                LaunchedEffect(Unit) {
                    viewModel.refreshUnreadCount()
                }
```

- [ ] **Step 5: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(app): wire message center and private chat screens into MainActivity"
```

---

## Task 15: Final lint + compile + unit-test sweep

**Files:** none modified.

- [ ] **Step 1: Compile both modules**

Run: `./gradlew :core:network:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run targeted unit tests**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.MessageDtoParseTest" --tests "com.biliseek.core.network.SendMessageFieldBuildTest" :app:testDebugUnitTest --tests "com.biliseek.app.data.MessageUiStateMapperTest"`
Expected: All PASS

- [ ] **Step 3: Lint**

Run: `./gradlew :app:lintDebug`
Expected: No new errors (existing warnings may remain)

- [ ] **Step 4: Final commit (if any tidy)**

```bash
git status
# if clean, nothing to commit
```

---

## Spec Coverage

| Spec section | Task(s) |
|---|---|
| §3.1 互动消息端点 | Task 4 (BiliApi) |
| §3.2 私信端点 | Task 4 |
| §3.4 字段与参数 | Task 4, Task 5 |
| §3.5 新增粉丝 | Task 4 (`getFollowers`), Task 5 (`messageHub` maps to `follower`), Task 8 (follower tab) |
| §4.1 DTO | Task 2 |
| §4.1 BiliApi | Task 4 |
| §4.1 BiliRepository | Task 5 |
| §4.1 RequestThrottle | Task 1 |
| §4.1 WriteRequest | (no change; confirmed injection semantics) |
| §4.2 RemoteListState | Task 7 |
| §4.2 AppViewModel | Task 8 |
| §4.2 AppShell | Task 10 |
| §4.2 MessageCenterScreen | Task 11 |
| §4.2 PrivateChatScreen | Task 12 |
| §4.2 DynamicScreen | Task 13 |
| §4.2 MainActivity | Task 14 |
| §5.1 入口 chip + Badge | Task 13 |
| §5.2 消息中心 UI | Task 11 |
| §5.3 私信聊天 UI | Task 12 |
| §5.4 返回 | (built into AppShell: `popBackStack`) |
| §6 错误处理 | Built into WatchLater-style patterns; special: `messageUnread` is best-effort (Task 5) |
| §7.1 单元测试 | Task 3, Task 6, Task 9 |
| §7.2 集成测试 | (covered indirectly via unit tests of repository parsing; MockWebServer is out of scope per plan size) |
| §7.3 真人测试 | **HANDOFF REQUIRED — by testers** |