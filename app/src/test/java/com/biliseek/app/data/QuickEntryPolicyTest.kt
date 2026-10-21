package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEntryPolicyTest {

    private val all = ALL_QUICK_ENTRIES

    @Test
    fun `从没设置过_出厂顺序_固定入口与分区开_子分区关`() {
        val r = resolveQuickEntries(null)
        assertEquals(all, r.map { it.entry })
        assertEquals(DEFAULT_QUICK_ENTRIES, r)
        assertTrue(r.filter { it.entry is FixedQuickEntry }.all { it.enabled })
        assertTrue(r.filter { it.entry is RegionQuickEntry }.none { it.enabled })
        assertEquals(8 + 22, r.size)
    }

    @Test
    fun `出厂顺序`() {
        assertEquals(
            listOf("weekly", "precious", "hot_songs", "original_songs", "podcast", "video_podcast", "ranking", "regions", "region:28"),
            all.take(9).map { it.id },
        )
        assertEquals("region:257", all.last().id)
    }

    @Test
    fun `第一期存的设置_升级后分区总入口追加并打开_子分区追加但关`() {
        val r = resolveQuickEntries("weekly,-precious,hot_songs,original_songs,podcast,video_podcast,ranking")
        assertEquals(FixedQuickEntry.REGIONS, r[7].entry)
        assertTrue(r[7].enabled)
        assertTrue(r.drop(8).all { it.entry is RegionQuickEntry && !it.enabled })
        assertEquals(false, r[1].enabled)
    }

    @Test
    fun `子分区入口往返_未知tid丢掉`() {
        val r = resolveQuickEntries("region:267,region:99999,-regions,weekly")
        assertEquals("region:267", r[0].entry.id)
        assertTrue(r[0].enabled)
        assertEquals(FixedQuickEntry.REGIONS, r[1].entry)
        assertEquals(false, r[1].enabled)
        assertEquals(all.size, r.size)
        assertEquals("电台", r[0].entry.title)
        assertEquals(r, resolveQuickEntries(encodeQuickEntries(r)))
    }

    @Test
    fun `顺序与开关往返`() {
        val list = listOf(
            QuickEntrySetting(FixedQuickEntry.RANKING, true),
            QuickEntrySetting(FixedQuickEntry.WEEKLY, false),
            QuickEntrySetting(FixedQuickEntry.PRECIOUS, true),
            QuickEntrySetting(FixedQuickEntry.HOT_SONGS, true),
            QuickEntrySetting(FixedQuickEntry.ORIGINAL_SONGS, false),
            QuickEntrySetting(FixedQuickEntry.PODCAST, true),
            QuickEntrySetting(FixedQuickEntry.VIDEO_PODCAST, true),
        ) + DEFAULT_QUICK_ENTRIES.drop(7)
        val text = encodeQuickEntries(list)
        assertTrue(text.startsWith("ranking,-weekly,precious,hot_songs,-original_songs,podcast,video_podcast,regions,-region:28"))
        assertEquals(list, resolveQuickEntries(text))
    }

    @Test
    fun `不认识的id丢掉_重复的只留第一个`() {
        val r = resolveQuickEntries("region:2024,precious,-precious,weekly")
        assertEquals(QuickEntrySetting(FixedQuickEntry.PRECIOUS, true), r[0])
        assertEquals(QuickEntrySetting(FixedQuickEntry.WEEKLY, true), r[1])
        assertEquals(all.size, r.size)
    }

    @Test
    fun `存的里没有的入口_追加到末尾_固定入口打开_子分区关闭`() {
        val r = resolveQuickEntries("-ranking,weekly")
        assertEquals(listOf(FixedQuickEntry.RANKING, FixedQuickEntry.WEEKLY), r.take(2).map { it.entry })
        assertEquals(false, r[0].enabled)
        assertEquals(
            listOf(FixedQuickEntry.PRECIOUS, FixedQuickEntry.HOT_SONGS, FixedQuickEntry.ORIGINAL_SONGS, FixedQuickEntry.PODCAST, FixedQuickEntry.VIDEO_PODCAST, FixedQuickEntry.REGIONS),
            r.drop(2).take(6).map { it.entry },
        )
        assertTrue(r.drop(2).take(6).all { it.enabled })
        assertTrue(r.drop(8).none { it.enabled })
    }

    @Test
    fun `全部关掉_首页一个都不显示`() {
        val off = DEFAULT_QUICK_ENTRIES.map { it.copy(enabled = false) }
        assertEquals(off, resolveQuickEntries(encodeQuickEntries(off)))
        assertEquals(emptyList<QuickEntry>(), visibleQuickEntries(off))
    }

    @Test
    fun `首页只显示打开的_保持顺序`() {
        val list = DEFAULT_QUICK_ENTRIES.withEnabled(FixedQuickEntry.PRECIOUS, false)
        assertEquals((all - FixedQuickEntry.PRECIOUS).filter { it.defaultEnabled }, visibleQuickEntries(list))
        assertEquals(all, list.map { it.entry })
    }
}
