package com.biliseek.core.network

import com.biliseek.core.network.internal.IdempotentCodes
import com.biliseek.core.network.internal.WriteOutcome
import com.biliseek.core.network.internal.resolveWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** 写操作错误码分流的纯函数判据（工单 §5.1）。 */
class WritePolicyTest {

    @Test
    fun `code 0 是成功`() {
        assertEquals(WriteOutcome.Success, resolveWriteOutcome(0))
    }

    @Test
    fun `幂等码在集合内当成 AlreadyDone`() {
        assertEquals(WriteOutcome.AlreadyDone, resolveWriteOutcome(65006, IdempotentCodes.LIKE))
    }

    @Test
    fun `幂等码不在集合内退化为 Failed`() {
        // 65006 对点赞是幂等成功，但对不声明它的接口只是普通失败——绝不能一刀切
        assertEquals(WriteOutcome.Failed(65006), resolveWriteOutcome(65006, emptySet()))
    }

    @Test
    fun `负一一一是 csrf 失效`() {
        assertEquals(WriteOutcome.CsrfInvalid, resolveWriteOutcome(-111))
    }

    @Test
    fun `负一零一是需要登录`() {
        assertEquals(WriteOutcome.NeedLogin, resolveWriteOutcome(-101))
    }

    @Test
    fun `负三五二是风控拦截`() {
        assertEquals(WriteOutcome.Blocked, resolveWriteOutcome(-352))
    }

    @Test
    fun `负四一二是重种凭据`() {
        assertEquals(WriteOutcome.ReseedCredentials, resolveWriteOutcome(-412))
    }

    @Test
    fun `其它码落进 Failed 并带上码`() {
        assertEquals(WriteOutcome.Failed(12345), resolveWriteOutcome(12345))
    }

    @Test
    fun `幂等码不影响风控码的分流`() {
        // 即便声明了幂等集，-352/-111 仍按各自语义分流，不被吞掉
        assertEquals(WriteOutcome.Blocked, resolveWriteOutcome(-352, setOf(65006)))
        assertEquals(WriteOutcome.CsrfInvalid, resolveWriteOutcome(-111, setOf(65006)))
    }
}
