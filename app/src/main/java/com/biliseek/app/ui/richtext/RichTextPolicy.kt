package com.biliseek.app.ui.richtext

import com.biliseek.core.network.dto.Emote

/** 富文本切分后的一段。 */
sealed interface RichSegment {
    /** 普通文字。 */
    data class Plain(val text: String) : RichSegment

    /** 表情图。[key] 是正文里的占位串（`[doge]`），图片加载失败时显示它。 */
    data class EmoteImage(val key: String, val url: String, val large: Boolean) : RichSegment

    /** `@昵称`。[mid] 为 0 表示只知道昵称（高亮但不可点）。 */
    data class Mention(val text: String, val mid: Long) : RichSegment

    /** 服务端标记的蓝字关键词（评论 `jump_url`）或动态话题 `#话题#`。点击 = 以它为关键词搜索。 */
    data class Keyword(val text: String) : RichSegment

    /** 裸链接。 */
    data class Link(val url: String) : RichSegment
}

/** 富文本的输入素材。所有字段都可缺省：只有 [text] 时退化为纯文本（外加链接识别）。 */
data class RichTextSource(
    val text: String,
    /** 本条内容自带的表情表（评论 `content.emote` / 动态 rich_text_nodes / 私信 `e_infos`）。 */
    val emotes: Map<String, Emote> = emptyMap(),
    /** 昵称 → mid（评论 `content.members`）。 */
    val mentions: Map<String, Long> = emptyMap(),
    /** 蓝字关键词（评论 `jump_url` 的 key）。 */
    val keywords: List<String> = emptyList(),
    /** 是否把 `#话题#` 识别成关键词（动态正文开，评论关）。 */
    val topics: Boolean = false,
)

/**
 * 富文本切分：把「文本 + 表情表 + @ 表 + 关键词」切成 [RichSegment] 序列。
 *
 * 规则（优先级从高到低，在每个位置依次尝试）：
 * 1. `[xxx]`：先查本条的表情表，再查 [fallback]（进程级表情字典——`回复与@`、私信摘要等
 *    接口不下发表情表，靠用户表情面板里的同名表情补齐）。都查不到 → 原样当文字。
 * 2. `@昵称`：[RichTextSource.mentions] 里最长匹配；表为空时不猜（避免把邮箱误判成 @）。
 * 3. 关键词：[RichTextSource.keywords] 最长匹配。
 * 4. `#话题#`（仅 [RichTextSource.topics]）：两个 `#` 之间 1..32 个非空白字符。
 * 5. `http(s)://` 链接：到空白为止。
 * 其余字符累积为 [RichSegment.Plain]，相邻文字段合并。
 *
 * 纯函数，无 Android 依赖，配单测。
 */
object RichTextPolicy {

    /** 表情占位串最大长度（`[` 与 `]` 之间）。B 站最长的约 12 个字，留余量。 */
    private const val MAX_EMOTE_KEY = 24
    private const val MAX_TOPIC = 32
    private val URL_PREFIXES = listOf("https://", "http://")

    fun parse(source: RichTextSource, fallback: Map<String, Emote> = emptyMap()): List<RichSegment> {
        val text = source.text
        if (text.isEmpty()) return emptyList()
        val mentionNames = source.mentions.keys.filter { it.isNotBlank() }.sortedByDescending { it.length }
        val keywords = source.keywords.filter { it.isNotBlank() }.sortedByDescending { it.length }

        val out = ArrayList<RichSegment>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += RichSegment.Plain(plain.toString())
                plain.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // 1. 表情
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i + 1 && close - i - 1 <= MAX_EMOTE_KEY) {
                    val key = text.substring(i, close + 1)
                    val emote = source.emotes[key] ?: fallback[key]
                    if (emote != null && emote.url.isNotBlank()) {
                        flush()
                        out += RichSegment.EmoteImage(key = key, url = emote.url, large = emote.isLarge)
                        i = close + 1
                        continue
                    }
                }
            }

            // 2. @昵称
            if (c == '@' && mentionNames.isNotEmpty()) {
                val name = mentionNames.firstOrNull { text.startsWith(it, i + 1) }
                if (name != null) {
                    flush()
                    out += RichSegment.Mention(text = "@$name", mid = source.mentions[name] ?: 0L)
                    i += 1 + name.length
                    continue
                }
            }

            // 3. 蓝字关键词
            if (keywords.isNotEmpty()) {
                val kw = keywords.firstOrNull { text.startsWith(it, i) }
                if (kw != null) {
                    flush()
                    out += RichSegment.Keyword(kw)
                    i += kw.length
                    continue
                }
            }

            // 4. #话题#
            if (c == '#' && source.topics) {
                val close = text.indexOf('#', i + 1)
                if (close > i + 1 && close - i - 1 <= MAX_TOPIC) {
                    val inner = text.substring(i + 1, close)
                    if (inner.none { it.isWhitespace() }) {
                        flush()
                        out += RichSegment.Keyword("#$inner#")
                        i = close + 1
                        continue
                    }
                }
            }

            // 5. 链接
            if ((c == 'h' || c == 'H') && URL_PREFIXES.any { text.startsWith(it, i, ignoreCase = true) }) {
                var end = i
                while (end < text.length && !text[end].isWhitespace() && !isUrlTerminator(text[end])) end++
                val url = text.substring(i, end)
                if (URL_PREFIXES.none { url.equals(it, ignoreCase = true) }) {
                    flush()
                    out += RichSegment.Link(url)
                    i = end
                    continue
                }
            }

            plain.append(c)
            i++
        }
        flush()
        return out
    }

    /**
     * 是否整条只有大表情（忽略空白）——官方把这种评论里的大表情单独放大显示；
     * 与文字混排时一律按行内尺寸，避免把行高撑乱。
     */
    fun isStandaloneLargeEmote(segments: List<RichSegment>): Boolean {
        val meaningful = segments.filterNot { it is RichSegment.Plain && it.text.isBlank() }
        return meaningful.isNotEmpty() && meaningful.all { it is RichSegment.EmoteImage && it.large }
    }

    /** 中文全角标点不算 URL 的一部分（「看这个https://b23.tv/xx。」）。 */
    private fun isUrlTerminator(c: Char): Boolean =
        c in "，。！？；：、」』）【】《》\"'<>"
}
