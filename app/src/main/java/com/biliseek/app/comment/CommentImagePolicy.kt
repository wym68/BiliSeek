package com.biliseek.app.comment

import com.biliseek.core.network.dto.UploadedImage

/** 一条评论最多带几张图（与 B 站网页端一致）。 */
const val MAX_COMMENT_IMAGES = 9

/**
 * 原图超过这个大小就压成 JPEG 再传：相机原图动辄几 MB，手机网络上传几张就超时（10-24 真机）。
 * GIF 压了就不动了，放宽到 [MAX_GIF_BYTES]。
 */
const val MAX_COPY_BYTES = 2L * 1024 * 1024
const val MAX_GIF_BYTES = 15L * 1024 * 1024

/** 压成 JPEG 时长边最多多少像素（评论里看图足够清楚）。 */
const val REENCODE_MAX_EDGE = 2560

/**
 * 输入栏里待发的一张图：已复制进 App 缓存的本地文件（不依赖相册给的临时读权限）。
 * [uploaded] 非 null = 已传到图床：发送失败后再点发送不重传。
 */
data class CommentImage(val path: String, val mimeType: String, val uploaded: UploadedImage? = null)

/** 选来的图怎么存：B 站认的格式且不太大就原样复制；其余（HEIC、大图）压成 JPEG。 */
enum class ImageImportAction { COPY, REENCODE_JPEG }

private val UPLOADABLE_MIME = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

/** [sizeBytes] 为 null = 不知道大小：先原样复制，复制时超过 [copyLimitFor] 再改压 JPEG。 */
fun resolveImageImport(mimeType: String?, sizeBytes: Long?): ImageImportAction = when {
    mimeType?.lowercase() !in UPLOADABLE_MIME -> ImageImportAction.REENCODE_JPEG
    sizeBytes != null && sizeBytes > copyLimitFor(mimeType) -> ImageImportAction.REENCODE_JPEG
    else -> ImageImportAction.COPY
}

/** 原样复制的大小上限。 */
fun copyLimitFor(mimeType: String?): Long =
    if (mimeType?.lowercase() == "image/gif") MAX_GIF_BYTES else MAX_COPY_BYTES

/** 解码时的 inSampleSize（2 的幂）：缩到长边不超过 [maxEdge]。 */
fun sampleSizeFor(width: Int, height: Int, maxEdge: Int = REENCODE_MAX_EDGE): Int {
    var sample = 1
    while (maxOf(width, height) / sample > maxEdge) sample *= 2
    return sample
}

/** 还能再加几张。 */
fun remainingImageSlots(current: Int): Int = (MAX_COMMENT_IMAGES - current).coerceAtLeast(0)

/** 发送前上传时的进度提示。 */
fun uploadStatusText(done: Int, total: Int): String = "正在上传图片 ${done + 1}/$total…"

/**
 * 按顺序把还没上传的图传上去（一张失败就停，已传的留着结果，下次发送跳过）。
 * 每传完一张调 [onProgress]（带上已更新的列表），全部成功返回与 [images] 同序的上传结果。
 */
suspend fun uploadPendingImages(
    images: List<CommentImage>,
    upload: suspend (CommentImage) -> Result<UploadedImage>,
    onProgress: (images: List<CommentImage>, uploading: Int) -> Unit,
): Result<List<UploadedImage>> {
    val current = images.toMutableList()
    for (i in current.indices) {
        if (current[i].uploaded != null) continue
        onProgress(current.toList(), i)
        val result = upload(current[i]).getOrElse { return Result.failure(it) }
        current[i] = current[i].copy(uploaded = result)
    }
    onProgress(current.toList(), current.size)
    return Result.success(current.map { it.uploaded!! })
}
