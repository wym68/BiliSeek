package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `x/polymer/web-dynamic/v1/mention/search` —— 写评论时 @ 的候选人（最近 @ 过的、关注的…分组给）。 */
@Serializable
data class MentionSearchData(
    val groups: List<MentionGroup> = emptyList(),
)

@Serializable
data class MentionGroup(
    @SerialName("group_name") val groupName: String = "",
    @SerialName("group_type") val groupType: Int = 0,
    val items: List<MentionUser> = emptyList(),
)

/** 一个可 @ 的人。uid / fans 有时是字符串，容错解析。 */
@Serializable
data class MentionUser(
    @Serializable(with = TolerantLongSerializer::class) val uid: Long = 0,
    val name: String = "",
    /** 头像，可能是 `//` 或 `http://` 开头，显示前走 normalizeCoverUrl。 */
    val face: String = "",
    @Serializable(with = TolerantLongSerializer::class) val fans: Long = 0,
)
