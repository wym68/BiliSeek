package com.biliseek.core.player.internal

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 命令表的补齐依赖 android.util.SparseBooleanArray，JVM 单测里构造不了，这里只测转发。 */
class QueueSkipPlayerTest {

    private val calls = mutableListOf<String>()
    private val added = mutableListOf<Player.Listener>()
    private val removed = mutableListOf<Player.Listener>()

    /** 只记录被调了什么的假播放器。 */
    private val fake = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, args ->
        when (method.name) {
            "addListener" -> added += args!![0] as Player.Listener
            "removeListener" -> removed += args!![0] as Player.Listener
            else -> calls += method.name
        }
        null
    } as Player

    private val player = QueueSkipPlayer(fake)

    @After
    fun tearDown() {
        QueueSkipBridge.handler = null
    }

    @Test
    fun `skip buttons go to the queue`() {
        val asked = mutableListOf<Boolean>()
        QueueSkipBridge.handler = { asked += it; true }

        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()

        assertEquals(listOf(true, true, false, false), asked)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `queue end falls back to the player`() {
        QueueSkipBridge.handler = { false }

        player.seekToNext()
        player.seekToPrevious()

        assertEquals(listOf("seekToNext", "seekToPrevious"), calls)
    }

    @Test
    fun `no handler falls back to the player`() {
        player.seekToPreviousMediaItem()

        assertEquals(listOf("seekToPreviousMediaItem"), calls)
    }

    @Test
    fun `listener receives every other event`() {
        val events = mutableListOf<Any>()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                events += isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                events += playbackState
            }
        }

        player.addListener(listener)
        added.single().onIsPlayingChanged(true)
        added.single().onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(listOf<Any>(true, Player.STATE_READY), events)
    }

    @Test
    fun `remove hands back the listener that was added`() {
        val listener = object : Player.Listener {}

        player.addListener(listener)
        player.removeListener(listener)

        assertSame(added.single(), removed.single())
    }
}
