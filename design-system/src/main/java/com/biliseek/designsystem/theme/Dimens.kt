package com.biliseek.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 间距刻度。页面里写死的 `.dp` 一律从这里取，不再各写各的。
 *
 * 规则：页面左右边距 [lg]；卡片内边距 [md]；同一行元素之间 [sm]；
 * 图标与紧贴的文字 [xs]；大区块之间 [xl]。
 */
object BsSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** 尺寸刻度：图标、头像、触控区。 */
object BsSize {
    /** 行内小图标（按钮里紧贴文字的图标、互动栏横排图标）。 */
    val iconSm = 18.dp
    /** 标准图标。 */
    val icon = 24.dp
    /** 大图标（竖排互动栏、快捷入口）。 */
    val iconLg = 28.dp
    /** 评论 / 楼中楼头像。 */
    val avatarSm = 28.dp
    /** 列表行头像。 */
    val avatar = 40.dp
    /** UP 主页头像。 */
    val avatarLg = 64.dp
    /** 最小触控区（M3 规范 48dp）。 */
    val touchTarget = 48.dp
    /** 行内表情高度（与 bodyMedium 行高对齐）。 */
    val emoteInline = 20.dp
    /** 大表情（meta.size=2）。 */
    val emoteLarge = 48.dp
}

/** 圆角刻度。 */
object BsShapes {
    /** 标签、封面角标。 */
    val xs = RoundedCornerShape(4.dp)
    /** 封面、小卡片、输入框。 */
    val sm = RoundedCornerShape(8.dp)
    /** 卡片、楼中楼底板。 */
    val md = RoundedCornerShape(12.dp)
    /** 快捷按钮块、底部弹层内的分组。 */
    val lg = RoundedCornerShape(20.dp)
    /** 胶囊（Chip、搜索框）。 */
    val pill = RoundedCornerShape(percent = 50)
}
