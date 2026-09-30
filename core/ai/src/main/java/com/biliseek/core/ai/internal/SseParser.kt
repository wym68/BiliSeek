package com.biliseek.core.ai.internal

/**
 * Server-Sent Events 行解析器。逐行喂入（行尾已去掉），遇空行派发一个事件。
 *
 * 只处理本模块用得到的子集：`data:` 字段（多行 data 用 `\n` 拼接）、`:` 开头的注释/心跳行忽略、
 * 其余字段（event/id/retry）忽略。`data: [DONE]` 作为结束标记由调用方判断。
 *
 * 跨 TCP 包的断行由 okio 的按行读取解决，所以这里是纯函数式的行状态机，便于单测。
 */
internal class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    /** 喂入一行（不含行尾）。返回本行触发派发的事件数据，未派发返回 null。 */
    fun feedLine(rawLine: String): String? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != "data") return null
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (hasData) data.append('\n')
        data.append(value)
        hasData = true
        return null
    }

    /** 流结束时调用：有未以空行收尾的事件也派发出去（部分服务端最后不补空行）。 */
    fun finish(): String? = dispatch()

    private fun dispatch(): String? {
        if (!hasData) return null
        val event = data.toString()
        data.setLength(0)
        hasData = false
        return event
    }
}

/** 测试与非流式兜底用：把整段 SSE 文本解析成事件数据列表。 */
internal fun parseSse(text: String): List<String> {
    val parser = SseParser()
    val events = text.split("\n").mapNotNull { parser.feedLine(it) }.toMutableList()
    parser.finish()?.let { events += it }
    return events
}

internal const val SSE_DONE = "[DONE]"
