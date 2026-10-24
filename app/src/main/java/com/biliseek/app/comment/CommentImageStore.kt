package com.biliseek.app.comment

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 评论配图的本地暂存：选来的图先复制进 [dir]（App 缓存），上传时从这里读。
 * 草稿只在内存里，所以 App 重启时这里的文件都是孤儿，[clearAll] 在启动时清一次。
 */
class CommentImageStore(private val dir: File, private val resolver: ContentResolver) {

    /** 逐张导入；读不了 / 解不开的图跳过。返回成功的那些（顺序同 [uris]）。 */
    suspend fun import(uris: List<Uri>): List<CommentImage> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        uris.mapNotNull { uri ->
            val result = runCatching { importOne(uri) }
            result.getOrNull().also {
                if (it == null) Log.w(TAG, "导入失败 mime=${resolver.getType(uri)} uri=$uri", result.exceptionOrNull())
            }
        }
    }

    /** 视频截图存成 JPEG（长边超过 [REENCODE_MAX_EDGE] 先缩）。存完回收 [frame]。存不下返回 null。 */
    suspend fun importFrame(frame: Bitmap): CommentImage? = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val (w, h) = scaledToMaxEdge(frame.width, frame.height)
            val scaled = if (w == frame.width && h == frame.height) frame else Bitmap.createScaledBitmap(frame, w, h, true)
            val file = newFile("jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (scaled !== frame) scaled.recycle()
            CommentImage(file.path, "image/jpeg")
        } catch (e: Exception) {
            Log.w(TAG, "截图保存失败", e)
            null
        } finally {
            frame.recycle()
        }
    }

    /** 上传时读字节。 */
    suspend fun read(image: CommentImage): ByteArray = withContext(Dispatchers.IO) { File(image.path).readBytes() }

    fun delete(images: List<CommentImage>) {
        images.forEach { File(it.path).delete() }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun importOne(uri: Uri): CommentImage? {
        val mime = resolver.getType(uri)
        if (resolveImageImport(mime, sizeOf(uri)) == ImageImportAction.COPY) {
            val file = newFile(extensionOf(mime!!))
            if (copyWithin(uri, file, copyLimitFor(mime))) return CommentImage(file.path, mime.lowercase())
            file.delete()
        }
        return reencodeJpeg(uri)
    }

    private fun sizeOf(uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** 复制，超过 [limit] 就放弃（返回 false）。 */
    private fun copyWithin(uri: Uri, target: File, limit: Long): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        input.use { src ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) return false
                    out.write(buf, 0, n)
                }
            }
        }
        return true
    }

    private fun reencodeJpeg(uri: Uri): CommentImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 只读尺寸时 decodeStream 固定返回 null，不能拿返回值判断成败，看 outWidth / outHeight
        (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return try {
            val file = newFile("jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            CommentImage(file.path, "image/jpeg")
        } finally {
            bitmap.recycle()
        }
    }

    private fun newFile(ext: String) = File(dir, "${UUID.randomUUID()}.$ext")

    private fun extensionOf(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val JPEG_QUALITY = 90
        const val TAG = "BiliSeekCommentImage"
    }
}
