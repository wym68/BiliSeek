package com.biliseek.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biliseek.core.player.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
)

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
    }
}
