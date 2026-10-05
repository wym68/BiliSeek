package com.biliseek.app.ui

import com.biliseek.app.data.DynamicExpandState
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.dto.DynamicBlock
import com.biliseek.core.network.dto.DynamicPic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicBodyPolicyTest {

    private val body = listOf(
        DynamicBlock.Text("开头"),
        DynamicBlock.Images(listOf(DynamicPic("a"))),
        DynamicBlock.Text("中间"),
        DynamicBlock.Images(listOf(DynamicPic("b"), DynamicPic("c"))),
        DynamicBlock.Images(listOf(DynamicPic("d"))),
    )

    private fun entry(body: List<DynamicBlock>) = DynamicEntry(
        idStr = "1", authorName = "UP", authorFace = "", pubTs = 0,
        descText = "全文", rawType = "DYNAMIC_TYPE_DRAW", video = null, coverUrl = "", body = body,
    )

    @Test
    fun `展开且详情带段落才按段落显示`() {
        assertEquals(body, expandedBody(DynamicExpandState(expanded = true, entry = entry(body))))
        // 收起（保留缓存）、加载中、失败、未展开过、详情没段落：都退回整段文字
        assertTrue(expandedBody(DynamicExpandState(expanded = false, entry = entry(body))).isEmpty())
        assertTrue(expandedBody(DynamicExpandState(expanded = true)).isEmpty())
        assertTrue(expandedBody(DynamicExpandState(expanded = true, error = "加载失败")).isEmpty())
        assertTrue(expandedBody(null).isEmpty())
        assertTrue(expandedBody(DynamicExpandState(expanded = true, entry = entry(emptyList()))).isEmpty())
    }

    @Test
    fun `正文图片按出现顺序摊平`() {
        assertEquals(listOf("a", "b", "c", "d"), bodyImageUrls(body))
        assertTrue(bodyImageUrls(listOf(DynamicBlock.Text("只有字"))).isEmpty())
    }

    @Test
    fun `每段第一张图在摊平列表里的下标`() {
        // 文字段落的值等于它前面的图片张数（用不到）
        assertEquals(listOf(0, 0, 1, 1, 3), bodyImageOffsets(body))
    }

    @Test
    fun `单图按原图比例`() {
        assertEquals(1.5f, inlineImageAspect(1200, 800), 0.001f)
        assertEquals(0.75f, inlineImageAspect(900, 1200), 0.001f)
        // 很宽的图照原比例，不放大
        assertEquals(20f, inlineImageAspect(2000, 100), 0.001f)
    }

    @Test
    fun `特别长的图最高到宽的两倍`() {
        assertEquals(0.5f, inlineImageAspect(1080, 2160), 0.001f)
        assertEquals(0.5f, inlineImageAspect(1080, 8000), 0.001f)
    }

    @Test
    fun `缺宽高按 4比3`() {
        assertEquals(4f / 3f, inlineImageAspect(0, 0), 0.001f)
        assertEquals(4f / 3f, inlineImageAspect(1200, 0), 0.001f)
        assertEquals(4f / 3f, inlineImageAspect(-1, 800), 0.001f)
    }
}
