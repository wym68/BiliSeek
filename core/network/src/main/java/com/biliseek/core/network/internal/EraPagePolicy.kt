package com.biliseek.core.network.internal

import com.biliseek.core.network.EraGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 活动页里内嵌的页面数据变量。 */
private val PAGE_DATA = Regex("""window\.__BILIACT_EVAPAGEDATA__\s*=\s*""")

/** 合法 BV 号：`BV` + 10 位字母数字。 */
private val BVID_FULL = Regex("BV[0-9A-Za-z]{10}")

private const val TABS_PANEL = "EvaTabs.Panel"

/**
 * 从活动页（`blackboard/era`）的 HTML 里取出按标签页分组的 BV 号。
 *
 * 页面是运营用搭建工具做的，数据在内嵌的 `window.__BILIACT_EVAPAGEDATA__`。**不写死层级路径**，
 * 搭建器的布局改了也尽量能认：找所有标签面板，标签取面板的标签文字，BV 取面板内所有 `bvid`。
 * 没有任何标签面板时，整页的 BV 号退化成一组「全部」。解析不出来返回空列表。
 */
internal fun parseEraGroups(html: String): List<EraGroup> {
    val marker = PAGE_DATA.find(html) ?: return emptyList()
    val text = extractJsonObject(html, marker.range.last + 1) ?: return emptyList()
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()

    val panels = mutableListOf<JsonObject>()
    collectPanels(root, panels)
    if (panels.isEmpty()) {
        val all = collectBvids(root)
        return if (all.isEmpty()) emptyList() else listOf(EraGroup(label = "全部", bvids = all))
    }
    return panels.mapIndexedNotNull { index, panel ->
        val bvids = collectBvids(panel)
        if (bvids.isEmpty()) null else EraGroup(label = panelLabel(panel) ?: "第${index + 1}组", bvids = bvids)
    }
}

/**
 * 从 [start]（必须是 `{`）开始，取出配对的 JSON 对象文本。
 * 认字符串和转义：字符串里的花括号、`\"` 都不参与配对。括号没配上、起点不对返回 null。
 */
internal fun extractJsonObject(text: String, start: Int): String? {
    if (start !in text.indices || text[start] != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
    }
    return null
}

/** 找所有标签面板。找到一个就不再往它里面钻，免得嵌套的标签页被重复计数。 */
private fun collectPanels(element: JsonElement, out: MutableList<JsonObject>) {
    when (element) {
        is JsonObject ->
            if ((element["name"] as? JsonPrimitive)?.contentOrNull == TABS_PANEL) {
                out += element
            } else {
                element.values.forEach { collectPanels(it, out) }
            }
        is JsonArray -> element.forEach { collectPanels(it, out) }
        else -> Unit
    }
}

/** [element] 里所有键名为 `bvid`、值是合法 BV 号的字符串，按出现顺序去重。 */
private fun collectBvids(element: JsonElement): List<String> {
    val found = LinkedHashSet<String>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> e.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (key == "bvid" && text != null && BVID_FULL.matches(text)) found += text else walk(value)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(element)
    return found.toList()
}

private fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

private fun panelLabel(panel: JsonObject): String? =
    (panel.field("props").field("tabItem").field("tabItemProps").field("textContent").field("content") as? JsonPrimitive)
        ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val WHITESPACE = Regex("\\s+")

/** 活动页的标题：HTML 的 `<title>`，换行和多余空白折成一个空格；没有时返回空串。 */
internal fun parseEraPageTitle(html: String): String =
    TITLE_TAG.find(html)?.groupValues?.get(1)?.replace(WHITESPACE, " ")?.trim().orEmpty()
