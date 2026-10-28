package com.biliseek.core.network.internal

/**
 * 合集 / 系列这类带总数的分页接口还有没有下一页。
 *
 * - 这一页一条都没有：到底（总数对不上时也不会一直往后翻）；
 * - 给了总数：按「已取到的页 × 每页条数 < 总数」；
 * - 没给总数（0）：这一页满了才认为还有。
 */
internal fun hasMorePages(page: Int, pageSize: Int, total: Int, received: Int): Boolean = when {
    received == 0 -> false
    total > 0 -> page * pageSize < total
    else -> received >= pageSize
}
