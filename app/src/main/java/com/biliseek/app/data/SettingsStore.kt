package com.biliseek.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.PersistedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 起播时用哪种呈现态。 */
enum class DefaultPresentation {
    /** 一律先只听。**出厂默认**——「默认只听」是产品身份，不能靠用户去关。 */
    AUDIO_ONLY,

    /** 记住上次：上次在看模式，下次起播就直接进看模式。 */
    REMEMBER_LAST,
}

/** 用户设置。全部有出厂默认值，未设置过时按默认走。 */
data class AppSettings(
    val defaultPresentation: DefaultPresentation = DefaultPresentation.AUDIO_ONLY,
    /** 上次是不是在看模式。[DefaultPresentation.REMEMBER_LAST] 用。 */
    val lastPresentationWasVideo: Boolean = false,
    /** 计量网络（移动数据）下允许拉视频。默认关——视频比音频贵一个量级。 */
    val allowVideoOnMetered: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val loudnessNormalization: Boolean = true,
    /** 上次下载选的保存位置，下次默认带入（M9）。 */
    val lastDownloadLocation: String = DownloadLocation.PRIVATE,
    /** 上次选的 SAF 目录 uri。持久授权后重进 App 不必重选。 */
    val lastDownloadSafTreeUri: String = "",
    /** Ch5：minibar 风格（FLOAT 浮在底部上、BOTTOM 贴底）。默认 FLOAT。 */
    val miniBarStyle: MiniBarStyle = MiniBarStyle.FLOAT,
    /** Wi-Fi 下的默认画质（B 站 qn），0 = 自动。 */
    val videoQualityWifi: Int = VideoQualityDefaults.WIFI,
    /** 移动网络下的默认画质。默认 480P——本 App 以听为主，看也要替流量着想。 */
    val videoQualityMetered: Int = VideoQualityDefaults.METERED,
    /** 弹幕开关与显示设置（只在全屏显示）。 */
    val danmaku: com.biliseek.app.danmaku.DanmakuSettings = com.biliseek.app.danmaku.DanmakuSettings(),
)

/** 默认画质的出厂值与设置页可选档位。 */
object VideoQualityDefaults {
    const val WIFI = 0
    const val METERED = 32

    /** 设置页可选的档位 (qn, 名字)。0 = 自动。 */
    val CHOICES: List<Pair<Int, String>> = listOf(
        0 to "自动",
        80 to "1080P",
        64 to "720P",
        32 to "480P",
        16 to "360P",
    )
}

/** 按当前网络挑默认画质。 */
fun AppSettings.defaultVideoQuality(isMetered: Boolean): Int =
    if (isMetered) videoQualityMetered else videoQualityWifi

/** minibar 风格（Ch5 §5.1.4）。 */
enum class MiniBarStyle { FLOAT, BOTTOM }

private val Context.settingsDataStore by preferencesDataStore(name = "biliseek_settings")

/**
 * 设置存储。
 *
 * 写法照 `SessionStore`：一个 [StateFlow] 给 UI 订阅，一个 [current] 给需要同步读取的
 * 调用方——播放内核的 `preferenceProvider` 是同步回调，拿不到挂起函数。
 */
class SettingsStore(private val context: Context) {

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** 供播放内核同步读取。 */
    @Volatile
    var current: AppSettings = AppSettings()
        private set

