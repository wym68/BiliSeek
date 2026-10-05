package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 本地列表的预设图标：key 存库、不重复；空串（没选过）和不认识的 key（以后删掉的图标）都用默认图标。
 */
class PlaylistIconsTest {

    @Test
    fun `二十个预设图标，key 不重复，第一个是默认的播放列表图标`() {
        val keys = PLAYLIST_ICONS.map { it.key }
        assertEquals(20, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(DEFAULT_PLAYLIST_ICON, keys.first())
    }

    @Test
    fun `空串和不认识的 key 都当默认图标`() {
        assertEquals(DEFAULT_PLAYLIST_ICON, normalizePlaylistIcon(""))
        assertEquals(DEFAULT_PLAYLIST_ICON, normalizePlaylistIcon("removed_icon"))
        assertSame(playlistIcon(DEFAULT_PLAYLIST_ICON), playlistIcon(""))
        assertSame(playlistIcon(DEFAULT_PLAYLIST_ICON), playlistIcon("removed_icon"))
    }

    @Test
    fun `认识的 key 原样保留，用自己的图标`() {
        assertEquals("headphones", normalizePlaylistIcon("headphones"))
        assertNotSame(playlistIcon(DEFAULT_PLAYLIST_ICON), playlistIcon("headphones"))
    }
}
