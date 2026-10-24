package com.biliseek.app.comment

/** B 站昵称最长 16 个字：@ 后面超过这么长就不当成在 @ 人了。 */
const val MAX_MENTION_QUERY = 16

/** 输入框里正在打的 @：[start] 是 `@` 的位置，[query] 是 `@` 到光标之间的字。 */
data class ActiveMention(val start: Int, val query: String)

/**
 * 光标前面是不是正在打 `@昵称`：往前找最近的 `@`，它到光标之间不能有空白（`@小明 ` 打完空格就算结束）、
 * 也不能超过 [MAX_MENTION_QUERY] 个字。刚打出 `@` 时 query 为空（显示默认候选：最近 @ 过的、关注的）。
 */
fun resolveActiveMention(text: String, cursor: Int): ActiveMention? {
    if (cursor <= 0 || cursor > text.length) return null
    val at = text.lastIndexOf('@', cursor - 1)
    if (at < 0) return null
    val query = text.substring(at + 1, cursor)
    if (query.length > MAX_MENTION_QUERY || query.any { it.isWhitespace() }) return null
    return ActiveMention(at, query)
}

/** 选了候选人：把 `@query` 换成 `@昵称 `（带空格，B 站靠它断开昵称），返回新文本和新光标。 */
fun insertMention(text: String, mention: ActiveMention, cursor: Int, name: String): Pair<String, Int> {
    val inserted = "@$name "
    return text.replaceRange(mention.start, cursor, inserted) to mention.start + inserted.length
}
