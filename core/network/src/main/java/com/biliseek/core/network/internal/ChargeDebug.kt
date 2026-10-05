package com.biliseek.core.network.internal

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 只在 debug 包：把接口返回里每个视频和充电有关的字段打成一行（tag `BiliSeekCharge`），
 * 用来对照「限免」「已充电」在真实数据里怎么表示（设计稿：2026-10-04-charge-badge）。
 *
 * 只读不改（`peekBody`）。要加在解压拦截器外层，拿到的才是明文 JSON。
 * 现有的 `BiliSeekHttp` 整包日志太长会被 logcat 截断，所以单独打这种短行。
 */
internal class ChargeDebugInterceptor(
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.body?.contentType()?.subtype != "json") return response
        runCatching {
            val text = response.peekBody(PEEK_LIMIT).string()
            val path = chain.request().url.encodedPath
            chargeDebugLines(Json.parseToJsonElement(text)).forEach { log("$path $it") }
        }
        return response
    }

    private companion object {
        const val TAG = "BiliSeekCharge"
        const val PEEK_LIMIT = 4L * 1024 * 1024
    }
}

/**
 * 在 JSON 里找带 `bvid` 的对象，每个一行：`bvid 「标题前 16 字」 键=值 …`，只列键名和充电有关的字段。
 * 没有这类字段的视频不打；对象 / 数组值压成一行，超过 160 字截断——但 `rights` 逐个展开成 `rights.键=值`、不截断
 * （限免和已充电在视频详情里的 upower 字段一样，区别可能在 rights 里，2026-10-05）。子对象里的视频另起一行。
 */
internal fun chargeDebugLines(root: JsonElement): List<String> {
    val out = mutableListOf<String>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                debugLineOf(element)?.let(out::add)
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return out
}

private val CHARGE_KEY = Regex("charg|upower|elec|pay|badge|free|preview|privilege|rights", RegexOption.IGNORE_CASE)
private const val TITLE_MAX = 16
private const val VALUE_MAX = 160

private fun debugLineOf(obj: JsonObject): String? {
    val bvid = (obj["bvid"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
    val fields = obj.filterKeys { CHARGE_KEY.containsMatchIn(it) }
    if (fields.isEmpty()) return null
    val title = (obj["title"] as? JsonPrimitive)?.content.orEmpty().let {
        if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it
    }
    val values = fields.entries.joinToString(" ") { (key, value) ->
        if (key == "rights" && value is JsonObject) {
            value.entries.joinToString(" ") { (k, v) -> "rights.$k=$v" }
        } else {
            val text = value.toString()
            "$key=" + if (value !is JsonPrimitive && text.length > VALUE_MAX) text.take(VALUE_MAX) + "…" else text
        }
    }
    return "$bvid 「$title」 $values"
}