    suspend fun load() {
        val p = context.settingsDataStore.data.first()
        val restored = AppSettings(
            defaultPresentation = p[KEY_DEFAULT_PRESENTATION]
                ?.let { name -> DefaultPresentation.entries.firstOrNull { it.name == name } }
                ?: DefaultPresentation.AUDIO_ONLY,
            lastPresentationWasVideo = p[KEY_LAST_WAS_VIDEO] ?: false,
            allowVideoOnMetered = p[KEY_ALLOW_METERED] ?: false,
            audioQuality = p[KEY_AUDIO_QUALITY]
                ?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
                ?: AudioQuality.AUTO,
            loudnessNormalization = p[KEY_LOUDNESS] ?: true,
            lastDownloadLocation = p[KEY_DOWNLOAD_LOCATION]
                ?: DownloadLocation.PRIVATE,
            lastDownloadSafTreeUri = p[KEY_DOWNLOAD_SAF_TREE] ?: "",
            miniBarStyle = p[KEY_MINI_BAR_STYLE]
                ?.let { name -> MiniBarStyle.entries.firstOrNull { it.name == name } }
                ?: MiniBarStyle.FLOAT,
            videoQualityWifi = p[KEY_VIDEO_QUALITY_WIFI] ?: VideoQualityDefaults.WIFI,
            videoQualityMetered = p[KEY_VIDEO_QUALITY_METERED] ?: VideoQualityDefaults.METERED,
            danmaku = com.biliseek.app.danmaku.DanmakuSettings().let { d ->
                d.copy(
                    enabled = p[KEY_DM_ENABLED] ?: d.enabled,
                    opacity = p[KEY_DM_OPACITY] ?: d.opacity,
                    fontScale = p[KEY_DM_FONT_SCALE] ?: d.fontScale,
                    area = p[KEY_DM_AREA] ?: d.area,
                    speed = p[KEY_DM_SPEED] ?: d.speed,
                    minWeight = p[KEY_DM_MIN_WEIGHT] ?: d.minWeight,
                    showScroll = p[KEY_DM_SHOW_SCROLL] ?: d.showScroll,
                    showTop = p[KEY_DM_SHOW_TOP] ?: d.showTop,
                    showBottom = p[KEY_DM_SHOW_BOTTOM] ?: d.showBottom,
                    showColored = p[KEY_DM_SHOW_COLORED] ?: d.showColored,
                )
            },
        )
        publish(restored)
    }

    suspend fun setDefaultPresentation(value: DefaultPresentation) =
        update { it.copy(defaultPresentation = value) }

    suspend fun setLastPresentationWasVideo(value: Boolean) =
        update { it.copy(lastPresentationWasVideo = value) }

    suspend fun setAllowVideoOnMetered(value: Boolean) =
        update { it.copy(allowVideoOnMetered = value) }

    suspend fun setAudioQuality(value: AudioQuality) = update { it.copy(audioQuality = value) }

    suspend fun setLoudnessNormalization(value: Boolean) =
        update { it.copy(loudnessNormalization = value) }

    /** 记住下载位置，下次默认带入。 */
    suspend fun setDownloadLocation(value: String) =
        update { it.copy(lastDownloadLocation = value) }

    suspend fun setDownloadSafTreeUri(value: String) =
        update { it.copy(lastDownloadSafTreeUri = value) }

    /** Ch5：切换 minibar 风格。 */
    suspend fun setMiniBarStyle(value: MiniBarStyle) =
        update { it.copy(miniBarStyle = value) }

    suspend fun setVideoQualityWifi(value: Int) = update { it.copy(videoQualityWifi = value) }

    suspend fun setVideoQualityMetered(value: Int) = update { it.copy(videoQualityMetered = value) }

