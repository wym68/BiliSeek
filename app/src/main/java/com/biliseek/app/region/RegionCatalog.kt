package com.biliseek.app.region

// 分区入口的清单（设计稿：docs/superpowers/specs/2026-10-21-region-entries-design.md）。
// 全部是**旧版（v1）分区号**：newlist_rank 只认 v1；取自 bilibili-API-collect 镜像 docs/video/video_zone.md，已下线的排除。
// 只收适合「听」的；「杂谈」「配音与广播剧」是自拟分组（原分属影视 / 娱乐 / 动画 / 国创）。

/** 分区大类（总览页的一张卡片、详情页的一组标签）。声明顺序 = 显示顺序。 */
enum class RegionGroup(val title: String) {
    MUSIC("音乐"),
    KNOWLEDGE("知识"),
    DOCUMENTARY("纪录片"),
    TALK("杂谈"),
    VOICE("配音与广播剧"),
}

/** 一个子分区：[tid] 是 v1 分区号。 */
data class SubRegion(val tid: Int, val name: String, val group: RegionGroup)

object RegionCatalog {
    val all: List<SubRegion> = listOf(
        SubRegion(28, "原创音乐", RegionGroup.MUSIC),
        SubRegion(31, "翻唱", RegionGroup.MUSIC),
        SubRegion(59, "演奏", RegionGroup.MUSIC),
        SubRegion(30, "VOCALOID·UTAU", RegionGroup.MUSIC),
        SubRegion(29, "音乐现场", RegionGroup.MUSIC),
        SubRegion(243, "乐评盘点", RegionGroup.MUSIC),
        SubRegion(267, "电台", RegionGroup.MUSIC),
        SubRegion(130, "音乐综合", RegionGroup.MUSIC),
        SubRegion(228, "人文历史", RegionGroup.KNOWLEDGE),
        SubRegion(124, "社科·法律·心理", RegionGroup.KNOWLEDGE),
        SubRegion(201, "科学科普", RegionGroup.KNOWLEDGE),
        SubRegion(207, "财经商业", RegionGroup.KNOWLEDGE),
        SubRegion(209, "职业职场", RegionGroup.KNOWLEDGE),
        SubRegion(208, "校园学习", RegionGroup.KNOWLEDGE),
        SubRegion(37, "人文·历史", RegionGroup.DOCUMENTARY),
        SubRegion(178, "科学·探索·自然", RegionGroup.DOCUMENTARY),
        SubRegion(180, "社会·美食·旅行", RegionGroup.DOCUMENTARY),
        SubRegion(182, "影视杂谈", RegionGroup.TALK),
        SubRegion(241, "娱乐杂谈", RegionGroup.TALK),
        SubRegion(253, "动漫杂谈", RegionGroup.TALK),
        SubRegion(195, "动态漫·广播剧", RegionGroup.VOICE),
        SubRegion(257, "配音", RegionGroup.VOICE),
    )

    private val byTid: Map<Int, SubRegion> = all.associateBy { it.tid }

    fun inGroup(group: RegionGroup): List<SubRegion> = all.filter { it.group == group }

    fun byTid(tid: Int): SubRegion? = byTid[tid]
}
