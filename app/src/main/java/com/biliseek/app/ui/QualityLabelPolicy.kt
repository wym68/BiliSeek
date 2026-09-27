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
