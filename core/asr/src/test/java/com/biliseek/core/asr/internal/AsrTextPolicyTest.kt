package com.biliseek.core.asr.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrTextPolicyTest {
    @Test
    fun stripsSenseVoiceTags() {
        assertEquals("今天天气不错。", AsrTextPolicy.clean("<|zh|><|NEUTRAL|><|Speech|><|withitn|>今天天气不错。"))
    }

    @Test
    fun emptyResult() {
        assertEquals("", AsrTextPolicy.clean("<|nospeech|><|EMO_UNKNOWN|><|Event_UNK|>"))
        assertEquals("", AsrTextPolicy.clean("   "))
    }

    @Test
    fun mixedChineseEnglishKeepsSingleSpaces() {
        assertEquals("我们用 Kotlin 写 Android。", AsrTextPolicy.clean("我们用  Kotlin\n写 Android。"))
    }

    @Test
    fun collapsesRepeatedAndLeadingPunctuation() {
        assertEquals("好的，走吧。", AsrTextPolicy.clean("，好的，，走吧。。"))
    }

    @Test
    fun language() {
        assertEquals("zh", AsrTextPolicy.language("<|zh|>"))
        assertEquals("en", AsrTextPolicy.language("en"))
        assertNull(AsrTextPolicy.language("<|nospeech|>"))
        assertNull(AsrTextPolicy.language(""))
        assertNull(AsrTextPolicy.language(null))
    }
}
