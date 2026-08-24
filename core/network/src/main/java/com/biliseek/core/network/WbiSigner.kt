package com.biliseek.core.network

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * WBI 签名。
 *
 * B 站自 2023 年起对搜索、playurl 等接口强制要求 `w_rid` 签名，缺失直接 -412。
 *
 * **刻意不做成 OkHttp 拦截器**（见《架构设计篇》§3.2）：签名必须覆盖最终确定的
 * 参数集，而拦截器拿到的是已构造好的 URL，改参数再算签名容易出错、也难测。
 * 用法是在调用点签好，把返回的 Map 用 `@QueryMap` 传进去。
 */
object WbiSigner {

    /** 官方前端 JS 里的固定打乱表，用于从 imgKey+subKey 推导 mixinKey。 */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    /**
     * @param params 业务参数（未编码）
     * @return 追加了 `wts` 与 `w_rid` 的完整参数表，值仍未编码——交给 Retrofit 去编码。
     *         签名计算时用编码后的值，但返回原值，避免二次编码。
     */
    fun sign(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val mixinKey = mixinKey(imgKey + subKey)

        val raw = LinkedHashMap<String, String>()
        params.forEach { (k, v) -> raw[k] = filterIllegalChars(v) }
        raw["wts"] = timestampSeconds.toString()

        val query = raw.keys.sorted().joinToString("&") { key ->
            "$key=${encodeComponent(raw.getValue(key))}"
        }
        raw["w_rid"] = md5(query + mixinKey)
        return raw
    }

    internal fun mixinKey(orig: String): String {
        val sb = StringBuilder()
        for (i in MIXIN_KEY_ENC_TAB) {
            if (i < orig.length) sb.append(orig[i])
        }
        return sb.toString().take(32)
    }

    /** B 站要求剔除这几个字符后再参与签名。 */
    internal fun filterIllegalChars(value: String): String =
        value.replace(Regex("[!'()*]"), "")

    /** 对齐 JS 的 encodeURIComponent 行为。 */
    internal fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    internal fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 从 nav 接口返回的 `wbi_img.img_url` 里抠出 key（取文件名去扩展名）。 */
    fun extractKey(url: String): String =
        url.substringAfterLast('/').substringBefore('.')
}
