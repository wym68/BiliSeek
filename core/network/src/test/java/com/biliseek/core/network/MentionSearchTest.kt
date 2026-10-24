package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.MentionSearchData
import com.biliseek.core.network.internal.flattenMentionUsers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** `mention/search` 的解析与拍平。样例按 bilipai ResponseModels.kt:606 的字段手写，不是抓包。 */
class MentionSearchTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val raw = """
        {"code":0,"message":"0","data":{"groups":[
          {"group_name":"最近@过的人","group_type":1,"items":[
            {"uid":"42","name":"小明","face":"//i0.hdslb.com/a.jpg","fans":"1200"},
            {"uid":0,"name":"无效","face":""}
          ]},
          {"group_name":"我的关注","group_type":2,"items":[
            {"uid":42,"name":"小明","face":"//i0.hdslb.com/a.jpg","fans":1200},
            {"uid":7,"name":"","face":""},
            {"uid":99,"name":"小红","face":"http://i0.hdslb.com/b.jpg","fans":5}
          ]}
        ]}}
    """.trimIndent()

    @Test
    fun `uid 和 fans 是字符串也能解析`() {
        val data = json.decodeFromString<BiliResponse<MentionSearchData>>(raw).data!!
        assertEquals(42L, data.groups[0].items[0].uid)
        assertEquals(1200L, data.groups[0].items[0].fans)
        assertEquals("我的关注", data.groups[1].groupName)
    }

    @Test
    fun `拍平：去掉无效项，同一人只留一次，保持分组顺序`() {
        val data = json.decodeFromString<BiliResponse<MentionSearchData>>(raw).data
        assertEquals(listOf(42L, 99L), data.flattenMentionUsers().map { it.uid })
    }

    @Test
    fun `没有数据时是空列表`() {
        assertEquals(emptyList<Any>(), (null as MentionSearchData?).flattenMentionUsers())
    }
}
