package com.biliseek.core.asr.internal

import com.biliseek.core.asr.AsrModelSpec
import com.biliseek.core.asr.ModelFile
import com.biliseek.core.asr.ModelSource
import com.biliseek.core.asr.ModelState
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** 全部走本地 MockWebServer，不访问任何真实下载源。 */
class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val files = mutableMapOf<String, ByteArray>()
    private val requests = mutableListOf<RecordedRequest>()

    /** 为 true 时忽略 Range，永远回 200 全量。 */
    private var ignoreRange = false

    private val bigBytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val smallBytes = "tokens\n".repeat(100).toByteArray()

    @Before
    fun setUp() {
        files["/m/model.int8.onnx"] = bigBytes
        files["/m/tokens.txt"] = smallBytes
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.url.encodedPath
                val body = files[path] ?: return MockResponse.Builder().code(404).build()
                val range = request.headers["Range"]
                if (range != null && !ignoreRange) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse.Builder()
                        .code(206)
                        .headers(Headers.headersOf("Content-Range", "bytes $from-${body.size - 1}/${body.size}"))
                        .body(Buffer().write(body, from, body.size - from))
                        .build()
                }
                return MockResponse.Builder().code(200).body(Buffer().write(body)).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun spec(tokensSha: String = sha(smallBytes)) = AsrModelSpec(
        id = "test_model",
        displayName = "test",
        files = listOf(
            ModelFile("model.int8.onnx", bigBytes.size.toLong(), sha(bigBytes)),
            ModelFile("tokens.txt", smallBytes.size.toLong(), tokensSha),
        ),
        sources = listOf(source()),
        license = "x",
        licenseUrl = "x",
    )

    private fun source() = ModelSource("local", "local", server.url("/m/").toString())

    private fun store() = ModelStore(tmp.root, OkHttpClient())

    @Test
    fun downloadsAllFilesAndBecomesReady() = runTest {
        val store = store()
        val spec = spec()
        store.download(spec, source())
        assertEquals(ModelState.Ready, store.state.value)
        assertNotNull(store.modelDir(spec))
        assertTrue(File(store.dir(spec), "model.int8.onnx").readBytes().contentEquals(bigBytes))
        assertFalse(File(store.dir(spec), "model.int8.onnx.part").exists())
        // 重新创建（模拟重启）后仍识别为已安装
        assertEquals(ModelState.Ready, store().refresh(spec))
    }

    @Test
    fun resumesWithRangeFromPartialFile() = runTest {
        val store = store()
        val spec = spec()
        // 上次下载中断留下的前 50000 字节
        store.dir(spec).mkdirs()
        File(store.dir(spec), "model.int8.onnx.part").writeBytes(bigBytes.copyOf(50_000))
        store.download(spec, source())
        assertEquals(ModelState.Ready, store.state.value)
        val request = requests.first { it.url.encodedPath.endsWith("model.int8.onnx") }
        assertEquals("bytes=50000-", request.headers["Range"])
        assertTrue(File(store.dir(spec), "model.int8.onnx").readBytes().contentEquals(bigBytes))
    }

    @Test
    fun serverIgnoringRangeRestartsFromZero() = runTest {
        val store = store()
        val spec = spec()
        store.dir(spec).mkdirs()
        File(store.dir(spec), "model.int8.onnx.part").writeBytes(ByteArray(1000) { 9 })
        ignoreRange = true
        store.download(spec, source())
        assertEquals(ModelState.Ready, store.state.value)
        assertTrue(File(store.dir(spec), "model.int8.onnx").readBytes().contentEquals(bigBytes))
    }

    @Test
    fun hashMismatchIsBrokenAndNotReady() = runTest {
        val store = store()
        val spec = spec(tokensSha = "0".repeat(64))
        try {
            store.download(spec, source())
            fail("expected failure")
        } catch (_: Exception) {
        }
        val state = store.state.value
        assertTrue(state is ModelState.Broken)
        assertTrue((state as ModelState.Broken).reason.contains("tokens.txt"))
        assertNull(store.modelDir(spec))
        assertFalse(File(store.dir(spec), "tokens.txt.part").exists())
    }

    @Test
    fun switchingSourceReusesVerifiedFiles() = runTest {
        val store = store()
        val spec = spec()
        store.download(spec, source())
        File(store.dir(spec), ModelStore.MARKER).delete()
        files["/mirror/model.int8.onnx"] = bigBytes
        files["/mirror/tokens.txt"] = smallBytes
        requests.clear()
        store.download(spec, ModelSource("mirror", "mirror", server.url("/mirror/").toString()))
        assertEquals(ModelState.Ready, store.state.value)
        assertEquals(0, requests.size)
    }

    @Test
    fun http404IsBroken() = runTest {
        val store = store()
        try {
            store.download(spec(), ModelSource("bad", "bad", server.url("/nothing/").toString()))
            fail()
        } catch (_: Exception) {
        }
        assertTrue((store.state.value as ModelState.Broken).reason.contains("404"))
    }

    @Test
    fun deleteRemovesEverything() = runTest {
        val store = store()
        val spec = spec()
        store.download(spec, source())
        store.delete(spec)
        assertEquals(ModelState.NotInstalled, store.state.value)
        assertFalse(store.dir(spec).exists())
    }

    @Test
    fun importMatchesByNameThenSize() = runTest {
        val store = store()
        val spec = spec()
        store.import(
            spec,
            listOf(
                ModelStore.ImportInput("model.int8.onnx", bigBytes.size.toLong()) { bigBytes.inputStream() },
                // 文件名被改过，按大小匹配
                ModelStore.ImportInput("download (1).txt", smallBytes.size.toLong()) { smallBytes.inputStream() },
            ),
        )
        assertEquals(ModelState.Ready, store.state.value)
    }

    @Test
    fun importMissingFileIsBroken() = runTest {
        val store = store()
        try {
            store.import(spec(), listOf(ModelStore.ImportInput("tokens.txt", null) { smallBytes.inputStream() }))
            fail()
        } catch (_: Exception) {
        }
        assertTrue((store.state.value as ModelState.Broken).reason.contains("model.int8.onnx"))
    }

    @Test
    fun progressOnlyOnIntegerPercentChange() {
        val emitted = mutableListOf<Long>()
        val t = ProgressThrottle(1_000) { emitted += it }
        (0L..1_000L).forEach(t::report)
        assertEquals(101, emitted.size)
        assertEquals(0L, emitted.first())
        assertEquals(1_000L, emitted.last())
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
