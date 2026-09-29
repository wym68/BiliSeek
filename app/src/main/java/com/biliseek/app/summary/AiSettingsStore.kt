package com.biliseek.app.summary

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biliseek.app.data.SecretCipher
import com.biliseek.core.ai.provider.AiEndpointConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** 文件名与备份排除规则（res/xml/backup_rules.xml）对应，改名要一起改。 */
private val Context.aiSettingsDataStore by preferencesDataStore(name = "biliseek_ai_settings")

/**
 * AI 总结设置（工单 AI-3）。独立的 DataStore，不塞进 AppSettings；不进备份。
 *
 * API Key 只以 [SecretCipher] 密文落盘；[state] 里只有「已配置 / 末 4 位」，
 * 明文只在构造请求时经 [resolve] 解出。
 */
class AiSettingsStore(
    private val context: Context,
    private val cipher: SecretCipher,
) {
    private val _state = MutableStateFlow(AiSettings())
    val state: StateFlow<AiSettings> = _state.asStateFlow()

    suspend fun load() {
        _state.value = read(context.aiSettingsDataStore.data.first())
    }

    /** 保存端点。草稿 Key 为空 = 沿用已保存的 Key。 */
    suspend fun saveEndpoint(slot: EndpointSlot, draft: EndpointDraft) = edit { p ->
        val k = keys(slot)
        p[k.preset] = draft.presetId
        p[k.baseUrl] = draft.baseUrl.trim()
        p[k.model] = draft.model.trim()
        val newKey = draft.apiKey.trim()
        if (newKey.isNotEmpty()) {
            p[k.secret] = cipher.encrypt(newKey)
            p[k.hint] = AiSettingsPolicy.keyHint(newKey)
        }
    }

    suspend fun clearEndpoint(slot: EndpointSlot) = edit { p ->
        val k = keys(slot)
        listOf(k.preset, k.baseUrl, k.model, k.secret, k.hint).forEach { p.remove(it) }
    }

    /** 解出可用于请求的配置；未配置或 Key 解不开（换机恢复等）返回 null。 */
    suspend fun resolve(slot: EndpointSlot): AiEndpointConfig? {
        val p = context.aiSettingsDataStore.data.first()
        val k = keys(slot)
        val preset = p[k.preset] ?: return null
        val key = p[k.secret]?.let(cipher::decrypt) ?: return null
        return AiEndpointConfig(preset, p[k.baseUrl].orEmpty(), p[k.model].orEmpty(), key)
    }

    /** 已保存的 Key 明文（设置页测试连接时，草稿 Key 留空就用它）。 */
    suspend fun savedKey(slot: EndpointSlot): String? =
        context.aiSettingsDataStore.data.first()[keys(slot).secret]?.let(cipher::decrypt)

    suspend fun setLastStyle(value: StyleChoice) = edit { it[KEY_LAST_STYLE] = value.name }

    suspend fun setCustomPrompt(value: String) = edit { it[KEY_CUSTOM_PROMPT] = value }

    suspend fun setPrivacyAccepted() = edit { it[KEY_PRIVACY] = true }

    suspend fun recordAsrRtf(measured: Float) = edit {
        it[KEY_ASR_RTF] = AiSettingsPolicy.blendRtf(it[KEY_ASR_RTF], measured)
    }

    // ── 预检记录（工单 §3.7.3）──────────────────────────────

    suspend fun isVerified(config: AiEndpointConfig): Boolean =
        AiSettingsPolicy.fingerprint(config) in context.aiSettingsDataStore.data.first()[KEY_VERIFIED].orEmpty()

    suspend fun markVerified(config: AiEndpointConfig) = edit {
        // 只保留若干条（集合无序，超出时随意淘汰几条旧的即可），避免反复改配置后无限增长
        val next = (it[KEY_VERIFIED].orEmpty() + AiSettingsPolicy.fingerprint(config)).toList().takeLast(MAX_VERIFIED)
        it[KEY_VERIFIED] = next.toSet()
    }

    suspend fun clearVerified(config: AiEndpointConfig) = edit {
        it[KEY_VERIFIED] = it[KEY_VERIFIED].orEmpty() - AiSettingsPolicy.fingerprint(config)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        val updated = context.aiSettingsDataStore.edit(block)
        _state.value = read(updated)
    }

    private fun read(p: Preferences) = AiSettings(
        primary = view(p, EndpointSlot.PRIMARY),
        backup = view(p, EndpointSlot.BACKUP),
        lastStyle = p[KEY_LAST_STYLE]?.let { name -> StyleChoice.entries.firstOrNull { it.name == name } }
            ?: StyleChoice.OUTLINE,
        customPrompt = p[KEY_CUSTOM_PROMPT].orEmpty(),
        privacyAccepted = p[KEY_PRIVACY] ?: false,
        asrRtf = p[KEY_ASR_RTF],
    )

    private fun view(p: Preferences, slot: EndpointSlot): EndpointView? {
        val k = keys(slot)
        val preset = p[k.preset] ?: return null
        return EndpointView(
            presetId = preset,
            baseUrl = p[k.baseUrl].orEmpty(),
            model = p[k.model].orEmpty(),
            keyConfigured = p[k.secret] != null,
            keyHint = p[k.hint].orEmpty(),
        )
    }

    private class SlotKeys(prefix: String) {
        val preset = stringPreferencesKey("${prefix}_preset")
        val baseUrl = stringPreferencesKey("${prefix}_base_url")
        val model = stringPreferencesKey("${prefix}_model")
        val secret = stringPreferencesKey("${prefix}_key_enc")
        val hint = stringPreferencesKey("${prefix}_key_hint")
    }

    companion object {
        @Volatile
        private var instance: AiSettingsStore? = null

        /** 进程内单例：设置页与后台总结任务读写同一份状态。 */
        fun get(context: Context): AiSettingsStore =
            instance ?: synchronized(this) {
                instance ?: AiSettingsStore(context.applicationContext, com.biliseek.app.data.KeystoreCipher())
                    .also { instance = it }
            }

        private val PRIMARY_KEYS = SlotKeys("primary")
        private val BACKUP_KEYS = SlotKeys("backup")
        private fun keys(slot: EndpointSlot) = if (slot == EndpointSlot.PRIMARY) PRIMARY_KEYS else BACKUP_KEYS

        private val KEY_LAST_STYLE = stringPreferencesKey("last_style")
        private val KEY_CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
        private val KEY_PRIVACY = booleanPreferencesKey("privacy_accepted")
        private val KEY_ASR_RTF = floatPreferencesKey("asr_rtf")
        private val KEY_VERIFIED = stringSetPreferencesKey("verified_configs")
        private const val MAX_VERIFIED = 8
    }
}
