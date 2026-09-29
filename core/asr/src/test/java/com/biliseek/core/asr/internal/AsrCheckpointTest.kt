package com.biliseek.core.asr.internal

import com.biliseek.core.asr.AsrSegment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AsrCheckpointTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "asr_partial.jsonl")

    @Test
    fun missingFileStartsFromZero() {
        val segments = AsrCheckpoint(file()).load()
        assertEquals(emptyList<AsrSegment>(), segments)
        assertEquals(0L, AsrCheckpoint.resumeMs(segments))
    }

    @Test
    fun resumeFromLastSegmentEnd() {
        val cp = AsrCheckpoint(file())
        cp.append(AsrSegment(0, 1_200, "第一句", "zh"))
        cp.append(AsrSegment(2_000, 4_500, "第二句\t带\"引号\"\n换行", null))
        val loaded = AsrCheckpoint(file()).load()
        assertEquals(2, loaded.size)
        assertEquals("第二句\t带\"引号\"\n换行", loaded[1].text)
        assertEquals(4_500L, AsrCheckpoint.resumeMs(loaded))
    }

    @Test
    fun truncatedLastLineIsDroppedAndFileRepaired() {
        val cp = AsrCheckpoint(file())
        cp.append(AsrSegment(0, 1_000, "完整", "zh"))
        file().appendText("""{"s":2000,"e":30""") // 进程被杀：写了一半
        val loaded = AsrCheckpoint(file()).load()
        assertEquals(listOf(AsrSegment(0, 1_000, "完整", "zh")), loaded)
        assertEquals(1_000L, AsrCheckpoint.resumeMs(loaded))
        // 修复后继续追加，新行不会接在残行后面
        AsrCheckpoint(file()).append(AsrSegment(1_500, 2_500, "新的", "zh"))
        assertEquals(2, AsrCheckpoint(file()).load().size)
    }

    @Test
    fun emptyTextSegmentStillAdvancesResumePoint() {
        val cp = AsrCheckpoint(file())
        cp.append(AsrSegment(0, 1_000, "有字", "zh"))
        cp.append(AsrSegment(3_000, 3_600, "", null))
        assertEquals(3_600L, AsrCheckpoint.resumeMs(cp.load()))
    }
}
