package com.flux.m3u8

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.m3u8.model.Segment
import com.flux.m3u8.playback.LocalPlayServer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 边下边播本地服务的集成测试。
 *
 * 覆盖：播放列表生成、已下载分片读取、Range 请求、越界/无权限处理、
 * 以及"分片没下载时按需回源"这条关键路径。
 */
@RunWith(AndroidJUnit4::class)
class LocalPlayServerTest {

    private lateinit var context: Context
    private lateinit var server: LocalPlayServer
    private lateinit var tmpDir: File
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tmpDir = File(context.cacheDir, "playtest_tmp").apply { deleteRecursively(); mkdirs() }
        cacheDir = File(context.cacheDir, "playtest_cache").apply { deleteRecursively(); mkdirs() }
        server = LocalPlayServer { OkHttpClient() }
    }

    @After
    fun tearDown() {
        server.stop()
        tmpDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private fun session(
        segments: List<Segment>,
        token: String = "tok",
        taskId: String = "task1"
    ) = LocalPlayServer.Session(
        token = token,
        taskId = taskId,
        segments = segments,
        headers = emptyMap(),
        keys = emptyMap(),
        tmpDir = tmpDir,
        cacheDir = cacheDir,
        targetDuration = 10
    )

    private fun seg(i: Int, uri: String = "https://unused/$i.ts", dur: Double = 6.0) =
        Segment(index = i, uri = uri, duration = dur)

    private data class Resp(val code: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun request(url: String, vararg headers: Pair<String, String>): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.readBytes() ?: ByteArray(0)
        val map = conn.headerFields
            .filterKeys { it != null }
            .mapValues { it.value.firstOrNull() ?: "" }
        conn.disconnect()
        return Resp(code, body, map)
    }

    // ────────────── 播放列表 ──────────────

    @Test
    fun portIsValidAfterStart() {
        server.register(session(listOf(seg(0))))
        assertTrue(server.port > 0)
        assertTrue(server.isRunning)
    }

    @Test
    fun servesPlayablePlaylist() {
        server.register(session(listOf(seg(0), seg(1))))
        val r = request("http://127.0.0.1:${server.port}/tok/task1/index.m3u8")

        assertEquals(200, r.code)
        val text = r.body.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue(text.contains("#EXT-X-ENDLIST"))
        assertTrue(text.contains("#EXT-X-TARGETDURATION:10"))
        assertTrue(text.contains("s/0.ts"))
        assertTrue(text.contains("s/1.ts"))
        assertEquals("application/vnd.apple.mpegurl", r.headers["Content-Type"])
    }

    // ────────────── 分片 ──────────────

    @Test
    fun servesDownloadedSegment() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        File(tmpDir, "0000000.ts").writeBytes(payload)
        server.register(session(listOf(seg(0))))

        val r = request("http://127.0.0.1:${server.port}/tok/task1/s/0.ts")
        assertEquals(200, r.code)
        assertArrayEquals(payload, r.body)
        assertEquals("1000", r.headers["Content-Length"])
    }

    @Test
    fun rangeRequestReturns206() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        File(tmpDir, "0000000.ts").writeBytes(payload)
        server.register(session(listOf(seg(0))))

        val r = request(
            "http://127.0.0.1:${server.port}/tok/task1/s/0.ts",
            "Range" to "bytes=10-19"
        )
        assertEquals(206, r.code)
        assertEquals("bytes 10-19/1000", r.headers["Content-Range"])
        assertArrayEquals(payload.copyOfRange(10, 20), r.body)
    }

    @Test
    fun unsatisfiableRangeReturns416() {
        File(tmpDir, "0000000.ts").writeBytes(ByteArray(100))
        server.register(session(listOf(seg(0))))

        val r = request(
            "http://127.0.0.1:${server.port}/tok/task1/s/0.ts",
            "Range" to "bytes=5000-6000"
        )
        assertEquals(416, r.code)
        assertEquals("bytes */100", r.headers["Content-Range"])
    }

    @Test
    fun outOfRangeSegmentReturns404() {
        server.register(session(listOf(seg(0))))
        val r = request("http://127.0.0.1:${server.port}/tok/task1/s/99.ts")
        assertEquals(404, r.code)
    }

    // ────────────── 安全 ──────────────

    @Test
    fun wrongTokenReturns404() {
        File(tmpDir, "0000000.ts").writeBytes(ByteArray(10))
        server.register(session(listOf(seg(0))))

        assertEquals(404, request("http://127.0.0.1:${server.port}/wrong/task1/index.m3u8").code)
        assertEquals(404, request("http://127.0.0.1:${server.port}/wrong/task1/s/0.ts").code)
    }

    @Test
    fun mismatchedTaskIdReturns404() {
        server.register(session(listOf(seg(0))))
        assertEquals(404, request("http://127.0.0.1:${server.port}/tok/otherTask/index.m3u8").code)
    }

    @Test
    fun traversalAttemptReturns404() {
        server.register(session(listOf(seg(0))))
        // 序号必须是纯数字，任何 ../ 或文件名拼接都应被拒
        assertEquals(404, request("http://127.0.0.1:${server.port}/tok/task1/s/..%2F..%2Fetc%2Fpasswd").code)
        assertEquals(404, request("http://127.0.0.1:${server.port}/tok/task1/s/abc.ts").code)
    }

    @Test
    fun unknownPathReturns404() {
        server.register(session(listOf(seg(0))))
        assertEquals(404, request("http://127.0.0.1:${server.port}/").code)
        assertEquals(404, request("http://127.0.0.1:${server.port}/tok/task1/other.m3u8").code)
    }

    // ────────────── 按需回源 ──────────────

    @Test
    fun fetchesMissingSegmentOnDemand() {
        val remote = MockWebServer()
        remote.enqueue(MockResponse().setBody("REMOTE-SEGMENT"))
        remote.start()
        try {
            val uri = remote.url("/seg0.ts").toString()
            // tmpDir 里没有这个分片，必须回源
            server.register(session(listOf(seg(0, uri = uri))))

            val r = request("http://127.0.0.1:${server.port}/tok/task1/s/0.ts")
            assertEquals(200, r.code)
            assertEquals("REMOTE-SEGMENT", r.body.toString(Charsets.UTF_8))

            // 回源结果要落缓存，下次不必再走网络
            assertTrue(File(cacheDir, "0000000.ts").exists())
        } finally {
            remote.shutdown()
        }
    }

    @Test
    fun upstreamFailureReturns502() {
        val remote = MockWebServer()
        remote.enqueue(MockResponse().setResponseCode(500))
        remote.start()
        try {
            val uri = remote.url("/seg0.ts").toString()
            server.register(session(listOf(seg(0, uri = uri))))
            assertEquals(502, request("http://127.0.0.1:${server.port}/tok/task1/s/0.ts").code)
        } finally {
            remote.shutdown()
        }
    }

    // ────────────── 生命周期 ──────────────

    @Test
    fun stopClosesPort() {
        server.register(session(listOf(seg(0))))
        val port = server.port
        server.stop()
        assertPortClosed(port)
    }

    private fun assertPortClosed(port: Int) {
        var opened = false
        try {
            val conn = URL("http://127.0.0.1:$port/tok/task1/index.m3u8").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.connect()
            opened = true
            conn.disconnect()
        } catch (e: Exception) {
            // 期望连不上
        }
        assertTrue("stop() 之后端口应关闭", !opened)
    }
}
