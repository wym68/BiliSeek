package com.biliseek.core.network.internal

import com.biliseek.core.network.dto.MentionSearchData
import com.biliseek.core.network.dto.MentionUser

/** 各分组拍平成一列：丢掉 uid / 昵称缺失的，同一个人在多个分组里只留第一次出现。 */
internal fun MentionSearchData?.flattenMentionUsers(): List<MentionUser> =
    this?.groups.orEmpty()
        .flatMap { it.items }
        .filter { it.uid > 0 && it.name.isNotBlank() }
        .distinctBy { it.uid }
