package com.biliseek.core.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** 账号凭据。未登录时为 [EMPTY]。 */
data class BiliSession(
    val sessData: String,
    val biliJct: String,
    val dedeUserId: String,
    val refreshToken: String,
) {
    val isLoggedIn: Boolean get() = sessData.isNotBlank()

    companion object {
        val EMPTY = BiliSession("", "", "", "")
    }
}

private val Context.sessionDataStore by preferencesDataStore(name = "biliseek_session")

/**
 * 账号会话存储。
 *
 * 与风控凭据（[AuthGate]）分开管理：登出只清账号，buvid / ticket 要保留——
 * 那是设备身份，重新种植反而更像异常行为。
 */
class SessionStore(private val context: Context) {

    private val _session = MutableStateFlow(BiliSession.EMPTY)
    val session: StateFlow<BiliSession> = _session.asStateFlow()

    /** 供 CookieJar 同步读取。 */
    @Volatile
    var current: BiliSession = BiliSession.EMPTY
        private set

    suspend fun load() {
        val p = context.sessionDataStore.data.first()
        val restored = BiliSession(
            sessData = p[KEY_SESSDATA].orEmpty(),
            biliJct = p[KEY_JCT].orEmpty(),
            dedeUserId = p[KEY_UID].orEmpty(),
            refreshToken = p[KEY_REFRESH].orEmpty(),
        )
        current = restored
        _session.value = restored
    }

    suspend fun save(session: BiliSession) {
        current = session
        _session.value = session
        context.sessionDataStore.edit { p ->
            p[KEY_SESSDATA] = session.sessData
            p[KEY_JCT] = session.biliJct
            p[KEY_UID] = session.dedeUserId
            p[KEY_REFRESH] = session.refreshToken
        }
    }

    suspend fun clear() {
        current = BiliSession.EMPTY
        _session.value = BiliSession.EMPTY
        context.sessionDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_SESSDATA = stringPreferencesKey("sessdata")
        val KEY_JCT = stringPreferencesKey("bili_jct")
        val KEY_UID = stringPreferencesKey("dede_user_id")
        val KEY_REFRESH = stringPreferencesKey("refresh_token")
    }
}
