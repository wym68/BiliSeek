package com.biliseek.app.summary

import com.biliseek.core.ai.Chapter
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 风格弹窗的状态（工单 AI-6）。 */
data class StyleSheetState(
    /** 单个视频时为 1 项；多 P 批量时为多项（批量不做逐项探查，来源在执行时自动决定）。 */
    val videos: List<SummaryVideo>,
    /** B 站官方总结（仅单个视频查询）。 */
    val official: OfficialSummary = OfficialSummary.Loading,
    /** 原文来源（仅单个视频；null = 查询中）。 */
    val source: SummarySource? = null,
    val chapters: List<Chapter> = emptyList(),
    val selected: StyleChoice = StyleChoice.OUTLINE,
    val customPrompt: String = "",
    val llmReady: Boolean = false,
    val loggedIn: Boolean = false,
    val isMetered: Boolean = false,
    /** 这条视频已有版本（「生成新版本」进来的）：弹窗里提示旧版本不会被覆盖。 */
    val hasExisting: Boolean = false,
    /** 本地识别的实测 RTF（滑动平均）；null = 还没测过，按默认值估。 */
    val asrRtf: Float? = null,
) {
    val isBatch: Boolean get() = videos.size > 1
}

/** 用户在弹窗里的选择。 */
sealed interface SheetChoice {
    data object UseOfficial : SheetChoice

    /** [forceLocalAsr]：有字幕但需要登录时用户点了「改用本地识别」。 */
    data class Generate(val style: StyleChoice, val customPrompt: String, val forceLocalAsr: Boolean = false) : SheetChoice
}

/** 阅读页打开的是哪一条；[versionId] 为 null 显示最新版本。 */
data class ReaderTarget(val bvid: String, val cid: Long, val video: SummaryVideo?, val versionId: Long? = null)

