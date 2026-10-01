package com.biliseek.core.player.internal

import android.util.Base64
import android.util.Log
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.network.WbiSigner
import com.biliseek.core.network.dto.PlayUrlData
import com.biliseek.core.player.AudioQuality
import java.util.concurrent.ConcurrentHashMap

/** 一次取流的结果。 */
internal data class ResolvedMedia(
    /** 喂给 Media3 的 URI：DASH 走 data: 内联 manifest，回退路径走直链，离线命中走 file:// */
    val uri: String,
    val mimeType: String?,
    val durationMs: Long,
    val hasVideoTrack: Boolean,
    /** playurl 返回的响度数据（EBU R128），响度均衡用 */
    val measuredLoudness: Double?,
    /** 接口给的目标响度。有它就不必自己定一个 target。 */
    val targetLoudness: Double?,
    /** 这次真的挑中了无损 / 杜比轨。用户选了无损却为 false，就是「不可用」。 */
    val losslessSelected: Boolean = false,
    /**
     * 真实轨道直链，**下载用**。
     *
     * DASH 音频轨本身就是完整的 M4A/AAC 文件（下载即完整文件，无需转码），
     * `collectTracks` 已按音质档位排好序，第一条就是目标。MPD 组装只用了
     * data URI，这两条 URL 此前从未带出来过。
     */
    val audioUrl: String = "",
    /** 视频轨直链。带 codec / 宽高的轨道信息在 DTO 里，mux 时从文件里读即可。 */
    val videoUrl: String = "",
    /**
     * 本地已下载文件命中。
     *
     * 播放页据此显示「本地文件」而不是谎报音质档位——
     * 音质档位对本地文件无意义（M9 E-7 判据）。
     */
    val isOffline: Boolean = false,
    /**
     * playurl 的全部画质档位 (qn, 名字)，含当前账号拿不到的。
     * 画质面板据此把锁住的档位也列出来（置灰），本地文件 / durl 回退时为空。
     */
    val acceptQualities: List<Pair<Int, String>> = emptyList(),
    /** 实际播放的分 P cid。请求没指定 cid 时由取流补全；本地文件沿用请求里的值。 */
    val resolvedCid: Long = 0,
)

/**
 * 取流：playurl → 本地 MPD → MediaItem 可用的 URI。
 *
 * 降级链（《架构设计篇》§4.1）：`dash` → `durl`（老式单文件 MP4）。
 * 第三级 `platform=html5` 兜底留到需要时再加。
 *
 * URL 缓存：playurl 响应有效期约 2 小时，整份缓存，切模式/重播不重新请求。
 * 能不能复用看「播到结尾前直链会不会过期」（见 [shouldReusePlayUrl]）；
 * 播放中断后的重试与自动恢复先 [invalidate]——失败多半就是缓存里这份直链不能用了。
 */
internal class PlayUrlResolver(private val network: BiliNetwork) {

    private data class CacheEntry(val media: ResolvedMedia, val fetchedAt: Long)

