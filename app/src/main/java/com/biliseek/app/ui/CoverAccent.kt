package com.biliseek.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.biliseek.designsystem.theme.CoverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面主色缓存。
 *
 * 按 URL 缓存，避免展开播放器每次重组都重新解码一遍封面。简单 LRU：
 * 封面色是「氛围」不是精确值，容量 32 足够覆盖一次会话里看过的封面。
 */
object CoverAccentCache {
    private const val maxEntries = 32
    private val cache = object : LinkedHashMap<String, Color?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color?>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): Color? = cache[url]

    @Synchronized
    fun put(url: String, color: Color?) {
        cache[url] = color
    }
}

/**
 * 取封面主色（挂起、可取消）。
 *
 * 走 App 装配的 [coil3.SingletonImageLoader]（Referer 头已注入，M5 封面图
 * 的同一套）——封面取色请求不能另起炉灶裸请求，B 站 CDN 会 403。
 *
 * 解码 + 取色较重，只在播放器展开这种「一次一处」的场景调用，不进搜索
 * 结果列表（M12 计划 A-2 性能边界）。取色失败（网络 403 / 解码失败 /
 * 无主色）返回 null，由策略层回落 M3 默认。
 */
suspend fun extractCoverDominant(context: Context, url: String): Color? {
    if (url.isBlank()) return null
    CoverAccentCache.get(url)?.let { return it }

    val request = ImageRequest.Builder(context)
        .data(url)
        // 取色不需要原图，把解码尺寸压小
        .size(Size(512, 512))
        .build()
    val result = context.imageLoader.execute(request)
    val bitmap = (result as? SuccessResult)
        ?.image
        ?.let { it as? BitmapImage }
        ?.bitmap
        ?: return null
    // Palette 读像素要求软件位图：硬件位图（Coil 在 API 26+ 常见配置）先转 ARGB_8888
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
        runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
    } else {
        bitmap
    }

    // Palette 是同步 CPU 活，丢到默认调度器，别卡主线程
    val dominant = withContext(Dispatchers.Default) {
        runCatching {
            Palette.from(software)
                .maximumColorCount(16)
                .generate()
                .dominantSwatch
                ?.rgb
        }.getOrNull()
    }?.let { Color(it) }

    CoverAccentCache.put(url, dominant)
    return dominant
}

/**
 * 当前封面的取色结果（Compose 状态）。
 *
 * keyed by [coverUrl]：换歌自动重取；同 URL 命中缓存，重组不重复解码。
 * 取色失败返回 [CoverPalette.Empty]，UI 回落到 M3 默认——不闪白、不崩。
 */
@Composable
fun rememberCoverPalette(coverUrl: String): CoverPalette {
    val context = LocalContext.current
    var dominant by remember(coverUrl) { mutableStateOf(CoverAccentCache.get(coverUrl)) }
    LaunchedEffect(coverUrl) {
        if (coverUrl.isBlank()) return@LaunchedEffect
        dominant = extractCoverDominant(context, coverUrl)
    }
    return CoverPalette(dominant)
}
