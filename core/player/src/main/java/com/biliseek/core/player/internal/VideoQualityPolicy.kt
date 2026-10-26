package com.biliseek.core.player.internal

import com.biliseek.core.player.VIDEO_QUALITY_AUTO
import com.biliseek.core.player.VideoQualityOption

/**
 * 画质选择。
 *
 * **切画质不重新取流。** 取流时已经把所有能拿到的视频轨放进同一个 MPD 的同一个
 * AdaptationSet（见 [buildLocalDashManifest]），切画质只是给视频轨组加一个
 * `TrackSelectionOverride` 锁定其中一条——不 seek、不重建播放源，与听⇄看切换
 * 用的是同一套无缝机制。
 */

/** 视频轨组里的一条轨，从 ExoPlayer 的 `Format` 里取出来的最小信息。 */
internal data class VideoTrackCandidate(
    val qn: Int,
    val codecs: String,
    val height: Int,
    /** 设备有没有能解这条轨的解码器（`Tracks.Group.isTrackSupported`）。 */
    val supported: Boolean,
)

/**
 * 按偏好画质挑一条要锁定的视频轨，返回它在轨道组里的下标；返回 null 表示不锁定（自动）。
 *
 * - 偏好为 [VIDEO_QUALITY_AUTO] → 不锁定。
 * - 偏好档位存在 → 就选它；同一档有多种编码时按 [codecRank] 挑。
 * - 偏好档位不存在（这个视频没有 / 账号拿不到）→ 往下找最接近的一档，
 *   不往上跳：用户选 720P 多半是在省流量，自作主张升到 1080P 违背本意。
 * - 连更低的都没有 → 选能播的最低一档。
 * - 一条能播的都没有 → 不锁定，交还给播放器。
 */
internal fun resolveVideoTrackIndex(
    candidates: List<VideoTrackCandidate>,
    preferredQn: Int,
): Int? {
    if (preferredQn == VIDEO_QUALITY_AUTO) return null
    val playable = candidates.withIndex().filter { it.value.supported }
    if (playable.isEmpty()) return null

    val targetQn = playable.map { it.value.qn }.filter { it <= preferredQn }.maxOrNull()
        ?: playable.minOf { it.value.qn }

    return playable
        .filter { it.value.qn == targetQn }
        .minByOrNull { codecRank(it.value.codecs) }
        ?.index
}

/**
 * 下载挑哪条视频轨，返回下标；没有视频轨返回 null。
 *
 * 与 [resolveVideoTrackIndex] 的区别只在自动：下载没有自适应，自动就是取能拿到的最高一档（不设上限，含 4K）。
 * 选了档位时规则相同——有就用、没有往下找、同档按 [codecRank] 优先 AVC。
 * 取流时还不知道设备解不解得了，一律当作能播。
 */
internal fun resolveDownloadVideoTrackIndex(
    candidates: List<VideoTrackCandidate>,
    preferredQn: Int,
): Int? {
    if (candidates.isEmpty()) return null
    val qn = if (preferredQn == VIDEO_QUALITY_AUTO) candidates.maxOf { it.qn } else preferredQn
    return resolveVideoTrackIndex(candidates, qn)
}

/**
 * 编码优先级，越小越优先：AVC > HEVC > AV1 > 其他。
 *
 * AVC 在所有设备上都有硬解；HEVC 在老设备上偶有只能软解、发热掉帧的情况；
 * AV1 硬解要到很新的芯片才有。本 App 以听为主，看的时候求稳不求省。
 * 高档位（4K / HDR）通常只有 HEVC，此时自然落到 HEVC。
 */
internal fun codecRank(codecs: String): Int = when {
    codecs.startsWith("avc", ignoreCase = true) -> 0
    codecs.startsWith("hev", ignoreCase = true) || codecs.startsWith("hvc", ignoreCase = true) -> 1
    codecs.startsWith("av01", ignoreCase = true) -> 2
    else -> 3
}

/**
 * 组装给 UI 的档位列表，按 qn 从高到低。
 *
 * [accepted] 来自 playurl 的 `accept_quality` / `accept_description`，包含当前拿不到的档位；
 * 本地文件、durl 回退等没有这份列表时，退回用轨道本身组装。
 * 能播的档位不到两个时返回空——只有一档，选择入口没有意义。
 */
internal fun resolveVideoQualityOptions(
    accepted: List<Pair<Int, String>>,
    candidates: List<VideoTrackCandidate>,
): List<VideoQualityOption> {
    val playableQns = candidates.filter { it.supported }.map { it.qn }.toSet()
    val options = if (accepted.isNotEmpty()) {
        accepted.map { (qn, label) ->
            VideoQualityOption(qn, label.ifBlank { qnLabel(qn, height = 0) }, qn in playableQns)
        }
    } else {
        candidates.groupBy { it.qn }.map { (qn, tracks) ->
            VideoQualityOption(qn, qnLabel(qn, tracks.maxOf { it.height }), qn in playableQns)
        }
    }
    val sorted = options.distinctBy { it.qn }.sortedByDescending { it.qn }
    return if (sorted.count { it.available } < 2) emptyList() else sorted
}

/** 常见 qn 的名字。未知代号退回「{高度}P」。 */
internal fun qnLabel(qn: Int, height: Int): String = when (qn) {
    6 -> "240P"
    16 -> "360P"
    32 -> "480P"
    64 -> "720P"
    74 -> "720P60"
    80 -> "1080P"
    100 -> "智能修复"
    112 -> "1080P+"
    116 -> "1080P60"
    120 -> "4K"
    125 -> "HDR"
    126 -> "杜比视界"
    127 -> "8K"
    else -> if (height > 0) "${height}P" else "qn$qn"
}

/**
 * MPD 里视频 Representation 的 id。
 *
 * 同一档画质常有 AVC / HEVC / AV1 三条轨，B 站给的 id 都是 qn——直接用会撞 id。
 * 带上下标保证唯一，qn 仍可从前缀解析回来（见 [qnOfRepresentationId]）。
 */
internal fun videoRepresentationId(qn: Int, index: Int): String = "$qn-$index"

/** 从 [videoRepresentationId] 生成的 id 里解析 qn。解析不了返回 0。 */
internal fun qnOfRepresentationId(id: String?): Int =
    id?.substringBefore('-')?.toIntOrNull() ?: 0
