package com.biliseek.core.player.internal

import android.util.Base64
import android.util.Log
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.network.WbiSigner
import com.biliseek.core.network.dto.PlayUrlData

/** 一次取流的结果。 */
internal data class ResolvedMedia(
    /** 喂给 Media3 的 URI：DASH 走 data: 内联 manifest，回退路径走直链 */
    val uri: String,
    val mimeType: String?,
    val durationMs: Long,
    val hasVideoTrack: Boolean,
    /** playurl 返回的响度数据，响度均衡用（P1） */
    val measuredLoudness: Double?,
)

/**
 * 取流：playurl → 本地 MPD → MediaItem 可用的 URI。
 *
 * 降级链（《架构设计篇》§4.1）：`dash` → `durl`（老式单文件 MP4）。
 * 第三级 `platform=html5` 兜底留到需要时再加。
 *
 * URL 缓存：playurl 响应有效期约 2 小时，整份缓存，切模式/重播不重新请求。
 */
internal class PlayUrlResolver(private val network: BiliNetwork) {

    private data class CacheEntry(val media: ResolvedMedia, val fetchedAt: Long)

    private val cache = mutableMapOf<String, CacheEntry>()

    suspend fun resolve(bvid: String, cid: Long?, preferHiRes: Boolean = false): ResolvedMedia {
        val credentials = network.authGate.awaitReady()

        val resolvedCid = cid ?: firstCid(bvid)
        val key = "$bvid:$resolvedCid:$preferHiRes"

        cache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.fetchedAt < CACHE_TTL_MS) {
                Log.d(TAG, "playurl 命中缓存 $key")
                return entry.media
            }
        }

        val params = WbiSigner.sign(
            params = mapOf(
                "bvid" to bvid,
                "cid" to resolvedCid.toString(),
                "fnval" to FNVAL_DASH_ALL,
                "fnver" to "0",
                "fourk" to "1",
                "voice_balance" to "1",
            ),
            imgKey = credentials.wbiImgKey,
            subKey = credentials.wbiSubKey,
        )

        val response = network.api.getPlayUrl(params)
        require(response.code == 0) { "playurl 失败: code=${response.code} ${response.message}" }
        val data = requireNotNull(response.data) { "playurl 返回 data 为空" }

        val media = toMedia(data)
        cache[key] = CacheEntry(media, System.currentTimeMillis())
        return media
    }

    private suspend fun firstCid(bvid: String): Long {
        val response = network.api.getPageList(bvid)
        require(response.code == 0) { "pagelist 失败: code=${response.code} ${response.message}" }
        val pages = requireNotNull(response.data?.takeIf { it.isNotEmpty() }) { "pagelist 为空" }
        return pages.first().cid
    }

    private fun toMedia(data: PlayUrlData): ResolvedMedia {
        val dash = data.dash
        if (dash != null) {
            val (videos, audios) = collectTracks(dash, preferHiRes = false)
            require(audios.isNotEmpty()) { "无可播放音频流" }

            val durationMs = if (dash.duration > 0) dash.duration * 1000 else data.timelength
            val manifest = buildLocalDashManifest(
                durationMs = durationMs,
                minBufferMs = (dash.minBufferTime * 1000).toLong().coerceAtLeast(1500),
                videoTracks = videos,
                audioTracks = audios,
            )
            Log.d(TAG, "MPD 组装完成：视频轨 ${videos.size} 条，音频轨 ${audios.size} 条")

            return ResolvedMedia(
                uri = manifest.toDataUri(),
                mimeType = MIME_DASH,
                durationMs = durationMs,
                hasVideoTrack = videos.isNotEmpty(),
                measuredLoudness = data.volume?.measuredI,
            )
        }

        // 降级：老式单文件
        val durl = data.durl?.firstOrNull()?.url
        require(!durl.isNullOrBlank()) { "dash 与 durl 均不可用" }
        Log.w(TAG, "dash 不可用，回退 durl 单文件")
        return ResolvedMedia(
            uri = durl,
            mimeType = null,
            durationMs = data.timelength,
            hasVideoTrack = true,
            measuredLoudness = data.volume?.measuredI,
        )
    }

    /**
     * MPD 以 data URI 内联传给 Media3，避免落盘。
     * B 站的 BaseURL 是绝对地址，因此 manifest 没有 base URI 也能解析。
     */
    private fun String.toDataUri(): String {
        val encoded = Base64.encodeToString(toByteArray(), Base64.NO_WRAP)
        return "data:$MIME_DASH;base64,$encoded"
    }

    private companion object {
        const val TAG = "BiliSeekPlayUrl"
        const val MIME_DASH = "application/dash+xml"

        /** DASH + 8K + HDR + 杜比 + Hi-Res 的位掩码。 */
        const val FNVAL_DASH_ALL = "4048"

        /** playurl 实际有效期约 2 小时，留出余量。 */
        const val CACHE_TTL_MS = 100 * 60 * 1000L
    }
}
