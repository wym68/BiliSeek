package com.biliseek.core.player.internal

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * 队列在 PlayerConnection 里，不在播放器里：同进程下由它登记「切到上一首 / 下一首」的处理，
 * 返回是否真的切了。参数 `true` 为下一首。
 */
internal object QueueSkipBridge {
    var handler: ((toNext: Boolean) -> Boolean)? = null
}

/**
 * 交给 MediaSession 的播放器：让通知栏、锁屏、耳机按键的上一首 / 下一首走 BiliSeek 自己的队列。
 *
 * ExoPlayer 里永远只有当前这一首（切歌是 `setMediaItem()` 整体替换），原样交给 session 的话
 * 系统认为没有下一首，按钮灰掉；「上一首」也只是回到本首开头。这里始终声明可切歌，
 * 按下后转给 [QueueSkipBridge]；队列到头或没登记时退回播放器原本的行为。
 */
internal class QueueSkipPlayer(player: Player) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands =
        withSkipCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        command in SKIP_COMMANDS || super.isCommandAvailable(command)

    override fun seekToNext() {
        if (QueueSkipBridge.handler?.invoke(true) != true) super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (QueueSkipBridge.handler?.invoke(true) != true) super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (QueueSkipBridge.handler?.invoke(false) != true) super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (QueueSkipBridge.handler?.invoke(false) != true) super.seekToPreviousMediaItem()
    }

    // session 收到「可用命令变了」时直接用事件里的那份，不会回头问 getAvailableCommands()，
    // 所以事件也得补上，否则底层一变按钮又灰回去
    /** 原 listener → 包装后的代理。ForwardingPlayer 按对象身份认 listener，移除时必须交回同一个代理。 */
    private val wrappedListeners = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapped = synchronized(wrappedListeners) {
            wrappedListeners.getOrPut(listener) { skipCommandsListener(listener) }
        }
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapped = synchronized(wrappedListeners) { wrappedListeners.remove(listener) }
        super.removeListener(wrapped ?: listener)
    }

    /**
     * 用动态代理而不是 `Player.Listener by listener`：Kotlin 委托不转发 Java 接口的默认方法，
     * 而 Listener 的方法全是默认方法——那样写会把 session 要的其余事件全吞掉。
     */
    private fun skipCommandsListener(listener: Player.Listener): Player.Listener =
        Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader,
            arrayOf(Player.Listener::class.java),
        ) { proxy, method, args ->
            val arguments = args ?: emptyArray()
            // release 包里 R8 会给 Media3 的方法改名，只有 Object 的方法名靠得住；
            // onAvailableCommandsChanged 按参数认——Listener 里只有它只收一个 Commands
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> proxy === arguments[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "SkipCommands($listener)"
                }
                method.parameterTypes.contentEquals(COMMANDS_PARAMETER) ->
                    listener.onAvailableCommandsChanged(withSkipCommands(arguments[0] as Player.Commands))
                else -> try {
                    method.invoke(listener, *arguments)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as Player.Listener

    private companion object {
        val SKIP_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        val COMMANDS_PARAMETER = arrayOf<Class<*>>(Player.Commands::class.java)

        fun withSkipCommands(commands: Player.Commands): Player.Commands =
            commands.buildUpon().addAll(*SKIP_COMMANDS).build()
    }
}
