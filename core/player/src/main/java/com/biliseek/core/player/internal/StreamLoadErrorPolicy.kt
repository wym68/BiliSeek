package com.biliseek.core.player.internal

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/** 一次分片加载失败属于哪一类。 */
internal enum class LoadFailure {
    /** 连不上 / 读超时 / DNS 失败——请求根本没拿到响应。 */
    NETWORK,

    /** 服务端回了错误状态码：403（直链过期、换网后失效）、404、5xx…… */
    HTTP_STATUS,

    /** 其余可重试的 IO 异常。 */
    OTHER,
}

/** 非「断网等待」时最多试几次，与 Media3 默认的 `DEFAULT_MIN_LOADABLE_RETRY_COUNT` 一致。 */
internal const val LOAD_RETRY_LIMIT = 3

/**
 * 可重试的加载失败该等多久再试；null = 不再重试，错误上浮给播放器
 * （进而由 PlayerConnection 重新取流，见 PlaybackRecoveryPolicy）。
 *
 * **设备没网时一直等**：Media3 默认只试 3 次，缓冲一旦耗尽就判死，播放器停在 IDLE——
 * 进电梯、过隧道、切网的空窗都会被放大成「永久卡住」。这里改成等网回来，退避封顶 5 秒，
 * 网一通 5 秒内自动续上，不重建播放源、不丢位置。
 *
 * **设备有网却连不上**说明是这条线路的问题（节点挂了、换网后旧节点不通），与其他失败一样
 * 试 3 次就上浮：重新取流能换到新节点，死等只会卡到 Media3 的 10 分钟卡死检测。
 */
internal fun resolveLoadRetryDelayMs(failure: LoadFailure, errorCount: Int, deviceOnline: Boolean): Long? {
    val delayMs = ((errorCount - 1) * 1000L).coerceIn(0L, 5000L)
    val waitForNetwork = failure == LoadFailure.NETWORK && !deviceOnline
    return if (waitForNetwork || errorCount <= LOAD_RETRY_LIMIT) delayMs else null
}

/**
 * 网络类失败要不要换 manifest 里的下一条线路（BaseURL）。
 *
 * **设备有网时换**：连得上网却连不上这个节点，就是节点的问题（实测：海外网络下 Akamai 主线路
 * 连上即断）。默认策略只在 403/404/5xx 时换线路，连接被断开会一直死磕同一个节点。
 * **没网时不换**：所有线路都会失败，换了只会把好线路也一个个排除掉（各排除 5 分钟）。
 * 没有别的线路可换时也不兜底换轨——降个码率解决不了连不上。
 */
internal fun shouldSwitchCdnOnFailure(failure: LoadFailure, deviceOnline: Boolean, anotherLocation: Boolean): Boolean =
    failure == LoadFailure.NETWORK && deviceOnline && anotherLocation

/**
 * 播放器的加载失败策略。决策在 [resolveLoadRetryDelayMs] / [shouldSwitchCdnOnFailure]，
 * 这里只负责把异常分类。错误状态码（403/404/5xx）的换线路 / 换轨沿用默认策略。
 */
@OptIn(UnstableApi::class)
internal class StreamLoadErrorPolicy(
    /** 设备当前有没有可用网络。在加载线程上调用。 */
    private val deviceOnline: () -> Boolean,
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // 默认策略判为不可重试的（解析失败、本地文件不存在、明文被拦……）照旧直接上浮
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        val failure = classify(loadErrorInfo.exception)
        return resolveLoadRetryDelayMs(failure, loadErrorInfo.errorCount, deviceOnline()) ?: C.TIME_UNSET
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val failure = classify(loadErrorInfo.exception)
        if (failure != LoadFailure.NETWORK) return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        val anotherLocation = fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        if (!shouldSwitchCdnOnFailure(failure, deviceOnline(), anotherLocation)) return null
        return LoadErrorHandlingPolicy.FallbackSelection(
            LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
            DEFAULT_LOCATION_EXCLUSION_MS,
        )
    }

    /**
     * 上不上浮只由 [getRetryDelayMsFor] 决定。默认实现按次数判死（第 4 次失败且缓冲耗尽就报错），
     * 那样「断网一直等」就无从谈起。
     */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    private fun classify(exception: IOException): LoadFailure = when (exception) {
        is HttpDataSource.InvalidResponseCodeException -> LoadFailure.HTTP_STATUS
        is HttpDataSource.HttpDataSourceException -> LoadFailure.NETWORK
        else -> LoadFailure.OTHER
    }
}
