package com.biliseek.app.comment

import com.biliseek.core.network.dto.UploadedImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentImagePolicyTest {

    @Test
    fun `B 站认的格式且不超限原样复制`() {
        assertEquals(ImageImportAction.COPY, resolveImageImport("image/jpeg", 1_000))
        assertEquals(ImageImportAction.COPY, resolveImageImport("IMAGE/PNG", 1_000))
        assertEquals(ImageImportAction.COPY, resolveImageImport("image/gif", null))
    }

    @Test
    fun `HEIC 未知格式或超大图压成 JPEG`() {
        assertEquals(ImageImportAction.REENCODE_JPEG, resolveImageImport("image/heic", 1_000))
        assertEquals(ImageImportAction.REENCODE_JPEG, resolveImageImport(null, 1_000))
        assertEquals(ImageImportAction.REENCODE_JPEG, resolveImageImport("image/jpeg", MAX_COPY_BYTES + 1))
    }

    @Test
    fun `GIF 压了就不动了，放宽到 15MB 才压`() {
        assertEquals(ImageImportAction.COPY, resolveImageImport("image/gif", MAX_COPY_BYTES + 1))
        assertEquals(ImageImportAction.REENCODE_JPEG, resolveImageImport("image/gif", MAX_GIF_BYTES + 1))
    }

    @Test
    fun `inSampleSize 缩到长边不超过上限`() {
        assertEquals(1, sampleSizeFor(2560, 1920))
        assertEquals(2, sampleSizeFor(2561, 1920))
        assertEquals(2, sampleSizeFor(4000, 3000))
        assertEquals(8, sampleSizeFor(3000, 12000))
    }

    @Test
    fun `剩余张数不为负`() {
        assertEquals(9, remainingImageSlots(0))
        assertEquals(0, remainingImageSlots(9))
        assertEquals(0, remainingImageSlots(12))
    }

    @Test
    fun `按顺序上传，已传的跳过`() = runBlocking {
        val done = UploadedImage(url = "u0")
        val images = listOf(CommentImage("p0", "image/jpeg", done), CommentImage("p1", "image/png"), CommentImage("p2", "image/png"))
        val uploadedPaths = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val result = uploadPendingImages(
            images,
            upload = { img -> uploadedPaths += img.path; Result.success(UploadedImage(url = "u-${img.path}")) },
            onProgress = { _, i -> progress += i },
        )
        assertEquals(listOf("p1", "p2"), uploadedPaths)
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(listOf("u0", "u-p1", "u-p2"), result.getOrThrow().map { it.url })
    }

    @Test
    fun `一张失败就停，前面传好的结果留在列表里`() = runBlocking {
        val images = listOf(CommentImage("p0", "image/jpeg"), CommentImage("p1", "image/jpeg"), CommentImage("p2", "image/jpeg"))
        var last: List<CommentImage> = images
        val calls = mutableListOf<String>()
        val result = uploadPendingImages(
            images,
            upload = { img ->
                calls += img.path
                if (img.path == "p1") Result.failure(RuntimeException("boom")) else Result.success(UploadedImage(url = img.path))
            },
            onProgress = { list, _ -> last = list },
        )
        assertTrue(result.isFailure)
        assertEquals(listOf("p0", "p1"), calls)
        assertEquals(listOf("p0", null, null), last.map { it.uploaded?.url })
    }

    @Test
    fun `上传进度文案`() {
        assertEquals("正在上传图片 2/5…", uploadStatusText(1, 5))
    }
}
