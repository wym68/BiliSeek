package com.biliseek.core.network.internal

import com.biliseek.core.network.dto.UploadedImage
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `x/v2/reply/add` 的 `pictures` 表单字段：上传结果按顺序转成 JSON 数组字符串
 * （`[{"img_src":…,"img_width":…,"img_height":…,"img_size":…}]`）。没有图返回 null（字段整个不带）。
 */
internal fun buildReplyPictures(images: List<UploadedImage>): String? {
    if (images.isEmpty()) return null
    // 手拼 JSON 而不用 @Serializable：dto 包外的可序列化类要单独加 R8 keep 规则
    return buildJsonArray {
        images.forEach { img ->
            add(
                buildJsonObject {
                    put("img_src", img.url)
                    put("img_width", img.width)
                    put("img_height", img.height)
                    put("img_size", img.sizeKb)
                },
            )
        }
    }.toString()
}

/** `upload_bfs` 的图片 part：字段名必须是 `file_up`。 */
internal fun imageFilePart(bytes: ByteArray, mimeType: String, fileName: String): MultipartBody.Part =
    MultipartBody.Part.createFormData("file_up", fileName, bytes.toRequestBody(mimeType.toMediaType()))

/** multipart 里的文本字段（biz / category / 已注入的 csrf）。 */
internal fun textParts(fields: Map<String, String>): Map<String, RequestBody> =
    fields.mapValues { (_, v) -> v.toRequestBody(TEXT_PLAIN) }

private val TEXT_PLAIN = "text/plain".toMediaType()
