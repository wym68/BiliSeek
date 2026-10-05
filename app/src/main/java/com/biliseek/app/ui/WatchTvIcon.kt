package com.biliseek.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 「看」的图标：小电视——带两根天线的电视机身，屏幕挖空。
 *
 * Material 自带的 `Tv` 没有天线，`LiveTv` 里是播放三角；用户要的正是摆脱「播放」这层意思，所以自绘。
 * 24 的画布：机身 20×14（圆角 3.5），屏幕 15×9（圆角 1.5），天线描边宽 1.8、圆头。
 * 颜色只是占位，用的时候由 `Icon(tint = …)` 统一上色。
 */
internal val WatchTvIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WatchTv",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(
            pathData = addPathNodes(
                "M5.5 7h13A3.5 3.5 0 0 1 22 10.5v7A3.5 3.5 0 0 1 18.5 21h-13A3.5 3.5 0 0 1 2 17.5v-7A3.5 3.5 0 0 1 5.5 7z" +
                    "M6 9.5h12a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 17v-6A1.5 1.5 0 0 1 6 9.5z",
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        .addPath(
            pathData = addPathNodes("M8 3.2 11 7M16 3.2 13 7"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        )
        .build()
}
