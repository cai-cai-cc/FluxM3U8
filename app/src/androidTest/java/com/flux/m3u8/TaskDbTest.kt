package com.flux.m3u8

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.m3u8.data.TaskDb
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 数据库集成测试。
 *
 * 重点验证**升级不丢数据**——旧实现是 DROP TABLE + 重建，
 * 用户一升级任务列表就清空，这是最严重的数据问题。
 */
@RunWith(AndroidJUnit4::class)
class TaskDbTest {

    private lateinit var context: Context
    private lateinit var db: TaskDb

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("flux_tasks.db")
        db = TaskDb(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("flux_tasks.db")
    }

    @Test
    fun insertAndReadRoundTrip() {
        val task = DownloadTask(
            id = "abc12345",
            url = "https://example.com/a.m3u8",
            name = "测试视频",
            saveDirUri = "internal:剧集/第一季",
            status = TaskStatus.Downloading,
            variantLabel = "1080P",
            totalSegments = 100,
            doneSegments = 25,
            totalBytes = 1024 * 1024,
            doneBytes = 512,
            estimated = true,
            isLive = false,
            encrypted = true,
            connections = 32,
            toMp4 = true,
            referer = "https://ref",
            userAgent = "UA/1",
            cookie = "sid=xyz",
            variantIndex = 2
        )
        db.insert(task)

        val read = db.get("abc12345")
        assertNotNull(read)
        assertEquals("测试视频", read!!.name)
        assertEquals("internal:剧集/第一季", read.saveDirUri)
        assertEquals(TaskStatus.Downloading, read.status)
        assertEquals("1080P", read.variantLabel)
        assertEquals(25, read.doneSegments)
        assertEquals(512L, read.doneBytes)
        assertTrue(read.estimated)
        assertTrue(read.encrypted)
        assertEquals(32, read.connections)
        assertTrue(read.toMp4)
        assertEquals("sid=xyz", read.cookie)
        assertEquals(2, read.variantIndex)
    }

    @Test
    fun cookieIsEncryptedAtRest() {
        val secret = "sid=supersecret;token=abc123"
        db.insert(DownloadTask("sec1", "u", "n", "internal", cookie = secret))

        // 读回来必须是原文
        assertEquals(secret, db.get("sec1")!!.cookie)

        // 库里不能是明文（个别 ROM 的 KeyStore 不可用时 SecretBox 会降级，
        // 那种情况下不强求，但绝不能出现"加了密却解不出来"）
        db.readableDatabase.query(
            "tasks", arrayOf("cookie"), "id=?", arrayOf("sec1"), null, null, null
        ).use { c ->
            assertTrue(c.moveToFirst())
            val stored = c.getString(0) ?: ""
            if (stored.startsWith("enc1:")) {
                assertTrue("加密后仍然包含明文", !stored.contains("supersecret"))
            }
        }
    }

    @Test
    fun progressUpdateKeepsOtherColumns() {
        val task = DownloadTask(
            id = "p1", url = "https://a/x.m3u8", name = "n", saveDirUri = "internal",
            cookie = "keep=me"
        )
        db.insert(task)
        db.updateProgress("p1", 5, 999, 10, 2000, true)

        val read = db.get("p1")!!
        assertEquals(5, read.doneSegments)
        assertEquals(999L, read.doneBytes)
        // 高频进度更新不能把 cookie 之类字段冲掉
        assertEquals("keep=me", read.cookie)
        assertEquals("n", read.name)
    }

    @Test
    fun resumableFilter() {
        db.insert(DownloadTask("r1", "u", "n", "internal", status = TaskStatus.Downloading))
        db.insert(DownloadTask("r2", "u", "n", "internal", status = TaskStatus.Queued))
        db.insert(DownloadTask("r3", "u", "n", "internal", status = TaskStatus.Completed))
        db.insert(DownloadTask("r4", "u", "n", "internal", status = TaskStatus.Error))

        val ids = db.resumable().map { it.id }.toSet()
        assertTrue(ids.contains("r1"))
        assertTrue(ids.contains("r2"))
        assertFalse(ids.contains("r3"))
        assertFalse(ids.contains("r4"))
    }

    @Test
    fun clearFinished() {
        db.insert(DownloadTask("c1", "u", "n", "internal", status = TaskStatus.Completed))
        db.insert(DownloadTask("c2", "u", "n", "internal", status = TaskStatus.Downloading))
        db.clearFinished()
        assertEquals(1, db.all().size)
        assertNotNull(db.get("c2"))
    }

    @Test
    fun ensureColumnsIsAdditive() {
        // 手工造一个"缺很多列"的老库，并塞入一条数据
        val file = File(context.getNoBackupFilesDir(), "mig_test.db")
        if (file.exists()) file.delete()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                name TEXT NOT NULL,
                save_dir TEXT NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        raw.execSQL(
            "INSERT INTO tasks VALUES ('old1','https://a/b.m3u8','老任务','internal','Completed')"
        )

        db.ensureColumns(raw)

        // 新增列必须存在
        val cols = raw.query("tasks", null, null, null, null, null, null).use { c ->
            (0 until c.columnCount).map { c.getColumnName(it) }.toSet()
        }
        assertTrue("cookie 列未被补上", cols.contains("cookie"))
        assertTrue("variant_idx 列未被补上", cols.contains("variant_idx"))
        assertTrue("output_uri 列未被补上", cols.contains("output_uri"))

        // 老数据必须还在
        raw.query("tasks", arrayOf("name"), "id=?", arrayOf("old1"), null, null, null).use { c ->
            assertTrue("升级后老数据丢失", c.moveToFirst())
            assertEquals("老任务", c.getString(0))
        }
        raw.close()
        file.delete()
    }

    @Test
    fun ensureColumnsIsIdempotent() {
        val raw = db.writableDatabase
        db.ensureColumns(raw)
        db.ensureColumns(raw)   // 重复调用不能报错，也不能加重复列
        val cols = raw.query("tasks", null, null, null, null, null, null).use { c -> c.columnCount }
        assertTrue(cols > 10)
        assertTrue(db.all().isEmpty())
    }
}
