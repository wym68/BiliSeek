package com.biliseek.app.data

import kotlinx.coroutines.flow.Flow

/** 本地库读写。UI 层不直接碰 DAO。 */
class LibraryRepository(private val db: BiliSeekDatabase) {

    private val playlists = db.playlistDao()
    private val history = db.historyDao()
    private val searches = db.searchHistoryDao()

    // ── 歌单 ────────────────────────────────────────────

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists.observeAll()

    fun observeTracks(playlistId: Long): Flow<List<PlaylistTrack>> =
        playlists.observeTracks(playlistId)

    suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlists.insert(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
    }

    suspend fun renamePlaylist(id: Long, name: String) =
        playlists.rename(id, name, System.currentTimeMillis())

    suspend fun deletePlaylist(id: Long) = playlists.delete(id)

    /** 加入歌单。track 已存在时复用，避免重复行。 */
    suspend fun addToPlaylist(playlistId: Long, track: TrackEntity) {
        val existing = playlists.findTrack(track.bvid, track.cid)
        val trackId = existing?.id ?: playlists.insertTrack(track).let { inserted ->
            // IGNORE 冲突时返回 -1，需再查一次
            if (inserted > 0) inserted else playlists.findTrack(track.bvid, track.cid)!!.id
        }
        // sortKey 用浮点：插入中间位置时无需重排整表
        val next = (playlists.maxSortKey(playlistId) ?: 0.0) + SORT_STEP
        playlists.link(PlaylistTrackEntity(playlistId, trackId, next))
        playlists.rename(playlistId, playlists.byId(playlistId)!!.name, System.currentTimeMillis())
    }

    suspend fun removeFromPlaylist(playlistId: Long, trackId: Long) =
        playlists.unlink(playlistId, trackId)

    /** 把 track 移到 [before] 与 [after] 之间，只改一行。 */
    suspend fun reorder(playlistId: Long, trackId: Long, before: Double?, after: Double?) {
        val key = when {
            before == null && after == null -> SORT_STEP
            before == null -> after!! - SORT_STEP
            after == null -> before + SORT_STEP
            else -> (before + after) / 2
        }
        playlists.updateSortKey(playlistId, trackId, key)
    }

    // ── 播放历史 ────────────────────────────────────────

    fun observeRecent(limit: Int = 20): Flow<List<PlayHistoryEntity>> =
        history.observeRecent(limit)

    fun observeHeatmap(days: Int = 90): Flow<List<DayCount>> = history.observeHeatmap(days)

    /**
     * 记录一次播放。
     *
     * [positionMs] 传 `null` 表示**保留既有进度**——起播时调用本方法不应该把
     * 续播位置抹平，那正是「点最近播放却从头开始」的成因。
     */
    suspend fun recordPlay(
        bvid: String,
        cid: Long,
        title: String,
        author: String = "",
        coverUrl: String = "",
        durationSec: Long = 0,
        positionMs: Long? = null,
    ) {
        val existing = history.find(bvid, cid)
        history.upsert(
            PlayHistoryEntity(
                id = existing?.id ?: 0,
                bvid = bvid,
                cid = cid,
                title = title,
                author = author.ifBlank { existing?.author.orEmpty() },
                coverUrl = coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
                durationSec = if (durationSec > 0) durationSec else existing?.durationSec ?: 0,
                positionMs = positionMs ?: existing?.positionMs ?: 0L,
                playedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 播放中定期回写位置，只更新进度不改其他字段。 */
    suspend fun recordPlayPosition(bvid: String, cid: Long, positionMs: Long) {
        val existing = history.find(bvid, cid) ?: return
        history.upsert(existing.copy(positionMs = positionMs, playedAt = System.currentTimeMillis()))
    }

    /**
     * 取流后回写时长，**不动 positionMs / playedAt**。
     *
     * 不能复用 [recordPlay]：那是「记录一次播放」的语义，会刷新播放时间，
     * 把一次后台的时长补写伪装成用户又听了一遍。
     */
    suspend fun recordDuration(bvid: String, cid: Long, durationSec: Long) {
        if (durationSec <= 0) return
        val existing = history.find(bvid, cid) ?: return
        if (existing.durationSec == durationSec) return
        history.upsert(existing.copy(durationSec = durationSec))
    }

    /** 续播用：取上次播放位置。 */
    suspend fun resumePosition(bvid: String, cid: Long): Long =
        history.find(bvid, cid)?.positionMs ?: 0L

    // ── 搜索历史 ────────────────────────────────────────

    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>> = searches.observeRecent()

    suspend fun recordSearch(keyword: String) {
        if (keyword.isBlank()) return
        searches.delete(keyword)
        searches.upsert(SearchHistoryEntity(keyword = keyword, searchedAt = System.currentTimeMillis()))
        searches.trim()
    }

    suspend fun deleteSearch(keyword: String) = searches.delete(keyword)

    suspend fun clearSearchHistory() = searches.clear()

    private companion object {
        const val SORT_STEP = 1024.0
    }
}
