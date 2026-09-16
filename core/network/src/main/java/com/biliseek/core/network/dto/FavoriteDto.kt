package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 收藏夹（M11）─────────────────────────────────────────

/** `x/v3/fav/folder/created/list-all` —— 我创建的收藏夹目录。 */
@Serializable
data class FavoriteFolderListData(
    val count: Int = 0,
    val list: List<FavoriteFolderItem> = emptyList(),
)

@Serializable
data class FavoriteFolderItem(
    /** media_id。 */
    val id: Long = 0,
    val title: String = "",
    /** 封面，`//i0.hdslb.com/...` 需补协议（与搜索结果一致）。 */
    val cover: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
)

/** `x/v3/fav/resource/list` —— 收藏夹内容（分页）。 */
@Serializable
data class FavoriteResourceData(
    @SerialName("has_more") val hasMore: Boolean = false,
    val medias: List<FavoriteMedia> = emptyList(),
)

@Serializable
data class FavoriteMedia(
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    /** 秒。 */
    val duration: Long = 0,
    val upper: FavoriteUpper? = null,
    /** 2 = 视频稿件；其他类型（音频/相簿）不进入可播放列表。 */
    val type: Int = 0,
)

@Serializable
data class FavoriteUpper(
    val mid: Long = 0,
    val name: String = "",
)

/** `x/v3/fav/folder/add` —— 新建收藏夹返回的元数据。`id` 即 media_id。 */
@Serializable
data class FavoriteFolderData(
    val id: Long = 0,
    val fid: Long = 0,
    val mid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
    @SerialName("ctime") val ctime: Long = 0,
    @SerialName("mtime") val mtime: Long = 0,
)
