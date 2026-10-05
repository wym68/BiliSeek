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

    /**
     * B 站活动页（`blackboard/era`）：列出页面里的视频。
     * 网址已规整成不带分享参数的 `https://www.bilibili.com/blackboard/era/<id>.html`。
     */
    data class EraPage(val url: String) : SearchIntent
}

private val BVID = Regex("BV[0-9A-Za-z]{10}")

/** 整段输入就是 av 号：`av170001`（不分大小写）。纯数字、别的写法都不算。 */
private val AV_ONLY = Regex("^av(\\d{1,12})$", RegexOption.IGNORE_CASE)

/** 视频页网址里的 av 号：`…/video/av170001`，后面可以跟 `/`、`?`、`.html`。 */
private val AV_IN_URL = Regex("/video/av(\\d{1,12})(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)

private val B23 = Regex("https?://b23\\.tv/[0-9A-Za-z]+")

/** B 站活动页：`bilibili.com/blackboard/era/<id>.html`。分享参数（`?…`）不要，规整成 https 的 www 站。 */
private val ERA_PAGE = Regex(
    "https?://(?:www\\.|m\\.)?bilibili\\.com/blackboard/era/([0-9A-Za-z_-]+)\\.html",
    RegexOption.IGNORE_CASE,
)

/**
 * 解析搜索框输入。
 *
 * 「搜索即入口」意味着用户会往里粘链接。支持 BV / AV / b23.tv 短链 / 活动页网址直达，
 * 否则退回关键词搜索。优先级：b23 短链 → 活动页 → BV 号 → av 号 → 关键词。
 * 活动页放在 BV 号前面：活动页网址的参数里可能带 BV 号字样，仍应按活动页处理。
 */
fun parseSearchInput(raw: String): SearchIntent {
    val input = raw.trim()
    if (input.isEmpty()) return SearchIntent.Keyword("")

    B23.find(input)?.let { return SearchIntent.ShortLink(it.value) }
    ERA_PAGE.find(input)?.let {
        return SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/${it.groupValues[1]}.html")
    }
    BVID.find(input)?.let { return SearchIntent.Video(it.value) }

    // av 号：只认整段输入就是 av170001，或视频页网址里的 /video/av170001。
    // 纯数字、链接里别的 /数字/ 都不算——搜「2024」不该去播 av2024，UP 的 …/12345/favlist 也不是 av12345。
    (AV_ONLY.find(input) ?: AV_IN_URL.find(input))
        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        ?.let { return SearchIntent.VideoAid(it) }

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

/**
 * AV 号 → BV 号。**本地换算，不打网络请求。**
 *
 * B 站公开的换表算法：`(1<<51 | aid) xor XOR_CODE` 后按 Base58 换表填进固定位置，
 * 再交换两组下标打乱。多一次网络请求就是多给服务端一个「这个客户端不认识 BV」的行为特征，
 * 与本项目「不留多余行为特征」的取向相悖。
 */
fun avToBv(aid: Long): String {
    require(aid > 0) { "aid 必须为正：$aid" }
    val chars = charArrayOf('B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0')
    var index = chars.size - 1
    var tmp = (MAX_AID or aid) xor XOR_CODE
    while (tmp > 0 && index >= 0) {
        chars[index] = BV_ALPHABET[(tmp % BV_BASE).toInt()]
        tmp /= BV_BASE
        index--
    }
    swap(chars, 3, 9)
    swap(chars, 4, 7)
    return String(chars)
}

/** BV 号 → AV 号。[avToBv] 的逆运算，本身没有调用点，但让换算的正确性可以被往返验证。 */
fun bvToAv(bvid: String): Long {
    require(bvid.length == 12 && bvid.startsWith("BV")) { "不是合法 BV 号：$bvid" }
    val chars = bvid.toCharArray()
    swap(chars, 3, 9)
    swap(chars, 4, 7)
    var tmp = 0L
    for (i in 3 until chars.size) {
        val digit = BV_ALPHABET.indexOf(chars[i])
        require(digit >= 0) { "BV 号含非法字符：${chars[i]}" }
        tmp = tmp * BV_BASE + digit
    }
    return (tmp and BV_MASK_CODE) xor XOR_CODE
}

private fun swap(chars: CharArray, a: Int, b: Int) {
    val t = chars[a]
    chars[a] = chars[b]
    chars[b] = t
}

private const val XOR_CODE = 23442827791579L
private const val BV_MASK_CODE = 2251799813685247L
private const val MAX_AID = 1L shl 51
private const val BV_BASE = 58L
private const val BV_ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

/**
 * 短链展开后的落点是否可信。
 *
 * `b23.tv` 是通用短链服务，**重定向落点不保证还在 B 站**。
 * 不校验就等于把「跟随任意 302」的能力交给了链接的提供者。
 */
fun isTrustedBiliHost(url: String): Boolean {
    val host = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE)
        .find(url.trim())
        ?.groupValues
        ?.get(1)
        ?.substringAfter('@') // 挡掉 https://bilibili.com@evil.com/ 这类写法
        ?.substringBefore(':')
        ?.lowercase()
        ?: return false
    return host == "bilibili.com" || host.endsWith(".bilibili.com")
}

/**
 * 从外部分享的 Intent 里取出待解析文本。
 *
 * 只收字符串，不认识 `Intent`——这样它能和其他解析函数一样被单测覆盖。
 * `ACTION_SEND` 走 [extraText]（B 站 App 的「分享到」），
 * `ACTION_VIEW` 走 [dataString]（浏览器里点链接）。
 */
fun sharedTextFrom(
    action: String?,
    type: String?,
    extraText: String?,
    dataString: String?,
): String? = when {
    action == ACTION_SEND && type?.startsWith("text/") == true ->
        extraText?.takeIf { it.isNotBlank() }
    action == ACTION_VIEW -> dataString?.takeIf { it.isNotBlank() }
    else -> null
}

private const val ACTION_SEND = "android.intent.action.SEND"
private const val ACTION_VIEW = "android.intent.action.VIEW"

/**
 * 解析搜索结果里的时长文本，返回秒数。
 *
 * 接口给的是 `"4:32"` / `"1:02:03"` 这样的展示文本而非数字，
 * 但它是入库时**唯一**能拿到时长的地方——取流要等到真正播放才发生。
 * 解析不出来返回 0，由后续播放时的时长回写补上。
 */
fun parseDurationText(text: String): Long {
    val parts = text.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return 0
    var total = 0L
    for (part in parts) {
        val value = part.trim().toLongOrNull()?.takeIf { it >= 0 } ?: return 0
        total = total * 60 + value
    }
    return total
}

/**
 * 封面地址归一化：
 * - `//i0.hdslb.com/...`（无协议）→ 补 `https:`
 * - `http://...`（动态接口会给明文 http）→ **升 `https://`**。
 *   Android 9+ 默认禁止明文流量，动态封面 `http://i2.hdslb.com` 在真机上会被
 *   系统直接拦掉（M11 真机实测发现：动态页封面不加载）；B 站图片 CDN 均支持 https。
 */
fun normalizeCoverUrl(pic: String): String = when {
    pic.isBlank() -> ""
    pic.startsWith("//") -> "https:$pic"
    pic.startsWith("http://") -> "https:" + pic.removePrefix("http:")
    else -> pic
}
