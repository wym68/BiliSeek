package com.biliseek.core.network

import com.biliseek.core.network.dto.LikeContent
import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.dto.ReplyAtContent
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.dto.SessionPeer
import com.biliseek.core.network.dto.UserCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContentTest {

    // ── 私信内容 ──

    @Test
    fun `文字消息取 content，含转义`() {
        assertEquals("hi \"x\"\n换行", privateMessageText(1, """{"content":"hi \"x\"\n换行"}"""))
    }

    @Test
    fun `通知卡片预览取 title，全文带 text，不漏原始 JSON`() {
        val raw = """{"title":"里程碑达成通知","text":"恭喜！累计播放破万","jump_text":"查看","jump_uri":"https://x"}"""
        assertEquals("里程碑达成通知", privateMessageText(10, raw))
        assertEquals("里程碑达成通知\n恭喜！累计播放破万", privateMessageText(10, raw, full = true))
    }

    @Test
    fun `通知卡片没有 title 时退回 text`() {
        assertEquals("正文", privateMessageText(10, """{"title":"","text":"正文"}"""))
    }

    @Test
    fun `图片、撤回、表情、分享、视频、专栏各有占位`() {
        assertEquals("[图片]", privateMessageText(2, """{"url":"https://i0/x.jpg","height":1,"width":1}"""))
        assertEquals("[消息已撤回]", privateMessageText(5, "7123456789"))
        assertEquals("[表情]", privateMessageText(6, """{"url":"https://i0/e.png"}"""))
        assertEquals("[分享] 某视频", privateMessageText(7, """{"title":"某视频","author":"UP"}"""))
        assertEquals("[视频] 新投稿", privateMessageText(11, """{"title":"新投稿","cover":"c"}"""))
        assertEquals("[专栏] 文章", privateMessageText(12, """{"title":"文章"}"""))
    }

    @Test
    fun `未知类型尽量取常见文本字段，取不到给占位`() {
        assertEquals("系统提示", privateMessageText(18, """{"content":"系统提示"}"""))
        assertEquals("[暂不支持的消息类型]", privateMessageText(99, """{"foo":1}"""))
        assertEquals("[暂不支持的消息类型]", privateMessageText(99, "not json"))
    }

    @Test
    fun `文字消息 content 不是 JSON 时原样返回`() {
        assertEquals("plain", privateMessageText(1, "plain"))
    }

    // ── 回复与@ 跳转/回复目标 ──

    private fun reply(
        businessId: Int = 1,
        subjectId: Long = 170001,
        rootId: Long = 0,
        sourceId: Long = 9,
        uri: String = "",
        nativeUri: String = "",
    ) = ReplyAtItem(
        id = 1,
        item = ReplyAtContent(
            subjectId = subjectId, rootId = rootId, sourceId = sourceId,
            businessId = businessId, uri = uri, nativeUri = nativeUri,
        ),
    )

    @Test
    fun `视频评论区用 subject_id 换算 bvid，而不是 source_id`() {
        assertEquals(avToBv(170001), reply().videoBvid())
    }

    @Test
    fun `动态评论区且链接不是视频时不给 bvid`() {
        assertNull(reply(businessId = 17, uri = "https://t.bilibili.com/123").videoBvid())
    }

    @Test
    fun `非视频 business 但链接指向视频时从链接取`() {
        assertEquals(
            "BV17x411w7KC",
            reply(businessId = 0, subjectId = 0, uri = "https://www.bilibili.com/video/BV17x411w7KC").videoBvid(),
        )
    }

    @Test
    fun `只有视频评论区可站内回复`() {
        assertTrue(reply().canReplyInApp())
        assertFalse(reply(businessId = 17).canReplyInApp())
        assertFalse(reply(sourceId = 0).canReplyInApp())
    }

    // ── 收到喜欢 ──

    private fun like(type: String, itemId: Long = 2000, uri: String = "", nativeUri: String = "", business: String = "") =
        LikeItem(id = 1, item = LikeContent(itemId = itemId, type = type, uri = uri, nativeUri = nativeUri, business = business))

    @Test
    fun `被赞视频用 item_id 换算 bvid`() {
        assertEquals(avToBv(2000), like("video").videoBvid())
    }

    @Test
    fun `被赞评论不把 rpid 当 aid，改从 native_uri 找视频`() {
        assertNull(like("reply", itemId = 88888, nativeUri = "bilibili://comment/detail/1/2000/88888").videoBvid())
        assertEquals(avToBv(3000), like("reply", itemId = 88888, nativeUri = "bilibili://video/3000").videoBvid())
    }

    @Test
    fun `点赞动作文案按类型区分`() {
        assertEquals("赞了你的视频", like("video").likeActionText())
        assertEquals("赞了你的评论", like("reply").likeActionText())
        assertEquals("赞了你的动态", like("dynamic").likeActionText())
        assertEquals("赞了你的笔记", like("unknown", business = "笔记").likeActionText())
        assertEquals("赞了你的内容", like("unknown").likeActionText())
    }

    // ── 会话昵称补齐 ──

    @Test
    fun `缺 account_info 的会话用用户卡片补齐，官方号原有信息不覆盖`() {
        val sessions = listOf(
            Session(talkerId = 1),
            Session(talkerId = 2, accountInfo = SessionPeer(name = "UP主小助手", face = "//official", mid = 2)),
            Session(talkerId = 3),
        )
        val cards = mapOf(
            1L to UserCard(mid = 1, name = "甲", face = "//a"),
            2L to UserCard(mid = 2, name = "不该覆盖", face = "//x"),
        )
        val merged = mergeSessionPeers(sessions, cards)
        assertEquals("甲", merged[0].accountInfo?.name)
        assertEquals("//a", merged[0].accountInfo?.face)
        assertEquals("UP主小助手", merged[1].accountInfo?.name)
        assertNull(merged[2].accountInfo)
    }

    // ── 回复与 @ 合并 ──

    @Test
    fun `回复与 @ 按时间新到旧合并，key 不撞号`() {
        val r = ReplyAtItem(id = 1, replyTime = 100)
        val a = ReplyAtItem(id = 1, replyTime = 200, isAt = true)
        val merged = mergeReplyAndAt(listOf(r), listOf(a))
        assertEquals(listOf(200L, 100L), merged.map { it.replyTime })
        assertEquals(2, merged.map { it.listKey() }.toSet().size)
    }

    @Test
    fun `@ 条目缺 subject_id 时从视频链接反推 aid，可站内回复`() {
        val at = ReplyAtItem(
            id = 1,
            isAt = true,
            item = ReplyAtContent(businessId = 1, sourceId = 888, uri = "https://www.bilibili.com/video/BV17x411w7KC"),
        )
        assertEquals(bvToAv("BV17x411w7KC"), at.replyOid())
        assertTrue(at.canReplyInApp())
    }

    @Test
    fun `非视频评论区的 @ 不反推 aid，不可站内回复`() {
        val at = ReplyAtItem(
            id = 1,
            isAt = true,
            item = ReplyAtContent(businessId = 17, sourceId = 888, uri = "https://t.bilibili.com/123"),
        )
        assertEquals(0L, at.replyOid())
        assertFalse(at.canReplyInApp())
    }

    @Test
    fun `回复条目有 subject_id 时直接用它`() {
        assertEquals(170001L, reply().replyOid())
    }
}
