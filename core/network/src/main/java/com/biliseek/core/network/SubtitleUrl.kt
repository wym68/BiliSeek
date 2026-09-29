package com.biliseek.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 字幕地址规整：`//aisubtitle.hdslb.com/...` 补 `https:`；只接受 B 站自己的字幕 / 静态资源域名，
 * 其他一律拒绝（地址来自接口返回，不能把「请求任意 URL」的能力交出去）。
 */
internal fun normalizeSubtitleUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
        else -> trimmed
    }
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https") return null
    val host = url.host
    val trusted = TRUSTED_SUBTITLE_HOSTS.any { host == it || host.endsWith(".$it") }
    return if (trusted) url.toString() else null
}

private val TRUSTED_SUBTITLE_HOSTS = listOf("hdslb.com", "bilibili.com", "biliapi.net")
