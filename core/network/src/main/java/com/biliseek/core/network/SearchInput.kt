package com.biliseek.core.network

/** 搜索框输入的解析结果。 */
sealed interface SearchIntent {
    /** 普通关键词，走搜索接口 */
    data class Keyword(val text: String) : SearchIntent

    /** BV 号，直接播放 */
    data class Video(val bvid: String) : SearchIntent

    /** AV 号，直接播放 */
    data class VideoAid(val aid: Long) : SearchIntent

    /** b23.tv 短链，需展开后再解析 */
    data class ShortLink(val url: String) : SearchIntent
}

private val BVID = Regex("BV[0-9A-Za-z]{10}")
private val AVID = Regex("(?:^|/|av)(\\d{1,12})(?:$|/|\\?)", RegexOption.IGNORE_CASE)
private val B23 = Regex("https?://b23\\.tv/[0-9A-Za-z]+")

/**
 * 解析搜索框输入。
 *
 * 「搜索即入口」意味着用户会往里粘链接。支持 BV / AV / b23.tv 短链直达，
 * 否则退回关键词搜索。
 */
fun parseSearchInput(raw: String): SearchIntent {
    val input = raw.trim()
    if (input.isEmpty()) return SearchIntent.Keyword("")

    B23.find(input)?.let { return SearchIntent.ShortLink(it.value) }
    BVID.find(input)?.let { return SearchIntent.Video(it.value) }

    // av 号：形如 av123456 或含 /av123456/ 的链接
    if (input.contains("av", ignoreCase = true) || input.all { it.isDigit() }) {
        AVID.find(input)?.let { match ->
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let {
                return SearchIntent.VideoAid(it)
            }
        }
    }

    return SearchIntent.Keyword(input)
}

/**
 * 清洗搜索结果标题。
 *
 * 接口返回的标题既含 `<em>` 高亮标签，也含**二次转义**的 HTML 实体
 * （如 `&amp;quot;` —— 先转义了引号，又转义了 `&`），直接展示会露出乱码。
 */
fun stripHighlight(title: String): String =
    title.replace(Regex("</?em[^>]*>"), "")
        .let(::decodeHtmlEntities)
        .let(::decodeHtmlEntities) // 二次转义需解两遍

private fun decodeHtmlEntities(text: String): String = text
    .replace("&quot;", "\"")
    .replace("&#34;", "\"")
    .replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")

/** 封面地址返回的是 `//i0.hdslb.com/...`，需补协议。 */
fun normalizeCoverUrl(pic: String): String = when {
    pic.isBlank() -> ""
    pic.startsWith("//") -> "https:$pic"
    else -> pic
}
