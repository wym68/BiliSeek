package com.biliseek.app.ui

import com.biliseek.core.network.avToBv

/** 视频简介切分后的一段。 */
sealed interface DescSegment {
    data class Plain(val text: String) : DescSegment

    /** 时间点（`05:12` / `1:02:03`）。点击 = 跳到 [positionMs] 播放。 */
    data class Timestamp(val text: String, val positionMs: Long) : DescSegment

    /** 链接或视频号（BV / av）。[text] 是原文，点击按 [target] 打开。 */
    data class Link(val text: String, val target: DescLinkTarget) : DescSegment
}

/** 简介里可点元素的去向。 */
sealed interface DescLinkTarget {
    /** B 站视频：应用内播放。 */
    data class Video(val bvid: String) : DescLinkTarget

    /** b23.tv 短链：展开后是视频就应用内播放，否则交给浏览器。 */
    data class ShortLink(val url: String) : DescLinkTarget

    /** 其他网址：交给浏览器。 */
    data class Web(val url: String) : DescLinkTarget
}

/**
 * 播放页视频简介：是否显示、切分（时间点 / 链接 / BV·av 号）、统计行文案。
 *
 * 切分规则（每个位置依次尝试，命中即吞掉对应文字）：
 * 1. `http(s)://` 链接：到空白或中文全角标点为止（与评论富文本同口径）。
 *    `bilibili.com/video/BV…|av…` 与 `b23.tv/…` 算视频去向，其余一律走浏览器——
 *    不用搜索框的 [com.biliseek.core.network.parseSearchInput]：它对任意网址里的 `/123/` 会误判成 av 号。
 * 2. `BV` + 10 位字母数字、`av` + 数字：前后不能紧贴字母数字（避免把单词或长串里的片段当成视频号）。
 * 3. 时间点 `m:ss` / `mm:ss` / `mmm:ss` / `h:mm:ss`：秒、（三段时）分必须 < 60，前后不能紧贴数字或冒号。
 *    超过视频时长的不做成可点（多半指别的分 P），时长未知（≤ 0）时都可点。
 * 其余字符累积为 [DescSegment.Plain]，相邻文字段合并。
 *
 * 纯函数，无 Android 依赖，配单测。
 */
object VideoDescriptionPolicy {

    private val URL_PREFIXES = listOf("https://", "http://")
    private val BILI_VIDEO_URL =
        Regex("^https?://(?:www\\.|m\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10}|av\\d{1,12})(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
    private val B23_URL = Regex("^https?://b23\\.tv/[0-9A-Za-z]+/?$", RegexOption.IGNORE_CASE)
    private val TIMESTAMP = Regex("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?")

    /** B 站空简介常回一个 `-`，同样当没有。 */
    fun shouldShow(desc: String): Boolean = desc.isNotBlank() && desc.trim() != "-"

    /** 统计行：「播放 52.1 万 · 3天前」。两项都缺时返回空串（不显示）。 */
    fun statsLine(viewCount: Long, pubDateLabel: String): String = listOfNotNull(
        viewCount.takeIf { it > 0 }?.let { "播放 ${formatPlayCount(it)}" },
        pubDateLabel.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    fun parse(desc: String, durationMs: Long): List<DescSegment> {
        if (desc.isEmpty()) return emptyList()
        val out = ArrayList<DescSegment>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += DescSegment.Plain(plain.toString())
                plain.setLength(0)
            }
        }

        var i = 0
        while (i < desc.length) {
            val c = desc[i]

            // 1. 链接
            if ((c == 'h' || c == 'H') && URL_PREFIXES.any { desc.startsWith(it, i, ignoreCase = true) }) {
                var end = i
                while (end < desc.length && !desc[end].isWhitespace() && !isUrlTerminator(desc[end])) end++
                val url = desc.substring(i, end)
                if (URL_PREFIXES.none { url.equals(it, ignoreCase = true) }) {
                    flush()
                    out += DescSegment.Link(url, classifyUrl(url))
                    i = end
                    continue
                }
            }

            // 2. BV / av 号
            if (!desc.isWordCharAt(i - 1)) {
                videoIdAt(desc, i)?.let { (length, bvid) ->
                    flush()
                    out += DescSegment.Link(desc.substring(i, i + length), DescLinkTarget.Video(bvid))
                    i += length
                    continue
                }
            }

            // 3. 时间点
            if (c.isDigit() && !desc.isDigitOrColonAt(i - 1)) {
                val match = TIMESTAMP.matchAt(desc, i)
                if (match != null && !desc.isDigitOrColonAt(match.range.last + 1)) {
                    val ms = timestampMs(match)
                    if (ms != null && (durationMs <= 0 || ms <= durationMs)) {
                        flush()
                        out += DescSegment.Timestamp(match.value, ms)
                        i = match.range.last + 1
                        continue
                    }
                }
            }

            plain.append(c)
            i++
        }
        flush()
        return out
    }

    /** 网址的去向。只认 B 站视频页与 b23 短链，其余走浏览器。 */
    fun classifyUrl(url: String): DescLinkTarget {
        BILI_VIDEO_URL.matchEntire(url)?.let { m ->
            videoIdAt(m.groupValues[1], 0)?.let { (_, bvid) -> return DescLinkTarget.Video(bvid) }
        }
        if (B23_URL.matches(url)) return DescLinkTarget.ShortLink(url)
        return DescLinkTarget.Web(url)
    }

    /** `[start]` 处的 BV / av 号：返回（原文长度, bvid）。后面紧贴字母数字时不算。 */
    private fun videoIdAt(text: String, start: Int): Pair<Int, String>? {
        if (text.startsWith("BV", start) && start + 12 <= text.length) {
            val id = text.substring(start, start + 12)
            if (id.drop(2).all { it.isAsciiLetterOrDigit() } && !text.isWordCharAt(start + 12)) {
                return 12 to id
            }
        }
        if (text.startsWith("av", start, ignoreCase = true)) {
            var end = start + 2
            while (end < text.length && end - start - 2 < 12 && text[end].isDigit()) end++
            if (end > start + 2 && !text.isWordCharAt(end)) {
                val aid = text.substring(start + 2, end).toLongOrNull() ?: return null
                val bvid = runCatching { avToBv(aid) }.getOrNull() ?: return null
                return (end - start) to bvid
            }
        }
        return null
    }

    private fun timestampMs(m: MatchResult): Long? {
        val (a, b, c) = m.destructured
        val first = a.toLong()
        val second = b.toLong()
        return if (c.isEmpty()) {
            if (second >= 60) null else (first * 60 + second) * 1000
        } else {
            val third = c.toLong()
            if (a.length > 2 || second >= 60 || third >= 60) null
            else ((first * 60 + second) * 60 + third) * 1000
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun String.isWordCharAt(index: Int): Boolean =
        index in indices && this[index].isAsciiLetterOrDigit()

    private fun String.isDigitOrColonAt(index: Int): Boolean =
        index in indices && (this[index].isDigit() || this[index] == ':')

    /** 中文全角标点不算 URL 的一部分（与评论富文本 RichTextPolicy 同口径）。 */
    private fun isUrlTerminator(c: Char): Boolean =
        c in "，。！？；：、」』）【】《》\"'<>"
}