    // 播放（主线程）与下载（下载协程）共用这份缓存
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 作废这条内容各音质档位的缓存。[cid] 为空（起播第一 P）时连同该稿件的所有分 P 一起作废。 */
    fun invalidate(bvid: String, cid: Long?) {
        val prefix = if (cid != null) "$bvid:$cid:" else "$bvid:"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    /**
     * 离线命中检查。由外部（app 层）注入：查本地下载库，命中返回本地文件。
     *
     * 与 `resumePositionProvider` / `durationRecorder` 同一解耦惯例——
     * 播放内核不认识数据库。
     */
    var offlineLookup: (suspend (String, Long?) -> com.biliseek.core.player.OfflineMedia?)? = null

    suspend fun resolve(
        bvid: String,
        cid: Long?,
        quality: AudioQuality = AudioQuality.AUTO,
        /** false = 跳过本地文件、直接在线取流（只下了音频的条目要「看」时用）。 */
        allowOffline: Boolean = true,
    ): ResolvedMedia {
        // 离线拦截点。**必须在 awaitReady() 之前**：飞行模式下播放已下载的歌，
        // 若先等风控凭据，会卡在网络错误上——离线功能被自己的风控 gate 挡住。
        if (allowOffline) offlineLookup?.invoke(bvid, cid)?.let { offline ->
            Log.d(TAG, "离线命中 $bvid:$cid → ${offline.uri}")
            return ResolvedMedia(
                uri = offline.uri,
                mimeType = offline.mimeType,
                durationMs = offline.durationMs,
                hasVideoTrack = offline.hasVideoTrack,
                measuredLoudness = null,
                targetLoudness = null,
                isOffline = true,
                resolvedCid = cid ?: 0,
            )
        }
        val result = resolveNetwork(bvid, cid, quality)
        return result.media.copy(resolvedCid = result.cid)
    }

    /** 下载用的取流结果：真实轨道直链 + 实际解析出的 cid。 */
    internal data class DownloadResult(
        val cid: Long,
        val media: ResolvedMedia,
    )

    /**
     * 下载专用取流。与 [resolve] 的唯一区别：**不走离线拦截**——
     * 已下载过的内容再次下载（换位置 / 重新下载）时，不能命中本地文件。
     */
    suspend fun resolveForDownload(
        bvid: String,
        cid: Long?,
        quality: AudioQuality = AudioQuality.AUTO,
    ): DownloadResult = resolveNetwork(bvid, cid, quality)

    private suspend fun resolveNetwork(
        bvid: String,
        cid: Long?,
        quality: AudioQuality,
    ): DownloadResult {
        val credentials = network.authGate.awaitReady()

        val resolvedCid = cid ?: firstCid(bvid)
        // 音质进 key：不同档位的 manifest 不一样，共用一份会「换了档位没变化」
        val key = "$bvid:$resolvedCid:$quality"

        cache[key]?.let { entry ->
            val reusable = shouldReusePlayUrl(
                fetchedAtMs = entry.fetchedAt,
                deadlineMs = playUrlDeadlineMs(entry.media.audioUrl),
                durationMs = entry.media.durationMs,
                nowMs = System.currentTimeMillis(),
                maxAgeMs = CACHE_TTL_MS,
                marginMs = CACHE_MARGIN_MS,
            )
            if (reusable) {
                Log.d(TAG, "playurl 命中缓存 $key")
                return DownloadResult(resolvedCid, entry.media)
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

        val media = toMedia(data, quality)
        cache[key] = CacheEntry(media, System.currentTimeMillis())
        return DownloadResult(resolvedCid, media)
    }

    private suspend fun firstCid(bvid: String): Long {
        val response = network.api.getPageList(bvid)
        require(response.code == 0) { "pagelist 失败: code=${response.code} ${response.message}" }
        val pages = requireNotNull(response.data?.takeIf { it.isNotEmpty() }) { "pagelist 为空" }
        return pages.first().cid
    }

    private fun toMedia(data: PlayUrlData, quality: AudioQuality): ResolvedMedia {
        val dash = data.dash
        if (dash != null) {
            val tracks = collectTracks(dash, quality)
            require(tracks.audios.isNotEmpty()) { "无可播放音频流" }

            val durationMs = if (dash.duration > 0) dash.duration * 1000 else data.timelength
            // 播放服务刚记下的坏节点排到最后：自动恢复重新取流时不再先撞它
            val avoidHosts = CdnHostHealth.recentlyFailed()
            val manifest = buildLocalDashManifest(
                durationMs = durationMs,
                minBufferMs = (dash.minBufferTime * 1000).toLong().coerceAtLeast(1500),
                videoTracks = tracks.videos,
                audioTracks = tracks.audios,
                avoidHosts = avoidHosts,
            )
            val firstAudio = tracks.audios.first()
            val lines = orderCdnUrls(firstAudio.url(), firstAudio.backupUrls(), avoidHosts).map(::cdnHostOf)
            Log.d(
                TAG,
                "MPD 组装完成：视频轨 ${tracks.videos.size} 条，音频轨 ${tracks.audios.size} 条" +
                    "（音质 $quality${if (tracks.lossless) "，无损生效" else ""}）" +
                    "，线路 ${lines.joinToString(" > ")}" +
                    if (avoidHosts.isNotEmpty()) "（避开 ${avoidHosts.joinToString()}）" else "",
            )

            return ResolvedMedia(
                uri = manifest.toDataUri(),
                mimeType = MIME_DASH,
                durationMs = durationMs,
                hasVideoTrack = tracks.videos.isNotEmpty(),
                measuredLoudness = data.volume?.measuredI,
                targetLoudness = data.volume?.targetI,
                losslessSelected = tracks.lossless,
                // 下载用：轨道直链。音频取第一条（已按档位排好序）。
                // 视频轨可能不存在——只下了音频的 m4a 就是这条路径的产物
                audioUrl = tracks.audios.first().url(),
                videoUrl = tracks.videos.firstOrNull()?.url().orEmpty(),
                acceptQualities = data.acceptQuality.mapIndexed { i, qn ->
                    qn to data.acceptDescription.getOrElse(i) { "" }
                },
            )
        }

        // 降级：老式单文件。此路径没有轨道可挑，音质偏好一律落空
        val durl = data.durl?.firstOrNull()?.url
        require(!durl.isNullOrBlank()) { "dash 与 durl 均不可用" }
        Log.w(TAG, "dash 不可用，回退 durl 单文件")
        // durl 单文件本身音视频都在，音频直链与视频直链指向同一份
        return ResolvedMedia(
            uri = durl,
            mimeType = null,
            durationMs = data.timelength,
            hasVideoTrack = true,
            measuredLoudness = data.volume?.measuredI,
            targetLoudness = data.volume?.targetI,
            losslessSelected = false,
            audioUrl = durl,
            videoUrl = durl,
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

        /** 复用缓存时，播完之后直链还得再撑这么久（暂停、缓冲）。 */
        const val CACHE_MARGIN_MS = 5 * 60 * 1000L
    }
}
