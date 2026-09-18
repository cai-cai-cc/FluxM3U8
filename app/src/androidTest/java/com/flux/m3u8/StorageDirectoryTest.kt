package com.flux.m3u8

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.m3u8.data.DirCheck
import com.flux.m3u8.data.Storage
import com.flux.m3u8.util.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 下载目录功能的集成测试：路径解析、默认目录、自定义子目录、异常回退。
 */
@RunWith(AndroidJUnit4::class)
class StorageDirectoryTest {

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
    }

    // ────────────── 编码 / 解析 ──────────────

    @Test
    fun internalUriDetection() {
        assertTrue(Storage.isInternal(Storage.INTERNAL))
        assertTrue(Storage.isInternal("internal:Movies"))
        assertTrue(Storage.isInternal(""))
        assertFalse(Storage.isInternal("content://com.android.externalstorage.documents/tree/primary%3AMovies"))
    }

    @Test
    fun encodeSubDir() {
        assertEquals(Storage.INTERNAL, Storage.encodeInternal(""))
        assertEquals(Storage.INTERNAL, Storage.encodeInternal("   "))
        assertEquals("internal:Movies", Storage.encodeInternal("Movies"))
        assertEquals("internal:剧集/第一季", Storage.encodeInternal("剧集/第一季"))
    }

    @Test
    fun defaultDirIsMovies() {
        val dir = storage.internalDir(Storage.INTERNAL)
        assertEquals("Movies", dir.name)
        assertTrue(dir.absolutePath.endsWith("/files/Movies"))
        assertTrue(dir.exists())
    }

    @Test
    fun customSubDirWorks() {
        val dir = storage.internalDir(Storage.encodeInternal("Downloads"))
        assertEquals("Downloads", dir.name)
        assertTrue(dir.exists())

        val nested = storage.internalDir(Storage.encodeInternal("剧集/第一季"))
        assertEquals("第一季", nested.name)
        assertTrue(nested.absolutePath.contains("剧集"))
    }

    @Test
    fun pathTraversalBlocked() {
        // 即使输入非法，也不允许跳出 files 根目录
        val dir = storage.internalDir(Storage.encodeInternal("../../../../etc"))
        assertTrue(
            "目录必须仍在应用私有目录下，实际：${dir.absolutePath}",
            dir.absolutePath.startsWith(storage.internalRoot().absolutePath)
        )
    }

    @Test
    fun subDirOfEcho() {
        assertEquals("Movies", storage.subDirOf(Storage.INTERNAL))
        assertEquals("Downloads", storage.subDirOf("internal:Downloads"))
    }

    // ────────────── 可用性校验 ──────────────

    @Test
    fun internalDirCheckOk() {
        val check = storage.check(Storage.INTERNAL)
        assertTrue("内部目录应当可写：${(check as? DirCheck.Failed)?.reason}", check is DirCheck.Ok)
        val ok = check as DirCheck.Ok
        assertTrue(ok.displayPath.isNotBlank())
        assertTrue(ok.freeBytes != 0L)
    }

    @Test
    fun customSubDirCheckOk() {
        val check = storage.check(Storage.encodeInternal("Downloads"))
        assertTrue(check is DirCheck.Ok)
    }

    @Test
    fun unauthorizedSafFails() {
        val fake = "content://com.android.externalstorage.documents/tree/primary%3ADoesNotExist_flux"
        val check = storage.check(fake)
        assertTrue(check is DirCheck.Failed)
        assertTrue((check as DirCheck.Failed).reason.contains("重新选择") || check.reason.contains("授权"))
    }

    @Test
    fun unauthorizedFallsBackToInternal() {
        val fake = "content://com.android.externalstorage.documents/tree/primary%3ADoesNotExist_flux"
        val result = storage.ensureUsable(fake)
        assertEquals(Storage.INTERNAL, result)
    }

    @Test
    fun usableDirKept() {
        assertEquals(Storage.INTERNAL, storage.ensureUsable(Storage.INTERNAL))
        val custom = Storage.encodeInternal("ensure_usable_test")
        assertEquals(custom, storage.ensureUsable(custom))
        storage.internalDir(custom).deleteRecursively()
    }

    // ────────────── 落盘 ──────────────

    @Test
    fun createFileInSubDir() {
        val dirUri = Storage.encodeInternal("write_test")
        val created = storage.createFile(dirUri, "a.ts", "video/mp2ts")
        created.stream.use { it.write("hello".toByteArray()) }

        val file = File(storage.internalDir(dirUri), created.name)
        assertTrue(file.exists())
        assertEquals("hello", file.readText())

        assertTrue(storage.deleteUri(created.uri))
        assertFalse(file.exists())
        storage.internalDir(dirUri).deleteRecursively()
    }

    @Test
    fun duplicateNamesAreNumbered() {
        val dirUri = Storage.encodeInternal("dup_test")
        val a = storage.createFile(dirUri, "v.ts", "video/mp2ts")
        a.stream.use { it.write("first".toByteArray()) }
        val b = storage.createFile(dirUri, "v.ts", "video/mp2ts")
        b.stream.use { it.write("second".toByteArray()) }

        assertEquals("v.ts", a.name)
        assertEquals("v (1).ts", b.name)
        assertEquals("first", File(storage.internalDir(dirUri), "v.ts").readText())
        assertEquals("second", File(storage.internalDir(dirUri), "v (1).ts").readText())

        storage.internalDir(dirUri).deleteRecursively()
    }

    @Test
    fun freeSpaceFormattingIsSafe() {
        val check = storage.check(Storage.INTERNAL)
        if (check is DirCheck.Ok) {
            assertTrue(formatBytes(check.freeBytes).isNotBlank())
        }
    }
}
