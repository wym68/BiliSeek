package com.biliseek.core.ai.internal

import com.biliseek.core.ai.provider.ReasoningFormat

private const val OPEN = "<think>"
private const val CLOSE = "</think>"

/**
 * 流式开头先憋住的字符数：在此之内看到孤立的 `</think>` 就整段丢弃，免得思考内容先闪现再消失。
 * 超过后照常输出；之后再遇到孤立 `</think>` 仍会整段回收（见 [stripReasoning]），只是会闪一下。
 */
internal const val ORPHAN_PREBUFFER_CHARS = 256

/**
 * 从模型输出的正文里去掉「思考内容」，返回用户可见的正文。
 *
 * **累积式**：传入的是到目前为止的全部原始正文（`delta.content` 拼接），返回的是到目前为止的全部可见正文。
 * 流式时每次重算（调用方节流），这样孤立 `</think>` 出现在后面时也能整段回收——增量式做不到。
 *
 * 规则：
 * - `<think>…</think>` 整段去掉（不区分大小写，可多段）；
 * - 未闭合的 `<think>`：其后全部视为仍在思考，不输出；
 * - **孤立的 `</think>`**（服务端吃掉了开头的 `<think>`，借鉴 ownscribe `clean_response`）：
 *   在第一个 `<think>` 之前出现 `</think>` → 它及之前的全部内容都是思考，丢弃；
 * - 流式未完成（[complete] = false）时：末尾可能是被截断的标签前缀（`<thi`），先不输出；
 *   在开头 [ORPHAN_PREBUFFER_CHARS] 字符内且尚未见到任何标签时，整体先不输出，等孤立 `</think>` 是否出现。
 *
 * `reasoning_content` 独立字段的情况无需在此处理——客户端直接不收它（[ReasoningFormat.SEPARATE_FIELD]）。
 * [ReasoningFormat.NONE] 原样返回（仅去首尾空白）。
 */
internal fun stripReasoning(raw: String, format: ReasoningFormat, complete: Boolean): String {
    if (format == ReasoningFormat.NONE || format == ReasoningFormat.SEPARATE_FIELD) {
        return raw.trim()
    }
    var text = raw

    // 孤立 </think>：出现在任何 <think> 之前
    val firstOpen = text.indexOf(OPEN, ignoreCase = true)
    val firstClose = text.indexOf(CLOSE, ignoreCase = true)
    if (firstClose >= 0 && (firstOpen < 0 || firstClose < firstOpen)) {
        text = text.substring(firstClose + CLOSE.length)
    } else if (!complete && firstOpen < 0 && firstClose < 0 && text.length <= ORPHAN_PREBUFFER_CHARS) {
        // 开头憋一小段，等一等孤立的 </think>
        return ""
    }

    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val open = text.indexOf(OPEN, i, ignoreCase = true)
        if (open < 0) {
            out.append(text, i, text.length)
            break
        }
        out.append(text, i, open)
        val close = text.indexOf(CLOSE, open + OPEN.length, ignoreCase = true)
        if (close < 0) {
            // 仍在思考（或思考未闭合就结束了）：其后不输出
            i = text.length
            break
        }
        i = close + CLOSE.length
    }

    var visible = out.toString()
    if (!complete) visible = visible.dropPartialTagSuffix()
    return visible.trim()
}

/** 末尾若是 `<think>` / `</think>` 的不完整前缀（如 `<th`、`</thin`），先不输出，等下一个增量。 */
private fun String.dropPartialTagSuffix(): String {
    val lt = lastIndexOf('<')
    if (lt < 0) return this
    val tail = substring(lt)
    val isPrefix = (OPEN.startsWith(tail, ignoreCase = true) || CLOSE.startsWith(tail, ignoreCase = true))
    return if (isPrefix && tail.length < CLOSE.length) substring(0, lt) else this
}
