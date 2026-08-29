package com.biliseek.core.network

/**
 * 搜索筛选（M9 打磨项）。
 *
 * B 站搜索接口支持 `order`（排序）与 `duration`（时长区间）两个维度，
 * 对「听」场景价值极高：筛长视频听专辑、筛短视频听单曲。
 * 参数拼装抽成纯函数——拼错不会崩，只会「筛了个寂寞」，必须能被单测钉住。
 */

/** 排序方式。空串 = 综合排序（接口默认）。 */
enum class SearchOrder(val param: String) {
    COMPREHENSIVE(""),
    /** 最多播放 */
    CLICK("click"),
    /** 最新发布 */
    PUBDATE("pubdate"),
    /** 最多弹幕 */
    DM("dm"),
}

/** 时长区间。0 = 不限。 */
enum class SearchDuration(val param: Int) {
    ANY(0),
    /** 10 分钟以下 */
    UNDER_10(1),
    /** 10–30 分钟 */
    _10_30(2),
    /** 30–60 分钟 */
    _30_60(3),
    /** 60 分钟以上 */
    OVER_60(4),
}

/**
 * 搜索接口参数表（未签名）。
 *
 * 调用方拿到后过一遍 [WbiSigner.sign] —— WBI 签名覆盖整张参数表，
 * 筛选参数同样参与签名，不必额外处理。
 */
fun buildSearchParams(
    keyword: String,
    page: Int,
    order: SearchOrder = SearchOrder.COMPREHENSIVE,
    duration: SearchDuration = SearchDuration.ANY,
): Map<String, String> = buildMap {
    put("search_type", "video")
    put("keyword", keyword)
    put("page", page.toString())
    put("page_size", PAGE_SIZE.toString())
    // 综合排序用空串而非 totalrank：接口对未登录的综合排序返回更稳的结果。
    // 空串参数不写进表里，服务端取默认值，效果相同且请求更干净
    if (order.param.isNotBlank()) put("order", order.param)
    if (duration != SearchDuration.ANY) put("duration", duration.param.toString())
}

internal const val PAGE_SIZE = 20
