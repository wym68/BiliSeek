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
