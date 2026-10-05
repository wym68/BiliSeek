package com.biliseek.app.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 真实收听时长的计时接线：开始播放就计时，停下（暂停 / 缓冲 / 停止）就结算，
 * 播放中每 [FLUSH_INTERVAL_MS] 结算一次交给 [record] 落库（进程被杀最多丢这一段）。
 * 判断与切日都在 [ListenClock] 里；生命周期跟着 [scope]（宿主的 viewModelScope）。
 */
class ListenTimeTracker(
    private val scope: CoroutineScope,
    isPlaying: Flow<Boolean>,
    private val record: suspend (DaySlice) -> Unit,
) {
    private val clock = ListenClock(ZoneId.systemDefault())
    private var ticker: Job? = null

    init {
        scope.launch {
            isPlaying.distinctUntilChanged().collect { playing ->
                if (playing) {
                    clock.start(SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    if (ticker == null) {
                        ticker = scope.launch {
                            while (true) {
                                delay(FLUSH_INTERVAL_MS)
                                save(clock.tick(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
                            }
                        }
                    }
                } else {
                    ticker?.cancel()
                    ticker = null
                    save(clock.stop(SystemClock.elapsedRealtime()))
                }
            }
        }
    }

    private suspend fun save(slices: List<DaySlice>) = slices.forEach { record(it) }

    private companion object {
        const val FLUSH_INTERVAL_MS = 30_000L
    }
}
