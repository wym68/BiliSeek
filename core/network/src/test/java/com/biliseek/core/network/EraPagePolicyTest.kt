package com.biliseek.core.network

import com.biliseek.core.network.internal.extractJsonObject
import com.biliseek.core.network.internal.parseEraGroups
import com.biliseek.core.network.internal.parseEraPageTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动页（blackboard/era）HTML 的解析。
 *
 * 真实页面样例取自用户用 App 同款 UA 存下的《热门播客》页，裁剪成 3 期、每期 3 个 BV，
 * 保留真实的嵌套结构；其余边界情况用内联 JSON 钉住。
 */
class EraPagePolicyTest {

    private val a = "BV1aaaaaaaaa"
    private val b = "BV1bbbbbbbbb"
    private val c = "BV1ccccccccc"

    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("era_podcast_sample.html")) { "缺少测试资源" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 把一段 JSON 包成最小的活动页 HTML：数据变量后面还跟着别的脚本内容。 */
    private fun page(json: String) =
        "<html><script>\nwindow.__BILIACT_EVAPAGEDATA__ = $json;\nwindow.other = {};\n</script></html>"

    /** 一个标签面板；[label] 为 null 表示没有标签文字。 */
    private fun panel(label: String?, vararg bvids: String): String {
        val props = if (label == null) {
            ""
        } else {
            "\"props\":{\"tabItem\":{\"tabItemProps\":{\"textContent\":{\"content\":\"$label\"}}}},"
        }
        val ids = bvids.joinToString(",") { "{\"bvid\":\"$it\"}" }
        return "{\"name\":\"EvaTabs.Panel\",$props\"slots\":[{\"children\":" +
            "[{\"name\":\"NativePickResource\",\"props\":{\"ids\":[$ids]}}]}]}"
    }

    private fun tabs(vararg panels: String) =
        "{\"layerTree\":[{\"name\":\"EvaTabs\",\"slots\":[{\"children\":[${panels.joinToString(",")}]}]}]}"

    @Test
    fun `真实页面样例按期分组，保持页面里的顺序`() {
        val groups = parseEraGroups(sample())
        assertEquals(listOf("第63期", "第62期", "第61期"), groups.map { it.label })
        assertEquals(listOf("BV1bLei6BEGo", "BV1Zhen63EeK", "BV1LMeb6FEqp"), groups[0].bvids)
        assertEquals(listOf("BV15iY56DE85", "BV1XMYE6xEkM", "BV1CCY76kEmt"), groups[1].bvids)
        assertEquals(listOf("BV1hwtB6YEnk", "BV1jati6NE1i", "BV1bdbA6YEXr"), groups[2].bvids)
    }

    @Test
    fun `面板内重复的 BV 去重并保持顺序`() {
        val groups = parseEraGroups(page(tabs(panel("第2期", a, b, a))))
        assertEquals(listOf(a, b), groups.single().bvids)
    }

    @Test
    fun `有面板时只认面板里的 bvid，面板外的不算`() {
        val json = "{\"outside\":{\"bvid\":\"$c\"},\"layerTree\":[{\"name\":\"EvaTabs\"," +
            "\"slots\":[{\"children\":[${panel("第1期", a)}]}]}]}"
        val groups = parseEraGroups(page(json))
        assertEquals(1, groups.size)
        assertEquals(listOf(a), groups[0].bvids)
    }

    @Test
    fun `没有标签面板时退化成一组全部`() {
        val json = "{\"layerTree\":[{\"name\":\"Whatever\",\"props\":{\"ids\":[{\"bvid\":\"$a\"},{\"bvid\":\"$b\"}]}}]}"
        val groups = parseEraGroups(page(json))
        assertEquals(listOf("全部"), groups.map { it.label })
        assertEquals(listOf(a, b), groups[0].bvids)
    }

    @Test
    fun `面板缺标签文字时用第N组，没有 BV 的面板丢掉，N 是面板在页面里的位置`() {
        val groups = parseEraGroups(page(tabs(panel(null, a), panel("第3期"), panel(null, b))))
        assertEquals(listOf("第1组", "第3组"), groups.map { it.label })
        assertEquals(listOf(listOf(a), listOf(b)), groups.map { it.bvids })
    }

    @Test
    fun `不合法的 BV 号忽略`() {
        val groups = parseEraGroups(page(tabs(panel("第1期", "BV1short", "BV1aaaaaaaaaaTOOLONG", a))))
        assertEquals(listOf(a), groups.single().bvids)
    }

    @Test
    fun `字符串里的花括号和转义引号不影响括号配对`() {
        val json = "{\"text\":\"a } b { \\\" c\",\"layerTree\":[{\"name\":\"EvaTabs\"," +
            "\"slots\":[{\"children\":[${panel("第1期", a)}]}]}]}"
        assertEquals(listOf(a), parseEraGroups(page(json)).single().bvids)
    }

    @Test
    fun `没有数据变量、数据不是合法 JSON、或没有任何 bvid 时返回空列表`() {
        assertTrue(parseEraGroups("<html>nothing</html>").isEmpty())
        assertTrue(parseEraGroups("<script>window.__BILIACT_EVAPAGEDATA__ = {\"layerTree\": [;</script>").isEmpty())
        assertTrue(parseEraGroups("<script>window.__BILIACT_EVAPAGEDATA__ = {\"layerTree\":[]};</script>").isEmpty())
    }

    @Test
    fun `括号配对取出完整的对象，不被字符串里的右花括号截断`() {
        val text = "x = {\"a\":{\"b\":1},\"c\":\"}\"}; y = {}"
        assertEquals("{\"a\":{\"b\":1},\"c\":\"}\"}", extractJsonObject(text, text.indexOf('{')))
    }

    @Test
    fun `起点不是左花括号或括号没配上时返回 null`() {
        assertNull(extractJsonObject("abc", 0))
        assertNull(extractJsonObject("{\"a\":1", 0))
        assertNull(extractJsonObject("{}", 5))
    }

    @Test
    fun `活动页标题取自 title 标签`() {
        assertEquals("热门播客榜单", parseEraPageTitle(sample()))
    }

    @Test
    fun `标题里的换行和多余空白折成一个空格，没有 title 标签时是空串`() {
        assertEquals("a b c", parseEraPageTitle("<head><TITLE>\n  a   b\n c </TITLE></head>"))
        assertEquals("", parseEraPageTitle("<html>no title</html>"))
    }
}
