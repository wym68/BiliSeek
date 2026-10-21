package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickEntryPolicyTest {

    private val all = QuickEntry.entries.toList()

    @Test
    fun `从没设置过_出厂顺序全部打开`() {
        val r = resolveQuickEntries(null)
        assertEquals(all, r.map { it.entry })
        assertEquals(true, r.all { it.enabled })
        assertEquals(DEFAULT_QUICK_ENTRIES, r)
    }

    @Test
    fun `出厂顺序`() {
        assertEquals(
            listOf("weekly", "precious", "hot_songs", "original_songs", "podcast", "video_podcast", "ranking"),
            all.map { it.id },
        )
    }

    @Test
    fun `顺序与开关往返`() {
        val list = listOf(
            QuickEntrySetting(QuickEntry.RANKING, true),
            QuickEntrySetting(QuickEntry.WEEKLY, false),
            QuickEntrySetting(QuickEntry.PRECIOUS, true),
            QuickEntrySetting(QuickEntry.HOT_SONGS, true),
            QuickEntrySetting(QuickEntry.ORIGINAL_SONGS, false),
            QuickEntrySetting(QuickEntry.PODCAST, true),
            QuickEntrySetting(QuickEntry.VIDEO_PODCAST, true),
        )
        val text = encodeQuickEntries(list)
        assertEquals("ranking,-weekly,precious,hot_songs,-original_songs,podcast,video_podcast", text)
        assertEquals(list, resolveQuickEntries(text))
    }

    @Test
    fun `不认识的id丢掉_重复的只留第一个`() {
        val r = resolveQuickEntries("region:2024,precious,-precious,weekly")
        assertEquals(QuickEntrySetting(QuickEntry.PRECIOUS, true), r[0])
        assertEquals(QuickEntrySetting(QuickEntry.WEEKLY, true), r[1])
        assertEquals(all.size, r.size)
    }

    @Test
    fun `存的里没有的入口_追加到末尾并打开`() {
        val r = resolveQuickEntries("-ranking,weekly")
        assertEquals(listOf(QuickEntry.RANKING, QuickEntry.WEEKLY), r.take(2).map { it.entry })
        assertEquals(false, r[0].enabled)
        assertEquals(
            listOf(QuickEntry.PRECIOUS, QuickEntry.HOT_SONGS, QuickEntry.ORIGINAL_SONGS, QuickEntry.PODCAST, QuickEntry.VIDEO_PODCAST),
            r.drop(2).map { it.entry },
        )
        assertEquals(true, r.drop(2).all { it.enabled })
    }

    @Test
    fun `全部关掉_首页一个都不显示`() {
        val off = DEFAULT_QUICK_ENTRIES.map { it.copy(enabled = false) }
        assertEquals(off, resolveQuickEntries(encodeQuickEntries(off)))
        assertEquals(emptyList<QuickEntry>(), visibleQuickEntries(off))
    }

    @Test
    fun `首页只显示打开的_保持顺序`() {
        val list = DEFAULT_QUICK_ENTRIES.withEnabled(QuickEntry.PRECIOUS, false)
        assertEquals(all - QuickEntry.PRECIOUS, visibleQuickEntries(list))
        assertEquals(all, list.map { it.entry })
    }
}
