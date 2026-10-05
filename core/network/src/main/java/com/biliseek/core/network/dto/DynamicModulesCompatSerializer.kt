package com.biliseek.core.network.dto

import com.biliseek.core.network.normalizeCoverUrl
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `DynamicItem.modules` 双形态兼容。
 *
 * feed/all、feed/space、web detail 的 modules 是对象；**opus/detail 的 modules
 * 是 `MODULE_TYPE_*` 数组**（module_title / module_content.paragraphs …）。
 * 直接按对象解码会让 detail 整页失败。
 *
 * 数组形态处理（对齐 bilipai `DynamicModulesFlexibleSerializer`，取简化版）：
 * - 对象形态的元素（module_author/module_stat/…）逐个合并；
 * - `MODULE_TYPE_TITLE` 抽出标题；
 * - `MODULE_TYPE_CONTENT` 的 paragraphs 抽出正文（word/rich/formula 节点）
 *   与图片（pic.pics / pics / line.pic），归一化进 module_dynamic：
 *   desc 取更长者，图合并进 `major.opus.pics`，保留已有非 OPUS major；
 *   同时按原文顺序收成 `major.opus.blocks`（文字段 / 图片段，展开后图文穿插用）。
 */
object DynamicModulesCompatSerializer : KSerializer<DynamicModules> {
    override val descriptor: SerialDescriptor = DynamicModules.serializer().descriptor

