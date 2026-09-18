package com.flux.m3u8

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.m3u8.data.Settings
import com.flux.m3u8.data.Storage
import com.flux.m3u8.data.TaskDb
import com.flux.m3u8.download.SpeedMeter
import com.flux.m3u8.download.TaskControl
import com.flux.m3u8.download.TaskRunner
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 端到端下载流程集成测试：解析 → 并发下载 → 合并 → 落盘。
 *
 * 用 MockWebServer 模拟源站，不依赖外网。
 */
@RunWith(AndroidJUnit4::class)
class TaskRunnerPipelineTest {

    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var storage: Storage
    private lateinit var db: TaskDb
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("flux_tasks.db")

        settings = Settings(context).apply {
            // 关掉分块，走最朴素的单连接路径，减少不确定性
            chunkThreads = 1
            autoConcurrency = false
            retryTimes = 1
            timeoutSeconds = 10
            toMp4 = false
            keepSegments = false
            speedLimitKbps = 0
        }
        storage = Storage(context)
        db = TaskDb(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        context.deleteDatabase("flux_tasks.db")
    }

    private fun playlist(vararg names: String): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:10")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        names.forEach { appendLine("#EXTINF:10.0,"); appendLine(it) }
        appendLine("#EXT-X-ENDLIST")
    }

    // ────────────── 主流程 ──────────────

    @Test
    fun vodDownloadAndMerge() = runBlocking {
        val base = server.url("/").toString()
        server.enqueue(MockResponse().setBody(playlist("a.ts", "b.ts", "c.ts")))
        server.enqueue(MockResponse().setBody("AAA"))
        server.enqueue(MockResponse().setBody("BBB"))
        server.enqueue(MockResponse().setBody("CCC"))

        val task = DownloadTask(
            id = "e2e1",
            url = server.url("/index.m3u8").toString(),
            name = "e2e_video",
            saveDirUri = Storage.INTERNAL,
            connections = 1,
            toMp4 = false
        )
        assertTrue(base.isNotBlank())

        val runner = TaskRunner(context, settings, storage, db)
        val result = runner.run(task, TaskControl(), SpeedMeter()) {}

        assertEquals(TaskStatus.Completed, result.status)
        assertEquals(3, result.totalSegments)
        assertEquals(3, result.doneSegments)
        assertEquals(9L, result.doneBytes)
        assertNotNull(result.outputUri)

        val out = File(storage.internalDir(Storage.INTERNAL), result.outputName!!)
        assertTrue("输出文件应存在：${out.absolutePath}", out.exists())
        assertEquals("AAABBBCCC", out.readText())

        out.delete()
    }

    @Test
    fun masterPlaylistPicksHighest() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
            360p.m3u8
        """.trimIndent()

        server.enqueue(MockResponse().setBody(master))
        server.enqueue(MockResponse().setBody(playlist("h1.ts", "h2.ts")))
        server.enqueue(MockResponse().setBody("H1"))
        server.enqueue(MockResponse().setBody("H2"))

        val task = DownloadTask(
            id = "e2e2",
            url = server.url("/master.m3u8").toString(),
            name = "e2e_master",
            saveDirUri = Storage.INTERNAL,
            connections = 1
        )

        val result = TaskRunner(context, settings, storage, db)
            .run(task, TaskControl(), SpeedMeter()) {}

        assertEquals(TaskStatus.Completed, result.status)
        // 默认取最高清晰度
        assertEquals("1080P", result.variantLabel)
        assertEquals(2, result.totalSegments)

        val out = File(storage.internalDir(Storage.INTERNAL), result.outputName!!)
        assertEquals("H1H2", out.readText())
        out.delete()
    }

    @Test
    fun explicitVariantIndexWorks() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
            360p.m3u8
        """.trimIndent()

        server.enqueue(MockResponse().setBody(master))
        server.enqueue(MockResponse().setBody(playlist("l1.ts")))
        server.enqueue(MockResponse().setBody("L1"))

        val task = DownloadTask(
            id = "e2e3",
            url = server.url("/master.m3u8").toString(),
            name = "e2e_low",
            saveDirUri = Storage.INTERNAL,
            connections = 1,
            variantIndex = 1        // 排序后 index 1 = 360P
        )

        val result = TaskRunner(context, settings, storage, db)
            .run(task, TaskControl(), SpeedMeter()) {}

        assertEquals(TaskStatus.Completed, result.status)
        assertEquals("360P", result.variantLabel)
        assertEquals("L1", File(storage.internalDir(Storage.INTERNAL), result.outputName!!).readText())
        File(storage.internalDir(Storage.INTERNAL), result.outputName!!).delete()
    }

    // ────────────── 异常路径 ──────────────

    @Test
    fun http404FailsTask() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val task = DownloadTask(
            id = "e2e4",
            url = server.url("/missing.m3u8").toString(),
            name = "e2e_404",
            saveDirUri = Storage.INTERNAL,
            connections = 1
        )

        val result = TaskRunner(context, settings, storage, db)
            .run(task, TaskControl(), SpeedMeter()) {}

        assertEquals(TaskStatus.Error, result.status)
        assertTrue(result.error!!.contains("404"))
    }

    @Test
    fun nonM3u8ContentFailsTask() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>not a playlist</html>"))

        val task = DownloadTask(
            id = "e2e5",
            url = server.url("/page.html").toString(),
            name = "e2e_html",
            saveDirUri = Storage.INTERNAL,
            connections = 1
        )

        val result = TaskRunner(context, settings, storage, db)
            .run(task, TaskControl(), SpeedMeter()) {}

        assertEquals(TaskStatus.Error, result.status)
    }

    @Test
    fun segmentFailureFailsFast() = runBlocking {
        server.enqueue(MockResponse().setBody(playlist("a.ts", "b.ts")))
        server.enqueue(MockResponse().setBody("AAA"))
        // b.ts 永远失败；多排几个 500，保证重试也不会耗尽 MockWebServer 队列而挂住
        repeat(6) { server.enqueue(MockResponse().setResponseCode(500)) }

        val task = DownloadTask(
            id = "e2e6",
            url = server.url("/index.m3u8").toString(),
            name = "e2e_failed_seg",
            saveDirUri = Storage.INTERNAL,
            connections = 1
        )

        val result = TaskRunner(context, settings, storage, db)
            .run(task, TaskControl(), SpeedMeter()) {}

        assertEquals(TaskStatus.Error, result.status)
        // 关键：错误信息必须明确指出是"分片未下载完成"，
        // 而不是等到合并阶段才抛出含糊的"分片缺失"
        assertTrue(
            "期望分片级失败提示，实际：${result.error}",
            result.error!!.contains("分片未下载完成")
        )
        // 失败后不应留下半成品
        assertEquals(
            null,
            storage.internalDir(Storage.INTERNAL).listFiles()
                ?.firstOrNull { it.name.startsWith("e2e_failed_seg") }
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun cancelCleansTempFiles() = runBlocking {
        server.enqueue(MockResponse().setBody(playlist("a.ts", "b.ts", "c.ts", "d.ts")))
        repeat(8) { server.enqueue(MockResponse().setBody("X")) }

        val task = DownloadTask(
            id = "e2e7",
            url = server.url("/index.m3u8").toString(),
            name = "e2e_cancel",
            saveDirUri = Storage.INTERNAL,
            connections = 1
        )
        val control = TaskControl()

        val runner = TaskRunner(context, settings, storage, db)
        val job = kotlinx.coroutines.GlobalScope.launch {
            runner.run(task, control, SpeedMeter()) {}
        }
        // 等下载跑起来再取消，然后等它自己收尾（超时就放弃等待）
        kotlinx.coroutines.delay(300)
        control.cancel()
        withTimeoutOrNull(10_000) { job.join() }

        // 临时目录应被清掉
        val left = storage.taskTempDir("e2e7")
        assertTrue("取消后临时目录应被清理", !left.exists() || left.listFiles().isNullOrEmpty())
    }
}
