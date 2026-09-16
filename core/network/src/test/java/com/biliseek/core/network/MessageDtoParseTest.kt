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
        val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return regex.find(raw)?.groupValues?.get(1)?.replace("\\\\", "\\")
            ?.replace("\\\"", "\"")
            ?: raw
    }
}