    override fun deserialize(decoder: Decoder): DynamicModules {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return DynamicModules.serializer().deserialize(decoder)
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> jsonDecoder.json.decodeFromJsonElement(DynamicModules.serializer(), element)
            is JsonArray -> flattenModuleArray(jsonDecoder.json, element)
            else -> DynamicModules()
        }
    }

    override fun serialize(encoder: Encoder, value: DynamicModules) {
        DynamicModules.serializer().serialize(encoder, value)
    }

    private fun flattenModuleArray(json: Json, array: JsonArray): DynamicModules {
        var author: DynamicAuthor? = null
        var dynamic: DynamicContent? = null
        var stat: DynamicStatModule? = null
        var title: String? = null
        val paragraphTexts = mutableListOf<String>()
        val paragraphPics = mutableListOf<String>()
        val blocks = mutableListOf<DynamicBlock>()

        for (node in array) {
            val obj = node as? JsonObject ?: continue
            // 对象形态模块（module_author / module_dynamic / module_stat …）逐个合并
            runCatching { json.decodeFromJsonElement<DynamicModules>(obj) }.getOrNull()?.let { parsed ->
                if (author == null) author = parsed.author
                if (dynamic == null) dynamic = parsed.dynamic
                if (stat == null) stat = parsed.stat
            }
            val moduleType = obj["module_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            (obj["module_title"] as? JsonObject)
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { title = it }
            val content = obj["module_content"] as? JsonObject
            if (moduleType == "MODULE_TYPE_CONTENT" || content != null) {
                (content?.get("paragraphs") as? JsonArray)?.forEach { paragraphNode ->
                    val paragraph = paragraphNode as? JsonObject ?: return@forEach
                    extractParagraphText(paragraph).takeIf { it.isNotBlank() }?.let { text ->
                        paragraphTexts += text
                        appendTextBlock(blocks, text)
                    }
                    val pics = extractParagraphPics(paragraph)
                    if (pics.isNotEmpty()) blocks += DynamicBlock.Images(pics)
                    paragraphPics += pics.map { it.url }
                    extractLinePicUrl(paragraph)?.let(paragraphPics::add)
                }
            }
        }

        return normalize(author, dynamic, stat, title, paragraphTexts, paragraphPics, blocks)
    }

    /** 相邻的文字段落合成一段（换行连接），和以前拼整段正文的样子一致。 */
    private fun appendTextBlock(blocks: MutableList<DynamicBlock>, text: String) {
        val last = blocks.lastOrNull()
        if (last is DynamicBlock.Text) {
            blocks[blocks.lastIndex] = DynamicBlock.Text(last.text + "\n" + text)
        } else {
            blocks += DynamicBlock.Text(text)
        }
    }

    /** 把段落内容归一化进 module_dynamic（对齐 bilipai normalizeWithOpusModules）。 */
    private fun normalize(
        author: DynamicAuthor?,
        existingContent: DynamicContent?,
        stat: DynamicStatModule?,
        title: String?,
        paragraphTexts: List<String>,
        paragraphPics: List<String>,
        blocks: List<DynamicBlock>,
    ): DynamicModules {
        val existingDesc = existingContent?.desc
        val joined = paragraphTexts.joinToString("\n")
        val mergedDescText = when {
            joined.isBlank() -> existingDesc?.text.orEmpty()
            existingDesc?.text.isNullOrBlank() -> joined
            joined.length > existingDesc.text.length -> joined
            else -> existingDesc.text
        }
        val mergedDesc = if (mergedDescText.isNotBlank()) DynamicDesc(text = mergedDescText) else existingDesc

        val existingMajor = existingContent?.major
        val majorType = existingMajor?.type.orEmpty()
        val hasDerivedContent = joined.isNotBlank() || paragraphPics.isNotEmpty() || title != null
        val mergedMajor = when {
            !hasDerivedContent -> existingMajor
            // 已有非 OPUS 主体（视频/直播等）时不动 major
            existingMajor != null && majorType.isNotBlank() && majorType != "MAJOR_TYPE_OPUS" -> existingMajor
            else -> {
                val baseOpus = existingMajor?.opus ?: DynamicOpus()
                val mergedOpus = baseOpus.copy(
                    title = title ?: baseOpus.title,
                    pics = if (paragraphPics.isNotEmpty()) paragraphPics else baseOpus.pics,
                    blocks = if (blocks.isNotEmpty()) blocks else baseOpus.blocks,
                )
                (existingMajor ?: DynamicMajor()).copy(
                    type = "MAJOR_TYPE_OPUS",
                    opus = mergedOpus,
                )
            }
        }
        val mergedContent = if (existingContent != null || mergedDesc != null || mergedMajor != null) {
            DynamicContent(desc = mergedDesc, major = mergedMajor)
        } else {
            null
        }
        return DynamicModules(author = author, dynamic = mergedContent, stat = stat)
    }

    /** 段落纯文本：heading / text / list 三类节点，word.words → rich.orig_text → formula。 */
    private fun extractParagraphText(paragraph: JsonObject): String {
        val sb = StringBuilder()
        (paragraph["heading"] as? JsonObject)?.get("nodes")?.let { sb.append(nodesText(it)) }
        (paragraph["text"] as? JsonObject)?.get("nodes")?.let { sb.append(nodesText(it)) }
        (paragraph["list"] as? JsonObject)?.get("items")?.let { items ->
            (items as? JsonArray)?.forEach { item ->
                val text = nodesText((item as? JsonObject)?.get("nodes"))
                if (text.isNotBlank()) sb.append('\n').append(text)
            }
        }
        return sb.toString().trim()
    }

    private fun nodesText(element: JsonElement?): String = buildString {
        val nodes = element as? JsonArray ?: return@buildString
        for (nodeElement in nodes) {
            val node = nodeElement as? JsonObject ?: continue
            val text = (node["word"] as? JsonObject)?.get("words")?.jsonPrimitive?.contentOrNull
                ?: (node["rich"] as? JsonObject)?.let { rich ->
                    ((rich["orig_text"] ?: rich["text"]) as? JsonPrimitive)?.contentOrNull
                }
                ?: (node["formula"] as? JsonObject)?.get("latex_content")?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) append(text)
        }
    }

    /** 段落里的内容图（带宽高）：pic.pics[] / pic.url / pics[]。 */
    private fun extractParagraphPics(paragraph: JsonObject): List<DynamicPic> {
        val out = mutableListOf<DynamicPic>()
        val pic = paragraph["pic"] as? JsonObject
        (pic?.get("pics") as? JsonArray)?.forEach { element -> parsePic(element)?.let(out::add) }
        if (out.isEmpty()) parsePic(pic)?.let(out::add)
        (paragraph["pics"] as? JsonArray)?.forEach { element -> parsePic(element)?.let(out::add) }
        return out
    }

    /** 分割线图（line.pic）：装饰，只进平铺的 pics，不进正文段落。 */
    private fun extractLinePicUrl(paragraph: JsonObject): String? =
        ((paragraph["line"] as? JsonObject)?.get("pic") as? JsonObject)?.let(::parsePic)?.url

    private fun parsePic(element: JsonElement?): DynamicPic? {
        val obj = element as? JsonObject ?: return null
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return DynamicPic(
            url = normalizeCoverUrl(url),
            width = (obj["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            height = (obj["height"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
}
