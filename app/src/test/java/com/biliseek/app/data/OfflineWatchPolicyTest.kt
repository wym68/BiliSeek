package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineWatchPolicyTest {

    @Test
    fun `在线条目或带视频的本地文件照常切`() {
        assertEquals(OfflineWatch.NORMAL, resolveOfflineWatch(audioOnlyOffline = false, hasNetwork = false, automatic = false))
        assertEquals(OfflineWatch.NORMAL, resolveOfflineWatch(audioOnlyOffline = false, hasNetwork = true, automatic = true))
    }

    @Test
    fun `只有音频的本地文件主动点看且有网时联网播放`() {
        assertEquals(OfflineWatch.ONLINE, resolveOfflineWatch(audioOnlyOffline = true, hasNetwork = true, automatic = false))
    }

    @Test
    fun `没网时留在听并提示`() {
        assertEquals(OfflineWatch.NO_NETWORK, resolveOfflineWatch(audioOnlyOffline = true, hasNetwork = false, automatic = false))
    }

    @Test
    fun `自动进看模式时不为它联网`() {
        assertEquals(OfflineWatch.STAY_LISTENING, resolveOfflineWatch(audioOnlyOffline = true, hasNetwork = true, automatic = true))
        assertEquals(OfflineWatch.STAY_LISTENING, resolveOfflineWatch(audioOnlyOffline = true, hasNetwork = false, automatic = true))
    }
}
