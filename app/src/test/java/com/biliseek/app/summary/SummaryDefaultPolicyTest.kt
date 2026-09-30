package com.biliseek.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryDefaultPolicyTest {
    @Test
    fun defaultOfficialSelectsOfficialOnlyWhenAvailable() {
        assertTrue(SummaryDefaultPolicy.useOfficial(defaultOfficial = true, officialAvailable = true, manual = null))
        // 查询中 / 没有官方总结 / 未登录：退回风格
        assertFalse(SummaryDefaultPolicy.useOfficial(defaultOfficial = true, officialAvailable = false, manual = null))
    }

    @Test
    fun defaultStyleNeverSelectsOfficialEvenWhenAvailable() {
        assertFalse(SummaryDefaultPolicy.useOfficial(defaultOfficial = false, officialAvailable = true, manual = null))
        assertFalse(SummaryDefaultPolicy.useOfficial(defaultOfficial = false, officialAvailable = false, manual = null))
    }

    @Test
    fun manualChoiceWinsOverDefaultAndLateQueryResults() {
        // 用户在查询期间手动选了风格；官方总结随后到达，不能把用户的选择改掉
        assertFalse(SummaryDefaultPolicy.useOfficial(defaultOfficial = true, officialAvailable = true, manual = false))
        // 默认是风格，用户手动选了官方
        assertTrue(SummaryDefaultPolicy.useOfficial(defaultOfficial = false, officialAvailable = true, manual = true))
    }

    @Test
    fun followsDefaultOnceQueryFinishes() {
        val duringQuery = SummaryDefaultPolicy.useOfficial(true, officialAvailable = false, manual = null)
        val afterQuery = SummaryDefaultPolicy.useOfficial(true, officialAvailable = true, manual = null)
        assertEquals(listOf(false, true), listOf(duringQuery, afterQuery))
    }
}
