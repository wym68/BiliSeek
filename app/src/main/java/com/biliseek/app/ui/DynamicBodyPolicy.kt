package com.biliseek.app.ui

import com.biliseek.app.data.DynamicExpandState
import com.biliseek.core.network.dto.DynamicBlock

/**
 * 长图文展开后按原文段落显示的正文：已展开、拉到了详情、详情带段落时才有。
 * 其余情况（收起、加载中、失败、详情只有摘要）为空，退回整段文字。
 */
fun expandedBody(state: DynamicExpandState?): List<DynamicBlock> =
    if (state?.expanded == true) state.entry?.body.orEmpty() else emptyList()

/** 正文里的图片按出现顺序摊平：点开预览时可以左右翻到全文所有图。 */
fun bodyImageUrls(body: List<DynamicBlock>): List<String> =
    body.flatMap { block -> (block as? DynamicBlock.Images)?.pics.orEmpty().map { it.url } }

/** 每段第一张图在 [bodyImageUrls] 里的下标（文字段落的值是它前面的图片张数，用不到）。 */
fun bodyImageOffsets(body: List<DynamicBlock>): List<Int> =
    body.runningFold(0) { count, block -> count + ((block as? DynamicBlock.Images)?.pics?.size ?: 0) }
        .dropLast(1)

/**
 * 正文单张图的显示宽高比（宽 / 高）：按原图比例；缺宽高按 4:3；
 * 特别长的图最高到宽的 2 倍（约一屏），多出的裁掉，点开看全图。
 */
fun inlineImageAspect(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) 4f / 3f else (width.toFloat() / height).coerceAtLeast(MIN_INLINE_IMAGE_ASPECT)

private const val MIN_INLINE_IMAGE_ASPECT = 0.5f
