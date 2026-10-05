package com.biliseek.app.ui

import androidx.compose.ui.graphics.Color
import com.biliseek.core.network.DynamicVideo
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.dto.WatchLaterChargingPay
import com.biliseek.core.network.dto.WatchLaterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面充电角标（设计稿：docs/superpowers/specs/2026-10-04-charge-badge-design.md）。
 * 2026-10-05 用户要求去掉「已充电」、搜索结果不标：视频详情里限免和已充电分不开，搜索接口不带充电信息。
 */
class ChargeBadgePolicyTest {

    @Test
    fun `充电专属是粉色的「充电」 限时免费是绿色的「限免」`() {
        assertEquals("充电", ChargeExclusiveBadge.text)
        assertEquals(Color(0xFFFB7299), ChargeExclusiveBadge.color)
        assertEquals("限免", LimitedFreeBadge.text)
        assertEquals(Color(0xFF1DB46E), LimitedFreeBadge.color)
    }

    @Test
    fun `空间投稿按 is_charging_arc 或 elec_arc_type 为 1`() {
        assertEquals(ChargeExclusiveBadge, chargeBadge(SpaceVideo(bvid = "BV1", isChargingArc = true)))
        assertEquals(ChargeExclusiveBadge, chargeBadge(SpaceVideo(bvid = "BV1", elecArcType = 1)))
        assertNull(chargeBadge(SpaceVideo(bvid = "BV1")))
    }

    @Test
    fun `UP 空间里限时免费的充电视频标限免`() {
        // 2026-10-05 真机日志：限免视频 elec_arc_badge="限时免费"，is_charging_arc / elec_arc_type 和充电专属一样
        val free = SpaceVideo(bvid = "BV1", isChargingArc = true, elecArcType = 1, elecArcBadge = "限时免费")
        assertEquals(LimitedFreeBadge, chargeBadge(free))
        assertEquals(ChargeExclusiveBadge, chargeBadge(free.copy(elecArcBadge = "充电专属")))
    }

    @Test
    fun `稍后再看按 charging_pay 的 level`() {
        assertEquals(ChargeExclusiveBadge, chargeBadge(WatchLaterItem(bvid = "BV1", chargingPay = WatchLaterChargingPay(level = 1))))
        assertNull(chargeBadge(WatchLaterItem(bvid = "BV1", chargingPay = WatchLaterChargingPay(level = null))))
        assertNull(chargeBadge(WatchLaterItem(bvid = "BV1")))
    }

    @Test
    fun `动态里的视频按解析时算好的 chargeExclusive 与 limitedFree`() {
        val video = DynamicVideo(bvid = "BV1", title = "t", coverUrl = "", durationText = "03:00")
        assertEquals(ChargeExclusiveBadge, chargeBadge(video.copy(chargeExclusive = true)))
        assertEquals(LimitedFreeBadge, chargeBadge(video.copy(limitedFree = true)))
        assertNull(chargeBadge(video))
    }
}