    suspend fun updateDanmaku(transform: (com.biliseek.app.danmaku.DanmakuSettings) -> com.biliseek.app.danmaku.DanmakuSettings) =
        update { it.copy(danmaku = transform(it.danmaku)) }

    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current)
        publish(next)
        context.settingsDataStore.edit { p ->
            p[KEY_DEFAULT_PRESENTATION] = next.defaultPresentation.name
            p[KEY_LAST_WAS_VIDEO] = next.lastPresentationWasVideo
            p[KEY_ALLOW_METERED] = next.allowVideoOnMetered
            p[KEY_AUDIO_QUALITY] = next.audioQuality.name
            p[KEY_LOUDNESS] = next.loudnessNormalization
            p[KEY_DOWNLOAD_LOCATION] = next.lastDownloadLocation
            p[KEY_DOWNLOAD_SAF_TREE] = next.lastDownloadSafTreeUri
            p[KEY_MINI_BAR_STYLE] = next.miniBarStyle.name
            p[KEY_VIDEO_QUALITY_WIFI] = next.videoQualityWifi
            p[KEY_VIDEO_QUALITY_METERED] = next.videoQualityMetered
            next.danmaku.let { d ->
                p[KEY_DM_ENABLED] = d.enabled
                p[KEY_DM_OPACITY] = d.opacity
                p[KEY_DM_FONT_SCALE] = d.fontScale
                p[KEY_DM_AREA] = d.area
                p[KEY_DM_SPEED] = d.speed
                p[KEY_DM_MIN_WEIGHT] = d.minWeight
                p[KEY_DM_SHOW_SCROLL] = d.showScroll
                p[KEY_DM_SHOW_TOP] = d.showTop
                p[KEY_DM_SHOW_BOTTOM] = d.showBottom
                p[KEY_DM_SHOW_COLORED] = d.showColored
            }
        }
    }

    private fun publish(value: AppSettings) {
        current = value
        _state.value = value
    }

    private companion object {
        val KEY_DEFAULT_PRESENTATION = stringPreferencesKey("default_presentation")
        val KEY_LAST_WAS_VIDEO = booleanPreferencesKey("last_presentation_was_video")
        val KEY_ALLOW_METERED = booleanPreferencesKey("allow_video_on_metered")
        val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val KEY_LOUDNESS = booleanPreferencesKey("loudness_normalization")
        val KEY_DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val KEY_DOWNLOAD_SAF_TREE = stringPreferencesKey("download_saf_tree")
        val KEY_MINI_BAR_STYLE = stringPreferencesKey("mini_bar_style")
        val KEY_VIDEO_QUALITY_WIFI = intPreferencesKey("video_quality_wifi")
        val KEY_VIDEO_QUALITY_METERED = intPreferencesKey("video_quality_metered")
        val KEY_DM_ENABLED = booleanPreferencesKey("danmaku_enabled")
        val KEY_DM_OPACITY = floatPreferencesKey("danmaku_opacity")
        val KEY_DM_FONT_SCALE = floatPreferencesKey("danmaku_font_scale")
        val KEY_DM_AREA = floatPreferencesKey("danmaku_area")
        val KEY_DM_SPEED = floatPreferencesKey("danmaku_speed")
        val KEY_DM_MIN_WEIGHT = intPreferencesKey("danmaku_min_weight")
        val KEY_DM_SHOW_SCROLL = booleanPreferencesKey("danmaku_show_scroll")
        val KEY_DM_SHOW_TOP = booleanPreferencesKey("danmaku_show_top")
        val KEY_DM_SHOW_BOTTOM = booleanPreferencesKey("danmaku_show_bottom")
        val KEY_DM_SHOW_COLORED = booleanPreferencesKey("danmaku_show_colored")
        const val KEY_LAST_SESSION = "last_playback_session"
        const val KEY_QUEUE_SWIPE_HINT = "queue_swipe_hint_shown"

        val json = Json { ignoreUnknownKeys = true }
    }

    // ── 播放会话持久化（SharedPreferences，保证进程被杀前数据已落盘） ──

    private val sessionPrefs by lazy {
        context.getSharedPreferences("biliseek_session", Context.MODE_PRIVATE)
    }

    /** 保存播放会话。使用 commit() 同步写入磁盘，确保进程被杀前数据已落盘。 */
    fun saveLastSessionSync(session: PersistedSession) {
        val jsonStr = json.encodeToString(session)
        sessionPrefs.edit().putString(KEY_LAST_SESSION, jsonStr).commit()
    }

    /** 保存播放会话（挂起封装）。 */
    suspend fun saveLastSession(session: PersistedSession) {
        saveLastSessionSync(session)
    }

    /** 读取上次保存的播放会话，无则返回 null。 */
    fun getLastSessionSync(): PersistedSession? {
        val jsonStr = sessionPrefs.getString(KEY_LAST_SESSION, null) ?: return null
        return try {
            json.decodeFromString<PersistedSession>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    /** 读取上次保存的播放会话（挂起封装）。 */
    suspend fun getLastSession(): PersistedSession? = getLastSessionSync()

    /** 清除已保存的播放会话。 */
    fun clearLastSessionSync() {
        sessionPrefs.edit().remove(KEY_LAST_SESSION).commit()
    }

    /** 清除已保存的播放会话（挂起封装）。 */
    suspend fun clearLastSession() {
        clearLastSessionSync()
    }

    // ── 一次性操作引导（SharedPreferences：同步读，界面首帧就要知道放不放演示） ──

    private val hintPrefs by lazy {
        context.getSharedPreferences("biliseek_hints", Context.MODE_PRIVATE)
    }

    private val _queueSwipeHintShown by lazy {
        MutableStateFlow(hintPrefs.getBoolean(KEY_QUEUE_SWIPE_HINT, false))
    }

    /** 队列面板的右滑删除演示是否已经放过（docs/10：只在第一次打开时演示一次）。 */
    val queueSwipeHintShown: StateFlow<Boolean> by lazy { _queueSwipeHintShown.asStateFlow() }

    fun setQueueSwipeHintShown() {
        if (_queueSwipeHintShown.value) return
        _queueSwipeHintShown.value = true
        hintPrefs.edit().putBoolean(KEY_QUEUE_SWIPE_HINT, true).apply()
    }
}