/**
 * AI 总结的界面编排（工单 AI-6）：入口 → 风格弹窗（查一次官方总结与字幕）→ 入队 / 保存 → 阅读页。
 *
 * **请求纪律**：官方总结与字幕列表都只在打开弹窗这一刻各查一次，结果按 (bvid, cid) 缓存到进程结束；
 * 未登录不查官方总结；绝不在打开播放页时预取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryController(
    private val scope: CoroutineScope,
    val manager: SummaryManager,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val emitMessage: (String) -> Unit,
) {
    private val _sheet = MutableStateFlow<StyleSheetState?>(null)
    val sheet: StateFlow<StyleSheetState?> = _sheet.asStateFlow()

    private val _reader = MutableStateFlow<ReaderTarget?>(null)
    val reader: StateFlow<ReaderTarget?> = _reader.asStateFlow()

    /** 阅读页当前条目的状态（库 + 实时进度）。 */
    val readerState: Flow<SummaryUiState?> = _reader.flatMapLatest { target ->
        if (target == null) flowOf(null) else manager.observe(target.bvid, target.cid, target.versionId)
    }

    private data class Probe(val official: OfficialSummary, val subtitles: SubtitleProbe?)
    private val probeCache = object : LinkedHashMap<String, Probe>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Probe>) = size > 32
    }

    /** 入口：已确定 cid 的一个或多个视频。 */
    fun open(videos: List<SummaryVideo>) {
        if (videos.isEmpty()) return
        scope.launch {
            val settings = manager.settings.state.value
            val base = StyleSheetState(
                videos = videos,
                selected = settings.lastStyle,
                customPrompt = settings.customPrompt,
                llmReady = settings.primaryReady,
                loggedIn = session.value.isLoggedIn,
                isMetered = manager.network.isMetered(),
                asrRtf = settings.asrRtf,
            )
            if (videos.size > 1) {
                _sheet.value = base.copy(official = OfficialSummary.Unavailable(OfficialSummary.Reason.NO_SUMMARY))
                return@launch
            }
            val video = videos.single()
            val existing = manager.find(video.bvid, video.cid)
            if (SummaryEntryPolicy.decide(existing != null) == EntryDecision.OPEN_READER) {
                _reader.value = ReaderTarget(video.bvid, video.cid, video)
                return@launch
            }
            showSheetWithProbe(base, video)
        }
    }

    /** 阅读页「重新生成」：跳过「已有记录就开阅读页」的判断，直接弹风格弹窗。 */
    fun regenerate(video: SummaryVideo) {
        scope.launch {
            val settings = manager.settings.state.value
            showSheetWithProbe(
                StyleSheetState(
                    videos = listOf(video),
                    selected = settings.lastStyle,
                    customPrompt = settings.customPrompt,
                    llmReady = settings.primaryReady,
                    loggedIn = session.value.isLoggedIn,
                    isMetered = manager.network.isMetered(),
                    hasExisting = true,
                    asrRtf = settings.asrRtf,
                ),
                video,
            )
        }
    }

    private suspend fun showSheetWithProbe(base: StyleSheetState, video: SummaryVideo) {
        val key = "${video.bvid}_${video.cid}"
        val cached = synchronized(probeCache) { probeCache[key] }
        val asrReady = manager.localAsrRunner != null && manager.asrModelReady()
        val hasLocalAudio = manager.hasLocalAudio(video.bvid, video.cid)
        if (cached != null) {
            _sheet.value = base.withProbe(cached, asrReady, hasLocalAudio)
            return
        }
        _sheet.value = base
        val loggedIn = session.value.isLoggedIn
        val officialJob = scope.async {
            if (!OfficialSummaryPolicy.shouldQuery(loggedIn)) {
                OfficialSummary.Unavailable(OfficialSummary.Reason.NOT_LOGGED_IN)
            } else {
                OfficialSummaryPolicy.diagnose(repository.viewConclusion(video.bvid, video.cid, video.upMid.takeIf { it > 0 }))
            }
        }
        val subtitleJob = scope.async {
            probeSubtitles(repository, video.bvid, video.cid, video.durationMs.takeIf { it > 0 })
        }
        val probe = Probe(officialJob.await(), subtitleJob.await())
        // 查询失败的不缓存，下次打开可再查（仍然是一次用户操作一次请求）
        if (probe.official !is OfficialSummary.Unavailable ||
            (probe.official as OfficialSummary.Unavailable).reason != OfficialSummary.Reason.FAILED
        ) {
            if (probe.subtitles?.failed != true) synchronized(probeCache) { probeCache[key] = probe }
        }
        _sheet.update { current ->
            if (current?.videos?.singleOrNull()?.let { it.bvid == video.bvid && it.cid == video.cid } == true) {
                current.withProbe(probe, asrReady, hasLocalAudio)
            } else {
                current
            }
        }
    }

    private fun StyleSheetState.withProbe(probe: Probe, asrReady: Boolean, hasLocalAudio: Boolean): StyleSheetState {
        val subtitles = probe.subtitles
        return copy(
            official = probe.official,
            source = SummarySourcePolicy.resolve(subtitles?.pick ?: SubtitlePick.None, asrReady, hasLocalAudio),
            chapters = subtitles?.chapters.orEmpty(),
        )
    }

    fun dismissSheet() {
        _sheet.value = null
    }

    fun confirm(choice: SheetChoice) {
        val state = _sheet.value ?: return
        _sheet.value = null
        // 上次用的风格由 manager.enqueue 记住；自定义提示词在这里顺手存下
        if (choice is SheetChoice.Generate && choice.style == StyleChoice.CUSTOM) {
            manager.settingsScope { setCustomPrompt(choice.customPrompt) }
        }
        if (state.isBatch) {
            val generate = choice as? SheetChoice.Generate ?: return
            state.videos.forEach { manager.enqueue(it, generate.style, generate.customPrompt, JobSource.Auto) }
            emitMessage("已加入 ${state.videos.size} 项 AI 总结")
            return
        }
        val video = state.videos.single()
        when (choice) {
            SheetChoice.UseOfficial -> {
                val official = state.official as? OfficialSummary.Available ?: return
                manager.saveOfficial(video, official.result)
            }
            is SheetChoice.Generate -> {
                val source = when (val s = state.source) {
                    is SummarySource.UseSubtitle ->
                        if (choice.forceLocalAsr) JobSource.LocalAsr(state.chapters) else JobSource.Subtitle(s.track, state.chapters)
                    is SummarySource.UseLocalAsr -> JobSource.LocalAsr(state.chapters)
                    is SummarySource.NeedsLoginOrAsr -> JobSource.LocalAsr(state.chapters)
                    SummarySource.NeedsAsrModel, null -> JobSource.Auto
                }
                manager.enqueue(video, choice.style, choice.customPrompt, source)
            }
        }
        _reader.value = ReaderTarget(video.bvid, video.cid, video)
    }

    fun openReader(bvid: String, cid: Long) {
        _reader.value = ReaderTarget(bvid, cid, null)
    }

    /** 阅读页切换到某个版本（null = 回到最新）。 */
    fun selectVersion(versionId: Long?) {
        _reader.update { it?.copy(versionId = versionId) }
    }

    /**
     * 删除阅读页当前的版本。[versionCount] 是删除前的版本数：只剩这一个就一并退出阅读页；
     * 否则留在页内，自动显示剩下的最新版本。
     */
    fun deleteVersion(versionId: Long, versionCount: Int) {
        manager.delete(versionId)
        if (versionCount <= 1) {
            closeReader()
        } else {
            selectVersion(null)
        }
    }

    /** 清除这条视频的全部版本（含原文），并退出阅读页。 */
    fun deleteAllVersions(bvid: String, cid: Long) {
        manager.deleteAll(bvid, cid)
        closeReader()
    }

    fun closeReader() {
        _reader.value = null
    }

    /** 阅读页读取 md 正文（IO）。 */
    suspend fun readMarkdown(bvid: String, cid: Long, versionId: Long): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.files(bvid, cid).readSummary(versionId) }

    /** 阅读页「查看原文」：原文按行拼成纯文本（IO）。 */
    suspend fun readTranscriptText(bvid: String, cid: Long): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            manager.files(bvid, cid).readTranscript()?.let { t ->
                val useHours = (t.segments.maxOfOrNull { it.endMs } ?: 0) >= 3_600_000
                t.segments.joinToString("\n") {
                    "[${com.biliseek.core.ai.SummaryMarkdown.timestamp(it.startMs, useHours)}] ${it.text}"
                }
            }
        }

    private fun SummaryManager.settingsScope(block: suspend AiSettingsStore.() -> Unit) {
        scope.launch { settings.block() }
    }
}
