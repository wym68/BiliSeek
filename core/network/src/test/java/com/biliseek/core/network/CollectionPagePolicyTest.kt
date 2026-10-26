package com.biliseek.core.network

import com.biliseek.core.network.internal.hasMorePages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPagePolicyTest {

    @Test
    fun `给了总数就按总数算`() {
        assertTrue(hasMorePages(page = 1, pageSize = 20, total = 21, received = 20))
        assertFalse(hasMorePages(page = 2, pageSize = 20, total = 40, received = 20))
        assertFalse(hasMorePages(page = 1, pageSize = 20, total = 3, received = 3))
    }

    @Test
    fun `没给总数就看这一页满不满`() {
        assertTrue(hasMorePages(page = 1, pageSize = 20, total = 0, received = 20))
        assertFalse(hasMorePages(page = 1, pageSize = 20, total = 0, received = 19))
    }

    @Test
    fun `这一页一条都没有就到底 不管总数怎么说`() {
        assertFalse(hasMorePages(page = 3, pageSize = 20, total = 100, received = 0))
    }
}
