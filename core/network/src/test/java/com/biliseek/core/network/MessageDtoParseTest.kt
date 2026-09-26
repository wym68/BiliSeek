package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.FollowersData
import com.biliseek.core.network.dto.LikeFeedData
import com.biliseek.core.network.dto.MessageHistoryData
import com.biliseek.core.network.dto.MsgfeedUnreadData
import com.biliseek.core.network.dto.ReplyFeedData
import com.biliseek.core.network.dto.SessionListData
import com.biliseek.core.network.dto.SessionUnreadData
import com.biliseek.core.network.dto.UserCard
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

    /** 字段形状按 bilibili-API-collect `message/msg.md` 的 x/msgfeed/reply 示例构造（非线上抓取）。 */
    @Test
    fun `回复与@ 列表解析：正文取 source_content，视频 aid 取 subject_id`() {
        val data = json.decodeFromString<BiliResponse<ReplyFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "cursor": { "is_end": false, "id": 11, "time": 1700000000 },
                "items": [
                  {
                    "id": 11,
                    "user": { "mid": 222, "fans": 0, "nickname": "用户A", "avatar": "//fa", "follow": false },
                    "item": {
                      "subject_id": 170001,
                      "root_id": 5,
                      "source_id": 7,
                      "target_id": 6,
                      "type": "reply",
                      "business_id": 1,
                      "business": "评论",
                      "title": "我的视频标题",
                      "desc": "",
                      "image": "",
                      "uri": "https://www.bilibili.com/video/BV17x411w7KC",
                      "native_uri": "bilibili://comment/detail/1/170001/5/?subType=0&anchor=7",
                      "detail_title": "",
                      "root_reply_content": "根评论内容",
                      "source_content": "对方回复我的正文",
                      "target_reply_content": "我被回复的那条评论"
                    },
                    "counts": 1,
                    "is_multi": 0,
                    "reply_time": 1700000000
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val items = data.data?.items.orEmpty()
        assertEquals(1, items.size)
        val it = items[0]
        assertEquals("对方回复我的正文", it.content)
        assertEquals("我被回复的那条评论", it.quotedContent)
        assertEquals("我的视频标题", it.title)
        assertEquals(170001L, it.subjectId)
        assertEquals(5L, it.replyRootId)
        assertEquals(7L, it.replyParentId)
        assertEquals("用户A", it.user?.name)
        assertEquals("//fa", it.user?.face)
        assertEquals(true, data.data?.hasMore)
    }

    @Test
    fun `回复与@ 直接评论视频：无被引用评论，root 退回对方这条自身`() {
        val data = json.decodeFromString<BiliResponse<ReplyFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "cursor": { "is_end": true, "id": 12, "time": 1700000001 },
                "items": [
                  {
                    "id": 12,
                    "user": { "mid": 223, "nickname": "用户B", "avatar": "//fb" },
                    "item": {
                      "subject_id": 170002,
                      "root_id": 0,
                      "source_id": 9,
                      "target_id": 0,
                      "business_id": 1,
                      "title": "视频",
                      "root_reply_content": "",
                      "source_content": "沙发",
                      "target_reply_content": ""
                    },
                    "reply_time": 1700000001
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val it = data.data!!.items.first()
        assertEquals("沙发", it.content)
        assertEquals("", it.quotedContent)
        assertEquals(9L, it.replyRootId)
        assertEquals(9L, it.replyParentId)
        assertEquals(false, data.data?.hasMore)
    }

    /** 字段形状按 bilibili-API-collect `message/msg.md` 的 x/msgfeed/like 示例构造（非线上抓取）。 */
    @Test
    fun `收到喜欢列表解析：对象 id 取 item_id`() {
        val data = json.decodeFromString<BiliResponse<LikeFeedData>>(
            """
            {
              "code": 0,
              "data": {
                "total": { "cursor": { "is_end": true, "id": 1, "time": 1 }, "items": [] },
                "latest": {
                  "items": [
                    {
                      "id": 22,
                      "users": [
                        { "mid": 333, "nickname": "用户C", "avatar": "//fc" },
                        { "mid": 334, "nickname": "用户D", "avatar": "//fd" }
                      ],
                      "item": {
                        "item_id": 2000,
                        "pid": 0,
                        "type": "video",
                        "business": "视频",
                        "business_id": 0,
                        "reply_business_id": 1,
                        "title": "被点赞视频",
                        "desc": "",
                        "image": "",
                        "uri": "https://www.bilibili.com/video/BV1xx411c7mD",
                        "native_uri": "bilibili://video/2000"
                      },
                      "counts": 5,
                      "like_time": 1700000000,
                      "notice_state": 0
                    }
                  ],
                  "last_view_at": 0
                }
              }
            }
            """.trimIndent(),
        )
        val items = data.data?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals(2000L, items[0].itemId)
        assertEquals("video", items[0].type)
        assertEquals("被点赞视频", items[0].title)
        assertEquals("用户C", items[0].firstUser?.name)
        assertEquals(5, items[0].counts)
        assertEquals(false, data.data?.hasMore)
    }

    @Test
    fun `用户卡片批量解析`() {
        val data = json.decodeFromString<BiliResponse<List<UserCard>>>(
            """{"code":0,"message":"0","ttl":1,"data":[{"mid":1001,"name":"真名","sex":"保密","face":"https://i0.hdslb.com/x.jpg","sign":"","rank":10000,"level":5}]}""",
        )
        assertEquals("真名", data.data?.first()?.name)
        assertEquals(1001L, data.data?.first()?.mid)
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
