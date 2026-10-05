package com.biliseek.app.ui

import androidx.compose.ui.graphics.Color
import com.biliseek.core.network.DynamicVideo
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.dto.WatchLaterItem

/**
 * 视频封面右上角的充电角标（设计稿：docs/superpowers/specs/2026-10-04-charge-badge-design.md）。
 *
 * - 粉色「充电」= 充电专属；绿色「限免」= 充电视频正在限时免费（谁都能看）。
 * - 2026-10-05 用户要求去掉「已充电」：视频详情里限免和已充电的字段完全一样（`is_upower_play` 都是 true），
 *   会把只看过限免视频的 UP 误记成已充电。
 * - 搜索结果不标：搜索接口不带充电信息（充电专属、限免视频的 `badgepay` / `is_pay` / `is_charge_video` 都是 0）。
 * 各列表接口表示充电的字段不同，一个列表一个重载。
 */
val ChargeExclusiveBadge = CoverBadge("充电", Color(0xFFFB7299))
val LimitedFreeBadge = CoverBadge("限免", Color(0xFF1DB46E))

/**
 * UP 空间投稿：`is_charging_arc`，或 `elec_arc_type == 1`（真机日志核实）。
 * 限时免费的充电视频这两个字段一样，只有 `elec_arc_badge` 是「限时免费」（充电专属是「充电专属」）。
 */
fun chargeBadge(video: SpaceVideo): CoverBadge? = when {
    isLimitedFreeText(video.elecArcBadge) -> LimitedFreeBadge
    video.isChargingArc || video.elecArcType == 1 -> ChargeExclusiveBadge
    else -> null
}

/** 稍后再看：`charging_pay.level` 非空（bilipai 的判断）。 */
fun chargeBadge(item: WatchLaterItem): CoverBadge? =
    if (item.chargingPay?.level != null) ChargeExclusiveBadge else null

/** 动态里的视频：解析时已按角标文字算好（「充电专属」/「限时免费」，普通视频是「投稿视频」）。 */
fun chargeBadge(video: DynamicVideo): CoverBadge? = when {
    video.limitedFree -> LimitedFreeBadge
    video.chargeExclusive -> ChargeExclusiveBadge
    else -> null
}

private fun isLimitedFreeText(text: String): Boolean = "限时免费" in text || "限免" in text
