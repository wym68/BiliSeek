package com.biliseek.app.ui

import com.biliseek.core.player.VIDEO_QUALITY_AUTO
import com.biliseek.core.player.VideoQualityOption

/**
 * 档位名的短写：playurl 给的是「1080P 高清」「720P 准高清」这种，
 * 按钮上放不下，取第一个空格前的部分。
 */
fun shortQualityLabel(label: String): String = label.trim().substringBefore(' ')

/** 全屏底栏画质按钮上的字：自动时写「自动」，锁定时写实际档位。 */
fun videoQualityButtonLabel(
    options: List<VideoQualityOption>,
    preference: Int,
    currentQn: Int?,
): String {
    if (preference == VIDEO_QUALITY_AUTO || currentQn == null) return "自动"
    return options.firstOrNull { it.qn == currentQn }?.label?.let(::shortQualityLabel) ?: "画质"
}

/**
 * 所选档位没能生效（这个视频没有 / 账号拿不到）时，实际用的是哪一档。
 * 生效了或是自动，返回 null——不需要解释。
 */
fun videoQualityFallbackLabel(
    options: List<VideoQualityOption>,
    preference: Int,
    currentQn: Int?,
): String? {
    if (preference == VIDEO_QUALITY_AUTO || currentQn == null || currentQn == preference) return null
    return options.firstOrNull { it.qn == currentQn }?.label?.let(::shortQualityLabel)
}

/** 音质档位名。 */
fun audioQualityLabel(quality: com.biliseek.core.player.AudioQuality): String = when (quality) {
    com.biliseek.core.player.AudioQuality.AUTO -> "自动"
    com.biliseek.core.player.AudioQuality.LOSSLESS -> "无损优先"
    com.biliseek.core.player.AudioQuality.SAVER -> "省流量"
}

/** 全屏底栏音质按钮上的字：自动时写「音质」（入口提示），否则写所选档位。 */
fun audioQualityButtonLabel(requested: com.biliseek.core.player.AudioQuality): String = when (requested) {
    com.biliseek.core.player.AudioQuality.AUTO -> "音质"
    com.biliseek.core.player.AudioQuality.LOSSLESS -> "无损"
    com.biliseek.core.player.AudioQuality.SAVER -> "省流"
}

/**
 * 音质面板里「当前实际是什么」的说明。所选档位如实生效时返回 null。
 *
 * 原则同设置页：拿不到时如实说出原因，不默默降级。
 */
fun audioQualityStatusText(
    fallback: com.biliseek.core.player.QualityFallback,
    isOffline: Boolean,
): String? = when {
    isOffline -> "正在播放本地文件，音质档位不适用"
    fallback == com.biliseek.core.player.QualityFallback.UNAVAILABLE ->
        "这个视频没有无损 / 杜比音轨，或账号没有权限，当前为普通音质"
    fallback == com.biliseek.core.player.QualityFallback.SPEED_INCOMPATIBLE ->
        "当前倍速下无损已切换为 AAC，降回 1.5× 及以下自动恢复"
    else -> null
}
