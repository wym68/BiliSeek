package com.biliseek.core.network

import com.biliseek.core.network.internal.RequestThrottle
import com.biliseek.core.network.internal.ThrottleBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** B3 限速闸门的结构性判据：同 bucket 串行、透传返回值（工单 §4.1）。 */
class RequestThrottleTest {

    @Test
    fun `gate 透传 block 返回值`() = runBlocking {
        val throttle = RequestThrottle(nowMs = { 0L })
        val out = throttle.gate(ThrottleBucket.WRITE, minIntervalMs = 0, jitterMs = 0) { 42 }
        assertEquals(42, out)
    }

    @Test
    fun `同 bucket 并发被串行化`() = runBlocking {
        // now 恒为 0、minInterval 0 → 不引入 delay，只考验互斥。
        // block 内非原子地读改写共享计数并 yield 制造交错窗口；串行则无丢更新。
        val throttle = RequestThrottle(nowMs = { 0L })
        var counter = 0
        val n = 200
        val jobs = (1..n).map {
            async(Dispatchers.Default) {
                throttle.gate(ThrottleBucket.WRITE, minIntervalMs = 0, jitterMs = 0) {
                    val v = counter
                    yield()
                    counter = v + 1
                }
            }
        }
        jobs.awaitAll()
        assertEquals("同 bucket 未串行会丢更新", n, counter)
    }

    @Test
    fun `不同 bucket 各自独立`() = runBlocking {
        val throttle = RequestThrottle(nowMs = { 0L })
        val a = throttle.gate(ThrottleBucket.SEARCH, minIntervalMs = 0, jitterMs = 0) { "a" }
        val b = throttle.gate(ThrottleBucket.WRITE, minIntervalMs = 0, jitterMs = 0) { "b" }
        assertEquals("a", a)
        assertEquals("b", b)
    }
}
