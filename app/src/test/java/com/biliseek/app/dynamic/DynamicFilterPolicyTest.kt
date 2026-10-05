package com.biliseek.app.dynamic

import com.biliseek.core.network.dto.FollowingUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态页按 UP 筛选：点头像后筛选怎么变，以及选中 / 取消时的提示文字（含名字太长时的省略号）。
 */
class DynamicFilterPolicyTest {

    private fun up(mid: Long, name: String = "UP$mid") = FollowingUp(mid = mid, uname = name)

    // ── 点头像后筛选怎么变 ───────────────────────

    @Test
    fun `没有筛选时点头像是选中`() {
        val a = up(5)
        assertEquals(UpFilterChange.Select(a), resolveUpFilterChange(selectedMid = null, up = a))
    }

    @Test
    fun `再点已选中的头像是取消筛选`() {
        assertEquals(UpFilterChange.Clear, resolveUpFilterChange(selectedMid = 5, up = up(5)))
    }

    @Test
    fun `从一个 UP 直接点另一个是选中另一个`() {
        val b = up(7)
        assertEquals(UpFilterChange.Select(b), resolveUpFilterChange(selectedMid = 5, up = b))
    }

    @Test
    fun `传 null 回关注流：本来有筛选就是取消，本来没筛选就是没变化`() {
        assertEquals(UpFilterChange.Clear, resolveUpFilterChange(selectedMid = 5, up = null))
        assertNull(resolveUpFilterChange(selectedMid = null, up = null))
    }

    // ── 提示文字 ─────────────────────────────────

    @Test
    fun `选中提示带 UP 名，取消提示是已取消筛选`() {
        assertEquals("只看「老番茄」的动态", upFilterNotice(UpFilterChange.Select(up(1, "老番茄"))))
        assertEquals("已取消筛选", upFilterNotice(UpFilterChange.Clear))
    }

    @Test
    fun `名字首尾的空白去掉，名字是空白时不拼名字`() {
        assertEquals("只看「老番茄」的动态", upFilterNotice(UpFilterChange.Select(up(1, "  老番茄  "))))
        assertEquals("只看这位 UP 主的动态", upFilterNotice(UpFilterChange.Select(up(1, "   "))))
        assertEquals("只看这位 UP 主的动态", upFilterNotice(UpFilterChange.Select(up(1, ""))))
    }

    @Test
    fun `名字恰好在上限时原样显示，10 个汉字或 20 个英文字母`() {
        val cjk = "一二三四五六七八九十"
        assertEquals("只看「$cjk」的动态", upFilterNotice(UpFilterChange.Select(up(1, cjk))))
        val latin = "abcdefghijklmnopqrst"
        assertEquals("只看「$latin」的动态", upFilterNotice(UpFilterChange.Select(up(1, latin))))
    }

    @Test
    fun `超长中文名截断，用省略号代替放不下的部分`() {
        val name = "一二三四五六七八九十甲乙丙丁" // 14 个汉字，宽度 28
        // 保留宽度 ≤ 19 的前缀：9 个汉字（18），再接省略号
        assertEquals("只看「一二三四五六七八九…」的动态", upFilterNotice(UpFilterChange.Select(up(1, name))))
    }

    @Test
    fun `超长英文名截断，保留 19 个字母再接省略号`() {
        val name = "abcdefghijklmnopqrstuvwxyz" // 26 个字母
        assertEquals("只看「abcdefghijklmnopqrs…」的动态", upFilterNotice(UpFilterChange.Select(up(1, name))))
    }

    @Test
    fun `中英混排按显示宽度算`() {
        // ABC（3）+ 9 个汉字（18）= 21 > 20：保留宽度 ≤ 19 的前缀 = ABC + 8 个汉字（19）
        val name = "ABC" + "一二三四五六七八九"
        assertEquals("只看「ABC一二三四五六七八…」的动态", upFilterNotice(UpFilterChange.Select(up(1, name))))
    }

    @Test
    fun `emoji 按两格算，也不会被从中间切坏`() {
        val name = "😀".repeat(12) // 12 个 emoji，宽度 24
        val notice = upFilterNotice(UpFilterChange.Select(up(1, name)))
        assertEquals("只看「" + "😀".repeat(9) + "…」的动态", notice)
        // 每个码点都是完整的：没有落单的代理项
        assertTrue(notice.codePoints().noneMatch { it in 0xD800..0xDFFF })
    }
}
